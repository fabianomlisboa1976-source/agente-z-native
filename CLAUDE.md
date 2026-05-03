# Project Context Document: Agente Autônomo (agente-z-native)

## Project Overview

**Agente Autônomo** is a native Android application that implements a **multi-agent autonomous AI system** running 24/7 on Android devices. The app integrates with free LLM APIs (Groq, OpenRouter, GitHub Models, Cloudflare Workers AI) to provide an intelligent, persistent agent system with local data storage, cross-auditing, and conversational agent programming capabilities.

The app is designed to operate continuously in the background as a foreground service, survive device reboots, reconnect automatically on network restore, and provide a chat interface for interacting with the multi-agent system.

**Package Name:** `com.agente.autonomo`  
**Language:** Kotlin  
**Platform:** Android  
**Build System:** Gradle with Kotlin DSL (`build.gradle.kts`)

---

## Architecture

The project follows a layered architecture pattern:

```
┌─────────────────────────────────────────────┐
│                   UI Layer                   │
│  Activities + Adapters (RecyclerView)        │
├─────────────────────────────────────────────┤
│               Agent Layer                    │
│  AgentOrchestrator → AgentManager            │
│  ConversationAgentProgrammer                 │
├─────────────────────────────────────────────┤
│                API Layer                     │
│  LLMClient → LLMApiService (Retrofit)        │
│  FallbackLLMClient (offline-first chain)     │
│  MemoryApiClient (remote memory server)      │
├─────────────────────────────────────────────┤
│               Data Layer                     │
│  Room Database → DAOs → Entities             │
│  OfflineResponseCache (local LLM cache)      │
├─────────────────────────────────────────────┤
│              Service Layer                   │
│  AgenteAutonomoService (ForegroundService)   │
│  BootReceiver / NetworkReceiver              │
│  RestartService / ServiceRestartJob          │
└─────────────────────────────────────────────┘
```

### Multi-Agent System Design

The agent system includes specialized roles:
- **COORDINATOR** – Orchestrates execution across agents; always the first agent invoked for any user message
- **PLANNER** – Creates plans and organizes tasks
- **RESEARCHER** – Searches and analyzes information
- **EXECUTOR** – Executes practical tasks
- **AUDITOR** – Verifies quality and consistency; invoked as a cross-audit step after primary agents respond
- **MEMORY** – Manages context and history; consulted to inject relevant memories into prompts
- **COMMUNICATION** – Formats and sends messages
- **CUSTOM** – User-created agents via conversation commands

### AgentOrchestrator Message Routing Pipeline

The `AgentOrchestrator` implements a **sequential multi-agent pipeline** for every user message:

```
1. ConversationAgentProgrammer.processMessage()
       ↓ (command detected → handle & return early)
       ↓ (no command → continue pipeline)
2. saveUserMessage() → MessageDao
3. getConversationContext() → MessageDao.getRecentMessages()
   getRelevantMemories() → MemoryDao.searchMemories()
4. AgentManager.getCoordinatorAgent() → AgentDao
       ↓
5. FallbackLLMClient.sendMessage(coordinatorAgent, context, memories)
       ↓ (tries primary LLMClient → fallback providers → offline cache)
       ↓ coordinator response
6. [Optional] Route to specialized sub-agents based on coordinator output
       ↓ (RESEARCHER / PLANNER / EXECUTOR / etc.)
7. [Optional] AuditAgent cross-check if auditEnabled in Settings
8. saveAgentMessage() → MessageDao (each agent's response saved)
   AuditLogger.logAction() → AuditLogDao (each step logged with correlationId)
9. Return final Result<String> to UI
```

**Key routing decisions:**
- The COORDINATOR agent's LLM response determines which specialized agents are invoked next
- Each agent step uses the **same `correlationId`** (UUID) so the full pipeline is traceable in audit logs
- Memory retrieval is done **once before the pipeline starts** and injected into each agent's context
- Conversation context (recent messages) is prepended to every agent's system prompt
- If all LLM providers fail and no offline cache entry exists, the pipeline returns a meaningful degraded-mode error result
- If `conversationProgrammer` is not initialized, programming commands are skipped gracefully

### Offline-First LLM Fallback Chain

The app implements an **offline-first fallback chain** so the agent pipeline degrades gracefully rather than failing hard when the primary LLM provider is unavailable.

#### FallbackLLMClient

`FallbackLLMClient` wraps multiple `LLMClient` instances and an `OfflineResponseCache`. On each `sendMessage()` call it:

1. **Tries the primary `LLMClient`** (configured provider in Settings)
2. **Iterates through ordered fallback providers** (e.g., Groq → OpenRouter → GitHub Models → Cloudflare) if the primary fails
3. **Consults `OfflineResponseCache`** if all network providers fail; returns the best cached response for the given prompt/agent type
4. **Returns `Result.failure()`** with a descriptive error only if no cached response is available

Provider priority order is configurable; the default order is defined in `FallbackLLMClient.Companion.DEFAULT_PROVIDER_ORDER`.

#### OfflineResponseCache

`OfflineResponseCache` is a Room-backed local cache of LLM responses:

- **Entity:** `CachedResponse` – stores prompt hash, agent type, response text, provider, timestamp, hit count
- **DAO:** `CachedResponseDao` – lookup by prompt hash + agent type; eviction of oldest entries when cache exceeds `MAX_CACHE_SIZE`
- **Cache key:** SHA-256 hash of the normalized prompt text + agent type enum name
- **Eviction policy:** LRU-style; entries beyond `MAX_CACHE_SIZE` (default 500) are evicted by oldest `lastAccessed` timestamp
- **TTL:** Cached entries expire after `CACHE_TTL_MS` (default 7 days); expired entries are excluded from lookups and cleaned up lazily

#### FallbackChainSettings

Settings entity gains additional fields to control fallback behavior:
- `fallbackProvidersEnabled: Boolean` – master switch for the fallback chain (default `true`)
- `fallbackProviderOrder: String` – JSON array of `ApiProvider` enum names defining attempt order
- `offlineCacheEnabled: Boolean` – whether to use `OfflineResponseCache` as last resort (default `true`)
- `offlineCacheTtlDays: Int` – TTL in days for cached responses (default 7)

These are exposed in `SettingsActivity` alongside existing API configuration.

#### Audit Logging for Fallback Events

Each provider attempt (success or failure) and each cache hit/miss is recorded via `AuditLogger.logAction()` with:
- `type = AuditLogType.SYSTEM`
- `action` values: `"llm_primary_success"`, `"llm_primary_failure"`, `"llm_fallback_attempt"`, `"llm_fallback_success"`, `"llm_fallback_failure"`, `"llm_cache_hit"`, `"llm_cache_miss"`, `"llm_all_providers_failed"`
- The same `correlationId` as the enclosing pipeline step, enabling full tracing of which provider ultimately served each request

### Sub-Agent Routing Logic

The COORDINATOR's response text is parsed to determine which sub-agents to invoke. Routing decisions are keyword/pattern-based:
- Keywords like `pesquisa`, `busca`, `research`, `search` → route to **RESEARCHER**
- Keywords like `plano`, `planejamento`, `plan`, `organize` → route to **PLANNER**
- Keywords like `executa`, `implementa`, `execute`, `implement` → route to **EXECUTOR**
- Keywords like `comunica`, `envia`, `communicate`, `send` → route to **COMMUNICATION**
- If no specialized routing keyword is found, the COORDINATOR's response is used as the final answer
- Sub-agent responses are concatenated and returned as a composite final response when multiple agents are invoked

### Pipeline Documentation and Testing

The pipeline is formally documented via:
- **Inline KDoc comments** on `AgentOrchestrator` methods describing each pipeline step, guard conditions, and expected behavior
- **Unit tests** (in `test/` source set) covering: guard conditions (null LLMClient, uninitialized programmer), happy-path single-coordinator flow, sub-agent routing, audit step gating, correlationId propagation, fallback chain behavior, and offline cache interactions

---

## Tech Stack

| Category | Technology |
|----------|-----------|
| Language | Kotlin |
| Min/Target SDK | Android (foreground service + boot receiver implies API 26+) |
| Database | Room (SQLite) |
| HTTP Client | OkHttp + Retrofit2 |
| JSON Serialization | Gson |
| Async | Kotlin Coroutines + Flow |
| DI | Manual (constructor injection) |
| UI | Traditional Views (XML layouts) + RecyclerView |
| Background Work | Foreground Service + JobScheduler (`ServiceRestartJob`) |
| Build | Gradle Kotlin DSL |
| CI/CD | GitHub Actions (`.github/workflows/build-apk.yml`) |
| Testing | JUnit4 + Kotlin Coroutines Test (`runTest`) + Mockito/MockK |

### LLM API Providers

| Provider | Base URL | Default Model |
|----------|----------|---------------|
| Groq | `https://api.groq.com/openai/v1/` | `llama-3.1-8b-instant` |
| OpenRouter | `https://openrouter.ai/api/v1/` | `meta-llama/llama-3.1-8b-instruct` |
| GitHub Models | `https://models.inference.ai.azure.com/` | `Meta-Llama-3.1-8B-Instruct` |
| Cloudflare | `https://api.cloudflare.com/...` | `@cf/meta/llama-3.1-8b-instruct` |

All providers use OpenAI-compatible `chat/completions` API format.

---

## Key Files and Their Roles

### Entry Points
| File | Purpose |
|------|---------|
| `AgenteAutonomoApplication.kt` | Application class; creates notification channels, initializes Room DB |
| `AndroidManifest.xml` | Declares all permissions, services, receivers, activities |

### Agent System
| File | Purpose |
|------|---------|
| `agent/AgentOrchestrator.kt` | Core orchestration logic; implements the sequential multi-agent pipeline; manages conversation context, memory retrieval, sub-agent routing, and audit steps; all state is tied together via `correlationId`; uses `FallbackLLMClient` instead of `LLMClient` directly; fully KDoc-documented per pipeline step |
| `agent/AgentManager.kt` | CRUD operations for agents; retrieves agents by type/capability; logs agent usage via `AuditLogger` |
| `agent/ConversationAgentProgrammer.kt` | Natural language command parser; intercepts messages matching patterns to create/modify/delete agents and tasks; supports both Portuguese and English commands; must be initialized before use |

### API Layer
| File | Purpose |
|------|---------|
| `api/LLMApiService.kt` | Retrofit interface for OpenAI-compatible `POST chat/completions`; includes streaming variant |
| `api/LLMClient.kt` | Configures OkHttp + Retrofit per provider; handles retries, auth headers, timeout (60s); exposes `sendMessage()` and streaming flow |
| `api/FallbackLLMClient.kt` | Wraps multiple `LLMClient` instances and `OfflineResponseCache`; implements ordered provider fallback chain; logs each attempt outcome via `AuditLogger`; exposes same `sendMessage()` interface as `LLMClient` |
| `api/OfflineResponseCache.kt` | Room-backed LRU cache for LLM responses; keyed by SHA-256(prompt + agentType); TTL-based expiry; lazy eviction of oldest entries beyond `MAX_CACHE_SIZE` |
| `api/MemoryApiClient.kt` | Connects to remote Next.js memory server at `https://preview-chat-68144e5f.space.z.ai`; manages user identity caching |
| `api/model/LLMModels.kt` | All request/response data classes (`ChatCompletionRequest`, `Message`, `ChatCompletionResponse`, `Choice`, `Delta`, etc.); `ApiProvider` enum; `AvailableModels` catalog |

### Data Layer

#### Entities
| Entity | Table | Key Fields |
|--------|-------|-----------|
| `Agent` | `agents` | id, name, type (enum), systemPrompt, isActive, capabilities (JSON string), priority, temperature, maxTokens, color, usageCount |
| `Message` | `messages` | id (auto), conversationId, agentId, senderType (enum), content, timestamp, isSynced |
| `Memory` | `memories` | id, key, value, type (enum), category, sourceAgent, conversationId, importance, isArchived, expiresAt, accessCount |
| `AuditLog` | `audit_logs` | id (auto), type (enum), action, agentId, agentName, details, status (enum), durationMs, correlationId, isSynced |
| `Task` | `tasks` | Scheduled/pending tasks |
| `CachedResponse` | `cached_responses` | id (auto), promptHash, agentType, responseText, provider, createdAt, lastAccessed, hitCount |
| `Settings` | `settings` | id=1 (singleton), apiKey, apiProvider, apiModel, apiBaseUrl, temperature, maxTokens, serviceEnabled, autoStart, auditEnabled, fallbackProvidersEnabled, fallbackProviderOrder, offlineCacheEnabled, offlineCacheTtlDays |

#### DAOs
All DAOs follow consistent patterns:
- `Flow<List<T>>` for reactive queries (UI observation)
- `suspend fun` for one-shot operations
- Standard CRUD + specialized queries

| DAO | Notable Methods |
|-----|----------------|
| `AgentDao` | `getActiveAgents()`, `getAgentsByType()`, `getAgentsByCapability()`, `updateAgentUsage()` |
| `MessageDao` | `getMessagesByConversation()`, `getRecentMessages()`, `getUnsyncedMessages()`, `searchMessages()` |
| `MemoryDao` | `searchMemories()`, `deleteExpiredMemories()`, `updateAccess()`, `getMemoriesByConversation()` |
| `AuditLogDao` | `getLogsByCorrelationId()`, `getLogsSince()`, `getUnsyncedLogs()`, `getAverageDuration()` |
| `CachedResponseDao` | `findByPromptHash(promptHash, agentType)`, `updateLastAccessed()`, `incrementHitCount()`, `deleteExpiredEntries(before)`, `deleteOldestBeyondLimit(limit)`, `countAll()` |
| `SettingsDao` | Singleton pattern (id=1); `getSettingsSync()` for non-Flow access |
| `TaskDao` | Scheduled task management |

#### Database
| File | Purpose |
|------|---------|
| `data/database/AppDatabase.kt` | Room `@Database` class; singleton via `getDatabase(context)`; defines all entity/DAO registrations including `CachedResponse` and `CachedResponseDao` |

### Service Layer
| File | Purpose |
|------|---------|
| `service/AgenteAutonomoService.kt` | Persistent `ForegroundService`; main execution loop for 24/7 operation |
| `service/BootReceiver.kt` | `BroadcastReceiver` for `RECEIVE_BOOT_COMPLETED`; starts service after device reboot |
| `service/NetworkReceiver.kt` | `BroadcastReceiver` for connectivity changes; triggers LLM reconnection; also triggers `FallbackLLMClient` to re-attempt primary provider after network restore |
| `service/