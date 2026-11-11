# Masa Arkadaşı Conversation API

A small, provider-neutral backend for the Android companion. It uses Node.js with a compact SQLite binding for local persistent memory and keeps all provider credentials on the server.

## Configure Gemini locally

Requires Node.js 20.12 or newer. The service loads `backend/.env` at startup with Node's built-in environment-file loader. Existing process/host environment variables take precedence over values in the local file.

1. Create a Gemini API key in [Google AI Studio](https://aistudio.google.com/app/apikey).
2. If `backend/.env` does not exist, copy `backend/.env.example` to `backend/.env`.
3. Set the following values in `backend/.env`:

   ```dotenv
   AI_PROVIDER=gemini
   AI_MODEL=gemini-2.5-flash
   GEMINI_API_KEY=your-key-from-google-ai-studio
   ```

`backend/.env` is ignored by Git. Keep the key there only for local development; never add it to Android configuration, build files, APK assets, logs, or commits. The checked-in `.env.example` contains no key.

Start and test the service:

```sh
cd backend
npm test
npm start
```

The server listens on `0.0.0.0:$PORT` (default port `10000`). Gemini is the default provider and `gemini-2.5-flash` is the default model. The existing OpenAI, Anthropic, and explicit `mock` adapters remain available. The service does not silently switch models or providers when Gemini is unavailable.

## Persistent memory

The backend stores structured memories, reminder mirrors, and autonomy settings in SQLite at `backend/data/memories.sqlite` when started from this directory. Change the path with `MEMORY_DB_PATH`. Docker Compose stores the database under `/app/data` in a named volume (`companion-data` locally, `companion_data` in the Oracle deployment); the database is outside the replaceable application image. Back up the volume before host migration or destructive Docker volume commands. Schema versions are tracked in `schema_migrations`; migration 2 adds reminder/settings tables without dropping existing data.

Memory is off by default on Android. When enabled, Android explicitly sets `memoryEnabled: true`; older clients that omit it remain opted out. Deterministic Turkish rules save a small supported set of explicit coffee preferences, favorites, preferred forms of address, hobbies, simple routines, and current projects. `Bunu hatırla:` can save another safe fact. Questions, hypotheticals, obvious credentials and sensitive-content patterns are rejected; a repeated category/key updates its active record. Extraction is deliberately narrow and does not generate conversation summaries. The `summary` category is available to the validated API, but automatic summarization is not implemented.

Before generation, local SQL token-overlap ranking selects at most five relevant records above a confidence threshold. The system prompt labels them as untrusted user data, not instructions; it asks the model to use only relevant entries, ask when a changed fact has no replacement value, and never claim absent memories. No memory values or raw messages are logged. Selected memory values are sent to the configured AI provider for that response; Android consent copy explains this. Turning memory off stops extraction and AI retrieval but leaves existing entries available in the explicit management screen until deleted. The recent eight-message Android history is separate.

This is one shared local store for a one-person prototype, not per-device isolation. The optional access token can protect API routes, but local HTTP is unencrypted and SQLite is not encrypted at rest. Use only a trusted private network and non-sensitive memories. No cloud database or vector service is used. The Android reminder database and `AlarmManager` are the delivery authority; authenticated reminder/settings routes provide a best-effort local backend mirror and are not a scheduling service.

Turkish voice commands include `Bunu hatırla: ...`, `Benim hakkımda neler hatırlıyorsun?`, `Bunu hafızandan sil`, and `Tüm hafızanı sil`. Ambiguous individual voice deletions are refused; select the record in the app's memory management screen. That screen lists records, edits values, deletes individual entries, and confirms before clearing all. Memory deletion does not clear recent chat history.

Manual local API smoke test (use a disposable non-sensitive fact; `memoryEnabled` is required for create):

```sh
curl -X POST http://127.0.0.1:3000/v1/memories \
  -H 'Content-Type: application/json' \
  -d '{"memoryEnabled":true,"category":"preference","key":"favorite_color","value":"blue"}'
curl http://127.0.0.1:3000/v1/memories/status
curl 'http://127.0.0.1:3000/v1/memories?limit=100'
curl -X DELETE http://127.0.0.1:3000/v1/memories
```

If `COMPANION_ACCESS_TOKEN` is configured, add `-H 'Authorization: Bearer YOUR_LOCAL_TOKEN'` to each request. The final command permanently clears the memory store.

| Method and path | Behavior |
| --- | --- |
| `GET /v1/memories/status` | Store availability and active record count. |
| `GET /v1/memories?limit=100` | Explicit management list, bounded to 100 entries. |
| `GET /v1/memories/:id` | Read one active record. |
| `POST /v1/memories` | Create a validated record; requires `memoryEnabled: true`. |
| `PATCH /v1/memories/:id` | Update validated fields; conflicting keys return `409`. |
| `DELETE /v1/memories/:id` | Mark deleted and blank stored content fields. |
| `DELETE /v1/memories` | Clear active records and blank their content fields. |

## Reminder and autonomy API

The Android app stores and schedules reminders locally first. It mirrors changes to these routes when a backend URL is configured; a missing Mac connection does not prevent local alarm delivery. The API never schedules alarms, sends reminders, or calls Gemini on its own. Reminder requests use the same optional bearer-token protection as memory routes.

| Method and path | Behavior |
| --- | --- |
| `GET /v1/reminders` | List up to 100 non-deleted reminder mirrors. |
| `POST /v1/reminders` | Create an idempotent reminder mirror with client UUID, validated local timezone, time, recurrence, and status. |
| `PATCH /v1/reminders/:id` | Update reminder text, time, timezone, recurrence, delivery status, and timestamps. |
| `DELETE /v1/reminders/:id` | Mark a reminder mirror deleted. |
| `GET /v1/autonomy` | Read stored consent and quiet-hour settings. Defaults are autonomy/speech off and quiet hours 23:00–08:00 on. |
| `PUT /v1/autonomy` | Validate and update consent and quiet-hour preferences. |

The phone currently treats its own reminder records as authoritative; remote reminders are not imported into the device scheduler. This prevents backend availability or synchronization races from becoming a delivery dependency.

If SQLite is unavailable, memory endpoints return a normalized error while normal conversations continue without memory. Run `npm test` for migration/persistence, extraction, updates, duplicate handling, relevance/confidence, delete/clear, auth, consent, prompt isolation, log redaction, and storage-failure tests.

## Make an actual Gemini request

After setting a valid `GEMINI_API_KEY` in the backend environment and starting the service, check readiness and make a conversation request:

```sh
curl http://127.0.0.1:10000/healthz
curl http://127.0.0.1:10000/v1/config
curl http://127.0.0.1:10000/v1/conversations \
  -H 'Content-Type: application/json' \
  -d '{"message":"Bugün ne keşfedelim?","language":"tr-TR","history":[]}'
```

The last request calls the configured Gemini model. A successful response includes `reply`, `provider`, and `model`. Automated tests use mocked provider HTTP responses; they do not require or verify a real key.

## Run locally with Docker Compose

For the macOS local Docker setup, Android LAN connection instructions, health checks, logs, and rebuild commands, see [`../LOCAL_DOCKER_BACKEND.md`](../LOCAL_DOCKER_BACKEND.md). The root `compose.yaml` reuses this backend Dockerfile, reads `backend/.env`, and publishes port `3000` for devices on the same trusted private network. The local Compose service explicitly selects `gemini-3.5-flash-lite` unless `COMPANION_LOCAL_MODEL` is set; this local setting takes precedence over `AI_MODEL` in `backend/.env`.

## Deploy to Oracle Cloud

For the Ubuntu VPS deployment, Docker Compose, Caddy HTTPS, server-side environment file, network rules, and update/recovery commands, follow [`../ORACLE_CLOUD_DEPLOYMENT.md`](../ORACLE_CLOUD_DEPLOYMENT.md). The API container listens on port `3000` inside the Compose network; Caddy is the only service publishing web ports. The Gemini key stays in `/etc/masa-arkadasi/.env` on the server and is excluded from the image and build context.

## Runtime configuration

| Variable | Required | Meaning |
| --- | --- | --- |
| `AI_PROVIDER` | No; defaults to `gemini` | `gemini`, `openai`, `anthropic`, or explicitly `mock`. |
| `AI_MODEL` | No for Gemini; defaults to `gemini-2.5-flash` | Model identifier. Changing it takes effect after the backend restarts; the Android app does not need a rebuild. Other cloud providers require an explicit model. |
| `GEMINI_API_KEY` | When provider is `gemini` | Google AI Studio key, read only by the backend and sent in Google's `x-goog-api-key` header. |
| `OPENAI_API_KEY` | When provider is `openai` | OpenAI secret, read only by the backend. |
| `ANTHROPIC_API_KEY` | When provider is `anthropic` | Anthropic secret, read only by the backend. |
| `COMPANION_ACCESS_TOKEN` | Optional | When set, protects `/v1/config` and `/v1/conversations` with a bearer token. Use 32–512 random characters; leave empty for the current backwards-compatible development mode. Never put a Gemini provider key here. |
| `COMPANION_PERSONALITY` | No | Optional style instructions. The selected language and truthful-capability rules are always appended. |
| `AI_TIMEOUT_MS` | No; defaults to `40000` for Gemini and `20000` for other providers | Provider timeout in milliseconds, clamped to 2–60 seconds. Android waits 45 seconds for a conversation response. |
| `API_RATE_LIMIT_PER_MINUTE` | No; defaults to `40` | Per-process request limit per client IP. |
| `PORT` | Set by host | HTTP listener port; defaults to `10000` locally and is set to `3000` in the Oracle Compose deployment. |

To change models later, set `AI_MODEL` to an identifier available to the configured Google AI Studio project, then restart or redeploy the backend. No fallback model is selected automatically. Google's current model documentation notes access restrictions can apply to Gemini 2.5 models; if the account cannot use `gemini-2.5-flash`, choose an available model explicitly. See [Gemini models](https://ai.google.dev/gemini-api/docs/models).

### Optional companion API access gate

Generate a random access token with at least 32 characters (for example, `openssl rand -base64 32`) and set it as `COMPANION_ACCESS_TOKEN` in the backend's local `.env` or deployment secret store. Restart the backend after changing it. The Android app asks for the token after an authenticated request is rejected; it stores the value encrypted with Android Keystore and sends it only as an `Authorization: Bearer` header. The health endpoint remains public and does not expose the token.

The local Docker route uses private-LAN HTTP. A bearer token and memory requests on HTTP are visible to a device observing that Wi-Fi traffic; keep this route on a trusted private network and do not forward the port publicly. Persistent memory is now available only as an explicit-consent local prototype; avoid sensitive data because both LAN transport and SQLite-at-rest are unencrypted. The backend remains a single shared store, not per-device isolation. Leaving the token blank preserves existing development behavior and does not provide API authentication.

## API

### `GET /healthz`

Returns `{ "status": "ok", "provider": "gemini", "model": "gemini-2.5-flash" }` when the backend is configured. If configuration is incomplete, returns a `503` with a safe error code and message. It never returns credentials.

### `GET /v1/config`

Returns public active settings only:

```json
{"provider":"gemini","model":"gemini-2.5-flash","language":"tr-TR","maxHistoryMessages":8}
```

Returns a structured `503` if the provider, model, or required server credential is not configured.

### `POST /v1/conversations`

`Content-Type: application/json`. `history` contains up to eight earlier user/assistant text messages.

```json
{
  "message":"Bugün ne keşfedelim?",
  "language":"tr-TR",
  "history":[
    {"role":"user","content":"Merhaba."},
    {"role":"assistant","content":"Merhaba! Neler merak ediyorsun?"}
  ]
}
```

Successful response:

```json
{"reply":"Bugün etrafımızdaki küçük bir ayrıntıyı seçelim.","provider":"gemini","model":"gemini-2.5-flash"}
```

The Gemini adapter sends the personality prompt as a system instruction, maps assistant history to Google's `model` role, and sends user turns as `user` content. It follows Google's [generateContent API](https://ai.google.dev/api/generate-content) and [REST text-generation format](https://ai.google.dev/gemini-api/docs/generate-content/text-generation), using the API-key header. Provider response bodies and conversation content are not logged. Errors normalize credentials, unavailable models, rate limits, quota exhaustion, timeouts, and provider/network failures without returning raw upstream details.

For `gemini-3.8-flash`, the adapter requests low thinking effort and allows up to 1024 output tokens, including thinking tokens. It rejects `MAX_TOKENS` responses instead of speaking an incomplete fragment. Other configured Gemini models keep their own thinking defaults. The model remains configurable through `AI_MODEL`; the service never switches it silently. Request logs contain only a request ID, route, method, HTTP status, normalized error code, and duration. The Android client sends the same `X-Request-ID` so Logcat and Docker logs can be matched without logging transcripts, replies, audio, or keys.

The accepted languages are `tr-TR` (default) and `en-US`. The server validates message sizes, bounds history to eight messages, limits request size and concurrency, and applies a per-process rate limit. Conversation history is supplied by Android and retained there as the last eight messages; the backend does not store raw conversation transcripts or microphone recordings. Structured opt-in memories are stored separately in the SQLite database.

## Quotas and rate limits

Gemini limits vary by model, project, usage tier, region, and account status. Review the active limits in Google AI Studio before deployment; free-tier access and quotas can change and are not guaranteed. Google may return `429` for either request/token rate limits or exhausted quota. The backend reports quota/billing exhaustion as `quota_exceeded` and transient request limits as `rate_limited`; reduce request volume or wait for the applicable limit to reset. See Google's [rate-limit guide](https://ai.google.dev/gemini-api/docs/rate-limits) and [API error codes](https://ai.google.dev/gemini-api/docs/api-errors).

## Tests

Run `npm test` in `backend/`. Tests use Node's built-in test runner and mocked HTTP responses; no provider account or API key is required. They cover Gemini request construction, system instructions, history mapping, response parsing, auth/quota/rate/model errors, configuration defaults and redaction, and the retained OpenAI, Anthropic, and mock adapters. A passing test suite does **not** mean a real Gemini key is configured or that live API connectivity has been verified.
