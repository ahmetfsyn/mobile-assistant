# Phase 7.2 — Sleeping Robot Face & Wake Animation

Status: implemented and installed on the General Mobile GM 5 Plus (Android 7.1.1 / API 25). Automated reducer/presentation tests and the debug build passed. Device screenshots confirmed the sleeping face, the open attentive face after a tap, and the sleeping face after cancellation. The screenshots do not measure frame-by-frame animation quality or CPU/battery use.

## Behavior

- `RobotCore` is the only authority for `SLEEPING`, `WAKING`, `LISTENING`, `THINKING`, `SPEAKING`, `PROACTIVE_SPEAKING`, and `RETURNING_TO_SLEEP`.
- Startup enters `SLEEPING`. A screen tap starts a 720 ms `WAKING` transition and then follows the existing microphone-permission and Turkish push-to-talk flow.
- Speech completion, interaction cancellation, recognition/backend/TTS errors, and proactive reminder speech close the eyes through `RETURNING_TO_SLEEP` (980 ms), then settle in `SLEEPING`.
- `FaceBehavior` still handles local idle gaze/blinks but no longer independently wakes the face. Its idle sleep and long-press requests go through `RobotCore`.
- Sleeping eyes are soft cyan curved eyelid lines. The existing Canvas animator interpolates lids and gaze. While settled asleep, redraws are requested every 250 ms for the small breathing pulse; transitions use the existing roughly 33 ms frame cadence. This is the configured schedule, not a device performance benchmark.
- Reminder speech can start from sleep and maps to the speaking face; the face is not left visually asleep while TTS is active.

## Wake trigger and boundaries

As delivered in Phase 7.2, the face tap was the visual wake trigger and continued into push-to-talk; Phase 7.2 itself added no wake detector. Phase 7.3 later added opt-in local `Ayşe` recognition and a foreground service. Phase 7.4 connects detection directly to Turkish speech recognition and re-arms after return to sleep. See [Phase 7.3](PHASE7_3_AYSE_WAKE_WORD.md) and [Phase 7.4](PHASE7_4_WAKE_HANDOFF.md).

## Verification

- `./gradlew testDebugUnitTest assembleDebug` — passed (63 Android JVM unit tests; debug APK built).
- APK installed on the connected GM 5 Plus and `MainActivity` launched in the foreground without an app startup crash in the inspected log window.
- On-device visual checks: closed sleeping eyes on launch; open eyes and `DİNLİYORUM` after tap; closed sleeping eyes after a second tap canceled the empty listening interaction.
- The physical check did not include spoken Turkish input, Gemini, or a completed TTS response. Detailed animation-frame capture, audio behavior, CPU, RAM, battery, and thermal measurements remain unverified.

## Main implementation points

- `RobotCore.kt` — authoritative sleep/wake transitions, animation completion events, stale-session handling, and proactive reminder eligibility while asleep.
- `RobotFacePresentation.kt` — visual mapping from Core state to face expression and Turkish status.
- `FaceView.kt` / `FaceBehavior.kt` — Canvas eyelids, lightweight sleeping redraw cadence, and Core-routed local sleep requests.
- `MainActivity.kt` — lifecycle-safe animation completion callbacks and face-state presentation.
- `RobotCoreTest.kt` and `ReminderSyncAndSpeechTest.kt` — state, interruption, lifecycle, presentation, and reminder-speech coverage.
