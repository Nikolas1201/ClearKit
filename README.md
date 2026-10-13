# ClearKit — offline life-paperwork helper (Android)

Generic AI-chat UI + on-device vision for letters/forms, meds, and photo-proof.
Chat send is **disabled until a vision model is loaded** (drawer → Manage models).

## Features
- ☰ drawer: chat list, New chat, model status, Big-text mode, Helpers
- Chat: text + up to 4 photos, quick chip "What is this? By when?"
- Models screen: download GGUF vision models from Hugging Face (progress %),
  `Downloaded` badge, Switch + Load, Unload, Delete. Chat unlocks only after Load.
- Offline engine: ML Kit OCR (on-device) + rules → What is this / What to do / By when,
  dates, amounts, references. Optional `libllama.so` hook (`LlamaBridge`) for true
  GGUF generation later — no Kotlin changes needed.
- Meds & appointments tracker (big-text friendly), Photo-proof log — all JSON in
  app-private storage, no account, no network except HF download.

## Models (Hugging Face, direct download, no login)
| Model | File | ~Size |
|---|---|---|
| SmolVLM 256M Q8 | `ggml-org/SmolVLM-256M-Instruct-GGUF` | 330 MB |
| Moondream 2 F16 | `ggml-org/moondream2-20250414-GGUF` | 2800 MB |
| Qwen2-VL 2B Q4_K_M | `bartowski/Qwen2-VL-2B-Instruct-GGUF` | 1700 MB |

Edit list in `app/src/main/java/com/clearkit/app/models/VisionModels.kt`.

## Build (Android Studio)
1. Open this `ClearKit` folder in Android Studio (Hedgehog+).
2. Let Gradle sync (needs internet first time for deps + ML Kit module).
3. Run on a physical device (camera + ML Kit work best on hardware).
4. Build APK: **Build → Build App Bundle(s)/APK(s) → Build APK(s)**.
   Unsigned debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

Min SDK 26, target/compile 34. Kotlin 1.9.22, Compose BOM 2024.04.01.

## Permissions
Camera (optional — gallery picker works without it), photos, internet (HF downloads
+ Play-services OCR module fetch only), notifications (unused, safe to remove).

## Enabling true GGUF inference (optional, later)
1. Add `app/src/main/cpp/` wrapping llama.cpp + llava `common` helpers as `libllama.so`
   exposing `nativeInit / nativeGenerate / nativeFree` (see `LlamaBridge.kt`).
2. Rebuild — `ModelManager.loadActiveModel()` will memory-map the GGUF + mmproj and
   `LocalAiEngine` will route prompts to it first, OCR as fallback.
