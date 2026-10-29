# Pragon Mobile v11 (versionName 5.0)

Not compiled or run on a phone from where this was written: the build sandbox could not reach Google's Maven or Gradle's servers.
The phrase parser (V11Voice) was compiled and tested on a plain JVM (37 checks pass, `tests/V11Test.kt`). Everything else needs your
GitHub Actions build (`.github/workflows/buildapk.yml`) and a test on the phone. If the build prints `e:` lines, send them over.

## Main issue: offline speech recognition
v10 depended on Android's on-device recognizer plus a language pack that many phones don't have or won't download.
* New `VoskStt.kt`: Pragon's own offline recognizer (Vosk, `com.alphacephei:vosk-android`). One ~40 MB model, downloaded once
  (Settings > Offline > DOWNLOAD SPEECH MODEL, or say "download the speech model"). Or copy a model into `<models folder>/vosk`.
* Float bubble and the in-app mic both use it when offline (engine "auto"), or always ("Pragon"), or never ("Android").
  If Android's pack is missing and the Vosk model is installed, float mode switches by itself.
* English by default. For another language paste a model link from alphacephei.com/vosk/models in Settings.
* Clear spoken messages instead of a silent yellow bubble when something is missing.

## AirPods not connecting
Causes found in v10: the headset mic was off by default, and the Bluetooth permission was only requested when it was on, so Pragon never saw the AirPods.
* Bluetooth permission is asked at start-up and when float mode starts.
* Pragon watches for headsets connecting or disconnecting and re-routes listening within a second (`AudioRoute.watch`).
* Listening uses the AirPods microphone (Bluetooth call link, held between phrases) and falls back to the phone mic by itself after 1.5 s if the headset mic doesn't appear. Speech goes out on the same link.
* The link is released when float mode sleeps for media or closes, so music sounds normal.
* Voice: "airpods status", "connect my airpods" (opens Bluetooth settings: Android doesn't let apps pair devices).

## Pragon contacts ON/OFF
Settings > Calls: switch. OFF = "call Ravi" looks in the phone's contacts (and Pragon PA names callers from them); the saved list is kept.
Voice: "turn off pragon contacts", "turn on contacts", "is pragon contacts on".

## Online / offline follows the connection
* New `Net.kt` (cached connectivity). `Offline.active()` = lock on OR no validated internet. The v10 "Strict Offline" switch is now an "Always offline" lock, default OFF (new key, so old installs don't stay offline).
* No internet but on home Wi-Fi: PC link, Ghost and custom servers on your network still work (unless the lock is on).
* Settings > "Features right now" and "what can you do" list only what works in the current state; "Show all features" turns the filter off. The list refreshes when the connection changes, and float mode says so.

## Several LLMs in parallel, cheaply (`LlmHub.kt`, `OnDeviceLlm.kt`)
* Router and chat models now have their own locks: they load and run side by side. At start-up both preload in parallel only when the phone has 6+ cores, 6+ GB RAM and 1.5 GB free; otherwise only the router.
* Online: the cloud answers; the phone's chat model starts only if the cloud is slow (2.5 s) or fails, and the first to deliver wins.
* Idle models are released after 5 min; when RAM is short the idle one is released before the other loads.
* Phrase rules still answer first, with no model at all. Switch: Settings, or "turn off parallel models". "model status" reports each lane.

## More features (all work offline)
Time, date, storage left, notes (take / read / clear), coin, dice, random number, jokes, fun facts, "what works offline", "model status", "speech engine status".

## Updates
AGP 8.5.2 > 8.13.2, Gradle 8.9 > 8.13, compileSdk 35 (targetSdk stays 34 on purpose: 35 changes the foreground-service and overlay rules the bubble uses), core-ktx 1.16.0, appcompat 1.7.1, work 2.10.0. OkHttp stays on 4.12.0 (5.x changes APIs). litertlm unchanged at 0.18.1: I couldn't check for newer versions from here.

## Known limits
* The Vosk small model is noticeably less accurate than Google's recognizer, especially with accents and noise. Use "Android" in Settings when online if you prefer.
* Pragon PA (call answering) still uses Android's recognizer.
* The default model download links (Vosk, FunctionGemma) were not verified from here.
