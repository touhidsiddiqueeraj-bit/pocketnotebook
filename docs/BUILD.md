# Building PocketNotebook

Two build flavors, one codebase:

| Flavor | Command | Contains | Use |
|---|---|---|---|
| **Kit** | `./gradlew assembleDebug -PskipNative=true` | Stub `libpocketllm.so` (native calls degrade) | Fast CI checks, UI work |
| **Full (CPU)** | `./gradlew assembleRelease` (requires llama.cpp checkout) | Real llama.cpp CPU engine (`libpocketllm.so`), statically linked — loads on every device | Sideload / standard release APK |
| **Full (GPU)** | `./gradlew assembleRelease -PgpuOpencl=true` | OpenCL/Adreno engine (`libpocketllm_ocl.so`) | Experimental `-gpu` release APK |

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
  embeddings, abort. One engine lib per build (`libpocketllm.so` /
  `libpocketllm_ocl.so`).
- `CMakeLists.txt` builds llama.cpp with `BUILD_SHARED_LIBS=OFF` and links it
  **statically** — no `.so` dependency ordering issues on any Android version.
- Update the pin → bump `LLAMA_REF` in `.github/workflows/native.yml` and
  `release.yml` and the engine line in Settings.

### GPU acceleration (OpenCL / Adreno) — experimental

Since v0.1.0-alpha.10 there are **two engine libs** and two release APKs:

| Variant | Build | Engine lib | Ships in |
|---|---|---|---|
| CPU (default) | `./gradlew assembleRelease` | `libpocketllm.so` | standard APK |
| OpenCL/Adreno | `./gradlew assembleRelease -PgpuOpencl=true` | `libpocketllm_ocl.so` | experimental `-gpu` APK |

`PocketLlm.kt` tries `pocketllm_ocl` first and falls back to `pocketllm`, so a
GPU lib can never leave the app engineless. That fallback is the fix for the
alpha.9 regression: the OpenCL engine was the ONLY lib and it hard-links
`libOpenCL.so`, so ROMs that do not expose that vendor lib to apps (it must be
listed in `/vendor/etc/public.libraries.txt`) rejected the whole engine at
dlopen time — and the app showed "Kit build" on a perfectly good release APK
(load failures were mislabeled as kit builds).

The OpenCL engine offloads all model layers to the Adreno GPU automatically
(`n_gpu_layers` defaults to "all"; kernels are embedded into the .so, no `.cl`
files shipped; Adreno-tuned matmul/attention kernels enabled). Verify on-device:
Settings → Diagnostics → "GPU:" shows the OpenCL device and the "offloaded
N/M layers to GPU" line.

Build requirements for the -gpu variant (CI does this for you — see the
"OpenCL stub" step in `.github/workflows/release.yml`):

1. Khronos `OpenCL-Headers` copied into the NDK sysroot `usr/include/`.
2. Khronos `OpenCL-ICD-Loader` built for `arm64-v8a` with the NDK toolchain —
   this is a **build-time stub only**; the app does not ship it.
3. The stub copied to `usr/lib/aarch64-linux-android/` (and the `26/` API
   dir) inside the NDK sysroot.

At runtime the `libOpenCL.so` dependency resolves to the **device's own vendor
OpenCL**. No vendor lib, or not exposed to apps? The GPU engine refuses to
load and the app falls back (standard APK) or tells you to install the
standard APK (-gpu APK).

### 16 KB page-size compliance (read this before touching the NDK)

Android 15+ ships devices with **16 KB kernel page sizes** (mandatory for new
Play apps targeting SDK 35+ since Nov 2025; Xiaomi/Redmi HyperOS 3 phones among
them). Their dynamic loader rejects any `.so` whose ELF `PT_LOAD` segments are
aligned below 16 KB — the app then dies at launch, which is exactly what bit us
in v0.1.0-alpha.1 on a Redmi Turbo 4 Pro (Android 16 / HyperOS 3).

Rules that keep us compliant:

- NDK r27 and lower **do not** link 16 KB-aligned by default.
  `app/src/main/cpp/CMakeLists.txt` passes the official flags
  (`-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`); don't remove
  them, and prefer NDK r28+ (16 KB by default) when the pin is bumped.
- CI gates every native release build with
  `scripts/check_16k_alignment.py` (fails on any `PT_LOAD < 16384` or a
  16 KB-unaligned ZIP entry). Run it locally too:
  `python3 scripts/check_16k_alignment.py app/build/outputs/apk/release/app-release.apk`
- On-device verification: Settings → **Diagnostics** shows the kernel page size
  plus the exact `System.loadLibrary` error, and CrashGuard keeps the last five
  crash reports in `filesDir/crashes/` (no adb needed on sideloaded installs).

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
| `release.yml` | tag `v*` | both native release APKs (CPU + GPU) → GitHub Release |

## Models & licenses

Never bundled in the APK — downloaded on demand from HF at runtime:
LFM3-1.7B / LFM3-Audio-2B (LFM Open License v1.0 — free under $10M revenue),
bge-small-en-v1.5 (MIT), plus any GGUF the user picks. See
[MODEL_MATRIX.md](MODEL_MATRIX.md).
