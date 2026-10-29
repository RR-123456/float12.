# Pragon Mobile v12 (versionName 6.0): streaming speech (sherpa-onnx), speculative routing

**Not compiled or run from where this was written** (no network or Kotlin compiler in the build sandbox). Everything that touches
sherpa-onnx, LiteRT's GPU backend or the phone needs your GitHub Actions build and a test on the phone. If the build prints `e:` lines, send them over.

## Before you build (new step)
sherpa-onnx's native libraries and Kotlin API are not in this folder. `tools/fetch_sherpa.sh` downloads both from ONE release (so they match).
* GitHub Actions: nothing to do, the workflow runs it.
* Android Studio: run `bash tools/fetch_sherpa.sh` once (Git Bash, WSL, Linux or macOS), then sync. `SHERPA_VERSION=x.y.z` pins a version.

## Speech: Vosk replaced by sherpa-onnx streaming (`SherpaStt.kt`)
* Streaming zipformer transducer (small English, int8). Words appear while you speak; the recognizer ends the phrase itself after 0.7 s of silence.
* Optional Silero VAD (~1 MB, downloaded with the speech model): while nobody speaks the recognizer does no work; 400 ms of pre-roll keeps the first word.
  No VAD file = no gating, still works.
* Same public surface as the old `VoskStt`, so FloatService / MainActivity / Settings only changed the class name. The saved engine id is still `"vosk"`
  (meaning "Pragon's own engine"), so existing settings keep working.
* The model is a `.tar.bz2` (or `.zip`) of any sherpa-onnx streaming transducer; only the used files are kept. Download: Settings > Offline > DOWNLOAD SPEECH MODEL,
  or say "download the speech model". A v11 install must download once more (the old ~40 MB Vosk model is deleted afterwards).
* An old Vosk link saved in Settings is ignored. `vosk-android` is removed from the build. `commons-compress` is added (unpacks .tar.bz2).
* APK is limited to `arm64-v8a`.

## Speculative routing (`Speculate.kt`, `Router.kt`, FloatService)
* When the partial text has not changed for 350 ms (and has 3+ words), the router model starts on it in the background, only offline, only if the phrase rules
  do NOT already answer it. If the final sentence is the same, its answer is used immediately; otherwise the guess is dropped.
* When you start speaking, the router model is loaded if it was idle-released (`OnDeviceLlm.warmup`, a no-op if loaded).
* "model status" is unchanged; `Metrics` records `spec_hit`.

## Chat model on the GPU (optional, default off)
* Say "run chat on gpu" / "run chat on cpu" (also "turn on gpu"). Router and speech stay on the CPU, chat on the GPU, so the models do not fight over cores.
* Any GPU failure falls back to the CPU by itself; "model status" says which processor the chat model is on.
* `Backend.GPU()` is the LiteRT-LM call I could not check against 0.18.1. If the build shows an `e:` line there, delete the GPU `try` block in `OnDeviceLlm.load`.

## Not in v12 (deliberately)
Wake word (sherpa keyword spotting), TTS (Piper/Kokoro) and speaker ID are left for later: each needs on-phone tuning I can't do from here, and the phrase-based
wake ("I'm home") and Android TTS keep working unchanged.

## Tests
`tests/V12Test.kt` (model file picking, speculation key, GPU phrases). Run line is in `tests/README.txt`. Not run here (no kotlinc in the sandbox).

## Known limits
* Check accuracy on your own voice. If the small zipformer is not good enough, paste another streaming transducer model link in Settings (a NeMo or larger zipformer).
* The default download link and the release asset names were written from memory and were not verified from here.
* Pragon PA (call answering) still uses Android's recognizer.
