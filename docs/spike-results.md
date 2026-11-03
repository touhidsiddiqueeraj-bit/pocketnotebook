# Phase 0 Spike — Results Template

Record measurements here as they land (per-device sections). Each item maps to a
go/no-go decision in [PLAN.md](PLAN.md#8-roadmap) / [MODEL_MATRIX.md](MODEL_MATRIX.md).

## Device

- Phone model:
- SoC / RAM:
- Android version:
- Build (llama.cpp commit / app PoC hash):

## 1. LFM3-1.7B Q4_0 — text

| Metric | Target | Measured | Pass? |
|---|---|---|---|
| Decode tok/s @ 4k ctx | ≥ 8 (mid-tier) | | |
| Decode tok/s @ 8k ctx | ≥ 6 | | |
| TTFT (8k ctx) | < 3 s | | |
| Peak RSS (model + KV) | < 2.5 GB | | |
| 5-min sustained gen thermals | no SEVERE | | |

Qualitative: citation format adherence on 10 RAG-style prompts (IFEval spot
check), JSON tree/flashcard output validity x10 runs.

## 2. LFM3-Audio-2B Q4_0 — speech

| Metric | Target | Measured | Pass? |
|---|---|---|---|
| ASR WER (30-min lecture sample, spot-check vs ground truth) | ≈ Whisper-large-v3 | | |
| TTS intelligibility (subjective 1–5) | ≥ 4 | | |
| TTS latency per sentence | < 3 s | | |
| Distinct voices available (count + names) | ≥ 2 usable hosts | | |
| 1,500-word narration drift (sentence-chunked) | no artifacts/garbles | | |
| Voice-chat TTFT (if JNI streaming works) | < 500 ms | | |

## 3. Co-residency

| Metric | Target | Measured | Pass? |
|---|---|---|---|
| Peak RSS, both models + app | < 6 GB (mid) | | |
| Swap-in/swap-out time (LRU eviction) | < 10 s | | |

## 4. Pipeline

| Metric | Target | Measured | Pass? |
|---|---|---|---|
| HF download: resume after airplane-mode kill | works | | |
| sha256 verify + delete-on-corrupt | works | | |
| 100-page PDF → chunks → embeddings | < 60 s | | |

## Decisions

- [ ] Kokoro pack: default / optional / drop — reason:
- [ ] llama.cpp audio via JNI: native path / fallback stack — reason:
- [ ] Default chat context: 4k / 8k / 16k — reason:
- [ ] Tier presets confirmed or amended (MODEL_MATRIX.md):
- [ ] **Go / no-go: proceed to Phase 1**
