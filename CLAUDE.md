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
│  VectorMemoryStore (embedding-based search)  │
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
   getRelevantMemories() → VectorMemoryStore.searchSimilar() [vector similarity]
                         + MemoryDao.searchMemories() [keyword fallback]
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
   VectorMemoryStore.storeMemoryWithEmbedding() → persist embedding for future retrieval
9. Return final Result<String> to UI
```

**Key routing decisions:**
- The COORDINATOR agent's LLM response determines which specialized agents are invoked next
- Each agent step uses the **same `correlationId`** (UUID) so the full pipeline is traceable in audit logs
- Memory retrieval is done **once before the pipeline starts** and injected into each agent's context; vector similarity search is preferred over keyword search when embeddings are available
- Conversation context (recent messages) is prepended to every agent's system prompt
- If all LLM providers fail and no offline cache entry exists, the pipeline returns a meaningful degraded-mode error result
- If `conversationProgrammer` is not initialized, programming commands are skipped gracefully
- New memories saved during pipeline execution are embedded and stored via `VectorMemoryStore` for future similarity retrieval

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

### Structured Agent Memory with Vector Similarity Search

The memory system has been upgraded from keyword-only search to a **hybrid vector + keyword retrieval** architecture. This enables semantically relevant memories to be surfaced even when exact keyword matches are absent.

#### Architecture Overview

```
Memory Write Path:
  saveMemory() → MemoryDao.insert()
              → EmbeddingEngine.embed(memoryText)
              → VectorMemoryStore.storeEmbedding(memoryId, vector)

Memory Read Path:
  getRelevantMemories(query) → EmbeddingEngine.embed(query)
                             → VectorMemoryStore.searchSimilar(queryVector, topK)
                             → [fallback] MemoryDao.searchMemories(keywords)
```

#### EmbeddingEngine

`EmbeddingEngine` is responsible for producing dense vector representations of text:

- **Interface:** `EmbeddingEngine` with `suspend fun embed(text: String): FloatArray`
- **Default implementation:** `TFLiteEmbeddingEngine` – runs a bundled TensorFlow Lite sentence embedding model on-device (no network required)
- **Fallback implementation:** `BagOfWordsEmbeddingEngine` – lightweight TF-IDF-style bag-of-words embedding used when TFLite model is unavailable or on low-memory devices; deterministic and fast but lower quality
- **Dimensionality:** Configurable; default TFLite model produces 384-dimensional vectors (MiniLM-L6-v2 or equivalent)
- **Normalization:** All output vectors are L2-normalized before storage and query to enable cosine similarity via dot product

#### VectorMemoryStore

`VectorMemoryStore` manages embedding storage and similarity search:

- **Storage backend:** Room entity `MemoryEmbedding` in `memory_embeddings` table; stores `memoryId` (FK → `memories.id`), `vector` (serialized as `FloatArray` / BLOB), `modelVersion`, `createdAt`
- **DAO:** `MemoryEmbeddingDao` – `insert()`, `findByMemoryId()`, `deleteByMemoryId()`, `getAllEmbeddings()`
- **Similarity metric:** Cosine similarity (dot product of L2-normalized vectors)
- **Search algorithm:** Brute-force nearest-neighbor scan over all stored embeddings; suitable for up to ~10,000 memories on modern Android hardware; a future HNSW index migration path is noted in KDoc
- **Top-K selection:** Returns the `topK` (default 5) most similar memories above a configurable `similarityThreshold` (default 0.65)
- **Model versioning:** Embeddings store the `modelVersion` string; if the engine version changes, stale embeddings are detected and re-indexed lazily on next access

#### MemoryEmbedding Entity

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long (auto) | Primary key |
| `memoryId` | String | FK → `Memory.id` |
| `vector` | ByteArray | L2-normalized FloatArray serialized to bytes |
| `modelVersion` | String | Embedding model identifier (e.g., `"minilm-v6-384"`) |
| `createdAt` | Long | Unix timestamp ms |

#### MemorySearchStrategy

A `MemorySearchStrategy` sealed class/enum controls retrieval behavior:
- `VECTOR_ONLY` – use embedding similarity exclusively
- `KEYWORD_ONLY` – use existing `MemoryDao.searchMemories()` keyword search
- `HYBRID` – run both, merge and deduplicate results, rank by combined score (default)
- `VECTOR_WITH_KEYWORD_FALLBACK` – try vector first; if fewer than `minResults` returned, supplement with keyword results

The strategy is configurable in `Settings` (`memorySearchStrategy: String`) and exposed in `SettingsActivity`.

#### Integration with AgentOrchestrator

- `AgentOrchestrator` now depends on `VectorMemoryStore` and `EmbeddingEngine` (injected via constructor)
- `getRelevantMemories()` uses `HYBRID` strategy by default: query is embedded, `VectorMemoryStore.searchSimilar()` is called, results are merged with keyword matches, deduplicated by `memoryId`, and ranked
- After the pipeline produces a response, any new `Memory` entities saved are immediately embedded and stored via `VectorMemoryStore.storeMemoryWithEmbedding()`
- Embedding calls are always `suspend` and dispatched on `Dispatchers.Default` to avoid blocking the main thread

#### Audit Logging for Memory Search

Memory retrieval events are logged via `AuditLogger.logAction()`:
- `action = "memory_vector_search"` – records query, topK, similarityThreshold, number of results returned
- `action = "memory_keyword_search"` – records keyword query and result count
- `action = "memory_hybrid_search"` – records both vector and keyword result counts and final merged count
- `action = "memory_embedding_stored"` – records memoryId and modelVersion when a new embedding is persisted
- `action = "memory_embedding_reindex"` – records when stale embeddings are detected and re-indexed

#### Performance Considerations

- Embedding inference (`TFLiteEmbeddingEngine`) takes ~5–20ms on mid-range hardware; acceptable within the async pipeline
- Brute-force vector scan over 10K memories takes ~50–100ms on Dispatchers.Default; a warning is logged when scan time exceeds 200ms
- `BagOfWordsEmbeddingEngine` is selected automatically on devices with < 2GB RAM or when the TFLite model fails to load
- Embeddings are computed lazily at save time; no background re-indexing job runs unless a model version change is detected

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
- **Unit tests** (in `test/` source set) covering: guard conditions (null LLMClient, uninitialized programmer), happy-path single-coordinator flow, sub-agent routing, audit step gating, correlationId propagation, fallback chain behavior, offline cache interactions, vector similarity search correctness, hybrid search merging/deduplication, embedding engine selection logic, and VectorMemoryStore model-version staleness detection

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
| On-Device ML | TensorFlow Lite (sentence embeddings for vector memory search) |

### LLM API Providers

| Provider | Base URL | Default Model |
|----------|----------|---------------|
| Groq | `https://api.groq.com/openai/v1/` | `llama-3.1-8b-instant` |
| OpenRouter | `https://openrouter.ai/api/v1/` | `meta-llama/llama-3.1-8b-instruct` |
| GitHub Models | `https://models.inference.ai.azure.com/` | `Meta-