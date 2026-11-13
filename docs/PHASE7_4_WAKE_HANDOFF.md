# Phase 7.4 — Ayşe wake-to-voice hand-off

Status: implemented in code with JVM coverage; the revised flow has not yet been physically tested on the GM 5 Plus.

## Interaction

1. With local wake monitoring explicitly enabled, Vosk checks audio on-device for a completed, standalone `Ayşe` result.
2. The wake service closes its recorder and recognizer and releases its microphone lease before publishing the wake event.
3. `RobotCore` immediately enters `LISTENING` and starts the existing Turkish Android speech-recognition input. The face displays `DİNLİYORUM` while the eyes transition open.
4. A recognized command follows the existing conversation/backend/TTS flow. No backend request is made for the wake phrase itself.
5. If no command speech begins within 8 seconds, the voice session times out and the face returns to sleep. After a successful answer, eyes stay attentive for five seconds before sleep. The local detector resumes when consent and permission remain enabled.

The Android platform recognizer starts a new microphone session and cannot accept the audio buffer already consumed by the local wake detector. The accuracy filter waits for a completed standalone `Ayşe` result, which may increase hand-off latency. For reliable testing, say `Ayşe`, wait until the face shows `DİNLİYORUM`, and then say the command. A no-pause utterance such as `Ayşe, bana su içmeyi hatırlat` may not wake the robot and requires physical testing.

## Guards and privacy

- Wake detection remains local and opt-in. The listener releases the microphone before Android speech recognition starts; the two capture sources do not run concurrently.
- Wake monitoring re-arms while sleeping or during the five-second awake grace when the user setting and `RECORD_AUDIO` permission remain enabled. A service error does not retry indefinitely; the user must resolve the error or toggle monitoring again.
- A command-start timer applies to both Android's platform recognizer and the PCM path. It is cancelled as soon as speech begins; if no speech starts within 8 seconds, the session follows the existing error/sleep path and the local wake service re-arms. No empty transcript is submitted.
- Normal voice interaction and Gemini requests continue through the existing voice pipeline. The wake phrase does not itself trigger an AI or network request.

## Automated checks

JVM tests cover standalone final-result detection, duplicate suppression, microphone release before hand-off, automatic Core transition into listening, timeout-to-sleep, and the opt-in/permission/sleep guards for listener re-arm. They do not measure acoustic recognition, Android SpeechRecognizer start latency, command clipping, false activations, or device resource use.

## Manual acceptance still required

- Say `Ayşe` and confirm the listening face appears and Android speech recognition starts without touching the screen.
- Give a Turkish command after the listening cue; verify the correct transcript reaches the backend and the robot speaks the matching reply.
- Test a continuous `Ayşe, [command]` phrase and check whether its first words are clipped.
- Stay silent after wake; verify it returns to sleep after 8 seconds and resumes local wake listening.
- Complete a response; verify the face sleeps and local wake listening resumes.
- Repeat with TV/music/ordinary conversation, different distances and volumes, then measure latency, CPU, RAM, temperature, and battery.
