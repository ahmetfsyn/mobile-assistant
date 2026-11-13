# Phase 7.4 voice, identity, and system alarms

## Wake accuracy and hand-off

The previous `ayşe` / `[unk]` grammar and immediate partial-result trigger could promote similar sounds to `Ayşe`. The detector now waits for a completed, standalone `Ayşe` result, requests Vosk word confidence, requires at least 0.75 confidence, and adds `aylin`, `ayşegül`, and `ayşe'nin` as contrastive grammar entries. This is a conservative candidate filter, **not** a measured false-positive fix. It may miss a continuous `Ayşe, command` phrase; say `Ayşe`, wait for `DİNLİYORUM`, then speak. All wake audio remains local and ephemeral.

Repeatable acoustic corpus to record on GM 5 Plus, without retaining raw recordings: 10 utterances each of `Ayşe` at slow/normal/fast speed from at least three speakers; 10 each of `Aylin`, `Ayşegül`, `Ayşe'nin`, `Ayşen`, `Ayşelik`, `ayşe` inside another sentence, and unrelated Turkish words; ten 30-second samples each of ordinary conversation, TV, silence, fan noise, and street noise. Record correct/incorrect wake count, speaker, distance, noise type, and elapsed time from spoken word end to `wake_detected`. True-positive and false-positive rates are **not measured yet**. Mock transcript unit tests only prove the text gate.

## Conversation timing

`VoiceTiming` logs wake-to-dispatch time. `VoiceInteraction` logs command start after listening, speech end, recognition completion after speech end, conversation processing, and first TTS callback after reply. Logs contain interaction IDs and durations, not audio. No before/after latency comparison has been collected on spoken commands. Endpoint thresholds were left unchanged to protect final words. A successful TTS response now enters a five-second attentive grace state owned by `RobotCore`; touch or a new `Ayşe` event cancels its delayed sleep. The Android host removes the timer when state changes or the Activity is destroyed. A single idle phone snapshot with wake monitoring off showed 16,795 KB PSS and 0% process CPU; it does not characterize active wake listening or battery use.

## Turkish speech voice

The GM 5 Plus reported `tr-tr-x-mfm-network`, `tr-tr-x-mfm-local`, and `tr-TR-language` through Android TTS. Android `Voice` metadata provides locale, name, quality, latency, and network requirement, but no gender. Settings persist either the device's default Turkish voice or a lower-pitch **approximate** alternative. The latter is not verified as a male speaker. The selection applies through the shared `TextToSpeechManager` to conversation, reminders, and test speech. Settings include a test button. A distinct verified male voice remains unavailable until one is identified by listening or provider documentation.

## Speaker identity

Profiles store only explicitly entered name, optional relationship, address, and preferences locally. Add/edit/delete are available in settings. Voice enrollment is unavailable and all acoustic turns remain `Unknown`; profiles are not assigned to incoming speech or sent to general memory. Vosk offers a free 13 MB speaker model and an Android speaker-model API, but the present Android system STT owns its microphone session, so acquiring embeddings would require a separately validated audio path. No threshold, false-match rate, latency, or device RAM measurement exists. Do not greet a named person based on audio yet.

## Native Android alarm support

The GM 5 Plus resolves public `ACTION_SET_ALARM`, `ACTION_SHOW_ALARMS`, `ACTION_DISMISS_ALARM`, and `ACTION_SNOOZE_ALARM` to `com.google.android.deskclock`. Intent resolution does not prove that the action was executed. Alarm commands are parsed locally before companion reminders and Gemini.

| Operation | Implementation | Confirmation boundary |
|---|---|---|
| One-time alarm | `ACTION_SET_ALARM` with hour/minute and `EXTRA_SKIP_UI` | Reports request dispatched, not proven created; inspect Clock. |
| Weekdays/every day | `ACTION_SET_ALARM` with `EXTRA_DAYS` | Same confirmation boundary. |
| List | `ACTION_SHOW_ALARMS` | Opens Clock screen; app cannot enumerate another app's alarms. |
| Dismiss/disable | Opens Clock list | App cannot safely tell which alarm is ringing; no blind dismissal of next scheduled alarm. |
| Snooze | `ACTION_SNOOZE_ALARM` | Clock acts only if one is ringing; app cannot confirm effect. |
| Edit/delete | Opens Clock list | User selects the target in Clock. |
| Specific date | Clarification/fallback | `ACTION_SET_ALARM` has time-of-day and recurring days, not a date extra. |

Companion reminders continue to use the existing local database, Android AlarmManager, and backend sync. Native alarms are owned by the Clock application. No clock database, accessibility automation, or root access is used.

## Physical acceptance still open

After installation, test Ayşe positives and all corpus negatives, five-second grace timing, interruption during grace, end-of-speech latency, both voice settings and persistence, profile CRUD, spoken alarm create/recurring/list/dismiss/snooze, companion reminders, and resource/battery use. Mark each observed step separately; APK installation and startup are not acoustic acceptance.
