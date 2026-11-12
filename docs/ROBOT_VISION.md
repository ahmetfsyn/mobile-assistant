# Masa Arkadaşı: Robot Vision

## Product promise

Masa Arkadaşı is a small Turkish-first desk companion whose face, voice, attention, and eventually physical motion form one coherent character. It should be pleasant when left alone, useful when addressed, and quiet when the user needs quiet. It must state what it actually sensed, remembered, or did; an AI-generated sentence is never proof of a physical action.

The General Mobile GM 5 Plus is the first face, microphone, speaker, and interaction computer. The existing Mac and a single Docker Compose backend are the local AI gateway. An ESP32, sensors, motors, camera, and enclosure are optional later additions. The phone-only companion remains a supported product mode.

## Experience goals

1. **Reliable Turkish turn-taking.** Tap to talk; visible listening, thinking, and speaking states; interruption works; captions and a clear error appear when a service is unavailable. English is an explicit option, never an automatic switch.
2. **Explicit local wake-up (prototype implemented).** Monitoring is explicitly opt-in and uses the exact Turkish phrase `Ayşe` on-device. Detection opens the attentive face without starting speech recognition; face tap remains the manual push-to-talk fallback. Wake quality and false-trigger rate remain unverified.
3. **Character with restraint.** Eyes blink and gaze naturally. A bounded emotion model adjusts the face and delivery; it does not pretend to have human feelings or invent memories.
4. **Useful continuity.** Remember only consented, relevant preferences and events. Give the user a way to inspect, correct, export, and delete them. Do not retain microphone audio by default.
5. **Appropriate initiative.** Local schedules and sensor events can suggest a greeting or reminder within quiet hours, rate limits, and consent. Silence is a valid decision. No unattended model call loop.
6. **Physical trust.** Optional movement is slow, bounded, and interruptible. A hardware stop and ESP32 watchdog take priority over phone, backend, and model instructions.
7. **Graceful degradation.** With the Mac or internet off, retain the face, touch, basic local states, available on-device voice features, and safe stop. Never claim an unavailable cloud or hardware action succeeded.

## Hard boundaries

| Boundary | Product rule |
| --- | --- |
| Cost | Target 0 TL ongoing service/API spend. Use owned hardware and free software first. Gemini free-tier availability and limits may change; disable it when the free allowance is unavailable rather than enabling billing automatically. New ESP32, servos, sensors, power parts, or a Raspberry Pi have **up-front hardware costs**, so a fully physical robot cannot literally cost 0 TL unless those parts are already owned. |
| Device | Android minimum API 24, about 3 GB RAM, ARM64, no root, landscape immersive Canvas face. Benchmark optional local models on the actual device before shipping them. |
| Privacy | Microphone and camera are off outside an explicitly enabled interaction mode. Persistent memory is opt-in. Raw audio and camera frames are transient unless a separate, visible recording feature is approved. |
| Network | The current local HTTP path is for a trusted private LAN and carries unencrypted conversation text. Add pairing and authenticated transport before storing sensitive memories, accepting remote tools, or driving motors. No public port forwarding by default. |
| Safety | Model output is untrusted input. It cannot change permissions, erase memory, or actuate hardware directly. Local physical stop and electrical limits remain effective if Wi-Fi, Android, or the Mac fails. |

## What exists today, verified in source

| Area | Status and evidence |
| --- | --- |
| Face | Native `FaceView` Canvas, `FaceAnimator`, `FaceBehavior`, seven `FaceExpression` values, touch/idle/blink behavior, and curved closed eyelids. `RobotCore` is authoritative for sleeping, waking, and returning-to-sleep. Motion frames run around 33 ms; settled sleep redraws at 250 ms for subtle breathing. [Android source](../app/src/main/java/com/companion/assistant/FaceView.kt). |
| Robot Core | Synchronous `RobotCore` reducer owns boot/sleep/wake/awake/voice/proactive-speech/error transitions, permission and consent snapshots, session generation, and lifecycle cancellation. `MainActivity` executes effects and maps snapshots to the face. Observation/action states remain groundwork. [Core](../app/src/main/java/com/companion/assistant/RobotCore.kt), [Activity](../app/src/main/java/com/companion/assistant/MainActivity.kt). |
| Voice input | Tap requests microphone permission. Default Android platform recognition owns the microphone; the optional PCM path captures 16 kHz mono audio, stops on silence or at 15 seconds, and wipes the in-memory buffer after use. [Input](../app/src/main/java/com/companion/assistant/SpeechInput.kt), [recorder](../app/src/main/java/com/companion/assistant/AudioRecorder.kt). |
| Wake word | Explicit opt-in foreground service runs Vosk with the Turkish `Ayşe` constrained grammar. Reusable 16 kHz PCM is processed locally; exact final-result matching and `MicrophoneOwnership` protect the push-to-talk handoff. Physical false-activation and endurance gates remain open. [Phase 7.3](PHASE7_3_AYSE_WAKE_WORD.md). |
| Speech recognition | Default input is Android's installed speech service, requested with Turkish `tr-TR`; offline-only mode is off by default because it caused the observed GM 5 Plus service initialization failure. Enable `EXTRA_PREFER_OFFLINE` only after confirming an installed Turkish model. Device-level transcript quality and Wi-Fi-off behavior still require testing. Optional `HttpSpeechRecognizer` uses a separately configured HTTPS PCM-to-JSON service; the conversation backend does not implement STT. Mock transcription is explicit opt-in only. [Input](../app/src/main/java/com/companion/assistant/SpeechInput.kt), [recognizer](../app/src/main/java/com/companion/assistant/SpeechRecognizer.kt), [config](../app/src/main/java/com/companion/assistant/VoiceConfig.kt). |
| Voice output | Android `TextToSpeech` via a `SpeechOutput` interface, with stop and close lifecycle handling. A usable Turkish voice depends on the installed device engine/voice data. [TTS](../app/src/main/java/com/companion/assistant/TextToSpeechManager.kt). |
| Conversation | `RobotCore` is the single state-transition authority. `VoiceInteractionController` performs capture, recognition, HTTP, and TTS effects; explicit reminder commands use the deterministic local parser before normal conversation dispatch. Late callbacks are rejected by session generation. The existing eight-message text history and opt-in structured memory remain separate. [Controller](../app/src/main/java/com/companion/assistant/VoiceInteractionController.kt), [client/history](../app/src/main/java/com/companion/assistant/ConversationEngine.kt). |
| Backend | One Node 22 service with health/config/conversation, memory, reminder CRUD, and autonomy settings routes; bounded input/history; request IDs; IP rate limiting; Gemini/OpenAI/Anthropic/mock adapters; and a configured personality prompt. Optional bearer authentication protects configured routes. SQLite migrations preserve memories and persist reminder/settings mirrors. [Server](../backend/src/server.mjs), [repository](../backend/src/memory-repository.mjs), [providers](../backend/src/providers.mjs). |
| Deployment | Docker Compose binds port 3000 to the selected Mac LAN IP, reads a local ignored environment file, and runs the same Dockerfile intended for later Linux use. [Compose](../compose.yaml). The Android backend URL is a build property; absent URL selects mock conversation. |
| Reminders/autonomy | Android-private SQLite plus inexact AlarmManager schedules explicit reminders locally; boot, time, timezone, and package-replacement receivers re-arm schedules. Quiet hours and RobotCore state gate speech; silent notifications and bounded retry are fallbacks. Backend stores an optional mirror and does not schedule or initiate AI calls. Physical alarm behavior remains unverified. [Phase 6](PHASE6_REMINDERS.md). |
| Hardware/vision/tools | Audio output diagnostic screen exists. Camera pipeline, ESP32 integration, actuators, and general tool executor remain unimplemented. |

Phase 1, the initial Phase 6 reminder foundation, Phase 7.2 sleeping-face integration, and the Phase 7.3 local wake prototype are implemented. `RobotCore` owns voice, sleep, wake, and proactive-speech states; reminder commands are parsed from the current transcript, persisted locally, and scheduled without background AI. Phase 7.2 visuals were inspected on the GM 5 Plus. One real `Ayşe` wake event has since been observed on that phone; it does not yet establish detection reliability, false-activation rate, or long-term resource impact.

Wake-word status: Vosk `vosk-model-small-tr-0.3` runs locally only after opt-in, with a foreground notification and immediate stop action. Standby audio is neither stored nor sent to the Mac or Gemini. A successful exact-phrase match releases the mic and opens `AWAKE`; speech recognition begins only after a later face tap. Automatic monitoring restart after TTS is outside Phase 7.3. See [the Phase 7.3 record](PHASE7_3_AYSE_WAKE_WORD.md).

## Success measures

- **Phase 2 voice gate:** Platform adapter is implemented, but real Turkish transcription on the GM 5 Plus in quiet and desk noise, plus Wi-Fi-off/service-unavailable behavior, remains to be verified on-device. The app reports the platform path as network-uncertain rather than claiming offline operation.
- **Interaction gate:** Tap-to-reply and interrupt-to-relisten work repeatedly without microphone/TTS resource leaks or unwanted network calls.
- **Autonomy gate:** With no permission or quiet hours active, no unsolicited speech, camera capture, motor actuation, or paid API calls occur. Every proactive action has an auditable local trigger and cooldown.
- **Memory gate:** Stored facts have provenance and expiry; correction/deletion is observable and propagates to the sole durable store.
- **Hardware gate:** Disconnect, stale command, out-of-range target, and physical stop all leave motors in a defined safe state.

## Non-goals for the architecture phase

No implementation rewrite, new cloud service, paid account, continuous camera/microphone capture, general computer-control tool, or physical hardware purchase is authorized by this document. The first implementation should focus on a small Robot Core seam and real STT, not a broad feature launch.
