# AgentOrchestrator — Multi-Agent Message Routing Pipeline

> **File:** `app/src/main/java/com/agente/autonomo/agent/AgentOrchestrator.kt`  
> **Last documented:** see git log  
> **Audience:** Contributors extending or debugging the multi-agent pipeline

---

## Table of Contents

1. [Overview](#overview)
2. [Entry Point: `processUserMessage()`](#entry-point-processusermessage)
3. [Step-by-Step Execution Order](#step-by-step-execution-order)
   - [Step 0 — Special Command Interception](#step-0--special-command-interception)
   - [Step 1 — Persist User Message](#step-1--persist-user-message)
   - [Step 2 — Load Conversation Context (`getRecentMessages`)](#step-2--load-conversation-context-getrecentmessages)
   - [Step 3 — Retrieve Relevant Memories (`searchMemories`)](#step-3--retrieve-relevant-memories-searchmemories)
   - [Step 4 — Resolve Coordinator Agent](#step-4--resolve-coordinator-agent)
   - [Step 5 — Agent Routing Decision (`decideAgentsForTask`)](#step-5--agent-routing-decision-decideagentsfortask)
   - [Step 6 — Execute Selected Agents (`executeWithAgents`)](#step-6--execute-selected-agents-executewithagents)
   - [Step 7 — Optional Cross-Audit](#step-7--optional-cross-audit)
   - [Step 8 — Persist Agent Response](#step-8--persist-agent-response)
   - [Step 9 — Optional Memory Update](#step-9--optional-memory-update)
4. [Agent Context Data Contract](#agent-context-data-contract)
5. [Special Command Interception — Full Reference](#special-command-interception--full-reference)
6. [Routing Logic Deep-Dive](#routing-logic-deep-dive)
7. [Error Handling Matrix](#error-handling-matrix)
8. [Sequence Diagram](#sequence-diagram)
9. [Configuration Flags That Affect Pipeline Behaviour](#configuration-flags-that-affect-pipeline-behaviour)
10. [Known Limitations & Future Work](#known-limitations--future-work)

---

## Overview

`AgentOrchestrator` is the single entry point through which every user message travels before a language-model response reaches the UI. Its responsibilities are:

| Responsibility | Implementation |
|---|---|
| Command interception (special syntax) | `ConversationAgentProgrammer.processMessage()` |
| Conversation-history hydration | `MessageDao.getRecentMessages()` |
| Long-term memory retrieval | `MemoryDao.searchMemories()` |
| Dynamic agent selection | LLM-assisted `decideAgentsForTask()` |
| Multi-agent execution (sequential / parallel) | `executeWithAgents()` |
| Response quality audit | `performCrossAudit()` |
| Durable audit trail | `AuditLogger` at every significant step |

All public methods are `suspend` functions intended to be called from a `CoroutineScope` backed by `Dispatchers.IO` (already enforced via `withContext(Dispatchers.IO)` inside `processUserMessage`).

---

## Entry Point: `processUserMessage()`

```kotlin
suspend fun processUserMessage(
    userMessage: String,          // Raw UTF-8 text from the user
    conversationId: String = "default"  // Logical conversation bucket; defaults to "default"
): Result<String>
```

### Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `userMessage` | `String` | — | The raw user input exactly as typed. Never pre-processed before entering the pipeline. |
| `conversationId` | `String` | `"default"` | Scopes message history and memory retrieval. Use distinct IDs for parallel conversations. |

### Return Value

`Result<String>` — Kotlin stdlib sealed wrapper.

| Case | Meaning |
|---|---|
| `Result.success(text)` | Pipeline completed; `text` is the final response to display |
| `Result.failure(exception)` | At least one non-recoverable step failed; inspect `exception.message` for details |

### Correlation ID

At the very start of `processUserMessage()` a `correlationId = UUID.randomUUID().toString()` is generated. **Every** `auditLogger` call within the same invocation passes this ID, allowing a single user request to be traced end-to-end across all audit log rows.

---

## Step-by-Step Execution Order

```
╔══════════════════════════════════════════════════════════════════╗
║  processUserMessage(userMessage, conversationId)                 ║
║                                                                  ║
║  [0] ConversationAgentProgrammer.processMessage()  ←─ may exit  ║
║  [1] saveUserMessage()  →  MessageDao.insertMessage()            ║
║  [2] MessageDao.getRecentMessages()                              ║
║  [3] MemoryDao.searchMemories()  (per keyword)                   ║
║  [4] AgentManager.getCoordinatorAgent()                          ║
║  [5] decideAgentsForTask()  →  LLM call  →  parseAgentDecision() ║
║  [6] executeWithAgents()  →  N × LLM calls                       ║
║  [7] performCrossAudit()  (if Settings.crossAuditEnabled)        ║
║  [8] saveAgentMessage()  →  MessageDao.insertMessage()           ║
║  [9] updateMemory()  (if Settings.memoryEnabled)                 ║
╚══════════════════════════════════════════════════════════════════╝
```

---

### Step 0 — Special Command Interception

**Code location:** `AgentOrchestrator.kt` lines guarded by `::conversationProgrammer.isInitialized`

```kotlin
if (::conversationProgrammer.isInitialized) {
    val commandResult = conversationProgrammer.processMessage(userMessage)
    if (commandResult.isCommand) {
        // Short-circuit: persist user msg + system reply, then return immediately
        saveUserMessage(userMessage, conversationId)
        saveAgentMessage(commandResult.message, conversationId, "system", "Sistema")
        auditLogger.logAction(action = "Comando de programação executado", ...)
        return@withContext Result.success(commandResult.message)
    }
}
```

**Guard condition:** `conversationProgrammer` is a `lateinit` property; it is initialised by `initializeLLM()`. If `initializeLLM()` has never been called (e.g. first cold-start before any LLM key is configured) the entire interception block is **silently skipped** — the message falls through to the normal pipeline. See [Known Limitations](#known-limitations--future-work).

**Short-circuit semantics:** When a command is detected:
1. Both user message **and** the command response are persisted to the database.
2. An audit record is written.
3. `Result.success(commandResult.message)` is returned **immediately**; steps 1–9 below do **not** execute.

Full command reference: [Special Command Interception — Full Reference](#special-command-interception--full-reference)

---

### Step 1 — Persist User Message

```kotlin
private suspend fun saveUserMessage(content: String, conversationId: String) {
    val message = com.agente.autonomo.data.entity.Message(
        senderType = Message.SenderType.USER,
        content    = content,
        conversationId = conversationId
    )
    database.messageDao().insertMessage(message)
}
```

**DAO call:** `MessageDao.insertMessage(message): Long`  
**Conflict strategy:** `OnConflictStrategy.REPLACE`  
**Effect:** Writes one row to the `messages` table; the auto-generated `id` (Long) is returned but currently discarded. The row is available for all subsequent `getRecentMessages()` calls within the same request because Room I/O is serialised on `Dispatchers.IO`.

**Failure behaviour:** If the insert throws (e.g. disk full), the exception propagates to the `try/catch` in `processUserMessage()`, which logs the error via `auditLogger.logError()` and returns `Result.failure(e)`. No downstream steps execute.

---

### Step 2 — Load Conversation Context (`getRecentMessages`)

```kotlin
private suspend fun getConversationContext(conversationId: String): List<Message>
```

#### DAO Signature

```kotlin
// MessageDao.kt
@Query("""
    SELECT * FROM messages
    WHERE conversation_id = :conversationId
    ORDER BY timestamp DESC
    LIMIT :limit
""")
suspend fun getRecentMessages(conversationId: String, limit: Int): List<Message>
```

#### Call Site Parameters

| Parameter | Value at call site | Source |
|---|---|---|
| `conversationId` | Value of `processUserMessage`'s `conversationId` parameter | Caller |
| `limit` | `AgentOrchestrator.MAX_CONTEXT_MESSAGES` = **10** | Companion object constant |

> **Important — ordering:** The query returns rows in `DESC` order (newest first). `getConversationContext()` maps these directly into `List<Message>` (API model) **without reversing**. This means the LLM receives history with the most-recent turn at index 0. Some LLM providers interpret the `messages` array as chronological; consider whether reversal is needed when extending this function.

#### Mapping to API Message Format

```kotlin
recentMessages.map { msg ->
    when (msg.senderType) {
        SenderType.USER   -> Message(role = "user",      content = msg.content)
        SenderType.AGENT  -> Message(role = "assistant", content = msg.content)
        SenderType.SYSTEM -> Message(role = "system",    content = msg.content)
    }
}
```

`com.agente.autonomo.data.entity.Message` (Room entity) is mapped to `com.agente.autonomo.api.model.Message` (API DTO). **Both classes are named `Message`**; always verify the import. See [Known Limitations](#known-limitations--future-work).

#### Injection into Agent Context

The resulting `List<api.model.Message>` (`contextMessages`) is threaded through the pipeline as follows:

```
getConversationContext()  →  contextMessages
    ├─→ decideAgentsForTask(coordinator, userMessage, contextMessages)
    └─→ executeWithAgents(agentIds, userMessage, contextMessages, correlationId)
```

Inside each LLM call the context is prepended after the system prompt and before the current user turn:

```kotlin
buildList {
    add(Message(role = "system",    content = agent.systemPrompt))
    addAll(contextMessages)                    // history injected here
    add(Message(role = "user",      content = userMessage))
}
```

#### Failure Behaviour

`getRecentMessages` is a `suspend` function; if it throws, the exception surfaces in `processUserMessage`'s `catch` block → `Result.failure(e)`. The pipeline aborts and **no LLM call is made**.

---

### Step 3 — Retrieve Relevant Memories (`searchMemories`)

```kotlin
private suspend fun getRelevantMemories(query: String): List<Memory>
```

#### DAO Signature

```kotlin
// MemoryDao.kt
@Query("""
    SELECT * FROM memories
    WHERE (key   LIKE '%' || :query || '%'
        OR value LIKE '%' || :query || '%'
        OR category LIKE '%' || :query || '%')
    AND is_archived = 0
    ORDER BY importance DESC, access_count DESC
""")
suspend fun searchMemories(query: String): List<Memory>
```

#### Keyword Extraction Algorithm

```kotlin
val keywords = query
    .lowercase()
    .split(" ")
    .filter  { it.length > 3 }   // drop stop-words / short tokens
    .take(5)                      // cap at 5 keywords to bound DB calls
```

`searchMemories()` is called **once per keyword** in a sequential `for` loop. Results are accumulated in a mutable list then de-duplicated and trimmed:

```kotlin
for (keyword in keywords) {
    val found = database.memoryDao().searchMemories(keyword)
    memories.addAll(found)
}

return memories
    .distinctBy { it.id }           // remove cross-keyword duplicates
    .sortedByDescending { it.importance }
    .take(5)                        // hard ceiling: at most 5 memories injected
```

#### Query Parameters

| Aspect | Value |
|---|---|
| Search type | SQL `LIKE` substring match (not vector/embedding) |
| Fields searched | `key`, `value`, `category` |
| Archived filter | `is_archived = 0` (active memories only) |
| Sort order | `importance DESC`, then `access_count DESC` |
| Max results per keyword | No explicit DAO-level limit; bounded by de-dup + `.take(5)` after aggregation |
| Total memories injected | ≤ 5 |

> **No vector/embedding search is currently implemented.** Retrieval is purely lexical. This is a known limitation; see [Known Limitations](#known-limitations--future-work).

#### Injection into Agent Context

**Currently, `getRelevantMemories()` return value is not injected into the LLM prompt.** The call site in `processUserMessage()` is:

```kotlin
val relevantMemories = getRelevantMemories(userMessage)  // retrieved but unused below
```

The retrieved `List<Memory>` is assigned but never passed to `decideAgentsForTask()` or `executeWithAgents()`. This is a **documented gap** — the scaffolding exists but prompt-injection of memories is not yet implemented. See [Known Limitations](#known-limitations--future-work).

#### Failure Behaviour

`getRelevantMemories()` wraps its DB calls in its own `try/catch`:

```kotlin
return try {
    // ... DB calls ...
} catch (e: Exception) {
    emptyList()   // ← graceful degradation; pipeline continues without memories
}
```

**Memory retrieval failure is non-fatal.** The pipeline continues with an empty memory list. An error is not explicitly logged here; consider adding `auditLogger.logWarning()` for observability.

---

### Step 4 — Resolve Coordinator Agent

```kotlin
val coordinator = agentManager.getCoordinatorAgent()
    ?: return@withContext Result.failure(Exception("Agente coordenador não encontrado"))
```

`AgentManager.getCoordinatorAgent()` queries `AgentDao` for the first active agent with `type = AgentType.COORDINATOR`. If no coordinator exists (e.g. the database was wiped without re-seeding), the pipeline returns `Result.failure` immediately and steps 5–9 do not execute.

**Responsibility of the Coordinator:**
- Provides the `systemPrompt` used in the routing-decision LLM call (Step 5).
- Acts as the fallback single agent when routing parsing fails.
- Its `id` and `name` are used to attribute the final persisted agent message (Step 8).

---

### Step 5 — Agent Routing Decision (`decideAgentsForTask`)

```kotlin
private suspend fun decideAgentsForTask(
    coordinator: Agent,
    userMessage: String,
    contextMessages: List<Message>  // api.model.Message
): AgentDecision?
```

#### Mechanism

This step makes an **LLM call** — not a deterministic rule — to decide which agents should handle the request.

The prompt injected into the LLM is structured as:

```
[system]  <coordinator.systemPrompt>
[history] <contextMessages[0..N]>
[user]    Analise a solicitação... {enumerated agent list} ... Solicitação: "<userMessage>"
          Responda APENAS no seguinte formato JSON:
          { "selectedAgents": [...], "reasoning": "...", "executionOrder": "parallel"|"sequential" }
```

LLM call parameters:

| Parameter | Value |
|---|---|
| `maxTokens` | 500 |
| `temperature` | 0.3 (low, for deterministic routing) |

#### `AgentDecision` Data Class

```kotlin
data class AgentDecision(
    val selectedAgents: List<String>,  // List of agent IDs (String, matches Agent.id)
    val reasoning: String,             // LLM's explanation; logged to audit trail
    val executionOrder: String         // "parallel" or "sequential"
)
```

> **Note:** `executionOrder` is parsed and stored but the current implementation of `executeWithAgents()` always iterates agents **sequentially** in a `for` loop regardless of this value. Parallel execution is not yet implemented.

#### Parsing & Fallback

```kotlin
return try {
    parseAgentDecision(response)   // Gson.fromJson(json, AgentDecision::class.java)
} catch (e: Exception) {
    // Fallback: single coordinator, sequential
    AgentDecision(
        selectedAgents = listOf(coordinator.id),
        reasoning      = "Fallback para coordenador devido a erro no parsing",
        executionOrder = "sequential"
    )
}
```

The LLM response is parsed with `Gson`. If the JSON is malformed or missing required fields, `parseAgentDecision` throws and the `catch` block constructs a safe fallback `AgentDecision` containing only the coordinator. **The pipeline never aborts due to a routing parsing failure.**

#### Routing Decision Tree

```
decideAgentsForTask()
├── callLLM() returns non-null
│   ├── parseAgentDecision() succeeds
│   │   └── AgentDecision { selectedAgents: [id1, id2, ...], executionOrder: "sequential"|"parallel" }
│   └── parseAgentDecision() throws
│       └── FALLBACK: AgentDecision { selectedAgents: [coordinator.id] }
└── callLLM() returns null (LLM unavailable)
    └── returns null  →  processUserMessage returns Result.failure("Falha na decisão de agentes")
```

---

### Step 6 — Execute Selected Agents (`executeWithAgents`)

```kotlin
private suspend fun executeWithAgents(
    agentIds: List<String>,
    userMessage: String,
    contextMessages: List<Message>,
    correlationId: String
): Result<String>
```

#### Execution Loop

```kotlin
for (agentId in agentIds) {
    val agent = agentManager.getAgent(agentId) ?: continue  // skip unknown IDs silently

    val messages = buildList {
        add(Message(role = "system",    content = agent.systemPrompt))
        addAll(contextMessages)
        add(Message(role = "user",      content = userMessage))
    }

    val response = callLLM(messages, agent.maxTokens, agent.temperature)

    if (response != null) {
        results.add(response)
        agentManager.recordAgentUsage(agent.id)   // increments usageCount in DB
        auditLogger.logAction(...)                 // per-agent audit entry with correlationId
    }
    // null response: agent silently skipped; no error recorded
}
```

#### Result Aggregation

```kotlin
return if (results.isNotEmpty()) {
    Result.success(results.joinToString("\n\n---\n\n"))  // responses concatenated with separator
} else {
    Result.failure(Exception("Nenhum agente conseguiu responder"))
}
```

Multiple agent responses are **concatenated** with `\n\n---\n\n` as delimiter. There is no synthesis/summarisation step — all raw responses are returned as-is. If **zero** agents produce a non-null response, the result is `Result.failure`.

#### Per-Agent LLM Call Parameters

Each agent carries its own configuration:

| Parameter | Source |
|---|---|
| `maxTokens` | `agent.maxTokens` (entity field) |
| `temperature` | `agent.temperature` (entity field) |
| System prompt | `agent.systemPrompt` (entity field) |

---

### Step 7 — Optional Cross-Audit

Activated by `Settings.crossAuditEnabled == true`.

```kotlin
val auditResult = performCrossAudit(userMessage, executionResult, correlationId)
if (!auditResult.isSuccess) {
    auditLogger.logWarning(action = "Auditoria cruzada falhou", ...)
    // Pipeline continues — audit failure does NOT abort response delivery
}
```

`performCrossAudit()` fetches the first `AgentType.AUDITOR` agent and submits the coordinator's response for review. LLM call parameters: `maxTokens=500`, `temperature=0.2`. The auditor looks for the string `"REPROVADO"` in the response. If found, `performCrossAudit()` returns `Result.failure(...)`, which triggers a warning log but **does not suppress the response from the user**.

---

### Step 8 — Persist Agent Response

```kotlin
saveAgentMessage(finalResponse, conversationId, coordinator.id, coordinator.name)
```

Note: even when multiple agents responded (Step 6), the persisted agent attribution always uses the **coordinator's** `id` and `name`. Individual agent responses are available only in the audit log, not in the messages table.

---

### Step 9 — Optional Memory Update

Activated by `Settings.memoryEnabled == true`.

```kotlin
private suspend fun updateMemory(userMessage: String, response: String, conversationId: String) {
    val memory = Memory(
        id             = UUID.randomUUID().toString(),
        type           = Memory.MemoryType.CONTEXT,
        key            = "conversation_${conversationId}_${System.currentTimeMillis()}",
        value          = "Usuário: $userMessage\nResposta: ${response.take(200)}",
        conversationId = conversationId,
        importance     = 5
    )
    database.memoryDao().insertMemory(memory)
}
```

A `CONTEXT`-type memory is created for every successfully processed message. `response` is truncated to 200 characters. `importance` is hardcoded to `5` (mid-range on a 1–10 scale). Failures here are not caught — they propagate to the outer `try/catch` which would log and return `Result.failure`. Consider wrapping in its own try/catch since memory persistence is non-critical.

---

## Agent Context Data Contract

The following defines the exact payload shape delivered to each agent's LLM call. Expressed as a TypeScript-equivalent interface for clarity:

```typescript
/**
 * The ordered array passed as the `messages` field in the OpenAI-compatible
 * ChatCompletionRequest sent for each agent.
 */
type AgentContextPayload = [
  SystemMessage,        // index 0: always present
  ...HistoryMessage[],  // indices 1..N: from getRecentMessages(), max 10 items
  UserMessage           // last: the current user turn
];

interface SystemMessage {
  role: "system";
  content: string;  // agent.systemPrompt — defines the agent's persona & capabilities
}

interface HistoryMessage {
  role: "user" | "assistant" | "system";
  // Mapped from entity.Message.SenderType:
  //   USER   -> "user"
  //   AGENT  -> "assistant"
  //   SYSTEM -> "system"
  content: string;  // entity.Message.content verbatim
}

interface UserMessage {
  role: "user";
  content: string;  // processUserMessage's userMessage parameter verbatim
}

// ── What is NOT currently in the payload ──────────────────────────────────────
// relevantMemories: Memory[]  → retrieved but not injected (see Known Limitations)
// agentMetadata: { agentId, agentName, capabilities } → not passed to LLM
// correlationId: string       → audit only, not in LLM payload
```

### Kotlin Equivalent (api.model.Message)

```kotlin
// com.agente.autonomo.api.model.Message
data class Message(
    val role: String,     // "system" | "user" | "assistant"
    val content: String
)
```

---

## Special Command Interception — Full Reference

`ConversationAgentProgrammer` intercepts messages that match specific natural-language patterns **before** any LLM or database work is performed.

### `CommandResult` Data Class

```kotlin
data class CommandResult(
    val isCommand: Boolean,  // true  = short-circuit pipeline, return message
                             // false = pass through to normal pipeline
    val message: String      // human-readable response or empty string when isCommand=false
)
```

### Command Categories

The programmer recognises commands in **both Portuguese and English**. Command categories and their routing outcomes:

| Category | Example Patterns (PT) | Example Patterns (EN) | Pipeline Action |
|---|---|---|---|
| **Create agent** | `"criar agente ..."`, `"novo agente ..."`, `"adicionar agente ..."` | `"create agent ..."`, `"new agent ..."`, `"add agent ..."` | Creates `Agent` row via `AgentManager`; returns confirmation message; **full short-circuit** |
| **Modify agent** | `"modificar agente ..."`, `"editar agente ..."`, `"atualizar agente ..."` | `"modify agent ..."`, `"edit agent ..."`, `"update agent ..."` | Updates matching `Agent` row; short-circuit |
| **Delete agent** | `"deletar agente ..."`, `"remover agente ..."`, `"excluir agente ..."` | `"delete agent ..."`, `"remove agent ..."` | Deactivates or deletes `Agent` row; short-circuit |
| **List agents** | `"listar agentes"`, `"mostrar agentes"` | `"list agents"`, `"show agents"` | Queries `AgentDao`; formats list; short-circuit |
| **Create task** | `"criar tarefa ..."`, `"nova tarefa ..."`, `"agendar tarefa ..."` | `"create task ..."`, `"new task ..."`, `"schedule task ..."` | Creates `Task` row via `TaskDao`; short-circuit |
| **List tasks** | `"listar tarefas"` | `"list tasks"` | Queries `TaskDao`; short-circuit |
| **Help** | `"ajuda"`, `"comandos"` | `"help"`, `"commands"` | Returns static help text; short-circuit |

### Parsing Logic

1. `processMessage(userMessage)` lowercases and trims the input.
2. It iterates over an ordered list of `Regex` patterns (defined as companion object constants in `ConversationAgentProgrammer`).
3. The **first** matching pattern wins — no ambiguity resolution.
4. Matched groups are extracted (e.g. agent name, agent type, task description).
5. The appropriate `AgentManager` or `TaskDao` method is called.
6. A `CommandResult(isCommand = true, message = "...")` is returned.
7. If **no** pattern matches, `CommandResult(isCommand = false, message = "")` is returned and the pipeline proceeds normally.

### Routing Decision Tree

```
processMessage(userMessage)
├── matches any command pattern?
│   ├── YES
│   │   ├── Execute side-effect (create/update/delete DB row)
│   │   └── Return CommandResult(isCommand=true, message=confirmationText)
│   │       └── AgentOrchestrator: persist both messages, return Result.success immediately
│   └── NO
│       └── Return CommandResult(isCommand=false, message="")
│           └── AgentOrchestrator: continue to Step 1 (normal pipeline)
└── conversationProgrammer not initialized?
    └── Skip interception entirely → continue to Step 1
```

---

## Routing Logic Deep-Dive

### How Agents Are Selected

Agent selection is **LLM-assisted** (Step 5). The coordinator agent is given a dynamically built prompt listing all currently active agents (from `AgentManager.getAllActiveAgents().first()`). Each agent's entry in the prompt is:

```
- {agent.id}: {agent.name} ({agent.type.name}) - {agent.description}
```

The LLM decides which agent IDs to include in `selectedAgents` and whether they should run in `"sequential"` or `"parallel"` order.

### Sequential vs Parallel

| Declared `executionOrder` | Actual Runtime Behaviour |
|---|---|
| `"sequential"` | `for` loop — agents execute one at a time, results accumulated |
| `"parallel"` | **Same `for` loop** — parallelism is declared but not yet implemented |

### Criteria Driving Agent Selection

Because selection is LLM-driven, the criteria are implicit in the coordinator's `systemPrompt` and the routing prompt. Typical implicit criteria:

- Task type (research → RESEARCHER, execution → EXECUTOR, etc.)
- Required capabilities (matched against agent descriptions in the prompt)
- Complexity signals in the user message
- Conversation history patterns

### Fallback Chain

```
Normal route:  LLM parses → N agents from selectedAgents
     ↓ (LLM JSON malformed)
Fallback 1:  coordinator only (sequential)
     ↓ (coordinator Agent not in DB)
Fallback 2:  Result.failure("Agente coordenador não encontrado")
     ↓ (all selected agents return null from LLM)
Fallback 3:  Result.failure("Nenhum agente conseguiu responder")
```

---

## Error Handling Matrix

| Stage | Failure Condition | Behaviour | Pipeline Continues? |
|---|---|---|---|
| Step 0 — `conversationProgrammer` uninitialised | `initializeLLM()` not yet called | Interception silently skipped | ✅ Yes |
| Step 0 — command side-effect throws | DB error during agent create/update/delete | Exception propagates to outer catch → `Result.failure` | ❌ No |
| Step 1 — `insertMessage` throws | Disk full / DB error | Outer `catch` → `auditLogger.logError` → `Result.failure` | ❌ No |
| Step 2 — `getRecentMessages` throws | DB error | Outer `catch` → `Result.failure` | ❌ No |
| Step 3 — `searchMemories` throws | DB error | Inner `try/catch` → `emptyList()` returned | ✅ Yes (degraded) |
| Step 4 — no coordinator agent | `AgentDao` returns null | `return Result.failure("Agente coordenador não encontrado")` | ❌ No |
| Step 5 — LLM returns null | API key missing, network error | `return Result.failure("Falha na decisão de agentes")` | ❌ No |
| Step 5 — JSON parse error | LLM returns non-JSON text | Fallback `AgentDecision` (coordinator only) | ✅ Yes (degraded) |
| Step 6 — unknown agent ID | `agentManager.getAgent()` returns null | Agent silently `continue`-d | ✅ Yes (degraded) |
| Step 6 — LLM returns null for agent | Network/API error per agent | Agent silently skipped (no log) | ✅ Yes (degraded) |
| Step 6 — all agents skipped | Every LLM call returns null | `Result.failure("Nenhum agente conseguiu responder")` | ❌ No |
| Step 7 — audit LLM call fails | Network error | `Result.failure` in `auditResult`; `logWarning` only | ✅ Yes |
| Step 7 — audit returns REPROVADO | Quality check failed | `logWarning` only; response still delivered | ✅ Yes |
| Step 8 — `insertMessage` throws | DB error | Outer `catch` → `Result.failure` | ❌ No |
| Step 9 — `insertMemory` throws | DB error | Outer `catch` → `Result.failure` | ❌ No (see Known Limitations) |

---

## Sequence Diagram

```
ChatActivity          AgentOrchestrator      ConversationProgrammer   AgentManager    MessageDao    MemoryDao    LLMClient     AuditLogger
    │                       │                          │                    │               │             │             │              │
    │ processUserMessage()  │                          │                    │               │             │             │              │
    │──────────────────────▶│                          │                    │               │             │             │              │
    │                       │ processMessage()          │                    │               │             │             │              │
    │                       │─────────────────────────▶│                    │               │             │             │              │
    │                       │ CommandResult             │                    │               │             │             │              │
    │                       │◀─────────────────────────│                    │               │             │             │              │
    │                       │ [isCommand=true → return] │                    │               │             │             │              │
    │                       │ [isCommand=false ↓]       │                    │               │             │             │              │
    │                       │ insertMessage(USER)       │                    │               │             │             │              │
    │                       │───────────────────────────────────────────────────────────────▶│             │             │              │
    │                       │ getRecentMessages()        │                   │               │             │             │              │
    │                       │───────────────────────────────────────────────────────────────▶│             │             │              │
    │                       │ List<entity.Message>      │                    │               │             │             │              │
    │                       │◀───────────────────────────────────────────────────────────────│             │             │              │
    │                       │ searchMemories() ×N        │                   │               │             │             │              │
    │                       │─────────────────────────────────────────────────────────────────────────────▶│             │              │
    │                       │ List<Memory>               │                   │               │             │             │              │
    │                       │◀─────────────────────────────────────────────────────────────────────────────│             │              │
    │                       │ getCoordinatorAgent()      │                   │               │             │             │              │
    │                       │───────────────────────────────────────────────▶│               │             │             │              │
    │                       │ Agent                      │                   │               │             │             │              │
    │                       │◀───────────────────────────────────────────────│               │             │             │              │
    │                       │ [routing LLM call]         │                   │               │             │             │              │
    │                       │────────────────────────────────────────────────────────────────────────────────────────────▶│             │
    │                       │ AgentDecision              │                   │               │             │             │              │
    │                       │◀────────────────────────────────────────────────────────────────────────────────────────────│             │
    │                       │ logAgentDecision()         │                   │               │             │             │              │
    │                       │────────────────────────────────────────────────────────────────────────────────────────────────────────────▶│
    │                       │ [per-agent LLM calls]      │                   │               │             │             │              │
    │                       │────────────────────────────────────────────────────────────────────────────────────────────▶│             │
    │                       │ String responses           │                   │               │             │             │              │
    │                       │◀────────────────────────────────────────────────────────────────────────────────────────────│             │
    │                       │ [crossAudit if enabled]    │                   │               │             │             │              │
    │                       │ insertMessage(AGENT)       │                   │               │             │             │              │
    │                       │───────────────────────────────────────────────────────────────▶│             │             │              │
    │                       │ [updateMemory if enabled]  │                   │               │             │             │              │
    │                       │─────────────────────────────────────────────────────────────────────────────▶│             │              │
    │ Result.success(text)  │                          │                    │               │             │             │              │
    │◀──────────────────────│                          │                    │               │             │             │              │
```

---

## Configuration Flags That Affect Pipeline Behaviour

All flags are columns on the `Settings` entity (Room table `settings`, always `id = 1`).

| Flag | Type | Default | Effect on Pipeline |
|---|---|---|---|
| `apiKey` | `String` | `""` | Empty → `llmClient = null` → entire LLM pipeline unavailable |
| `crossAuditEnabled` | `Boolean` | `false` | `true` → Step 7 (`performCrossAudit`) executes |
| `memoryEnabled` | `Boolean` | `false` | `true` → Step 9 (`updateMemory`) executes |
| `apiProvider` | `ApiProvider` enum | `GROQ` | Selects base URL and auth header format for `LLMClient` |
| `apiModel` | `String` | provider default | Passed to `ChatCompletionRequest.model` |
| `temperature` | `Float` | `0.7` | Default temperature if `Agent.temperature` is null |
| `maxTokens` | `Int` | `1024` | Default token ceiling if `Agent.maxTokens` is null |

Settings are read via `database.settingsDao().getSettingsSync()` at multiple points during the pipeline. Changes take effect on the **next** `processUserMessage()` call.

---

## Known Limitations & Future Work

### 1. Memory Not Injected into LLM Prompt
**Issue:** `getRelevantMemories()` is called and its result assigned to `relevantMemories` but this variable is never incorporated into any LLM message payload.  
**Impact:** Long-term memory retrieval has zero effect on agent responses.  
**Fix:** Serialize retrieved `Memory` objects into a `"system"` or `"user"` message and insert them into `contextMessages` before building agent payloads.

### 2. `executionOrder = "parallel"` Not Implemented
**Issue:** `AgentDecision.executionOrder` can be `"parallel"` but `executeWithAgents()` always uses a sequential `for` loop.  
**Fix:** Replace the `for` loop with `coroutineScope { agentIds.map { async { ... } }.awaitAll() }` when `executionOrder == "parallel"`.

### 3. Message History Returned in Reverse Order
**Issue:** `getRecentMessages()` uses `ORDER BY timestamp DESC`, so `contextMessages[0]` is the **most recent** message. Most LLM providers expect chronological order.  
**Fix:** Either change the DAO query to `ASC` or call `.reversed()` after mapping.

### 4. Multi-Agent Response Not Synthesised
**Issue:** When multiple agents respond, their outputs are naively concatenated with `"---"` separators. No synthesis agent merges or deduplicates content.  
**Fix:** Add a synthesis step using a `COORDINATOR` or `COMMUNICATION` agent after `executeWithAgents()`.

### 5. Silent Skipping of Null LLM Responses in `executeWithAgents`
**Issue:** When `callLLM()` returns `null` for a specific agent, that agent is silently skipped without any log entry.  
**Fix:** Add `auditLogger.logWarning(...)` when `response == null`.

### 6. Memory Persistence Failure is Fatal
**Issue:** `updateMemory()` is called inside the outer `try/catch`. A DB error during memory write will cause `Result.failure` even though the LLM response was successfully obtained.  
**Fix:** Wrap `updateMemory()` in its own `try/catch` so memory persistence is truly non-critical.

### 7. Lexical Memory Search
**Issue:** `MemoryDao.searchMemories()` uses SQL `LIKE` — no semantic/vector search.  
**Fix:** Integrate an embedding model (e.g. via a local ONNX model or an embeddings API endpoint) and store embedding vectors alongside memory rows.

### 8. `conversationProgrammer` Initialisation Guard
**Issue:** If `initializeLLM()` is never called (no API key configured), command interception is silently bypassed.  
**Fix:** Initialize `conversationProgrammer` in the `AgentOrchestrator` constructor or at application start, independent of LLM key availability, since it only needs `database`, `agentManager`, and `auditLogger`.

### 9. Final Message Attribution Always Uses Coordinator
**Issue:** Step 8 always attributes the response to `coordinator.id / coordinator.name` even when other agents were the primary responders.  
**Fix:** Use the ID/name of the primary responding agent, or use a synthetic `"multi-agent"` attribution when multiple agents contributed.
