# PocketNotebook — Build Plan

**A local-first, on-device clone of Google NotebookLM for Android.**
Sources, grounded chat, audio overviews, mind maps, and study tools — all running
100% offline on the phone, shipped as a sideloadable APK. English-first.

- Status: **Phase 0 — spike & model validation** (see roadmap)
- Last updated: 2026-11-04
- Companion docs: [ARCHITECTURE.md](ARCHITECTURE.md) · [MODEL_MATRIX.md](MODEL_MATRIX.md) · [spike-results.md](spike-results.md)

---

## 1. Product definition

Replicate the NotebookLM core loop locally:

> **Add sources → ask questions with citations → generate artifacts (audio overviews,
> reports, mind maps, flashcards) → study.**

Everything happens on-device: no account, no network calls after model download,
no telemetry. The only cloud touch is downloading models and sources (web pages,
YouTube transcripts) that the user explicitly requests.

### 1.1 NotebookLM feature-parity map

Research basis: Google's NotebookLM help center and the 2025–2026 feature
announcements (audio overview formats, reports, flashcards/quizzes, learning guide).

| NotebookLM feature | What it does | Local plan | Feasibility | Phase |
|---|---|---|---|---|
| Notebooks + sources | 50 sources/notebook, 200 MB / 500k words per source | Local Room DB, no artificial limits (storage-bound) | Trivial | 1 |
| Source ingestion | PDF, DOCX, TXT, MD, URLs, YouTube (transcript), audio, images w/ OCR, CSV | PDF (PDFBox-Android), TXT/MD/HTML/EPUB/pasted text, URL (Jsoup), audio (LFM3-Audio ASR), images (ML Kit OCR). YouTube = paste-transcript in v1 | Easy–medium | 1, 5 |
| Grounded chat + citations | Answers with clickable [n] citations jumping to source passage | Hybrid retrieval (BM25 + vector), numbered-snippet prompting, citation repair pass, jump-to-chunk UI | **Core risk #1** | 2 |
| Audio Overviews | 2-host podcast (Deep Dive), Brief (1 host), Critique, Debate | LLM-scripted 2-voice dialogue → TTS segments → Media3 playlist | Medium | 3 |
| Interactive audio (join the podcast) | Listener asks questions mid-overview | LFM3-Audio-2B causal streaming voice chat | Now feasible (was v2 stretch) | 5+ |
| Mind Maps | Expandable topic tree | GBNF/JSON-constrained tree gen + Compose canvas renderer, PNG/SVG export | Medium | 4 |
| Studio reports | Briefing doc, study guide, FAQ, timeline, blog post, custom | Prompt templates + RAG + markdown renderer | Easy | 4 |
| Flashcards + Quizzes | Study sets, "explain", difficulty control, spaced repetition | JSON-constrained generation + SM-2 scheduler | Easy | 5 |
| Noteboard | Save/pin responses, convert to source | Room persistence | Trivial | 5 |
| Learning Guide | Socratic tutor mode | System-prompt variant of chat | Easy | 5 |
| Video Overviews | Narrated slideshow | MediaCodec slideshow + TTS | Stretch | backlog |
| Sharing / Classroom / OpenStax | Cloud collab, LMS | Export notebook bundle (.zip) only | Out of scope | backlog |

### 1.2 Non-goals (v1)

- Cloud sync, collaboration, accounts
- Non-English (Kokoro v1.1-zh and multilingual models exist; revisit post-v1)
- YouTube downloading (ToS + GPL-3.0 NewPipeExtractor contagion — paste transcripts instead)
- iOS

---

## 2. Target devices & budgets

Real measured baselines (see References):

| Device class | Chip / RAM | Expected decode (Q4_0) | Notes |
|---|---|---|---|
| Flagship | SD 8 Gen 2/Elite, D9300+, 12–16 GB | 15–30 tok/s (LFM3-1.7B), 10–20 tok/s (2B audio) | Both models resident comfortably |
| Mid | SD 8 Gen 1 class, 8–12 GB | 8–15 tok/s (LFM3-1.7B) | Two-model LRU loading |
| Low | 6–8 GB, 2019–2021 chips | 5–10 tok/s | LFM3-1.7B only; audio overview baking is slow but overnight-able |

Experience budgets (targets, verify in Phase 0):

| Operation | Budget |
|---|---|
| Chat first token | < 3 s (mid-tier, 8k ctx) |
| Chat sustained decode | ≥ 8 tok/s usable, ≥ 15 comfortable |
| Citation jump | < 100 ms |
| Audio overview (8 min audio) | ≤ 10 min bake on mid-tier, background w/ notification |
| ASR of 30-min lecture | ≤ 10 min, foreground service |
| Ingest 100-page PDF | ≤ 60 s incl. chunking + embedding |

---

## 3. Stack

| Role | Choice | Why | License |
|---|---|---|---|
| App | Kotlin + Jetpack Compose | JNI control, foreground services, Media3, PDF plumbing | — |
| LLM runtime | **llama.cpp via JNI** (NDK, `libllama.so`) | GGUF, GBNF grammar-constrained decoding, mmap, ngram speculative decode, embeddings support, LFM3 GGUFs run in mainline | MIT |
| Text model | **LFM3-1.7B Q4_0** (~1 GB) — default | Fastest in class on-device (1.8–2.8× next model, llama.cpp Q4_0), IFEval 96.66, 256K ctx | LFM Open 1.0 |
| Audio model | **LFM3-Audio-2B Q4_0** (~1.05 GB) — on demand | ASR 6.2 WER (≈ Whisper-large-v3), TTS UTMOS 3.8, causal encoder TTFT ~50 ms, streaming LiquidCodec | LFM Open 1.0 |
| Optional TTS pack | Kokoro-82M via sherpa-onnx | 2 distinct podcast-host voices if LFM3 voice variety disappoints | Apache-2.0 |
| Embeddings | bge-small-en-v1.5 (33M, 384-d) via llama.cpp GGUF | Tiny, fast; no LFM embedding model exists | MIT |
| Vector search | SQLite FTS5 (BM25) + brute-force cosine over fp16 blobs, reciprocal rank fusion | ~30k chunks → sub-300 ms on phone | — |
| DB | Room | notebooks/sources/chunks/chats/artifacts | — |
| PDF | PDFBox-Android (+ ML Kit OCR fallback) | text extraction + scanned-page fallback | Apache-2.0 |
| Web fetch | Jsoup | URL sources | MIT |
| Background work | WorkManager + foreground services | ingestion, audio baking, downloads | — |
| Playback | Media3 ExoPlayer + MediaSession | segment playlist, background, lockscreen | — |
| Model delivery | **HF Hub integration** (huggingface.co API, direct resolve endpoints) | users download any GGUF they want, with pause/resume; curated catalog for defaults | — |

**Kokoro vs LFM3-Audio:** Kokoro stays a first-class *optional* pack. Decision
criteria recorded in Phase 0 spike (voice count/quality of LFM3-Audio on-device).

---

## 4. Model strategy

See [MODEL_MATRIX.md](MODEL_MATRIX.md) for the full table. Principles:

1. **LFM3-first**: one runtime (llama.cpp), one GGUF format, two default models
   (~2.1 GB total), both tuned on real phones by Liquid AI's hardware-in-the-loop search.
2. **HF freedom**: a model manager lists a curated catalog (LFM3 family, Qwen3.5,
   Kokoro) **and** lets users search/download any HF GGUF repo (pause/resume,
   disk-space check, sha256 verification where the repo publishes it).
3. **Never bundle weights in the APK**: sideload-friendly APK size (~80 MB,
   arm64-v8a only), first-run model download with progress + retry.
4. **LRU model residency**: LFM3-1.7B stays resident for chat; LFM3-Audio-2B
   loads on demand; evict under memory pressure (mmap keeps eviction cheap).

---

## 5. Feature pipelines

### 5.1 Ingestion (Phase 1)
`PDF/DOCX/TXT/MD/EPUB/HTML/pasted → extractor → chunker (~400 tok, 15% overlap,
page/paragraph provenance) → embeddings (bge-small) → Room (+FTS5 index)`.
Audio path: `audio file → LFM3-Audio ASR (sequential mode) → text source`.
URLs: `Jsoup fetch → readability-style extraction → text source`.

### 5.2 Grounded chat (Phase 2)
1. Hybrid retrieve top-8 chunks (BM25 ∪ cosine, RRF fusion).
2. Numbered-snippet prompt `[1]…[8]` + few-shot citation drilling, temp ≤ 0.3.
3. **Citation repair pass**: strip invalid [n]; if zero citations but retrieval
   confident, append "Sources used" footer built from retrieval metadata
   (citations must never come from generation alone).
4. UI: `[n]` → chip → tap opens source viewer scrolled to chunk, highlighted.

### 5.3 Audio overview (Phase 3)
1. **Map-reduce digest**: per-source ~300-word summary → notebook digest ≤ 8k tokens.
2. **Script**: 2-host dialogue, 18–28 turns (~1,500 words ≈ 8 min), JSON
   `{speaker, text}[]`, **grammar-constrained** — with LFM3-1.7B's IFEval 96.66
   this should be near-deterministic. Format templates: Deep Dive / Brief / Debate / Critique.
3. **TTS**: per-sentence synthesis, voice per host; LFM3-Audio streaming or
   Kokoro 2-voice pack; fade-stitch into segments.
4. **Playback**: Media3 playlist, background audio, MediaSession notification,
   progress + ETA; bake runs in foreground service with notification.

### 5.4 Mind map / reports / flashcards / quizzes (Phase 4–5)
Same skeleton: RAG context → constrained JSON/markdown → Compose renderer.
Flashcards carry `source_chunk_id` → "explain" opens the passage (parity with
NotebookLM's explain-with-citations). SM-2 scheduling. Reports render markdown;
mind map exports PNG/SVG.

### 5.5 Interactive audio (Phase 5+, post-v1 candidate)
LFM3-Audio-2B interleaved mode: causal encoder prefills while the user speaks,
~50 ms TTFT after end-of-speech, LiquidCodec decodes ~87× real-time single-thread.
"Join the overview": pause playlist → user question → model answer in host voice →
resume. This is NotebookLM's most distinctive feature and the main reason we
bet on LFM3-Audio.

---

## 6. Native layer plan

- One NDK module building `libllama.so` + our thin `libpocketllm.so` JNI shim.
- JNI surface (draft): `loadModel`, `unloadModel`, `createCompletionStream(callback)`,
  `embed(text, out)`, `setGrammar(gbnf)`, plus audio: `asrFile`, `ttsStream`,
  `voiceChatBegin/Feed/End` once the llama.cpp audio API on Android is proven (Phase 0 item).
- Gradle: `externalNativeBuild` with CMake; prebuilt llama.cpp pinned by commit SHA.
- Threading: dedicated generation thread; big-core affinity optional;
  thermal monitor reads `BatteryManager` thermal status → suggest pause or slower bake.

---

## 7. Risks & mitigations

| # | Risk | Mitigation |
|---|---|---|
| 1 | Small models drift from citation format | Few-shot drilling + GBNF for structured outputs + repair pass + retrieval-derived footers |
| 2 | RAM pressure (two ~1 GB models + app) | mmap, LRU eviction, tier picker, 8k default context, KV cache quantization (q8_0) |
| 3 | Thermal throttling in long bakes | ngram speculative decoding (measured 1.34–2.2× on phones), foreground-service with thermal feedback, "overnight bake" mode |
| 4 | LFM3-Audio voice variety insufficient for 2-host podcasts | Kokoro optional pack (decision point in Phase 0) |
| 5 | llama.cpp audio API immature on Android NDK | Phase 0 spike gate; fallback = ASR/TTS via separate well-trodden paths (whisper.cpp + sherpa-onnx) at cost of RAM/complexity |
| 6 | LFM Open License v1.0 revenue cap ($10M) | Fine for this project; document it; users choosing Apache/MIT models (Qwen, Kokoro, bge) keep the stack clean if that ever changes |
| 7 | HF model zoo = users download incompatible GGUFs | Catalog with verified entries; warning + metadata check (arch, size) for arbitrary repos; per-model "verified" badge |

---

## 8. Roadmap

Pace assumption: ~8–10 h/week hobby time.

| Phase | Scope | Exit criteria | Est. |
|---|---|---|---|
| **0. Spike** | llama.cpp NDK PoC on target phone(s); LFM3-1.7B + LFM3-Audio-2B benchmarks; HF downloader PoC; PDF extraction PoC | Go/no-go decisions recorded in [spike-results.md](spike-results.md) | 1–2 wk |
| **1. Skeleton** | Compose app shell, Room schema, notebooks + sources, PDF/TXT/MD import, chunker, embeddings, FTS5, source viewer w/ search | Import 100-page PDF, full-text search it | 2–3 wk |
| **2. Grounded chat** | Hybrid retrieval, chat UI, citation chips + jump-to-source, model manager (curated + arbitrary HF GGUF), tier picker | Citations work on 3 test PDFs; user model download + swap works | 3 wk |
| **3. Audio overview** | Digest pipeline, grammar-constrained scripting, TTS (LFM3-Audio and/or Kokoro), bake service, Media3 player, Deep Dive + Brief | 8-min overview generated + played fully offline | 3 wk |
| **4. Studio + mind map** | Report templates, constrained-JSON mind map, canvas renderer, export | All artifacts generate + render | 2–3 wk |
| **5. Study + more sources** | Flashcards/quiz + SM-2, noteboard, audio ingestion via ASR, URL import, Debate/Critique formats, Learning Guide mode | Full study session on real material | 2–3 wk |
| **6. Polish → APK** | Thermal/battery guardrails, pause/resume everywhere, export/import notebook bundles, signed release via tag | Daily-driver release (v1.0) | 2–3 wk |

**Post-v1 backlog:** interactive audio overviews (join the podcast), video overviews,
multilingual (Kokoro v1.1-zh / LFM3 multilingual), optional LFM3-Omni for
"point camera + ask aloud".

---

## 9. Open questions

1. LFM3-Audio `--tts-voice` count and character on-device → Phase 0 (Kokoro decision).
2. Does llama.cpp's TTS/audio-in path work through JNI on Android today, or only
   via the CLI runners? → Phase 0.
3. Two models resident on 8 GB phones: real peak RSS? → Phase 0.
4. Long-form narration drift: can LFM3-Audio hold up over 1,500-word scripts
   chunked by sentence? → Phase 0.

---

## References

- Liquid AI, *Introducing LFM3* (Oct 27, 2026) — architecture, benchmarks, on-device numbers
- Liquid AI, *LFM2-Audio: An End-to-End Audio Foundation Model* (Oct 1, 2025)
- LFM2.5-Audio-1.5B & LFM3-Audio-2B model cards (Hugging Face)
- Liquid AI, *LFM Open License v1.0* (Apache-based, $10M revenue cap)
- Google, Gemini Notebook help center — sources, limits, audio overview formats
- Google blog, *6 ways to use NotebookLM to master any subject* (flashcards, quizzes, reports)
- llama.cpp discussion #30549 — on-phone tuning (big-core pinning, ngram spec-decode)
- r/LocalLLaMA on-device benchmarks — Qwen3-4B/1.7B on SD 8 Gen 1
- speechcentral.net — Kokoro-on-Android RTF analysis (adreno-llms OpenCL)
