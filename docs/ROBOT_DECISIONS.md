# Architectural Decisions and Open Questions

Status: **proposed**. These decisions guide future feature branches; they do not assert that unbuilt modules exist. Revisit a decision when measured device behavior or user needs invalidate its premise. Related detail is in [architecture](ROBOT_ARCHITECTURE.md), [stack](ROBOT_TECH_STACK.md), [roadmap](ROBOT_ROADMAP.md), and [hardware plan](ROBOT_HARDWARE_PLAN.md).

| ID | Decision and rationale | Alternatives considered and trigger to revisit |
| --- | --- | --- |
| D-01 | Put the logical **Robot Core reducer on Android**. The phone owns touch, face, mic, speaker, lifecycle, and consent; it must remain responsive when Mac/cloud fail. Keep effects off the reducer thread. | Backend-only coordinator would leave device state dependent on Wi-Fi. Revisit if phone becomes display-only on a different robot computer. |
| D-02 | Keep the backend a **single Node modular monolith**. Existing HTTP routes/provider adapters and Dockerfile already work; internal modules have no need for network boundaries. | Microservices/Kubernetes add deployment/auth/observability cost with no demonstrated gain. Split a component only for a measured resource or security isolation need. |
| D-03 | Preserve native Canvas face and current voice interfaces. Wrap existing components in event adapters; do not rewrite rendering, TTS, or provider adapters to add a core. | A new UI framework/game engine is unjustified for the current two-eye display. Revisit if an evaluated 3-D/graphics requirement cannot be met with Canvas. |
| D-04 | Turkish-first, honest capabilities, stable personality profile. AI text may suggest style but cannot assert nonexistent senses/actions or change permissions/emotion wholesale. | Fully model-defined personality is inconsistent and hard to audit. Revisit stylistic parameters through user tests, not safety authority. |
| D-05 | Try Android platform STT first, but **do not equate it with offline STT**. API 24 has `SpeechRecognizer`; offline preference can be ignored. Benchmark Vosk small Turkish as the guaranteed-offline candidate if needed. | Sending all audio to a paid cloud STT violates zero-cost/privacy goals. Mac-side Vosk is a fallback if phone RAM/quality fails and authenticated audio transport is available. |
| D-06 | Use Android native TTS initially; inspect installed Turkish voice for actual offline capability. Existing `SpeechOutput` permits later engine replacement. | Cloud TTS could sound better but may cost money or leak text. Revisit after device quality and network-off tests. |
| D-07 | Keep the current eight Android history messages while introducing **one canonical backend SQLite store** for future opt-in long-term memory. Store structured facts with provenance/TTL; SQL retrieval before embeddings. | Multiple replicated durable stores create deletion conflicts. Vector DB adds service/compute burden before a retrieval-quality need is proven. Revisit for an intentionally phone-only mode. |
| D-08 | Use REST/JSON for conversation/config and a typed in-process event reducer. Add WebSocket only for measured high-frequency telemetry; MQTT only for multi-device pub/sub. | A broker and always-open socket now add failure states without benefit. Revisit when sensor volume/latency data justify them. |
| D-09 | Default autonomy to **zero unsolicited cloud calls**, no background mic/camera, no autonomous motor movement, and quiet hours. Proactive speech requires opt-in, useful trigger, and budget/cooldown. | An unconstrained model loop can annoy users and exhaust free quotas. Revisit limits after measured use and explicit consent. |
| D-10 | Treat model tool proposals as untrusted data. Schema, capability scope, consent, idempotency, and execution results are checked outside the provider. ESP firmware independently enforces motor safety. | Raw model-to-HTTP/GPIO/shell access is unsafe. Revisit specific tool grants with narrowly scoped tests, never the validation boundary. |
| D-11 | Current private-LAN HTTP remains a development-only compatibility path. Add pairing, client authentication and authenticated encryption before sensitive memory, remote tools, or actuators. Keep provider keys only on the backend. | IP allowlists and a hardcoded APK token do not secure a shared Wi-Fi network. Local TLS with pinning is a no-service-fee candidate; test certificate rotation and API 24 behavior. |
| D-12 | No mandatory paid service or hardware for the **phone + owned Mac** companion. Gemini is optional under available free quota; unavailable quota means local degradation, not automatic billing/provider substitution. | Paid plans may be offered explicitly later, never required for baseline. Physical ESP32/motor parts have unavoidable up-front cost unless owned. |
| D-13 | ESP32 owns safety-critical stop, range, speed, and watchdog; the backend sends bounded named targets and Android reports verified outcomes. | Phone/backend-only emergency stop fails on Wi-Fi/host loss. Revisit a motor design only after mechanical and electrical testing, not through AI behavior changes. |
| D-14 | Version new API capabilities without breaking existing `/v1` clients. Keep request IDs and normalized errors; use `/v2` for incompatible envelopes. | A wholesale API replacement risks the working Android–Gemini path. Revisit when a concrete incompatible requirement exists. |

## Current gaps that must not be hidden by planning language

- The default STT is a fixed mock response after actual audio capture. No current backend STT route exists.
- The backend has no device authentication, durable robot database, autonomous scheduler, tool executor, or hardware gateway.
- The Android app has no camera permission or vision pipeline. Current face idle behavior is local character animation, not environment-aware autonomy.
- Current local Compose chooses `gemini-3.5-flash-lite`; bare backend configuration defaults to `gemini-2.5-flash`. Both are external services whose free-tier rules may change. The Android build with no backend URL uses a mock conversation reply.
- `backend/src/server.mjs` can load the local environment file at runtime; this planning task neither reads nor edits any `.env` file. Secrets must remain outside Git and the APK.

## Open questions and validation spikes

| Question | Required evidence before committing to implementation |
| --- | --- |
| Which Turkish STT is actually usable on this GM 5 Plus? | Detect installed recognition service and Turkish language data; compare Wi-Fi-on/off transcripts/latency. If unavailable or network-only, benchmark Vosk 35 MB small Turkish model on the device, including peak RAM and battery. |
| Is the installed Turkish TTS voice offline and clear enough? | Enumerate voices and check network requirement; test airplane mode, speaker routing, interruption, and long reply clipping. |
| Does the phone's camera see a useful desk scene when mounted horizontally? | Photograph/test field of view with explicit consent; measure frame acquisition heat and battery before person detection plans. |
| What are acceptable privacy and memory-retention defaults? | User review of opt-in text, fact categories, retention intervals, correction/deletion flow, and backup policy. No silent memory rollout. |
| Which host SQLite binding is supportable on Node 22 ARM/x64 Docker targets? | Small migration/backup spike with a maintained stable binding; verify FTS5, native build/release compatibility, and corruption recovery. Do not assume experimental `node:sqlite` is stable. |
| Which local authenticated channel works for phone↔Mac and Mac↔ESP on API 24? | Prototype pairing, TLS/certificate rotation or equivalent, replay protection, recovery after phone reinstall, and firewall isolation. Do not rely on private IP alone. |
| What physical motion is actually safe/useful? | Enclosure geometry, weight/torque, stall load, electrical stop, firmware limits, and user study. No servo purchase or autonomous motion specification before these measurements. |
| Can a future Raspberry Pi host the chosen local STT/LLM? | Benchmark exact board, model, concurrent STT + backend load, memory, heat, and Turkish quality. Docker portability alone does not prove performance. |

## Decision review rule

At each roadmap phase, update this file with the measured result, date, and affected decision ID. If a prerequisite fails, choose the simpler fallback and keep the phone-only companion functional. Avoid introducing a new background service, paid API, or distributed protocol without a measurable requirement and a rollback path.
