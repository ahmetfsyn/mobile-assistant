# Masa Arkadaşı Conversation API

This is a small, provider-neutral HTTPS backend for the Android companion. It uses Node.js built-ins only and has no dependency on the phone's paired computer at runtime.

## Run and test locally

Requires Node.js 20 or newer. No `npm install` is needed.

```sh
cd backend
npm test
AI_PROVIDER=mock AI_MODEL=companion-mock npm start
```

The service listens on `0.0.0.0:$PORT` (default port `10000`). In the mock configuration, `/v1/conversations` returns a clearly labeled test-mode response and does not call an AI provider.

## Deploy as a hosted service

One lightweight option is a Render **Web Service** backed by this repository:

1. Create a Node web service and select this repository.
2. Set **Root Directory** to `backend`.
3. Set **Build Command** to `npm test` and **Start Command** to `npm start`.
4. Set the health-check path to `/healthz`.
5. Add the provider environment variables below in the hosting dashboard, then deploy.
6. Copy the service's HTTPS base URL into the Android build setting described in `../VOICE_SETUP.md`.

The server binds to the hosting platform's `PORT` and `0.0.0.0`; the phone only talks to the hosted HTTPS URL. The backend runs independently of the development computer. Provider API keys belong in the host's secret environment-variable store, never in Git or the APK.

## Runtime configuration

| Variable | Required | Meaning |
| --- | --- | --- |
| `AI_PROVIDER` | No; defaults to `mock` | `mock`, `openai`, or `anthropic`. Selects a server-side adapter. |
| `AI_MODEL` | For OpenAI/Anthropic | Provider model identifier. Changing it does not require rebuilding the Android app. |
| `OPENAI_API_KEY` | When provider is `openai` | OpenAI secret, read only by the backend. |
| `ANTHROPIC_API_KEY` | When provider is `anthropic` | Anthropic secret, read only by the backend. |
| `COMPANION_PERSONALITY` | No | Optional replacement for the backend's default personality instructions. The selected language rule is still appended. |
| `AI_TIMEOUT_MS` | No; defaults to `20000` | Provider timeout in milliseconds, clamped to 2–60 seconds. |
| `API_RATE_LIMIT_PER_MINUTE` | No; defaults to `40` | Per-process request limit for an IP address. |
| `PORT` | Set by host | HTTP listener port; defaults to `10000` locally. |

For example, set `AI_PROVIDER=openai`, `AI_MODEL` to a model available to your account, and `OPENAI_API_KEY` in the hosting dashboard. To switch providers, set `AI_PROVIDER=anthropic`, choose an available `AI_MODEL`, and set `ANTHROPIC_API_KEY`. Restart/redeploy the service after changing its environment. The Android APK contains only the backend URL; it does not select or name the AI model.

With either cloud provider selected, missing credentials or model configuration are reported as clear configuration errors. The service never silently switches to mock mode. Choose `AI_PROVIDER=mock` explicitly when you want test replies.

## API

### `GET /healthz`

Returns a process health response:

```json
{"status":"ok"}
```

### `GET /v1/config`

Returns only public active settings; no provider keys or prompts are returned:

```json
{"provider":"openai","model":"your-configured-model","language":"tr-TR","maxHistoryMessages":8}
```

Returns a structured `503` if the provider, model, or required server credential is not configured.

### `POST /v1/conversations`

`Content-Type: application/json`. The current message is sent separately; `history` contains up to eight earlier user/assistant text messages.

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
{"reply":"Bugün etrafımızdaki küçük bir ayrıntıyı seçelim; sence hangisi bir hikâye saklıyor?","provider":"openai","model":"your-configured-model"}
```

Local request examples:

```sh
curl http://127.0.0.1:10000/v1/config
curl http://127.0.0.1:10000/v1/conversations \
  -H 'Content-Type: application/json' \
  -d '{"message":"Bugün ne keşfedelim?","language":"tr-TR","history":[]}'
```

`language` is optional and defaults to `tr-TR`; the only accepted values are `tr-TR` and `en-US`. The server validates message sizes, bounds the history, limits request size and per-process concurrency, and rate-limits requests. Provider response bodies and conversation content are not logged. Error responses use `{ "error": { "code": "...", "message": "..." } }` and normalize credential, unsupported-model, rate-limit, timeout, and provider-network errors.

Conversation history is sent from and retained on the Android device as the last eight text messages (four turns). The backend is stateless and does not keep a long-term memory or store microphone recordings.

## Tests

Run `npm test` in `backend/`. Tests use Node's built-in test runner and stub provider HTTP responses; no provider account or API key is needed. They cover the OpenAI and Anthropic request adapters, configuration redaction, conversation/prompt construction, language and input validation, provider errors, and rate limiting. A passing mock test does **not** mean a real provider is configured or reachable.
