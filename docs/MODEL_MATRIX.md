# PocketNotebook — Model Matrix

Default strategy: **LFM3-first, HF-open**. Two default models cover the whole
product (chat + speech); everything else is optional or user-supplied via the
HF downloader. English-first.

Last updated: 2026-11-04. Verify sizes/quants against the HF repos at download time.

## Default stack

| Role | Model | Quant / size | Why | License |
|---|---|---|---|---|
| Chat / RAG / generation (persistent) | **LFM3-1.7B** | Q4_0 ≈ 1 GB | Fastest in-class on-device (1.8–2.8× next model per Liquid's llama.cpp Q4_0 phone benchmarks), IFEval 96.66 → reliable citation/JSON formats, 256K ctx, 128K KV ≈ 1.6 GB, hardware-tuned on real phones | LFM Open 1.0 |
| Speech in/out (on demand) | **LFM3-Audio-2B** | Q4_0 ≈ 1.05 GB | ASR 6.2 WER EN (≈ Whisper-large-v3), TTS UTMOS 3.8 / WER 1.4, causal encoder TTFT ~50 ms, LiquidCodec ~87× real-time single CPU thread, streaming decode, 18 languages (EN-first for us) | LFM Open 1.0 |
| Embeddings | **bge-small-en-v1.5** | F16 ≈ 35 MB (33M, 384-d) | Tiny + proven; no LFM embedding model exists | MIT |

## Optional packs

| Pack | Model | Size | Purpose | License |
|---|---|---|---|---|
| Narration | Kokoro-82M v1.0 (sherpa-onnx) + 2 voices | ≈ 330 MB | 2 distinct podcast-host voices for Deep Dive format if LFM3-Audio's voice set underwhelms (Phase 0 decision) | Apache-2.0 |
| Bigger brain | Qwen3.5-4B / 9B GGUF | Q4 ≈ 2.4 / 5.3 GB | More knowledge at RAM + speed cost; flagship phones only | Apache-2.0 (verify per-repo) |
| Fallback ASR | whisper-base/small int8 (whisper.cpp) | ≈ 80 MB | Only if LFM3-Audio JNI proves immature — costs RAM + complexity | MIT |
| Future omni | LFM3-Omni-2B | Q4 ≈ 1.2 GB | "Point camera + ask aloud", spoken VQA | LFM Open 1.0 |

## Device-tier presets

| Tier | RAM / chip | Chat model | Audio model | Notes |
|---|---|---|---|---|
| Flagship | 12–16 GB, SD 8 Gen 2+ | LFM3-1.7B (option: Qwen3.5-4B) | LFM3-Audio-2B | Both resident |
| Mid | 8–12 GB | LFM3-1.7B | LFM3-Audio-2B (LRU swap) | Default preset |
| Low | 6–8 GB | LFM3-1.7B (Q4_0, 4k ctx) | optional; bakes are slow but overnight-able | Warn on first use |

## HF integration requirements (model manager)

1. **Curated catalog** (bundled JSON, updatable): repo, quant file, size, sha256,
   role, min-RAM, license, "verified" flag. Defaults above ship pinned.
2. **Arbitrary repo download**: user pastes any HF GGUF repo (e.g.
   `LiquidAI/LFM3-Audio-2B-GGUF`, `Qwen/Qwen3.5-4B-GGUF`); app lists files,
   shows size, checks device compatibility heuristics (arch metadata, RAM).
   Show license badge + a "unverified — may not work" notice.
3. **Robust download**: resumable (Range), pause/resume, retry with backoff,
   disk-space preflight, sha256 verify when available, delete-on-corrupt.
4. **Storage**: app-private external dir (`getExternalFilesDir`, no-backup flag)
   so models survive app updates but never leak to other apps.
5. **Swap UX**: model manager shows active model per role + resident RAM;
   switching models evicts LRU.

## License notes

- **LFM Open License v1.0** (LFM3 family, LFM2.5-Audio): Apache-2.0-based,
  royalty-free, no copyleft; commercial use free **below $10M annual revenue**.
  Fine for this project; flagged for the record.
- Kokoro, sherpa-onnx, whisper.cpp, llama.cpp, PDFBox, bge: Apache-2.0 / MIT —
  no restrictions relevant to us.
- Never bundle weights in the APK; download-on-first-run keeps the sideload
  ~80 MB and avoids redistributing model licenses.

## Phase 0 decision points (feeds this table)

1. LFM3-Audio voice count & character on-device → Kokoro pack default or optional.
2. LFM3-Audio JNI maturity (ASR + TTS through JNI on Android) → fallback stack?
3. Two-model peak RSS on mid-tier phone → tier presets above.
4. LFM3-1.7B tok/s @ 4k/8k on real device → chat budget, default ctx len.
