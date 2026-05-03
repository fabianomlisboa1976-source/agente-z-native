# Structured Agent Memory with Vector Similarity Search

> **Status:** Implemented in schema version 3.

---

## Motivation

The previous memory retrieval strategy executed SQL `LIKE '%keyword%'` queries for
each token in the user's message.  While simple, it had two significant weaknesses:

1. **Lexical brittleness** — queries like "find my note about project X" would miss a
   memory stored under key `"projeto_x_status"` even though the semantic intent is
   identical.
2. **Poor ranking** — results were sorted only by `importance` with no relevance
   signal derived from the query content.

For a 24/7 agent accumulating thousands of memories, these weaknesses compound:
 the most relevant context is increasingly buried under noise.

This document describes the replacement: an on-device **embedding + cosine
similarity** retrieval system.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                     AgentOrchestrator                           │
│  Stage 2: getRelevantMemories(query)                            │
│                    ↓                                            │
│             MemorySearchEngine.search(query, topK=5)            │
│                    ↓                                            │
│    ┌───────────────────────────────────────┐                    │
│    │          Phase 1: Retrieval            │                    │
│    │  MemoryDao.getAllEmbeddedMemories()    │                    │
│    │  (all active rows with non-null BLOB) │                    │
│    └───────────────────────────────────────┘                    │
│                    ↓                                            │
│    ┌───────────────────────────────────────┐                    │
│    │          Phase 2: Re-ranking           │                    │
│    │  EmbeddingEngine.embed(query)          │                    │
│    │  for each candidate:                  │                    │
│    │    cosineSimilarity(queryVec, memVec) │                    │
│    │    combined = 0.75*sim + 0.25*imp/10 │                    │
│    │  → filter(sim ≥ 0.20)                │                    │
│    │  → sortedByDescending(combined)       │                    │
│    │  → take(topK)                         │                    │
│    └───────────────────────────────────────┘                    │
│                    ↓                                            │
│    ┌───────────────────────────────────────┐                    │
│    │    Supplementary keyword fallback      │                    │
│    │  (for legacy rows without embedding)  │                    │
│    └───────────────────────────────────────┘                    │
└─────────────────────────────────────────────────────────────────┘
```

---

## Components

### EmbeddingEngine

`app/src/main/java/com/agente/autonomo/memory/EmbeddingEngine.kt`

- Singleton backed by an ONNX Runtime `OrtSession`.
- Model: all-MiniLM-L6-v2 (quantised INT8, ≈ 22 MB).
- Output: 384-dimensional L2-normalised float vector.
- Thread-safety: `Mutex` serialises ONNX session calls.
- NNAPI acceleration enabled where available; CPU fallback is automatic.
- Minimal whitespace tokeniser (see `docs/embedding-model-setup.md` for notes on
  production-grade WordPiece replacement).

### MemorySearchEngine

`app/src/main/java/com/agente/autonomo/memory/MemorySearchEngine.kt`

- Orchestrates Phase 1 (retrieval) and Phase 2 (re-ranking).
- Combined score: `0.75 × cosine + 0.25 × (importance / 10)`.
- Similarity threshold: 0.20 (configurable via `SIMILARITY_THRESHOLD`).
- Keyword fallback fills remaining slots when embedded candidates are insufficient.
- `backfillEmbeddings()` processes up to 50 legacy rows per call.

### EmbeddingBackfillWorker

`app/src/main/java/com/agente/autonomo/memory/EmbeddingBackfillWorker.kt`

- `CoroutineWorker` managed by WorkManager.
- Enqueued at application start with `ExistingWorkPolicy.KEEP` (idempotent).
- Processes batches of 50; re-enqueues itself if more rows remain.
- Returns `Result.retry()` on failure (WorkManager applies exponential back-off).

### Room schema changes

| Change | Detail |
|--------|--------|
| New column | `memories.embedding BLOB DEFAULT NULL` |
| New DAO method | `getAllEmbeddedMemories()` — fetches rows with non-null embedding |
| New DAO method | `getMemoriesWithoutEmbedding(limit)` — fetches legacy rows for back-fill |
| New DAO method | `updateEmbedding(id, blob, now)` — persists computed embedding |
| Migration | `MIGRATION_2_3` — `ALTER TABLE memories ADD COLUMN embedding BLOB DEFAULT NULL` |

---

## Data Flow: New Memory Insertion

```
AgentOrchestrator.updateMemory()
    ↓
 EmbeddingEngine.embed(key + ": " + value)
    ↓  [FloatArray, 384 dims, L2-normalised]
 EmbeddingEngine.floatArrayToBytes()
    ↓  [ByteArray, 1536 bytes]
 Memory.copy(embedding = blob)
    ↓
 MemoryDao.insertMemory(memory)   ← stored immediately, no back-fill needed
```

## Data Flow: Memory Retrieval (Stage 2)

```
MemorySearchEngine.search(query)
    ↓
 EmbeddingEngine.embed(query)     ← 384-dim query vector
    ↓
 MemoryDao.getAllEmbeddedMemories() ← all active rows with BLOB
    ↓
 for each candidate:
    EmbeddingEngine.bytesToFloatArray(candidate.embedding)
    cosineSimilarity(queryVec, candidateVec)
    combinedScore = 0.75 * sim + 0.25 * (importance / 10)
    filter(sim ≥ SIMILARITY_THRESHOLD)
    ↓
 sortedByDescending(combinedScore).take(topK)
    ↓
 supplement with keyword fallback for legacy rows if needed
    ↓
 → List<Memory>  (returned to AgentOrchestrator Stage 2)
```

---

## Fallback Chain

| Condition | Behaviour |
|-----------|----------|
| EmbeddingEngine throws on embed | Zero vector returned → keyword fallback activated |
| getAllEmbeddedMemories throws | Outer catch in `search()` → `emptyList()` (non-fatal) |
| Candidate BLOB corrupt | `mapNotNull` skips the row; warning logged |
| All candidates below threshold | Result set is empty; keyword fallback supplements |
| MemoryDao.searchMemories throws | Caught inside `keywordFallback`; empty list returned |

---

## Performance Budget

| Operation | Typical latency | Notes |
|-----------|----------------|-------|
| ONNX inference (CPU, arm64) | 30–80 ms | Varies by seq length; cap is 128 tokens |
| ONNX inference (NNAPI) | 10–30 ms | Requires NNAPI-compatible SoC |
| Room `getAllEmbeddedMemories()` | < 5 ms | SQLite scan on indexed table |
| Cosine similarity loop (1000 rows) | < 2 ms | Pure Kotlin, Dispatchers.Default |
| Total Stage 2 overhead | ≈ 50–90 ms | Acceptable for a 24/7 background agent |

The ONNX session is reused across calls (singleton); only tokenisation and
tensor allocation are repeated per request.

---

## Testing

| Test file | Coverage |
|-----------|----------|
| `EmbeddingEngineTest.kt` | `l2Normalise`, `cosineSimilarity`, `meanPool`, float↔byte round-trip, constants |
| `MemorySearchEngineTest.kt` | Scoring weights sum to 1, constants, DAO error resilience, `Memory.equals` semantics |
| `AgentOrchestratorTest.kt` | Existing tests unchanged; Stage 2 is exercised indirectly |

---

## Known Limitations

1. **Vocabulary is hash-based, not WordPiece.** Embedding values differ from the
   reference Python model.  Semantic ordering is preserved but cross-system
   comparison is not possible without replacing the tokeniser.

2. **Full candidate scan.** Phase 1 loads all embedded memories into memory for
   scoring.  For < 10 000 rows this is fast.  Beyond that, consider partitioning
   by `category` or `type` before scanning.

3. **No ANN index.** There is no approximate-nearest-neighbour index (e.g. HNSW).
   The brute-force cosine loop is O(N × D).  ANN becomes beneficial beyond ≈ 50 000
   rows, which is unlikely in a single-device scenario.

4. **ONNX model not bundled in git.** Must be downloaded separately before building.
   See `docs/embedding-model-setup.md`.
