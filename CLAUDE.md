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
│  MemoryApiClient (remote memory server)      │
├─────────────────────────────────────────────┤
│               Data Layer                     │
│  Room Database → DAOs → Entities             │
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
5. LLMClient.sendMessage(coordinatorAgent, context, memories)
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
- If `LLMClient` is null (no API key configured), the pipeline short-circuits and returns an error result
- If `conversationProgrammer` is not initialized, programming commands are skipped gracefully

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
- **Unit tests** (in `test/` source set) covering: guard conditions (null LLMClient, uninitialized programmer), happy-path single-coordinator flow, sub-agent routing, audit step gating, and correlationId propagation

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
| `agent/AgentOrchestrator.kt` | Core orchestration logic; implements the sequential multi-agent pipeline; manages conversation context, memory retrieval, sub-agent routing, and audit steps; all state is tied together via `correlationId`; fully KDoc-documented per pipeline step |
| `agent/AgentManager.kt` | CRUD operations for agents; retrieves agents by type/capability; logs agent usage via `AuditLogger` |
| `agent/ConversationAgentProgrammer.kt` | Natural language command parser; intercepts messages matching patterns to create/modify/delete agents and tasks; supports both Portuguese and English commands; must be initialized before use |

### API Layer
| File | Purpose |
|------|---------|
| `api/LLMApiService.kt` | Retrofit interface for OpenAI-compatible `POST chat/completions`; includes streaming variant |
| `api/LLMClient.kt` | Configures OkHttp + Retrofit per provider; handles retries, auth headers, timeout (60s); exposes `sendMessage()` and streaming flow |
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
| `Settings` | `settings` | id=1 (singleton), apiKey, apiProvider, apiModel, apiBaseUrl, temperature, maxTokens, serviceEnabled, autoStart, auditEnabled |

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
| `SettingsDao` | Singleton pattern (id=1); `getSettingsSync()` for non-Flow access |
| `TaskDao` | Scheduled task management |

#### Database
| File | Purpose |
|------|---------|
| `data/database/AppDatabase.kt` | Room `@Database` class; singleton via `getDatabase(context)`; defines all entity/DAO registrations |

### Service Layer
| File | Purpose |
|------|---------|
| `service/AgenteAutonomoService.kt` | Persistent `ForegroundService`; main execution loop for 24/7 operation |
| `service/BootReceiver.kt` | `BroadcastReceiver` for `RECEIVE_BOOT_COMPLETED`; starts service after device reboot |
| `service/NetworkReceiver.kt` | `BroadcastReceiver` for connectivity changes; triggers LLM reconnection |
| `service/RestartService.kt` | Service that restarts the main service if killed |
| `service/ServiceRestartJob.kt` | `JobService` using `JobScheduler` for system-level service restart scheduling |

### UI Layer
| File | Purpose |
|------|---------|
| `ui/activities/MainActivity.kt` | App entry point/dashboard |
| `ui/activities/ChatActivity.kt` | Primary chat interface |
| `ui/activities/AgentsActivity.kt` | Agent management screen |
| `ui/activities/AuditActivity.kt` | Audit log viewer |
| `ui/activities/SettingsActivity.kt` | API configuration and app settings |
| `ui/adapters/MessageAdapter.kt` | RecyclerView adapter for chat messages (3 view types: user/agent/system) |
| `ui/adapters/AgentAdapter.kt` | RecyclerView adapter for agent list |
| `ui/adapters/AuditLogAdapter.kt` | RecyclerView adapter for audit entries |

### Utilities
| File | Purpose |
|------|---------|
| `utils/AuditLogger.kt` | Utility class wrapping `AuditLogDao`; provides `logAction()` with correlationId support |
| `utils/DateConverter.kt` | Room `TypeConverter` for `Date` ↔ `Long` conversion |

### Tests
| File | Purpose |
|------|---------|
| `test/agent/AgentOrchestratorTest.kt` | Unit tests for the multi-agent pipeline; covers guard conditions, happy-path coordinator flow, sub-agent routing by keyword, audit step gating via `auditEnabled`, and correlationId propagation across all pipeline steps |

---

## Coding Conventions

### Kotlin Style
- **Coroutines**: All I/O operations use `withContext(Dispatchers.IO)` or are declared `suspend`
- **Flow**: Used for reactive UI data streams from Room DAOs; collected in Activities/Adapters
- **Result<T>**: Used as return type for operations that can fail (e.g., `Result.success()`, `Result.failure()`)
- **Companion Objects**: Used for constants (`TAG`, pattern lists, timeouts)
- **Named Parameters**: Kotlin named arguments used extensively in data class construction
- **Extension-style**: `apply {}` blocks used for configuration (OkHttp, NotificationChannel)

### Architecture Conventions
- **Dependency injection is manual** (constructor injection); no DI framework like Hilt/Dagger
- **Singleton DB access**: `AppDatabase.getDatabase(context)` provides thread-safe singleton
- **Settings as singleton**: `Settings` entity always uses `id = 1`; accessed via `getSettingsSync()` for non-reactive needs
- **CorrelationId pattern**: Operations generate `UUID.randomUUID().toString()` at the start of `processUserMessage()`; the same UUID propagates through every agent step and every `AuditLogger.logAction()` call, enabling full end-to-end tracing of a single user request in `AuditLogDao.getLogsByCorrelationId()`
- **Agent capabilities**: Stored as JSON array string in the `capabilities` column (e.g., `["search","write"]`)
- **Early-exit guards**: Both `LLMClient == null` and `::conversationProgrammer.isInitialized` are checked at the top of pipeline entry points before any async work begins
- **Memory injection pattern**: Relevant memories are retrieved once per request via `MemoryDao.searchMemories()` and passed as additional context into each agent's prompt; they are not re-fetched per agent step
- **Audit-gated steps**: The AUDITOR cross-check step is conditionally executed based on `settings.auditEnabled`; always read settings via `getSettingsSync()` inside the pipeline
- **Sub-agent routing is keyword-based**: The COORDINATOR's response text is scanned for Portuguese/English keywords to determine which specialized sub-agents to invoke; routing is additive (multiple sub-agents may be triggered by a single coordinator response)
- **KDoc documentation standard**: All public methods in `AgentOrchestrator` are documented with KDoc including `@param`, `@return`, and step-by-step pipeline descriptions; this is the established standard for agent layer classes

### Testing Conventions
- **Test framework**: JUnit4 with Kotlin Coroutines Test (`runTest` / `TestCoroutineDispatcher`)
- **Mocking**: Mockito or MockK for mocking DAOs, `LLMClient`, `AgentManager`, and `AuditLogger`
- **Test scope**: Unit tests focus on `AgentOrchestrator` pipeline logic in isolation; DAO interactions are mocked
- **Test naming**: Descriptive `fun` names using backtick strings (e.g., `` `processUserMessage returns failure when LLMClient