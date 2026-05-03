# AgentOrchestrator — Multi-Agent Routing Pipeline

> **Source file:** `app/src/main/java/com/agente/autonomo/agent/AgentOrchestrator.kt`  
> **Test file:** `app/src/test/java/com/agente/autonomo/agent/AgentOrchestratorTest.kt`

---

## Overview

`AgentOrchestrator` is the central entry point for all user messages that require
LLM processing. It implements a sequential multi-stage pipeline that assembles a
`PipelineContext` object from short-term and long-term memory, classifies the user's
intent, routes the message to one or more downstream agents, and optionally audits
the result.

Every stage inside `processUserMessage()` is annotated with a single-line comment
(`// Stage N: …`) so an AI coding agent can trace control flow without external docs.

---

## PipelineContext

The `PipelineContext` data class is the single argument passed to every downstream
agent. It is assembled progressively across Stages 0–3 and then passed unchanged
through Stages 4–7.

```kotlin
data class PipelineContext(
    val userMessage: String,           // raw user input (never pre-processed)
    val recentMessages: List<Message>, // Stage 1 — short-term history (MAX_CONTEXT_MESSAGES=10)
    val memorySnippets: List<Memory>,  // Stage 2 — long-term memory (max 5, importance DESC)
    val commandMeta: CommandResult?,   // Stage 0 — non-null when a command was intercepted
    val intentLabel: String?,          // Stage 3 — e.g. "research", "planning", "execution"
    val intentScore: Float?            // Stage 3 — confidence score [0.0, 1.0]
)
```

### Field Contracts

| Field | Type | Guaranteed non-null? | Notes |
|---|---|---|---|
| `userMessage` | `String` | ✅ | Exact text typed by the user |
| `recentMessages` | `List<Message>` | ✅ | Empty list if no history |
| `memorySnippets` | `List<Memory>` | ✅ | Always `[]` on null/error from MemoryDao |
| `commandMeta` | `CommandResult?` | ❌ | `null` in Stages 1–7 (command already handled) |
| `intentLabel` | `String?` | ❌ | `null` when score ≤ INTENT_THRESHOLD |
| `intentScore` | `Float?` | ❌ | `null` when intentLabel is `null` |

---

## Pipeline Stages

### Stage 0 — Intercept Special Commands

**Location:** Top of `processUserMessage()`, before any DB access.

**What it does:**

1. Checks `::conversationProgrammer.isInitialized` (guard — `initializeLLM()` must
   have been called at least once).
2. Calls `conversationProgrammer.processMessage(userMessage)` which checks:
   - Slash commands: `/reset`, `/help`, `/status`, `/agents`, `/tasks`
     (defined in `SLASH_COMMANDS` companion set, additionally filtered by
     `detectSpecialCommand()`).
   - Natural-language patterns (Portuguese + English) via `ConversationAgentProgrammer`.
3. On match (`isCommand == true`):
   - Persists user message + command response to `MessageDao`.
   - Writes audit record with `correlationId`.
   - Returns `Result.success(commandResponse)` immediately.
   - **Stages 1–7 do NOT execute.**

**Short-circuit contract:**  
When Stage 0 short-circuits, the pipeline does zero LLM API calls, no
`getRecentMessages()` calls, and no `searchMemories()` calls.

---

### Stage 1 — Short-term Context (Recent Messages)

**What it does:**

Calls `MessageDao.getRecentMessages(conversationId, MAX_CONTEXT_MESSAGES)`.

```
Parameters:
  conversationId  : String  — scopes query to current conversation bucket
  limit           : Int     — MAX_CONTEXT_MESSAGES = 10

Return shape:
  List<entity.Message>  ORDER BY timestamp DESC (newest first)
  Mapped → List<api.model.Message> via:
    SenderType.USER   → role = "user"
    SenderType.AGENT  → role = "assistant"
    SenderType.SYSTEM → role = "system"

Stored in:
  PipelineContext.recentMessages
```

**Error handling:** DB exception propagates to outer `catch` → `Result.failure`.

**Note:** The user's current message is persisted (via `saveUserMessage()`) immediately
before this call so that the current turn is included in subsequent internal calls.

---

### Stage 2 — Long-term Memory Injection

**What it does:**

Derives keywords from `userMessage` and queries `MemoryDao.searchMemories()` per keyword.

```
Keyword derivation:
  userMessage.lowercase().split(" ").filter { it.length > 3 }.take(5)
  (simple heuristic stop-word removal; tokens ≤ 3 chars are dropped)

DAO call per keyword:
  MemoryDao.searchMemories(keyword)
  SQL:    LIKE '%<keyword>%' on columns key, value, category
  Filter: is_archived = 0  (active memories only)
  Sort:   importance DESC, access_count DESC

Post-aggregation:
  distinctBy { id }                 → de-duplicate across keyword calls
  sortedByDescending { importance } → highest-importance first
  take(5)                           → hard ceiling of 5 memories

Stored in:
  PipelineContext.memorySnippets
```

**Null / empty result handling:**  
`searchMemories()` may return `null` (some DAO implementations) or an empty list.
Both cases are handled gracefully: `getRelevantMemories()` returns `emptyList()`
without throwing. All DB exceptions are caught internally; memory retrieval failure
is **always non-fatal** to the pipeline.

**Memory injection into LLM prompt:**  
Non-empty `memorySnippets` are serialised into a `Message(role="system")` block
and injected immediately after `agent.systemPrompt` in every agent LLM call
(Stage 4). This ensures the LLM treats long-term memories as authoritative
background knowledge for the current turn.

---

### Stage 3 — Classify Intent and Select Routing Target

**What it does:**

Calls `classifyIntent(userMessage)` to derive `intentLabel` and `intentScore`.

```
classifyIntent algorithm (keyword heuristics, no extra LLM call):

  Research keywords  : pesquisa, busca, encontra, research, find, search, …
  Planning keywords  : plano, planejamento, plan, planning, steps, strategy, …
  Execution keywords : execute, executar, fazer, run, create, build, cria, …

  Score per category = (matched keywords / total keywords) × amplification factor
  Amplification      = min(rawScore × 5, 1.0)  — handles short messages

Routing criteria (in order):
  (1) CommandMeta present → already handled in Stage 0 (unreachable here)
  (2) intentScore > INTENT_THRESHOLD (0.6) → prefer specialist agent
  (3) else                                  → COORDINATOR fallback
```

The `intentLabel` and `intentScore` are stored in `PipelineContext` and also
passed explicitly to `decideAgentsForTask()` so the routing LLM call can
honour the Stage 3 classification.

---

### Stage 4 — Route and Execute Agents

**What it does (routing — `decideAgentsForTask()`):**

```
LLM call: maxTokens=500, temperature=0.3

Payload:
  [system]  coordinator.systemPrompt
  [system]  serialised pipelineContext.memorySnippets (if non-empty)
  [history] pipelineContext.recentMessages
  [user]    routing prompt:
              - Enumerates all active agents (AgentManager.getAllActiveAgents())
              - Includes intentHint when intentScore > INTENT_THRESHOLD
              - Requests JSON: { selectedAgents, reasoning, executionOrder }

Expected LLM JSON:
  {
    "selectedAgents": ["agentId1", "agentId2"],
    "reasoning": "...",
    "executionOrder": "sequential" | "parallel"
  }

Parse failure → fallback AgentDecision(coordinator only, sequential)
LLM null      → Result.failure("Falha na decisão de agentes")
```

**What it does (execution — `executeWithAgents()`):**

```
For each agentId in agentDecision.selectedAgents (sequential):
  1. Resolve agent via AgentManager.getAgent(agentId)
     → null: silently skip
  2. Build LLM payload:
       [system]  agent.systemPrompt
       [system]  serialised pipelineContext.memorySnippets  ← long-term memory
       [history] pipelineContext.recentMessages             ← short-term context
       [user]    pipelineContext.userMessage
  3. Call LLM with agent.maxTokens, agent.temperature
     → null response: silently skip
  4. Append non-null response to results list
  5. AgentManager.recordAgentUsage(agent.id)
  6. AuditLogger.logAction(correlationId)

Result aggregation:
  Non-empty → results.joinToString("\n\n---\n\n")
  Empty     → Result.failure("Nenhum agente conseguiu responder")
```

**NOTE:** `executionOrder="parallel"` is declared in the schema but not yet
implemented. All execution is sequential regardless of the routing decision.

---

### Stage 5 — Optional Cross-Audit

**Activation:** `Settings.crossAuditEnabled == true`.

```
LLM call: maxTokens=500, temperature=0.2
Agent:    First AgentType.AUDITOR agent (no-op if none configured)
Detects:  "REPROVADO" substring in auditor response

Result: failure → logWarning only; response still delivered to user
```

---

### Stage 6 — Persist Agent Response

Writes a `SenderType.AGENT` row to `MessageDao`, attributed to the coordinator.
When multiple agents responded in Stage 4, individual attributions are in the
audit log; the persisted DB row always uses the coordinator.

---

### Stage 7 — Optional Memory Update

**Activation:** `Settings.memoryEnabled == true`.

```
Memory fields:
  id             : UUID
  type           : MemoryType.CONTEXT
  key            : "conversation_<conversationId>_<epochMs>"
  value          : "Usuário: <userMessage>\nResposta: <response[:200]>"
  importance     : 5 (hardcoded mid-range)

KNOWN ISSUE: failure here is not wrapped in its own try/catch.
A DB error during memory write causes Result.failure even though
the LLM response was already obtained. Consider making this non-critical.
```

---

## Correlation ID

A single `UUID.randomUUID().toString()` is generated at the top of
`processUserMessage()` (before Stage 0). Every `auditLogger.logAction()` and
`auditLogger.logAgentDecision()` call within the same invocation shares this
`correlationId`, enabling end-to-end tracing of a complete user turn in
`AuditLogDao.getLogsByCorrelationId()`.

---

## Initialisation Contract

`initializeLLM()` must be called before `processUserMessage()` to enable:

| What is enabled | By whom |
|---|---|
| LLM API calls (Stages 3–5) | `LLMClient(settings)` created if `apiKey` is non-blank |
| Stage 0 command interception | `ConversationAgentProgrammer` initialised unconditionally |

Safe to call multiple times; each call creates a fresh `LLMClient` so settings
changes (new API key, provider switch) are picked up immediately.

If `Settings.apiKey` is blank, `llmClient` remains `null`. All `callLLM()` calls
return `null`, causing `Result.failure` at Stage 3 routing.

---

## Error Handling Matrix

| Condition | Behaviour |
|---|---|
| No API key configured | `callLLM()` returns null → `Result.failure` at Stage 3 |
| `conversationProgrammer` not initialised | Stage 0 silently bypassed |
| `MessageDao.getRecentMessages()` throws | Propagates → `Result.failure` |
| `MemoryDao.searchMemories()` returns null | Caught internally → `emptyList()` (non-fatal) |
| `MemoryDao.searchMemories()` throws | Caught internally → `emptyList()` (non-fatal) |
| Coordinator agent not found | `Result.failure("Agente coordenador não encontrado")` |
| Routing LLM returns null | `Result.failure("Falha na decisão de agentes")` |
| Routing JSON parse fails | Fallback to coordinator-only `AgentDecision` |
| Agent ID not found in Stage 4 | Silently skipped |
| Agent LLM call returns null | Silently skipped |
| All agents return null | `Result.failure("Nenhum agente conseguiu responder")` |
| Cross-audit fails (Stage 5) | `logWarning` only; response still delivered |
| Memory write fails (Stage 7) | Propagates → `Result.failure` (known issue) |

---

## Known Limitations

1. **Memory injection was previously a no-op.** Fixed in this revision: Stage 2
   memories are now injected as a `Message(role="system")` block into every agent
   LLM call in Stage 4.

2. **Parallel execution not implemented.** `executionOrder="parallel"` is declared
   in `AgentDecision` but `executeWithAgents()` always iterates sequentially.

3. **Conversation history is newest-first (DESC).** `MessageDao.getRecentMessages()`
   returns messages in `ORDER BY timestamp DESC`. This means the most recent message
   is at index 0. Consider reversing before injecting into prompts if the LLM
   provider expects chronological order.

4. **Multi-agent responses are concatenated, not synthesised.** When multiple agents
   respond, their outputs are joined with `\n\n---\n\n`. No synthesis or deduplication
   step exists.

5. **Null agent LLM response is silently skipped.** No `logWarning()` is emitted
   when an agent's LLM call returns null. Consider adding observability here.

6. **Memory write failure is fatal to the pipeline.** A DB error in Stage 7
   (`updateMemory()`) causes `Result.failure` even though the LLM response was
   already obtained. The fix is to wrap `updateMemory()` in its own `try/catch`.

7. **Intent classification is keyword-based only.** `classifyIntent()` uses keyword
   count heuristics. Consider replacing with a fast LLM call or on-device ML model
   for higher accuracy.

8. **Token usage is not tracked.** `getLastTokenUsage()` always returns 0. Implement
   real token counting via `ChatCompletionResponse.usage`.

9. **Multi-agent attribution.** The persisted `MessageDao` row (Stage 6) always uses
   the coordinator's ID/name. Individual per-agent attributions are available only
   in the audit log.

---

## Unit Tests

See `AgentOrchestratorTest.kt` for the three required test cases:

| Test | Covers |
|---|---|
| **(a) Normal routing** | `MessageDao.getRecentMessages` + `MemoryDao.searchMemories` return valid data; correct agent invoked with populated `PipelineContext` |
| **(b) Special-command interception** | `/help` command short-circuits; no LLM calls made; no agent invoked |
| **(c) Missing memory graceful handling** | `MemoryDao.searchMemories` returns `null`; routing completes without throwing; `memorySnippets = []`; correct agent still invoked |

Additional unit tests cover `detectSpecialCommand()`, `classifyIntent()`, and
`PipelineContext` construction.
