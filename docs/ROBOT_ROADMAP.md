# Phased Robot Roadmap

This roadmap is a sequence of **small, separately reversible deliveries**, not authorization to implement them all. Complexity is a rough one-engineer estimate after the specified dependencies are available; device-specific investigation and hardware procurement can dominate it. Every phase keeps the existing face, tap-to-talk, provider-neutral backend, local Docker route, Turkish default, and API 24 build working. Phase 1 is implemented. Phase 2's platform adapter is implemented, with physical-device acceptance still open. Phase 4's local personality/emotion implementation is in place and the face was visually reviewed on the GM 5 Plus (API 25); spoken-quality review remains open. Phase 5 now has an initial consent-gated SQLite memory implementation; per-device isolation, encrypted local transport/storage, export, and automatic summaries remain open. Phase 3 reliability soak and all later phases remain planned.

## Priority and gates

**Best near-term value:** Phase 1 → Phase 2 → Phase 3. The Phase 2 Android speech adapter is implemented; device-level Turkish accuracy and offline behavior still need verification before the voice path is considered dependable. Phase 4 adds character only after turn-taking is dependable. Phase 5 currently permits only explicit-consent, non-sensitive prototype memories; authenticated encryption and per-device isolation are prerequisites for sensitive memories, remote access, tools, or actuators.

| Phase | Outcome | Complexity | Additional hardware | 0 TL ongoing software/API path? |
| --- | --- | --- | --- | --- |
| 1 | Robot Core and policy seams — complete | Medium, ~1–2 weeks | No | Yes |
| 2 | Real Turkish STT — adapter implemented; device acceptance open | Medium–high, ~1–3 weeks | No | Yes if platform/Vosk path passes; service behavior must be verified |
| 3 | Robust turn-taking and interruption | Medium, ~1–2 weeks | No | Yes |
| 4 | Personality and bounded emotion — implemented; device review open | Medium, ~1–2 weeks | No | Yes locally; Gemini usage remains quota-dependent |
| 5 | Consent-gated persistent memory — initial version implemented | High, ~2–4 weeks | No; backup storage optional | Yes locally |
| 6 | Controlled autonomy and reminders | High, ~2–3 weeks | No | Yes if local-only triggers; optional Gemini calls must respect free quota |
| 7 | Opt-in vision | High, ~2–4 weeks | No if phone camera geometry works | Yes for basic motion; ML may need host resources |
| 8 | ESP32 sensor integration | High, ~2–4 weeks | Yes unless owned | Yes ongoing, **not zero up-front** |
| 9 | Physical body and safe motors | Very high, ~4–8+ weeks | Yes | No zero-up-front claim; software may remain free |
| 10 | Advanced tools and autonomy | Very high, iterative | Depends on tools | Only with local/owned services and hard cloud-call budget |

## Phase 1 — Architecture foundation

- **Objective/modules:** Introduce an Android `RobotCore` reducer with exclusive activity state, session generation, typed events/effects, consent snapshot, and lifecycle state. Adapt `VoiceInteractionController` and `FaceView` at their existing boundaries; keep their rendering and audio internals. Backend capability metadata was not needed.
- **Dependencies/expected components:** Implemented `RobotCore.kt`; `MainActivity` executes permission/listen/cancel effects and renders state changes; `VoiceInteractionController` reports progress and no longer owns a duplicate state enum; `RobotCoreTest.kt` covers reducer transitions. No runtime service or dependency was added.
- **Acceptance/testing:** Tests cover permission gate/denial, lifecycle pause/resume, voice transitions, interruption, stale-event rejection, and opt-in capability defaults. `testDebugUnitTest`, debug APK, and Android test APK builds pass for minSdk 24. The APK installed on the GM 5 Plus (API 25), but its lock screen prevented an interactive foreground/touch smoke test in this task. No hardware capability outside the phone was added.
- **Risk/cost:** Future modules must keep dispatch serialized on the Core owner thread and execute I/O only as effects. Face idle/sleep behavior still lives in `FaceBehavior`; Core sleep state is groundwork and is not yet wired to those eye animations. Medium; 0 TL with existing hardware.

## Phase 2 — Real Turkish STT (first user-facing priority)

- **Objective/modules:** Replace default mock transcript in normal builds. **Implemented:** `PlatformSpeechInput` uses Android's installed `SpeechRecognizer` for `tr-TR`, while `SpeechInput` preserves the separately configured HTTPS PCM adapter and explicit mock mode. Offline is requested as a preference, not claimed as a guarantee.
- **Dependencies/expected components:** Phase 1 event/session gate; `SpeechInput.kt`, mapped service/network errors, existing microphone permission UX, and session/lifecycle cancellation. Platform recognizer owns its mic session and returns text rather than consuming `AudioRecording`.
- **Acceptance/testing:** Automated: default/endpoint/mock selection and direct LISTENING → THINKING/empty-result reducer paths are covered; `testDebugUnitTest` and `assembleDebug` pass. **Still required on the GM 5 Plus:** speak at least ten varied Turkish phrases in quiet and desk noise; verify changing transcripts, service/no-network errors, and Wi-Fi-off behavior; measure cold/warm latency, peak RAM, APK size, and battery. This task did not verify physical-device recognition or retain audio.
- **Risk/cost:** Platform offline hint may be ignored; Turkish service may be missing. Vosk model is 35 MB and small models use substantial runtime RAM; accuracy is unreported. Medium–high; 0 TL service path if a local recognizer passes.

## Phase 3 — Voice reliability and interruption

- **Objective/modules:** Unify microphone/TTS ownership, improve end-of-speech handling, allow tap to cancel/relisten, show captions and distinct STT/backend/TTS errors, handle audio focus and echo from the desk speaker.
- **Dependencies/expected components:** Phase 2 recognizer; `VoiceSession`/`AudioFocusCoordinator`, VAD adapter, retained `SpeechOutput` interface, existing HTTP cancellation.
- **Acceptance/testing:** Repeated 30-turn soak on GM 5 Plus; pause/resume and permission revocation release resources; rapid tap/barge-in ignores stale results; backend-down still shows face/caption rather than false success; no transcript or key in logs. Instrumentation, failure injection, audio-route and manual noise tests.
- **Risk/cost:** Echo and vendor-specific audio behavior. Medium; 0 TL with owned phone.

## Phase 4 — Personality and emotion

- **Objective/modules:** Version a Turkish-first character profile and implement a bounded, decaying local emotion vector. Map trusted events to existing eye expressions and limited TTS delivery changes without making claims of real feelings.
- **Dependencies/expected components:** Phase 1 state snapshots and stable voice; `PersonalityProfile`, `EmotionReducer`, `FaceCueMapper`, backend prompt builder tests. Reuse `FaceBehavior` and `FaceAnimator`.
- **Acceptance/testing:** Same trigger yields consistent bounded cue; idle decay returns to calm; model text cannot alter permissions/emotion state directly; accessibility/voice intelligibility preserved; rendering remains event-driven. Deterministic reducer tests and visual/device review.
- **Risk/cost:** Conflicting automatic eye behavior and Core cues; define override precedence. Medium; local logic 0 TL, optional Gemini replies quota-dependent.

### Phase 4 implementation status (2026-11-09)

Implemented the versioned Turkish-first `masa-arkadasi` profile, a local bounded five-axis emotion reducer with lazy exponential decay, deterministic mapping to existing eye expressions, and subtle capped native TTS delivery adjustments. Trusted voice lifecycle events are the only mood inputs; face voice/touch/sleep behavior keeps precedence, and emotion updates do not run a frame loop. The backend prompt builder now keeps language and truthful-capability boundaries even when `COMPANION_PERSONALITY` supplies a custom style. Automated reducer and prompt tests cover bounds, deterministic mapping, decay, delivery caps, and profile rules. Installed build rendering was visually checked on GM 5 Plus (API 25); TTS intelligibility and voice quality remain unverified.

## Phase 5 — Persistent memory and privacy controls

- **Objective/modules:** Store opt-in structured profile/preferences/facts/routines in backend SQLite; support explicit summaries through the API but do not auto-summarize; provide Android inspect/edit/delete/clear controls and bounded relevant-context retrieval. Keep the existing eight-message Android history separate.
- **Dependencies/expected components:** Implemented as `MemoryRepository`/`MemoryService`, versioned schema, Docker data volumes, consent provider, `MemoryApiClient`, and the security/privacy management dialog. The shared optional token is reused; no cloud database or pairing system was added.
- **Acceptance/testing:** Tests cover migration/reopen, deterministic extraction, correction/upsert and duplicate prevention, retrieval/confidence bounds, consent off/on, auth, delete/clear, prompt isolation, log redaction, invalid payloads, and degraded operation when SQLite is unavailable. Raw audio and conversation dumps are not stored. Manual GM 5 Plus verification remains pending.
- **Risk/cost:** Privacy, stale copies, schema migration, host disk failure. High; SQLite/software 0 TL; optional backup media costs money.

### Phase 5 implementation status (2026-11-11)

Android consent defaults off and is sent on each conversation request. When enabled, the backend applies deterministic Turkish rules to a narrow set of explicit statements and supports explicit safe fact saving, corrections, list/forget/clear commands. SQLite is mounted on a persistent Docker volume. Relevant records are confidence-filtered and bounded to five before they are passed as untrusted context to the configured provider. Android's management screen lists, edits values, deletes one record, and confirms clearing all. Disabling memory pauses extraction and provider retrieval but does not delete records; the user can still manage/delete them. The local route is HTTP and SQLite has no at-rest encryption, so this prototype must not store sensitive data. There is no automatic summary generation, export UI, per-device isolation, or GM 5 Plus memory-flow smoke test yet.

## Phase 6 — Controlled autonomy and reminders

- **Objective/modules:** Local schedule/event candidates, quiet hours, cooldowns, daily budget, opt-in proactive speech, reliable reminders, mute and failure recovery. Start with prewritten local utterances; no idle AI loop.
- **Dependencies/expected components:** Core, permission UI, memory for durable reminders; `AutonomyPolicy`, `ReminderStore`, `ScheduleDriver`, event audit. Backend only for an approved generation request.
- **Acceptance/testing:** With default settings, zero unsolicited cloud calls, camera captures, and motor actions; no speech in quiet hours; one reminder fires once despite restart; cooldown and daily limit hold; cancellation and errors back off. Fake-clock policy tests and multi-day device simulation.
- **Risk/cost:** Annoying behavior and accidental API usage. High; 0 TL with local actions, optional Gemini calls only under a hard free-tier budget.

## Phase 7 — Opt-in vision and environmental perception

- **Objective/modules:** Explicit short camera sessions; downscaled low-rate motion detection; optional, clearly labeled person/object detection if phone and host benchmarks support it. No always-on camera.
- **Dependencies/expected components:** Camera permission/indicator and authenticated transport if frames leave phone; `VisionSession`, frame sampler, quality/staleness types, optional host inference adapter. Check stand field of view first.
- **Acceptance/testing:** Camera opens only with consent; indicator remains visible; frames are discarded by default; revoke/background closes camera; heat, CPU, battery, and false-alert rate measured on GM 5 Plus; no identity claim from simple motion. Manual camera/privacy tests plus synthetic-frame tests.
- **Risk/cost:** Heat, battery, lens geometry, privacy, false detections. High; basic local motion 0 TL if phone camera suffices; optional hardware/model costs vary.

## Phase 8 — ESP32 sensor bridge

- **Objective/modules:** Read a physical button and one sensor through an authenticated gateway, present staleness/quality, and add a local physical stop input. **No motor yet.**
- **Dependencies/expected components:** ESP32 board/sensor/power, firmware repository area (proposed `hardware/esp32/`), backend `HardwareGateway`, pairing/protocol tests, Android event adapter; Phase 1 Core and security gate.
- **Acceptance/testing:** Sensor events have sequence and expiry; disconnect/reconnect and malformed/replayed messages fail safely; no public port exposure; stop remains effective when Mac/phone vanish. Device-in-loop tests and packet/fault injection.
- **Risk/cost:** Wi-Fi reliability, firmware updates, supply noise. High; nonzero up-front parts cost unless already owned.

## Phase 9 — Physical body and safe motor control

- **Objective/modules:** Enclosure, one bounded servo, driver and independent power, physical latched stop, watchdog, then optional additional axes. AI only proposes named high-level actions.
- **Dependencies/expected components:** Phase 8 trusted gateway, mechanical/electrical design, selected servo supply, hard limits and firmware safety state. See [hardware plan](ROBOT_HARDWARE_PLAN.md).
- **Acceptance/testing:** Out-of-range/stale/replayed command rejected; stop works during motion and without network; brownout/overcurrent/stall and tip/pinch tests pass; Android reports completion only after verified result; motor cannot restart after stop without local re-arm.
- **Risk/cost:** Injury/electrical damage and substantial mechanical effort. Very high; hardware purchases mean not a literal 0 TL build.

## Phase 10 — Advanced autonomy and tool usage

- **Objective/modules:** Add allowlisted read/time/sensor/reminder tools first, then carefully scoped actions; optional local LLM and multi-sensor context. Require permission and typed validation for every effect.
- **Dependencies/expected components:** Core, authenticated backend, memory privacy, reliable action audit, hardware safety where used; `ToolRegistry`, `ToolPolicy`, `ToolExecutor`, replay-safe command store.
- **Acceptance/testing:** Prompt-injection and malformed tool proposals cannot bypass schema/consent; rate/compute budgets enforced; paid-provider fallback never happens automatically; offline degradation is honest; physical stop outranks all tool calls. Contract, policy, adversarial, and device-in-loop tests.
- **Risk/cost:** Broad capability abuse, privacy and accidental charges. Very high and iterative; software can remain 0 TL only when tools use owned/local services and cloud calls stay inside free limits.

## Release discipline for every phase

Keep a feature branch, focused tests, API 24 debug build, actual GM 5 Plus smoke test where device behavior matters, and a rollback path. Record measured latency, memory, battery/heat, network calls, and user-visible errors. A pass in mocked CI does not prove on-device Turkish recognition, free-tier availability, or safe physical motion.
