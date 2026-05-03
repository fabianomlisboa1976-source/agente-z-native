# Offline-First LLM Fallback Chain

## Overview

The fallover chain ensures the agent remains operational even when individual
LLM API providers are unavailable due to rate-limiting, timeouts, or transient
network errors.

```
User request
    │
    ▼
FallbackLLMClient.sendMessage()
    │
    ├─ getOrderedAvailableProviders()   ← Room DB (ProviderHealthDao)
    │       filters out providers whose backoffUntil > now()
    │
    ├── [For each provider in chain: GROQ → OPENROUTER → GITHUB → CLOUDFLARE]
    │       │
    │       ├─ shouldRePing(lastChecked)?  YES → ProviderHealthChecker.ping()
    │       │       ping fails  → recordFailure() → skip to next provider
    │       │       ping ok     → continue
    │       │
    │       ├─ LLMClient.sendMessage()  (up to maxRetriesPerProvider times)
    │       │       success → recordSuccess() → return Result.success
    │       │       failure → recordFailure() → exponential delay → retry
    │       │
    │       └─ [All retries failed] → advance to next provider
    │
    └─ [All providers failed]
            → onAllProvidersFailed() callback
            → ProviderExhaustedNotifier.notifyAllProvidersExhausted()
            → Result.failure(AllProvidersExhaustedException)
```

## Components

### `ProviderHealth` (entity)
Room entity stored in `provider_health` table.  One row per provider.  Key fields:
- `isHealthy` – false when `consecutiveFailures >= MAX_CONSECUTIVE_FAILURES`
- `backoffUntil` – epoch ms; chain skips provider until this time passes
- `averageLatencyMs` – rolling average updated on every success
- `totalRequests` / `totalFailures` – lifetime counters for the dashboard

### `ProviderHealthDao`
Room DAO.  Notable queries:
- `getAvailableProviders(now)` – returns rows whose `backoffUntil <= now`
- `recordFailure(...)` – atomic SQL update: increments counter, sets backoff,
  flips `isHealthy` when threshold reached
- `recordSuccess(...)` – atomic SQL update: resets counter, updates rolling average

### `ProviderHealthRepository`
Business logic layer over the DAO.
- **`computeBackoff(n)`** – `BASE_BACKOFF_MS * 2^(n-1)`, capped at `MAX_BACKOFF_MS`
  | Failures | Backoff   |
  |----------|-----------|
  | 1        |  5 s      |
  | 2        | 10 s      |
  | 3        | 20 s      |
  | 4        | 40 s      |
  | …        | …         |
  | ≥ 9      |  5 min    |
- **`getOrderedAvailableProviders()`** – returns the canonical chain order minus
  any provider still inside its backoff window
- **`ensureAllProvidersSeeded()`** – idempotent seeding called at app start

### `ProviderHealthChecker`
Issues a real `chat/completions` request with `max_tokens=1` and a 10 s timeout
before each main request when the cached health record is stale
(`>= HEALTH_CHECK_INTERVAL_MS = 60 s` since last check).

### `FallbackLLMClient`
Drop-in replacement used by `AgentOrchestrator`.  Wraps `LLMClient` per provider.
Constructor parameters:
- `baseSettings` – used to extract `apiKey` and as template for per-provider copies
- `healthRepository` – reads/writes `ProviderHealth`
- `healthChecker` – issues pings
- `onAllProvidersFailed` – suspend callback; triggers notification
- `maxRetriesPerProvider` – default 2

### `ProviderExhaustedNotifier`
Posts a high-priority `NotificationCompat` with a deep-link action to
`SettingsActivity`.  Uses a dedicated channel `provider_exhausted`
(IMPORTANCE_HIGH).  Notification ID is fixed so subsequent calls update
in-place rather than stacking.

### `ProviderHealthActivity` + `ProviderHealthAdapter`
UI dashboard reachable from Settings.  Shows live Room `Flow` data for all
providers: status badge, failure count, backoff timer, average latency,
and per-provider Reset button.

## Database Migration

Schema bumped from version 1 → 2.  `MIGRATION_1_2` creates the
`provider_health` table:  existing installs upgrade seamlessly; fresh installs
start at version 2 directly.

## Configuration Constants

| Constant | Value | Location |
|----------|-------|----------|
| `MAX_CONSECUTIVE_FAILURES` | 3 | `ProviderHealthRepository` |
| `BASE_BACKOFF_MS` | 5 000 ms | `ProviderHealthRepository` |
| `MAX_BACKOFF_MS` | 300 000 ms (5 min) | `ProviderHealthRepository` |
| `PING_TIMEOUT_MS` | 10 000 ms | `ProviderHealthChecker` |
| `HEALTH_CHECK_INTERVAL_MS` | 60 000 ms | `ProviderHealthChecker` |
| `maxRetriesPerProvider` | 2 | `FallbackLLMClient` constructor |
| `BASE_RETRY_DELAY_MS` | 1 000 ms | `FallbackLLMClient` |

## Testing

See `AgentOrchestratorTest` for unit tests covering:
- `computeBackoff` boundary values (0 failures, first failure, doubling, cap)
- `shouldRePing` stale vs. fresh timestamps
- All-providers-in-backoff triggers `AllProvidersExhaustedException` and callback
- Ping failure records failure and calls callback
- `recordSuccess` / `recordFailure` delegate to DAO with correct arguments
- `ensureAllProvidersSeeded` inserts missing rows, skips existing
- `getOrderedAvailableProviders` correctly filters backoff windows
- `PROVIDER_CHAIN` order: GROQ → OPENROUTER → GITHUB → CLOUDFLARE
