# Masa Arkadaşı: Robot Vision

## Product promise

Masa Arkadaşı is a small Turkish-first desk companion whose face, voice, attention, and eventually physical motion form one coherent character. It should be pleasant when left alone, useful when addressed, and quiet when the user needs quiet. It must state what it actually sensed, remembered, or did; an AI-generated sentence is never proof of a physical action.

The General Mobile GM 5 Plus is the first face, microphone, speaker, and interaction computer. The existing Mac and a single Docker Compose backend are the local AI gateway. An ESP32, sensors, motors, camera, and enclosure are optional later additions. The phone-only companion remains a supported product mode.

## Experience goals

1. **Reliable Turkish turn-taking.** Tap to talk; visible listening, thinking, and speaking states; interruption works; captions and a clear error appear when a service is unavailable. English is an explicit option, never an automatic switch.
2. **Character with restraint.** Eyes blink and gaze naturally. A bounded emotion model adjusts the face and delivery; it does not pretend to have human feelings or invent memories.
3. **Useful continuity.** Remember only consented, relevant preferences and events. Give the user a way to inspect, correct, export, and delete them. Do not retain microphone audio by default.
4. **Appropriate initiative.** Local schedules and sensor events can suggest a greeting or reminder within quiet hours, rate limits, and consent. Silence is a valid decision. No unattended model call loop.
5. **Physical trust.** Optional movement is slow, bounded, and interruptible. A hardware stop and ESP32 watchdog take priority over phone, backend, and model instructions.
6. **Graceful degradation.** With the Mac or internet off, retain the face, touch, basic local states, available on-device voice features, and safe stop. Never claim an unavailable cloud or hardware action succeeded.

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
| Face | Native `FaceView` Canvas, `FaceAnimator`, `FaceBehavior`, seven `FaceExpression` values, touch/idle/blink/sleep transitions. Frames are scheduled around 33 ms only during active animation; sleeping uses a slower cadence. [Android source](../app/src/main/java/com/companion/assistant/FaceView.kt). |
| Voice input | Tap requests microphone permission. `AudioRecorder` captures 16 kHz mono PCM, stops on silence or at 15 seconds, and wipes the in-memory buffer after use. [Recorder](../app/src/main/java/com/companion/assistant/AudioRecorder.kt). |
| Speech recognition | The default `MockSpeechRecognizer` returns a fixed Turkish sentence after nonempty audio. The alternative `HttpSpeechRecognizer` expects a separately configured HTTPS PCM-to-JSON service; the existing conversation backend does not implement STT. Real Turkish transcription is **not implemented by default**. [Recognizer](../app/src/main/java/com/companion/assistant/SpeechRecognizer.kt), [config](../app/src/main/java/com/companion/assistant/VoiceConfig.kt). |
| Voice output | Android `TextToSpeech` via a `SpeechOutput` interface, with stop and close lifecycle handling. A usable Turkish voice depends on the installed device engine/voice data. [TTS](../app/src/main/java/com/companion/assistant/TextToSpeechManager.kt). |
| Conversation | `VoiceInteractionController` sequences listen, process, speak, and cancellation. `ConversationEngine` fetches `/v1/config` and posts to `/v1/conversations`. Eight recent text messages live in Android `SharedPreferences`; no structured long-term memory exists. [Controller](../app/src/main/java/com/companion/assistant/VoiceInteractionController.kt), [client/history](../app/src/main/java/com/companion/assistant/ConversationEngine.kt). |
| Backend | One Node 22 service with health/config/conversation routes, bounded input/history, request IDs, IP rate limiting, Gemini/OpenAI/Anthropic/mock adapters, and a configured personality prompt. It has **no client authentication or persistent memory**. [Server](../backend/src/server.mjs), [providers](../backend/src/providers.mjs). |
| Deployment | Docker Compose binds port 3000 to the selected Mac LAN IP, reads a local ignored environment file, and runs the same Dockerfile intended for later Linux use. [Compose](../compose.yaml). The Android backend URL is a build property; absent URL selects mock conversation. |
| Hardware/vision/tools | Audio output diagnostic screen exists. No camera pipeline, ESP32 integration, actuators, tool executor, reminder scheduler, or autonomous conversation policy exists. |

This is an architecture plan. The future modules and safety controls described in the other documents are proposals, not implemented features.

## Success measures

- **Phase 2 voice gate:** Real Turkish words are transcribed on the GM 5 Plus in quiet and moderate desk noise; an offline/network label accurately describes the chosen engine. Test both no-network and service-unavailable cases.
- **Interaction gate:** Tap-to-reply and interrupt-to-relisten work repeatedly without microphone/TTS resource leaks or unwanted network calls.
- **Autonomy gate:** With no permission or quiet hours active, no unsolicited speech, camera capture, motor actuation, or paid API calls occur. Every proactive action has an auditable local trigger and cooldown.
- **Memory gate:** Stored facts have provenance and expiry; correction/deletion is observable and propagates to the sole durable store.
- **Hardware gate:** Disconnect, stale command, out-of-range target, and physical stop all leave motors in a defined safe state.

## Non-goals for the architecture phase

No implementation rewrite, new cloud service, paid account, continuous camera/microphone capture, general computer-control tool, or physical hardware purchase is authorized by this document. The first implementation should focus on a small Robot Core seam and real STT, not a broad feature launch.
