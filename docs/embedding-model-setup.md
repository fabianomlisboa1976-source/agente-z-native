# Embedding Model Setup

## Overview

The `MemorySearchEngine` uses **all-MiniLM-L6-v2** (quantised INT8 ONNX export,
≈ 22 MB) to produce 384-dimensional sentence embeddings for semantic memory
retrieval.  The model is **not** tracked in git because of its size.  This
document explains how to obtain and place the model file before building the
APK.

---

## Quick Start

### Option A — Download the pre-quantised file (recommended)

```bash
# From the repo root
mkdir -p app/src/main/assets/models
curl -L \
  "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx" \
  -o app/src/main/assets/models/minilm_l6_v2_quantized.onnx
```

Verify the file size is roughly 22–23 MB before building.

### Option B — Export from the Hugging Face Hub yourself

```bash
pip install optimum[exporters] transformers
optimum-cli export onnx \
  --model sentence-transformers/all-MiniLM-L6-v2 \
  --optimize O2 \
  --device cpu \
  minilm_onnx/
# Copy the resulting model_quantized.onnx
cp minilm_onnx/model_quantized.onnx \
   app/src/main/assets/models/minilm_l6_v2_quantized.onnx
```

---

## Model Contract

The `EmbeddingEngine` expects the ONNX model to satisfy **one** of the following
output shapes.  The engine detects both transparently:

| Output name | Shape | Notes |
|---|---|---|
| `sentence_embedding` | `[1, 384]` | Used directly (no pooling needed) |
| `last_hidden_state` | `[1, seqLen, 384]` | Mean-pooled over the sequence dim |

If the model exposes `sentence_embedding`, it is preferred because it avoids the
mean-pool step in Kotlin.

### Inputs

| Name | Shape | dtype |
|---|---|---|
| `input_ids` | `[1, seqLen]` | INT64 |
| `attention_mask` | `[1, seqLen]` | INT64 |

`seqLen` is capped at 128 tokens by `EmbeddingEngine.tokenise()`.

---

## CI / CD

The GitHub Actions workflow (`.github/workflows/build-apk.yml`) downloads the
model during the build step.  Add the following step **before** the Gradle
assemble task:

```yaml
- name: Download MiniLM ONNX model
  run: |
    mkdir -p app/src/main/assets/models
    curl -fsSL \
      "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx" \
      -o app/src/main/assets/models/minilm_l6_v2_quantized.onnx
```

---

## Tokeniser Note

`EmbeddingEngine` ships with a **minimal whitespace tokeniser** that maps words
to a stable hash-based vocabulary rather than the original WordPiece vocabulary.
This means embedding _values_ will differ from the reference Python implementation,
but **relative semantic distances are preserved** — words that are close in meaning
produce embeddings that are closer in cosine space than words that are unrelated.

For production-grade similarity with exact parity against the reference model,
replace `tokenise()` with a proper WordPiece tokeniser backed by the
`vocab.txt` file (also downloadable from the Hugging Face Hub).

---

## .gitignore

Add the following to the project root `.gitignore` to avoid accidental commits
of large binary model files:

```gitignore
# ONNX models — download separately (see docs/embedding-model-setup.md)
app/src/main/assets/models/*.onnx
```
