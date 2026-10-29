# PragonMobile v10 (4.0): unified, strict-offline assistant

Target phone: OnePlus Nord CE5 (MediaTek Dimensity 8350 Apex, 8 or 12 GB RAM, Android 15). minSdk is now 31 (Android 12).

## What changed
**One routing layer** (`Router.kt`): speech -> phrase rules (instant) -> FunctionGemma 270M on the phone (only if the rules miss and the text
looks like a command) -> `CommandExecutor`. The model can only choose from the whitelist in `ToolCatalog.kt` (no privacy / firewall / macro /
force-stop actions), every answer is validated, and chit-chat never runs the model.

**Strict Offline** (`Offline.kt`, ON by default; Settings > OFFLINE): no Gemini, no Pragon Live, engine is always "local"; the network guard
lets only 127.x/localhost through; TTS never picks a network voice; speech uses Android's on-device recognizer (`OfflineSpeech.kt`).
Turn it off in Settings to get v9's online features back. The only network use is the model download you start yourself.

**On-device models** (`OnDeviceLlm.kt`, LiteRT-LM, CPU): loaded lazily, released after 5 idle minutes or on low memory.
Router = FunctionGemma 270M (~283 MB). Optional chat model: put any `chat.litertlm` in the models folder; without it offline Pragon only does commands.
Models folder: `/sdcard/Android/data/com.pragon.mobile/files/models/` (file `functiongemma-270m.litertlm`, or tap DOWNLOAD in Settings, or `adb push`).

**Actions**: new "send a message to Ravi saying ..." (WhatsApp / SMS, presses Send for you via accessibility; switchable), "how fast are you" (latency
per stage + RAM), "are you offline", "download / load the model", "what can you do". Open, close, scroll, swipe, tap by label, type, call, screenshot,
read screen, timers, alarms, volume, flashlight, settings pages already existed and are reused, not duplicated.

**Speed**: app list is cached (was rebuilt on every "open ..."); recognizer re-arms in 120-250 ms (was 150-400); "I'm home" wakes on the partial
result without waiting for end-of-speech; AirPods microphone (Bluetooth call mode, 1-2 s switch) is now OFF by default, AirPods are used for sound only.
Pragon Live (when strict offline is off): proactive audio / affective dialog default OFF, faster end-of-speech detection.

**Yellow bubble fix (likely cause, not confirmed on a phone)**: with Privacy Shield on, the recognizer was forced offline; a missing offline language
pack returned error 12/13, which fell into a silent back-off, so standby never heard "I'm home" (tapping worked because that started Live, which needs no
recognizer). Now: error is shown in words, standby shows "Heard: ...", Settings has INSTALL SPEECH PACK, and with strict offline off it falls back to online speech.

## Evaluation of the six projects
Reused as ideas only; none could be dropped in as a library (they are whole apps, several use cloud models, and Pragon already has the Accessibility layer).
- FunctionGemma 270M: used (router), via LiteRT-LM.
- PokeClaw: ideas (tool whitelist, loop guard); its Gemma 4 E2B agent needs ~45 s warm-up on CPU-only phones, too heavy for always-on voice.
- ClosePaw, CellClaw: ideas (per-app skills, agent loop); both are cloud-LLM-first harnesses.
- Deft, Open Jarvis: not evaluated (I could not find reliable information).
A multi-step screen-reading agent loop (model looks at the screen, taps, repeats) is NOT in v10: a 270M model is not reliable enough for it.

## Not verified (read this)
- All Kotlin compiles with kotlinc 1.9.24 against android.jar (API 34) and hand-written STUBS of okhttp / androidx / LiteRT-LM (zero errors). The real
  LiteRT-LM and okhttp APIs were not available here: expect small compile fixes on the first Gradle build. `litertlm-android:latest.release` should be
  pinned to the version Gradle resolves.
- Pure logic tests pass: `tests/V10Test.kt` (88 cases) and the existing parser tests. Nothing ran on a phone.
- Model download link (Hugging Face soniqo/FunctionGemma-270M-LiteRT-LM) is not checked; the Gemma license applies. FunctionGemma's tool-call latency on the
  Dimensity 8350 is unmeasured; use "how fast are you" after a few commands.
- If Gradle complains about minSdk of litertlm, raise it; if the CPU engine is slow, nothing to change (GPU is not faster for 270M).
- Android limits: no recording of call audio, some apps block accessibility (banking, FLAG_SECURE), messaging auto-send depends on the app's Send button label.
- Strict offline disables the PC link and Ghost (LAN) too, since they are not on the phone.
Test order: 1) install, Settings > OFFLINE: INSTALL SPEECH PACK, DOWNLOAD (Wi-Fi) or adb push the model. 2) airplane mode. 3) "I'm home", "open YouTube", "scroll down",
"send a message to <contact> saying hi", "take a screenshot". 4) "how fast are you".
