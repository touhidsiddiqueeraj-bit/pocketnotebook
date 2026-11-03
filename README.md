# PocketNotebook 📱🎙️

**A local-first, on-device clone of Google NotebookLM for Android.**
Add sources, chat with citations, and generate audio overviews, reports, mind
maps, flashcards and quizzes — **100% offline on your phone**, delivered as a
sideloadable APK.

No account. No cloud. No telemetry. The only network traffic is what you ask
for (model downloads, web-page sources).

## Status

🚧 **v0.1.0-alpha — full app implemented.** Notebooks, sources (PDF/DOCX/TXT/
MD/URL/paste), grounded chat with citation chips, studio (study guide, briefing,
FAQ, timeline, mind map, flashcards with SM-2, quiz), audio overview scripts +
platform-TTS narration, HF model manager with curated catalog **and arbitrary
GGUF search/download**, liquid-glass UI. LFM3-Audio native voice + native ASR
land after the Phase 0 spike (#1).

| Doc | Contents |
|---|---|
| [docs/PLAN.md](docs/PLAN.md) | Master plan: feature-parity map, stack, pipelines, risks, roadmap |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | System diagrams, data model, model lifecycle, JNI surface |
| [docs/MODEL_MATRIX.md](docs/MODEL_MATRIX.md) | On-device model strategy (LFM3-first), HF integration, licenses |
| [docs/BUILD.md](docs/BUILD.md) | Kit vs full builds, llama.cpp pin, signing, CI/CD |
| [docs/PRIVACY.md](docs/PRIVACY.md) | Local-first privacy model + v0.1 speech caveats |
| [docs/spike-results.md](docs/spike-results.md) | Phase 0 measurement template |

## The stack (short version)

| Role | Choice |
|---|---|
| Text brain | **LFM3-1.7B** (Q4_0, ~1 GB) via llama.cpp — fastest in-class on-device |
| Speech in/out | **LFM3-Audio-2B** (Q4_0, ~1.05 GB) — ASR ≈ Whisper-large-v3, streaming TTS, ~50 ms TTFT |
| Optional narration | Kokoro-82M pack (2 distinct podcast-host voices) |
| Embeddings | bge-small-en-v1.5 (384-d) |
| Retrieval | SQLite FTS5 (BM25) + vector cosine, reciprocal rank fusion |
| App | Kotlin + Jetpack Compose (liquid-glass UI), SQLite+FTS5, WorkManager, Media3 |

Models are **never bundled** — downloaded on first run (or any HF GGUF repo you
point the model manager at) with pause/resume and integrity checks.

## Build

Requirements: JDK 17, Android SDK (compileSdk 35).

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Unit tests + lint:

```bash
./gradlew testDebugUnitTest :app:lintDebug
```

## Install (sideload)

Grab the **full native APK** from the latest
[native build run](https://github.com/touhidsiddiqueeraj-bit/pocketnotebook/actions/workflows/native.yml)
(artifact `PocketNotebook-native-arm64`) or from the newest
[Release](https://github.com/touhidsiddiqueeraj-bit/pocketnotebook/releases),
then install it like any sideloaded APK (allow "install unknown apps"):

```bash
adb install PocketNotebook-*-arm64.apk
```

Requires: Android 8.0+ (arm64). First launch downloads the models —
LFM3-1.7B QAD-Q4_0 (~1.0 GB) and bge-small embeddings (35 MB) — over
whatever network you allow; everything after that is offline.

## CI/CD

- **`main` / `develop` push & PRs** → [Android CI](.github/workflows/android-ci.yml):
  unit tests → lint → debug APK artifact.
- **Tag push `v*`** → [Release](.github/workflows/release.yml): tests → release
  APK (signed with repo secrets when configured; debug-key fallback until then)
  → attached to a GitHub Release with generated notes.
- Dependabot keeps Gradle deps and Actions fresh weekly.

To enable release signing, set repo secrets `RELEASE_KEYSTORE_BASE64`,
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`
(instructions in the workflow file).

## Roadmap

| Phase | Scope |
|---|---|
| 0 | **Spike: validate LFM3 models on-device** (issue #1) |
| 1 | Notebooks, sources, ingestion, search |
| 2 | Grounded chat with citations, model manager + HF downloader |
| 3 | Audio overviews (Deep Dive + Brief) |
| 4 | Studio reports, mind maps |
| 5 | Flashcards/quizzes, noteboard, audio/URL sources, interactive audio |
| 6 | Polish → signed release APK |

## License

App code: Apache-2.0. Models carry their own licenses (LFM Open 1.0, Apache-2.0,
MIT) — see [MODEL_MATRIX.md](docs/MODEL_MATRIX.md#license-notes).
