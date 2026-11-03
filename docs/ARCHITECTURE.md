# PocketNotebook — Architecture

Target architecture. The scaffold (`:app`) is the seed; modules land as phases
progress (see [PLAN.md](PLAN.md)).

## System overview

```mermaid
flowchart TB
    subgraph UI["Jetpack Compose UI"]
        NL[Notebook list]
        SV[Source viewer]
        CH[Chat + citation chips]
        AP[Audio player]
        MM[Mind map canvas]
        ST[Studio / flashcards / quizzes]
        MGM[Model manager]
    end

    subgraph CORE["Core (Kotlin)"]
        ING[Ingestion: PDF/DOCX/TXT/MD/EPUB/HTML/paste → chunker → embedder]
        RET[Retrieval: FTS5 BM25 ∪ vector cosine → RRF fusion → rerank]
        GEN[Generators: chat / overview script / reports / mindmap / quiz — GBNF-constrained]
        PIPE[Pipelines: WorkManager foreground services — ingest, audio bake, downloads]
        HF[HF Hub client: catalog, search, pause/resume downloads, sha256 verify]
    end

    subgraph NATIVE["Native (NDK)"]
        LLM["libllama.so + JNI shim
        LFM3-1.7B (persistent) · LFM3-Audio-2B (on demand)
        GBNF grammars · mmap · ngram speculative decode"]
        EMB["bge-small-en-v1.5 GGUF (embeddings)"]
    end

    subgraph DATA["Room / SQLite"]
        DB[(notebooks · sources · chunks + embeddings · chats · artifacts)]
        FTS[(FTS5 index)]
    end

    UI --> CORE
    ING --> DB
    RET --> FTS
    RET --> DB
    GEN --> LLM
    GEN --> EMB
    PIPE --> ING
    PIPE --> GEN
    PIPE --> HF
    CH --> RET
    SV --> DB
    MGM --> HF
    LLM --> DB
```

## Audio overview pipeline (Phase 3)

```mermaid
flowchart LR
    S[sources] --> MR[map-reduce digest ≤ 8k tok]
    MR --> SC[script gen: 2-host JSON dialogue, grammar-constrained]
    SC --> TN[text normalization]
    TN --> TTS[TTS per sentence — LFM3-Audio streaming or Kokoro pack]
    TTS --> SEG[WAV segments]
    SEG --> BAKE[stitch + fades → m4a, foreground service w/ notification]
    BAKE --> PL[Media3 playlist + MediaSession]
```

## Data model (Room, Phase 1)

```
Notebook(id, title, createdAt)
Source(id, notebookId, type[pdf|text|md|html|epub|audio|url|pasted|ocr],
       title, filePath?, wordCount, status[pending|processing|ready|failed],
       addedAt)
Chunk(id, sourceId, ordinal, text, page?, paragraph?, embedding BLOB, tokens)
   -- FTS5 virtual table over Chunk(text) for BM25
ChatMessage(id, notebookId, role, content, citationJson?, createdAt)
Artifact(id, notebookId, kind[overview|report|mindmap|flashcards|quiz|note],
         status, payloadJson, audioPath?, createdAt)
ModelRecord(repoId, file, sizeBytes, sha256?, kind[chat|audio|embedding|tts],
            verified, installedAt)
```

Citations are `Chunk.id` references inside `citationJson` — provenance is a
first-class column, not a regex afterthought.

## Model lifecycle

```mermaid
stateDiagram-v2
    [*] --> Downloading: user picks (catalog or arbitrary HF repo)
    Downloading --> Verified: sha256 / size check
    Verified --> Resident: load for use (mmap)
    Resident --> Evicted: LRU under memory pressure / user swap
    Evicted --> Resident: reload (cheap, mmap)
    Downloading --> Paused: pause/resume supported
    Paused --> Downloading
```

- LFM3-1.7B: **persistent** residency (chat is the primary surface).
- LFM3-Audio-2B: **on-demand**, loaded by ingestion (ASR), audio bake (TTS),
  or interactive voice; evicted after idle timeout on low-RAM tiers.
- Embeddings model: tiny, loaded during ingestion only.

## Threading & services

- One dedicated generation thread per active model; JNI callbacks marshal to
  Kotlin `Flow` for token streaming.
- WorkManager jobs: `IngestWorker` (per source), `EmbedWorker`,
  `AudioBakeWorker`, `ModelDownloadWorker` (expedited + progress notification).
- Foreground services for: audio bake, ASR ingestion, audio overview playback
  (MediaSession).
- Thermal policy: `PowerManager.getCurrentThermalStatus()` polled during bakes;
  `SEVERE` → pause bake + notify; `MODERATE` → drop thread count.

## Target module layout (evolves from `:app`)

```
:app                     # shell, navigation, DI wiring
:core:database           # Room, FTS5, migrations
:core:models             # model manager, HF client, residency/LRU
:core:native             # JNI bindings, llama.cpp build (CMake), GBNF grammars
:core:ingestion          # extractors, chunker, embedder pipeline
:core:retrieval          # hybrid search, fusion, rerank
:feature:chat            # chat UI + citation engine
:feature:overview        # audio overview script/bake/play
:feature:studio          # reports, mind map, flashcards, quizzes
:feature:sources         # source viewer, importer UI
```

## JNI surface (draft — validated in Phase 0)

```kotlin
object PocketLlm {
    fun loadModel(path: String, kind: ModelKind, ctxLen: Int): Long   // handle
    fun unload(handle: Long)
    fun completeStream(handle: Long, prompt: String, grammar: String?,
                       params: GenParams): Flow<TokenEvent>
    fun embed(handle: Long, text: String): FloatArray
    // audio (LFM3-Audio-2B) — gate on Phase 0 findings:
    fun asrFile(handle: Long, wavPath: String, sysPrompt: String): String
    fun ttsToFile(handle: Long, text: String, voice: String, outWav: String)
    // future (interactive mode):
    fun voiceChatBegin(...): SessionHandle
    fun voiceChatFeed(session: Long, pcm: ShortArray)
    fun voiceChatStream(session: Long): Flow<AudioEvent>
}
```

## CI/CD

- **`android-ci.yml`** — every push/PR to `main`/`develop`: unit tests, lint,
  `assembleDebug`, APK artifact upload. Caching via `gradle/actions/setup-gradle`.
- **`release.yml`** — push a `v*` tag: tests, `assembleRelease` (signed if repo
  secrets set, debug-key fallback otherwise), APK attached to a GitHub Release
  with generated notes.
- Dependabot: weekly Gradle + Actions updates.
- Native (`llama.cpp`) build lands in Phase 1 as a CI job with NDK cache —
  the workflow already reserves room for it.
