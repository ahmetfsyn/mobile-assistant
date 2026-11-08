# Masa Arkadaşı Conversation API

A small, provider-neutral HTTPS backend for the Android companion. It uses Node.js built-ins only and keeps all provider credentials on the server.

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
| `COMPANION_PERSONALITY` | No | Optional replacement for the default personality instructions; the selected language rule is still appended. |
| `AI_TIMEOUT_MS` | No; defaults to `40000` for Gemini and `20000` for other providers | Provider timeout in milliseconds, clamped to 2–60 seconds. Android waits 45 seconds for a conversation response. |
| `API_RATE_LIMIT_PER_MINUTE` | No; defaults to `40` | Per-process request limit per client IP. |
| `PORT` | Set by host | HTTP listener port; defaults to `10000` locally and is set to `3000` in the Oracle Compose deployment. |

To change models later, set `AI_MODEL` to an identifier available to the configured Google AI Studio project, then restart or redeploy the backend. No fallback model is selected automatically. Google's current model documentation notes access restrictions can apply to Gemini 2.5 models; if the account cannot use `gemini-2.5-flash`, choose an available model explicitly. See [Gemini models](https://ai.google.dev/gemini-api/docs/models).

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

The accepted languages are `tr-TR` (default) and `en-US`. The server validates message sizes, bounds history to eight messages, limits request size and concurrency, and applies a per-process rate limit. Conversation history is supplied by Android and retained there as the last eight messages; the backend is stateless and does not store conversation text or microphone recordings.

## Quotas and rate limits

Gemini limits vary by model, project, usage tier, region, and account status. Review the active limits in Google AI Studio before deployment; free-tier access and quotas can change and are not guaranteed. Google may return `429` for either request/token rate limits or exhausted quota. The backend reports quota/billing exhaustion as `quota_exceeded` and transient request limits as `rate_limited`; reduce request volume or wait for the applicable limit to reset. See Google's [rate-limit guide](https://ai.google.dev/gemini-api/docs/rate-limits) and [API error codes](https://ai.google.dev/gemini-api/docs/api-errors).

## Tests

Run `npm test` in `backend/`. Tests use Node's built-in test runner and mocked HTTP responses; no provider account or API key is required. They cover Gemini request construction, system instructions, history mapping, response parsing, auth/quota/rate/model errors, configuration defaults and redaction, and the retained OpenAI, Anthropic, and mock adapters. A passing test suite does **not** mean a real Gemini key is configured or that live API connectivity has been verified.
