# CharChat

Android app to chat with a character persona using a **local** LLM (GGUF, via
[llama.cpp](https://github.com/ggml-org/llama.cpp)). Inference runs entirely
on-device — no server, no PC required after the model is downloaded once.

This is a **speed-validation MVP**: pick a small GGUF model, set a character
persona as the system prompt, chat, and run a built-in benchmark to see real
tokens/sec on your phone.

## Architecture

- **Inference engine**: llama.cpp compiled for Android via CMake/NDK (JNI), vendored as a
  git submodule at `llama.cpp/`. Built with `GGML_CPU_ALL_VARIANTS=ON` and
  `GGML_BACKEND_DL=ON`, so it picks the best CPU SIMD kernel (NEON dot-product,
  KleidiAI on arm64) for your exact chip at runtime instead of a generic build —
  this is the single biggest lever for phone inference speed.
- **`lib/`**: the `com.arm.aichat` Kotlin/JNI wrapper module (based on llama.cpp's
  official Android example) exposing `loadModel`, `setSystemPrompt`,
  `sendUserPrompt` (streamed `Flow<String>`), and `bench`.
- **`app/`**: the chat UI (`com.charchat.app`). No model is bundled in the APK —
  you pick a `.gguf` file from device storage (Storage Access Framework), so
  the app stays small and any model/finetune can be swapped in.
- Conversation memory: full context kept in the KV-cache; when the context
  fills up, the oldest half (after the system prompt) is discarded and the
  rest is shifted (`shift_context` in `ai_chat.cpp`) — simple sliding-window
  memory. A better summarization-based memory is future work, not in this MVP.

## Building

This sandbox environment cannot reach `dl.google.com` (org network policy),
so the Android SDK/NDK can't be installed here. The included GitHub Actions
workflow (`.github/workflows/build-apk.yml`) builds the debug APK on
GitHub's runners on every push and uploads it as a build artifact — download
it from the Actions run, no local Android Studio needed.

To build locally instead (needs Android Studio / SDK + NDK ~29 installed):

```
./gradlew assembleDebug
```

The resulting APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Getting a model onto your phone

Download a small instruction-tuned GGUF **directly in your phone's browser**
(no PC needed) from Hugging Face, e.g.:

- `Llama-3.2-1B-Instruct-Q4_K_M.gguf` (~0.8 GB)
- `gemma-3-1b-it-Q4_K_M.gguf` (~0.7 GB)
- `Qwen2.5-1.5B-Instruct-Q4_K_M.gguf` (~1 GB)

Save it anywhere accessible (e.g. `Download/`), open CharChat, tap the folder
icon, and pick the file. It gets copied once into the app's private storage.

## Testing on a Poco F3 (Snapdragon 870)

After loading a model and setting a persona, tap the **⚡** button to run the
built-in benchmark (512 prompt tokens / 128 generated tokens). Expect several
tokens/sec on CPU for a 1-3B Q4_K_M model — if you see anything close to
minutes-per-token again, something is off (wrong build flags, thermal
throttling, or a much bigger model than intended), not a hardware limit.

## Notes

- No app-side rate limiting or content filtering is implemented — behavior
  (including how "restricted" responses are) is entirely a property of the
  GGUF model/finetune you load, not this app.
- Google Play restricts apps centered on explicit sexual content; distributing
  this APK directly (sideloading) avoids that, but is worth deciding
  deliberately before a wider release.
