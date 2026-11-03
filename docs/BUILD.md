# Building PocketNotebook

Two build flavors, one codebase:

| Flavor | Command | Contains | Use |
|---|---|---|---|
| **Kit** | `./gradlew assembleDebug -PskipNative=true` | Stub `libpocketllm.so` (native calls degrade) | Fast CI checks, UI work |
| **Full** | `./gradlew assembleRelease` (requires llama.cpp checkout) | Real llama.cpp engine statically linked | Sideload / release |

## Requirements

- JDK 17, Android SDK (compileSdk 35), Android NDK r27+ (auto-installed by AGP)
- ~4 GB free disk for the first native build (llama.cpp objects)

## Full native build (local)

```bash
git clone https://github.com/touhidsiddiqueeraj-bit/pocketnotebook
cd pocketnotebook

# llama.cpp checkout — the pin CI uses is in .github/workflows/native.yml
git clone --depth 1 https://github.com/ggml-org/llama.cpp native/llama.cpp
cd native/llama.cpp
git fetch --depth 1 origin 6cb3e672e8d10fd4feab1a048bf2967c40623133
git checkout FETCH_HEAD
cd ../..

./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk (arm64-v8a)
```

`native/llama.cpp` is git-ignored; CI clones it fresh per run. To try a newer
llama.cpp, checkout a different commit there — CMake picks it up
automatically. The commit currently shipped:

```
6cb3e672e8d10fd4feab1a048bf2967c40623133  (mainline, pinned 2026-11-04)
```

### What the native layer is

- `app/src/main/cpp/pocket_jni.cpp` — the only C++ we own: model load/unload,
  chat-template application, streaming completion (token callback bridge),
  embeddings, abort. One `libpocketllm.so`.
- `CMakeLists.txt` builds llama.cpp with `BUILD_SHARED_LIBS=OFF` and links it
  **statically** — no `.so` dependency ordering issues on any Android version.
- Update the pin → bump `LLAMA_REF` in `.github/workflows/native.yml` and
  `release.yml` and the engine line in Settings.

## Emulator (x86_64)

Add `"x86_64"` to `ndk.abiFilters` in `app/build.gradle.kts`, rebuild native.

## Signing

Release signing priority (see `app/build.gradle.kts`):

1. **Repo secrets** (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`,
   `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) — used by `release.yml` when set.
2. **Committed sideload keystore** (`keystores/upload.p12`, password in
   `keystores/keystore.properties`) — keeps sideload updates signature-stable.
   ⚠️ This is a *convenience identity committed to a public repo* — anyone can
   sign APKs that upgrade over it. Fine for a personal sideload app; generate a
   fresh private key before distributing anywhere serious:

   ```bash
   keytool -genkeypair -keystore new-upload.p12 -storetype PKCS12 \
     -alias upload -keyalg RSA -keysize 2048 -validity 10000
   # then set the four repo secrets above and delete keystores/ from the repo
   ```

3. Debug key (last resort — signature changes every build machine).

## CI/CD

| Workflow | Trigger | What it does |
|---|---|---|
| `android-ci.yml` | push/PR to main, develop | kit tests + lint + APK artifact |
| `native.yml` | push to main, manual | full native release APK artifact + SHA256 |
| `release.yml` | tag `v*` | full native release APK → GitHub Release |

## Models & licenses

Never bundled in the APK — downloaded on demand from HF at runtime:
LFM3-1.7B / LFM3-Audio-2B (LFM Open License v1.0 — free under $10M revenue),
bge-small-en-v1.5 (MIT), plus any GGUF the user picks. See
[MODEL_MATRIX.md](MODEL_MATRIX.md).
