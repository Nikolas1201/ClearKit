# ClearKit — offline life-paperwork helper (Android)

Generic AI-chat UI + on-device vision for letters/forms, meds, and photo-proof.
Chat send is **disabled until a model is loaded** (drawer → Manage models).

How answering works: downloading any model unlocks chat. If `libllama.so` is
present (built from the `third_party/llama.cpp` submodule), the loaded GGUF
vision model answers freely; otherwise the built-in on-device OCR + rules
engine answers (text reading, dates, amounts, references).

## Features
- ☰ drawer: chat list, New chat, model status, Big-text mode, Helpers
- Chat: text + up to 4 photos, quick chip "What is this? By when?", tap a photo for fullscreen
- Models screen: download GGUF vision models from Hugging Face (progress %),
  `Downloaded` badge, hardware fit verdict (Recommended / Max / Not recommended),
  Switch + Load, Unload, Delete. Chat unlocks only after Load.
- Hugging Face browser: search vision GGUFs (only repos shipping an `mmproj`
  projector are listed), pick a quant, download straight into the app.
- Meds & appointments tracker (big-text friendly), Photo-proof log — all JSON in
  app-private storage, no account, no network except HF download.

## Models (Hugging Face, direct download, no login)
| Model | File | ~Size | Note |
|---|---|---|---|
| SmolVLM 256M Q8 | `ggml-org/SmolVLM-256M-Instruct-GGUF` | 330 MB | Default, safe pick |
| Moondream 2 F16 | `ggml-org/moondream2-20250414-GGUF` | 2800 MB | EXPERIMENTAL, OOM risk |
| Qwen2-VL 2B Q4_K_M | `bartowski/Qwen2-VL-2B-Instruct-GGUF` | 1700 MB | EXPERIMENTAL, needs ~3 GB free |

All filenames verified against the Hugging Face API (repo siblings lists).
Curated list: `app/src/main/java/com/clearkit/app/models/VisionModels.kt`.

## Build
Prereqs: JDK 17+, Android SDK with platform 35, NDK + CMake (both installable
from Android Studio's SDK Manager). No other setup — Ninja ships with the SDK
CMake package.

```sh
git clone --recurse-submodules https://github.com/Nikolas1201/ClearKit.git
# (or, in an existing clone: git submodule update --init --depth 1 third_party/llama.cpp)
```

Then either open the folder in Android Studio (Hedgehog+) and press Run, or:

```sh
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Min SDK 26, target/compile 35. Kotlin 2.0.20, AGP 8.5.2, Compose BOM 2024.06.00.
Native `libllama.so` (llama.cpp v0.6.0 + mtmd, CPU-only arm64-v8a) builds
automatically via CMake. First build takes a while (100+ C++ files).

## Permissions
Camera (optional — gallery picker works without it), photos, internet (HF downloads
+ Play-services OCR module fetch only).

## License
MIT — see [LICENSE](LICENSE). llama.cpp (submodule) is also MIT.
