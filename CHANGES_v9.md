# PragonMobile v9 (3.1)

**AirPods + speaker / earpiece** (`AudioRoute.kt`): AUTO uses a connected AirPods / Bluetooth / wired headset for Pragon's voice and
microphone, otherwise the loudspeaker. "use the speaker", "use the earpiece", "normal mode", "toggle the speaker", "use my airpods",
"turn off the headset mic". Settings buttons AUTO / SPEAKER / EARPIECE + headset-mic switch. Audio is restored after Live / PA ends.

**Voice calling** (`Calls` in `PragonContacts.kt`): "call Ravi" dials only names saved in Pragon contacts; "call 98765 43210" dials a number
directly (CALL_PHONE permission; without it the dialer opens). `*` and `#` numbers only open the dialer. Works with no AI key (offline parser `V9Voice.kt`).

**Pragon contacts**: "add Ravi 98xxxxxxxx to Pragon contacts"; without a number it is copied from the phone contacts. Settings: Name + Number + SAVE CONTACT, X to delete.

**Pragon PA** (`PragonPA.kt`): "turn on Pragon PA". On a call: OK = attend (answered on the loudspeaker), ignore = leave ringing, reject = cut
(by voice or notification buttons). Pragon asks who / why / urgency / callback, refuses personal info, OTP and bank details, flags scams, hangs up.
Spoken summary + notification afterwards; last 20 kept. Takeover: TAKE OVER button, one tap on the bubble, or "Pragon takeover".

**UI wording**: "Connecting to Gemini Live" -> PRAGON INITIALIZING...; Gemini -> Pragon, Ollama -> Ghost; "ghost pull" / "ghost list" / "use ghost model X" work.
**Settings**: new card "CALLS, AIRPODS & PRAGON PA".
**Fixes**: audio returns to normal after a call or Live session; Ghost error hint explains OLLAMA_HOST=0.0.0.0 + restart.

## Not verified (read this)
No Android SDK or kotlinc was available here, so v9 was **not compiled** and nothing ran on a phone. Done: brace/paren balance of every file,
and the offline phrase parser (`V9Voice`) via a Python port: `python3 tests/v9voice_regex_check.py` (39 phrases, 0 failures).
Expect to fix small compile errors on the first Gradle build (the GitHub Actions workflow will show them).
Known limits: Android does not let apps record the caller's voice, so Pragon PA hears the caller through the loudspeaker and the phone's
microphone (some phones silence it during calls); `acceptRingingCall` / `endCall` need the Phone permission prompt; the caller's number needs READ_CALL_LOG;
on-phone TTS (non-Live replies) does not follow the earpiece setting yet, only Pragon Live and PA do.
Test order: 1) Settings > ALLOW permissions. 2) add a contact, "call <name>". 3) AirPods connect, float mode, speak. 4) "turn on Pragon PA", ring the phone from another number.
