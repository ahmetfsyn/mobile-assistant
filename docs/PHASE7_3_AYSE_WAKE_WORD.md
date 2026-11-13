# Phase 7.3 — Local “Ayşe” Wake-Word Prototype

Status: implemented as an explicit opt-in prototype. Automated tests and the debug build pass. One actual Turkish `Ayşe` wake event has been observed on a GM 5 Plus after a repeated utterance. This proves the end-to-end local detector can trigger on the device once; it does **not** establish detection reliability, acceptable latency, or a false-activation rate.

## Phrase and engine

- The wake phrase is **`Ayşe`**. The Vosk constrained grammar uses the model's lowercase vocabulary spelling `ayşe`; text is normalized to Unicode NFC and lowercased with Turkish locale. The detector can trigger on a partial or final transcript only when `Ayşe` is the first complete token. Transliteration, aliases, and occurrences later in a sentence are rejected.
- Engine: Vosk Android `0.3.75`, with the official `vosk-model-small-tr-0.3` Turkish model. The bundled compressed model is about 35 MB; its expanded files are about 58.8 MB. It is Apache-2.0 licensed. The exact archive SHA-256 is `8c8d07cec1bce31add14967c16891c84152cdf76d391e33c08278119b9ea96e5`.
- Inspection confirmed `HCLr.fst` (runtime graph support) is present. A vocabulary check found lowercase `ayşe` in the model grammar; uppercase grammar spelling was warned as out-of-vocabulary. This is evidence for grammar construction only, **not acoustic recognition reliability**.
- Vosk is constrained general speech recognition, not a dedicated keyword spotter. The `[unk]` grammar alternative and exact final-result matcher provide rejection opportunities, but do not establish a false-activation rate.
- Vosk's published estimate for small-model runtime memory is roughly 300 MB. The model is loaded only after opt-in and released when monitoring ends. On the GM 5 Plus, model preparation plus initialization took about 7.3 seconds. A single listening snapshot reported about 106 MiB process PSS and about 89 MiB native heap; after the detector stopped on wake, the process snapshot fell to about 27 MiB PSS and 9 MiB native heap. These are single snapshots, not peak-memory or sustained-load measurements.

## Runtime and privacy

- Monitoring defaults **off**. The security/privacy settings display `Uyandırma sözcüğü: Ayşe`, a Turkish status line, the local-only/no-recording explanation, the model size note, a switch, and an immediate stop button.
- On opt-in, Android requests `RECORD_AUDIO` through the system permission dialog and starts a foreground service with a persistent notification and a stop action. API 26+ uses a notification channel; the project remains min API 24 compatible.
- The service extracts the bundled model to private app storage on a worker thread, captures reusable 16 kHz mono PCM16 buffers, and runs Vosk locally. The audio buffer is zeroed after use. Audio is not written to storage, sent to the Mac/backend, or sent to Gemini.
- `MicrophoneOwnership` enforces a single process-wide capture lease for wake monitoring and push-to-talk. A face tap requests monitoring pause before the existing voice flow. Wake detection stops/closes capture and releases the lease before the Core event is delivered.
- A detected wake stops and releases local audio capture before notifying `RobotCore`. Phase 7.4 connects that event directly to the existing Turkish speech-recognition flow; the face enters `DİNLİYORUM` and Android starts its own microphone session. No backend or Gemini request occurs until speech is recognized. After a no-speech timeout or a completed spoken response returns the robot to sleep, the local wake listener re-arms only if the user opt-in and microphone permission remain enabled.
- A boot receiver re-arms only a previously enabled preference with microphone permission. Android/OEM process survival is not guaranteed.

## Verification

Automated JVM tests cover Turkish matching and normalization, leading-prefix detection, rejection of transliteration and non-leading occurrences, one-shot detection, mocked audio processing, capture cleanup, mic exclusivity, automatic Core listening after wake, timeout-to-sleep, and re-arm consent gates. They do not exercise the model's acoustic decoder or Android's installed recognizer.

Physical observation on GM 5 Plus from Phase 7.3: with local monitoring enabled, one repeated exact `Ayşe` utterance produced a wake event without touching the screen; the initial check did not trigger. That observation predates the Phase 7.4 automatic STT hand-off. The new hand-off and automatic re-arm code has not yet been checked with speech on the phone.

Physical acceptance remains open: wake-to-listening timing; saying `Ayşe` followed by a command (including whether a pause is needed to avoid clipped first words); no-command timeout and sleep; a complete Gemini/TTS turn; automatic re-arm after the reply; false activations from ordinary speech, TV, music, and silence; detection across speakers/volume/distance; peak RSS, sustained CPU, latency, temperature, and battery soak. Keep the switch off by default while quality is unknown.

See [the Phase 7.1 engine decision](PHASE7_WAKE_WORD_DECISION.md), [sleeping face](PHASE7_2_SLEEPING_FACE.md), [architecture](ROBOT_ARCHITECTURE.md), and [roadmap](ROBOT_ROADMAP.md).
