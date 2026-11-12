# Phase 7.1 — Local Wake-Word Architecture Decision

Status: this document records the Phase 7.1 investigation. Phase 7.3 now contains an opt-in Vosk prototype; its device detection quality and resource behavior remain unverified. See [Phase 7.3 implementation and acceptance](PHASE7_3_AYSE_WAKE_WORD.md).

## Canonical wake phrase

The only requested wake phrase is **`Ayşe`**, with the Turkish letter `ş` and its standard Turkish pronunciation. Keep this spelling in source, settings, documentation, examples, and test fixtures. Do not alias, transliterate, or substitute it with another phrase. For future text comparison, normalize Unicode to NFC and use Turkish-aware case handling; normalization must not remove or replace `ş`.

Settings label: **`Uyandırma sözcüğü: Ayşe`**. The implemented control is explicit opt-in and shows monitoring status, local processing, and a stop action.

## Existing implementation and gaps

- `MainActivity` creates the existing face and voice controller. Tapping the face is the current path into microphone permission and push-to-talk.
- The default Android `SpeechRecognizer` owns its microphone session. The optional PCM path uses `AudioRecorder`/`AudioRecord`. A wake listener must stop and release its capture before either path starts; two simultaneous microphone owners are not acceptable.
- `RobotCore` owns sleeping/waking state and the sleeping face is mapped from Core. Phase 7.3 adds an `AWAKE` result for wake-word detection.
- `FaceBehavior` owns visual sleep/wake timers independently. It can wake itself after a short randomized sleep, so the current sleeping face is not a persistent wake-word standby state.
- `MainActivity` keeps the screen on while active, stops face behavior on pause, and the manifest declares `RECORD_AUDIO`. Phase 7.3 adds an opt-in foreground service and persistent notification.
- The current platform speech service may use network processing for the post-wake utterance. A local wake-word detector can avoid uploading standby audio, but that does not make the existing post-wake STT offline.

The Phase 7.3 implementation listens only after explicit opt-in, using Vosk locally. Actual Turkish acoustic detection and false-activation behavior have not yet been physically verified; no wake-word support claim is made.

## Candidate comparison

| Candidate | Turkish/custom phrase and Android fit | Cost/license and limitations | Phase 7 assessment |
| --- | --- | --- | --- |
| Vosk Android + `vosk-model-small-tr-0.3` | Offline Turkish model; model archive contains `HCLr.fst` runtime graph support. Lowercase `ayşe` is in the constrained grammar vocabulary; uppercase grammar spelling is not. Acoustic decoding has not been measured. | Vosk API and this model are Apache 2.0. The model is 35 MB compressed / 58.8 MB expanded; Vosk estimates small models around 300 MB at runtime. It is general ASR constrained to a short grammar, not a dedicated wake-word detector. False-activation and false-rejection rates remain unknown. | **Implemented as an opt-in prototype only.** Continue with phone testing; do not treat vocabulary/grammar support as a false-trigger guarantee or supported wake-word quality. |
| sherpa-onnx keyword spotting | Native Android/Kotlin support, ARM64, Apache-2.0 code, and open-vocabulary keywords without retraining. The documented pre-trained KWS models are Chinese, English, or Chinese-English; no Turkish KWS model is listed. | Model licensing is per model and must be reviewed. An English/Chinese acoustic model cannot be assumed to recognize Turkish `Ayşe` or its `ş` sound reliably. | Strong implementation alternative only if a suitable Turkish model becomes available or is trained and validated. Not the current pick. |
| openWakeWord | The upstream framework has no official Kotlin Android integration. Third-party Kotlin/ONNX wrappers now advertise Android SDK 23+, so API 25 is plausible, but wrapper maturity, model compatibility, CPU, and lifecycle behavior still need a source audit and device test. Its documented pre-trained models support English; a custom Turkish model would be required. | Framework code is Apache 2.0; included pre-trained models are CC-BY-NC-SA 4.0. Model/data licensing must be checked independently. Its training guidance calls for several thousand positive samples; its bundled models used roughly 30,000 hours of negative audio. That is a substantial data and validation task. | Local custom training is technically possible at no service fee, but no ready, validated Turkish `Ayşe` model or turnkey local workflow has been established. Consider only if Vosk cannot meet the measured false-activation gate. |
| Picovoice Porcupine | Android SDK and custom phrase model generation exist, but the supported language list does not include Turkish. Custom model creation uses Picovoice Console and an AccessKey. | Proprietary service. Picovoice currently states there is no dedicated free or paid plan for personal/non-commercial use; enterprise trial access is temporary and subject to limits. Not a durable no-cost Turkish solution. | Excluded for this project. |

Sources: [Vosk models and runtime guidance](https://alphacephei.com/vosk/models), [Vosk Android guide](https://alphacephei.com/vosk/android), [Vosk Android library setup](https://alphacephei.com/vosk/install), [Vosk grammar API](https://github.com/alphacep/vosk-api/blob/master/src/vosk_api.cc), [sherpa-onnx keyword spotting](https://k2-fsa.github.io/sherpa/onnx/kws/index.html), [sherpa-onnx Android/ARM support](https://github.com/k2-fsa/sherpa-onnx), [openWakeWord language and training notes](https://github.com/dscripka/openWakeWord), [third-party Kotlin Android wrapper](https://github.com/Re-MENTIA/openwakeword-android-kt), [Porcupine language list](https://picovoice.ai/docs/model-api/porcupine/), and [Picovoice personal-use licensing FAQ](https://picovoice.ai/docs/faq/general/).

### Free local training path if Vosk fails

A genuinely local, no-service-fee experiment is possible, but it is not a plug-and-play production solution:

1. Keep all data and training on the owned Mac. Generate positive `Ayşe` examples with a locally runnable Turkish TTS only after checking its voice and data licenses; add consented Turkish speaker recordings for pronunciation diversity.
2. Collect representative Turkish negative speech, similar-sounding speech, TV/radio, music, room noise, and silence. Keep separate held-out recordings for evaluation. Every dataset's redistribution and model-use terms must allow the intended use.
3. Train an openWakeWord-style custom detector or another small local KWS model. The code path can be free, but the Turkish text-to-speech quality, model language transfer, training data, and final model quality are not established here.
4. Tune against held-out speakers and long negative recordings. Do not ship an experimental model merely because training completes. A trained model must pass the same physical false-accept/false-reject and resource gates below.

This confirms that local custom training can avoid a paid wake-word service; it does not establish a reliable Turkish model today. Training from scratch remains the fallback investigation if the Vosk prototype fails.

## Proposed ownership and hand-off

Keep `RobotCore` as the sole authority for activity state. Add wake detection as a host effect/event at the existing reducer boundary; do not create a second state machine in the service or duplicate face animation logic.

```text
SLEEPING (opt-in wake detector owns the microphone)
  -> WAKING (valid Ayşe event; detector stops and releases capture)
  -> AWAKE (attentive face; no STT in Phase 7.3)
  -> LISTENING only after a later explicit face tap
  -> THINKING (existing conversation flow)
  -> SPEAKING (existing TTS owns audio output)
  -> RETURNING_TO_SLEEP (TTS complete, silence timeout, or recoverable error)
  -> SLEEPING (start wake detection only if user opted in and permission is granted)
```

The face maps Core states to closed eyes, an opening transition, attentive eyes, a thinking cue, speaking animation, and a closing transition. It must not infer that the robot is asleep from an idle timer while STT or TTS is active. The wake service releases the mic before sending the wake event; duplicate wake results are suppressed by the detector and stale Core state ignores events received outside sleep.

Use one low-cost audio capture loop and process bounded chunks locally. Do not persist standby audio or send it to the Mac, backend, Gemini, or any other network service. Start a foreground service and a persistent, understandable notification only after explicit opt-in and microphone permission. API 25 supports foreground services; notification-channel handling is needed only on API 26+. Screen-off capture, OEM process survival, and any minimal partial wake lock must be measured on the GM 5 Plus; do not assume the service alone guarantees reliable screen-off listening.

## Staged delivery and acceptance plan

| Subphase | Scope | Gate before proceeding |
| --- | --- | --- |
| 7.1 | This investigation and decision only. No behavior change. | Document the candidate, state ownership, privacy boundary, and device-test plan. |
| 7.2 | Sleep/wake face presentation, still using existing touch interaction. **Implemented:** Core state drives the sleeping/waking/returning face and tap-to-listening flow. APK build/install/launch and sleeping/listening/cancel-to-sleep screens were checked on the GM 5 Plus; detailed frame timing and CPU/battery remain unmeasured. |
| 7.3 | **Implemented prototype:** local Vosk monitor, bundled Turkish model, explicit opt-in, foreground notification, stop control, audio lease, and `AWAKE` state. | Acoustic recognition, false activations, and resource use must still be tested on GM 5 Plus. Unit tests use mocked audio. |
| 7.4 | Mic ownership hand-off from local detector to existing STT and back. | Repeatedly prove one mic owner at a time, session-scoped events, timeout/error recovery, and no wake detection during TTS. |
| 7.5 | Lifecycle, screen-off, reboot, and endurance. | Measure CPU, peak RAM, latency, false accepts/hour, missed wakes, battery, and temperature on the actual device; document unsupported lifecycle cases honestly. |

Suggested benchmark gate for a prototype (not yet measured): at least 95 detections in 100 intentional `Ayşe` trials across at least three Turkish speakers and quiet/desk-noise conditions, with no more than 0.5 unintended wakes per hour in representative negative audio. Record distance and environment; report the underlying counts, not only percentages. Include similar-sounding ordinary Turkish speech, TV/music, silence, screen-on/off, duplicate results, missing permission, capture failure, STT failure, and TTS completion. If the detector cannot reject common room speech reliably, keep wake-word monitoring disabled and do not change the canonical phrase.

No CPU, RAM, latency, false-activation, battery, temperature, screen-off, or actual `Ayşe` microphone measurements were collected in Phases 7.1 or 7.2. Phase 7.3 automated tests exercise exact Unicode matching and mocked audio, not the acoustic model. This document does not claim verified wake-word support.
