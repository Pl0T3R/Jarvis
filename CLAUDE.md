# JARVIS: instructions for Claude Code on the developer's PC

JARVIS is an always-on Polish voice assistant for Meta Quest 3, built as a native Android (Kotlin) 2D app. The user says "JARVIS, uruchom Spotify" and the headset opens Spotify. JARVIS answers aloud in a calm, JARVIS-style Polish voice. The voice is a synthesizer with an effect, not a copy of the film actor's voice; keep it that way.

The owner (Pl0T3R) speaks Polish, so talk to them in Polish. Code, comments and commit messages stay in English.

## Current state

- **Written and checked in a cloud session without a headset:**
  - `./gradlew assembleRelease` and `testDebugUnitTest` pass with 0 warnings.
  - The speech path was checked offline with `tools/roundtrip.py`. It runs the same sherpa-onnx models on synthetic Polish speech: accurate mode woke 17/20, with no false alarms.
  - The Claude API code was tested against a local fake server: request shape, tool loop and append-only history (`ClaudeBrainTest`).
- **Not yet run on a Quest.** The open questions are platform behaviour, listed under "Risks".

## Layout

| Path | What |
| --- | --- |
| `app/src/main/java/com/pl0t3r/jarvis/JarvisService.kt` | Foreground service (type `microphone`), `START_STICKY`, notification; owns the worker thread |
| `Assistant.kt` | The loop: mic → wake → phrase → Whisper → `Brain` → Piper → `VoiceFx` → speaker. Two wake modes (below) |
| `MainActivity.kt` | Panel: status, model download, mic permission, API key, settings, history. `EXTRA_API_KEY` (from install.ps1) and `EXTRA_AUTOSTART` |
| `BootReceiver.kt` | After boot / update: opens the panel briefly (needs the `SYSTEM_ALERT_WINDOW` app-op) so the service starts from the foreground |
| `brain/WakeMatcher.kt` | Finds "JARVIS" at the start of a transcript as Whisper writes it ("Dżarwis", "żar wys", "Dziary wys"…) |
| `brain/LocalCommands.kt`, `PolishWords.kt`, `Phrases.kt` | Offline Polish commands, numbers and dates in words, JARVIS's lines |
| `brain/AppMatcher.kt` | Spoken name → installed app (folding, aliases, Polish endings) |
| `brain/ClaudeBrain.kt` | Claude Messages API (Java SDK, beta endpoint): tools `launch_app`, `set_volume`, `device_status`, web search; `fallbacks: "default"`; `block_binding: drop_block` |
| `speech/Speech.kt` | sherpa-onnx wrappers: `WakeWord` (KWS), `Segmenter` (silero VAD), `Transcriber` (Whisper int8, `pl`), `Synthesizer` (Piper `pl_PL-darkman-medium`) |
| `models/ModelStore.kt` | Downloads models on first run into `/sdcard/Android/data/com.pl0t3r.jarvis/files/models` |
| `device/AndroidDevice.kt` | App list (`LAUNCHER` + `com.oculus.intent.category.VR`), launch, volume, battery, home |

Wake modes (`Settings.wakeMode`):
- **ACCURATE (default):** silero VAD cuts every phrase. Whisper base (2 threads) transcribes its first 1.8 s and `WakeMatcher` looks for the name. On a hit, the command model (small by default) transcribes the whole phrase.
- **ECONOMY:** an always-on zipformer keyword spotter (English model with the Polish-shaped variants in `WakeWord.VARIANTS`) fires. Then the last 1.5 s plus the rest of the phrase is cut and transcribed.

## Build, test, install

- Requirements:
  - JDK 17+,
  - Android SDK with platform 35 and build-tools 35. Android Studio's SDK or Unity's (`…\PlaybackEngines\AndroidPlayer\SDK`) both work.
- Put `sdk.dir=…` in `local.properties`. The sherpa-onnx AAR downloads itself on the first build (SHA-256 pinned in `app/build.gradle.kts`).
- `./gradlew testDebugUnitTest` runs the unit tests.
- `./gradlew assembleRelease` builds the APK. Copy `app/build/outputs/apk/release/app-release.apk` to `dist/jarvis.apk`.
  - Release uses R8. The Anthropic SDK ships its own keep rules, and `proguard-rules.pro` keeps sherpa-onnx (JNI).
  - If Claude calls fail only in release, compare with the debug APK first.
- Both build types are signed with `keystore/jarvis.jks` (password `jarvis-quest`), so updates install over the old version.
- Install with `install.ps1` (Windows) or by hand:
  ```
  adb install -r dist/jarvis.apk
  adb shell pm grant com.pl0t3r.jarvis android.permission.RECORD_AUDIO
  adb shell pm grant com.pl0t3r.jarvis android.permission.POST_NOTIFICATIONS
  adb shell appops set com.pl0t3r.jarvis SYSTEM_ALERT_WINDOW allow
  adb shell dumpsys deviceidle whitelist +com.pl0t3r.jarvis
  adb shell am start -n com.pl0t3r.jarvis/.MainActivity [--es api_key sk-ant-…]
  ```
- Logs: `adb logcat -s Jarvis` shows:
  - wake hits with their score,
  - Whisper text and timing,
  - brain input and output,
  - Claude errors and tool calls.
- Models can be pushed instead of downloaded: `adb push <dir> /sdcard/Android/data/com.pl0t3r.jarvis/files/models/` (layout in `ModelStore`).
- Speech round trip on the PC:
  ```
  pip install sherpa-onnx==1.13.8 sentencepiece pypinyin numpy soundfile
  python tools/roundtrip.py --models <models dir> --out tools/out
  ./gradlew testDebugUnitTest -PjarvisTranscripts=tools/out/transcripts.tsv -PjarvisSamples=tools/out
  ```
  The test writes `tools/out/understanding.txt` and voice samples in `tools/out/jarvis/`.

## On-device checklist

- [ ] First start: the mic permission prompt, model download (~600 MB) with progress, then "JARVIS gotowy, sir."
- [ ] In the Quest home: "JARVIS, uruchom [app]" opens it. Also with the JARVIS panel closed.
- [ ] Inside an immersive app (e.g. Stopklatka at 120 Hz):
  - JARVIS still hears and answers,
  - the reply is audible,
  - the game keeps 120 fps (`metavr perf` / OVR Metrics while speaking to JARVIS).
  - **This is the main unknown.** If Horizon OS silences the background mic, the log and the panel show "Mikrofon zajęty". See Plan B.
- [ ] Opening an app from the background works (needs the `SYSTEM_ALERT_WINDOW` app-op). If not, log what `startActivity` throws.
- [ ] Reboot the headset: JARVIS comes back by itself (panel flashes up and hides). If it can't, record why.
- [ ] Latency from end of speech to the start of the reply:
  - target under ~2 s for local commands and ~4 s for AI answers,
  - the log has the Whisper and brain timings,
  - if Whisper small is too slow on the XR2 Gen 2, make `base` the default (`Settings.whisper`).
- [ ] False wakes: with music or a video playing for 10 minutes, count unwanted replies. Tune `Assistant.threshold()` / `WakeMatcher.THRESHOLD`.
- [ ] Taking the headset off and on (sleep) doesn't break listening.
- [ ] Memory: `adb shell dumpsys meminfo com.pl0t3r.jarvis` stays reasonable. `onTrimMemory` frees the command model.
- [ ] With an API key:
  - a question gets a short spoken answer,
  - "puść mi muzykę" uses the `launch_app` tool,
  - a weather question uses web search,
  - no 400 errors in the log.

## Risks

- **Background microphone:** Android silences background capture when a foreground app records. `Microphone.blocked` uses `AudioRecordingConfiguration.isClientSilenced`. After a background restart the mic may be withheld (while-in-use rule). The service then brings the panel up once (`Status.requestForeground`).
- **`VOICE_COMMUNICATION` source** is used for the echo canceller (it removes game audio and JARVIS's own voice). If it changes routing or quality on Quest, fall back to `VOICE_RECOGNITION` in `Microphone.start`.
- **Starting activities from the background** relies on the `SYSTEM_ALERT_WINDOW` app-op exemption.
- **Boot:** Android 14 doesn't let `BOOT_COMPLETED` start a microphone service directly, hence the panel trick.
- **Synthetic tests are pessimistic.** Piper mispronounces English names ("Spotify"), so real voices should do better. Tune on real speech before changing thresholds.

### Plan B

If background listening is blocked during games:
1. JARVIS still works in the Quest home and next to 2D apps.
2. Optionally add a PC companion: the same brain on Windows, launching apps over Wi-Fi adb (`adb shell monkey -p <pkg> 1`).

Ask the owner before building it.

## Tuning spots

| What | Where |
| --- | --- |
| Wake strictness | `Assistant.threshold()` (from the sensitivity slider) and `WakeMatcher` |
| Keyword spotter | `WakeWord.keywords()` (score and threshold) and `VARIANTS` |
| App names | `AppMatcher.aliases`, `fillers` |
| Commands | `LocalCommands` regexes, `PolishWords` |
| What JARVIS says | `Phrases` |
| Voice effect | `VoiceFx` (chorus, presence, reverb amounts) |
| Personality, model and cost | `ClaudeBrain.systemPrompt()`, `DEFAULT_MODEL` (Opus 5.5), effort `LOW`, web search `maxUses(2)` |
| End-of-phrase silence | `Segmenter` (`minSilenceDuration` 0.8 s) |
