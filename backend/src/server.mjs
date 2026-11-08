import { createServer as createNodeServer } from "node:http";
import { existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createProvider, ProviderError } from "./providers.mjs";

const localEnvPath = resolve(dirname(fileURLToPath(import.meta.url)), "../.env");
if (existsSync(localEnvPath)) process.loadEnvFile(localEnvPath);

const MAX_BODY_BYTES = 24 * 1024;
const MAX_HISTORY_MESSAGES = 8;
const MAX_MESSAGE_CHARS = 1_800;
const MAX_REPLY_CHARS = 900;
const RATE_WINDOW_MS = 60_000;

class ApiError extends Error {
  constructor(code, status, message) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
  }
}

export function readRuntimeConfig(env = process.env) {
  const provider = String(env.AI_PROVIDER ?? "gemini").trim().toLowerCase();
  const modelFromEnvironment = String(env.AI_MODEL ?? "").trim();
  const timeout = Number.parseInt(env.AI_TIMEOUT_MS ?? "20000", 10);
  const rateLimit = Number.parseInt(env.API_RATE_LIMIT_PER_MINUTE ?? "40", 10);
  const defaultModel = provider === "mock" ? "companion-mock" : provider === "gemini" ? "gemini-2.5-flash" : "";

  return {
    provider,
    model: modelFromEnvironment || defaultModel,
    openAiKey: String(env.OPENAI_API_KEY ?? "").trim(),
    anthropicKey: String(env.ANTHROPIC_API_KEY ?? "").trim(),
    geminiKey: String(env.GEMINI_API_KEY ?? "").trim(),
    personality: String(env.COMPANION_PERSONALITY ?? "").trim(),
    timeoutMs: Number.isFinite(timeout) ? Math.max(2_000, Math.min(timeout, 60_000)) : 20_000,
    rateLimitPerMinute: Number.isFinite(rateLimit) ? Math.max(1, Math.min(rateLimit, 300)) : 40
  };
}

function configurationError(config) {
  if (!["mock", "openai", "anthropic", "gemini"].includes(config.provider)) {
    return new ApiError("unsupported_provider", 503, "The configured provider is not supported.");
  }
  if (!config.model) {
    return new ApiError("model_not_configured", 503, "Set AI_MODEL for the selected provider.");
  }
  if (config.provider === "openai" && !config.openAiKey) {
    return new ApiError("missing_provider_credentials", 503, "Set OPENAI_API_KEY on the backend.");
  }
  if (config.provider === "anthropic" && !config.anthropicKey) {
    return new ApiError("missing_provider_credentials", 503, "Set ANTHROPIC_API_KEY on the backend.");
  }
  if (config.provider === "gemini" && !config.geminiKey) {
    return new ApiError("missing_provider_credentials", 503, "Set GEMINI_API_KEY on the backend.");
  }
  return null;
}

function systemPrompt(language, configuredPersonality) {
  const defaultPersonality = language === "en-US"
    ? "You are Masa Arkadaşı, a small desk companion robot: friendly, curious, playful, expressive, and occasionally gently humorous. Speak naturally, like a personable little robot companion, never like customer support. Keep spoken replies short (usually one or two sentences). Do not claim physical abilities, senses, actions, memories, or real-world experiences you do not have. Be honest when you do not know something."
    : "Sen Masa Arkadaşı adında küçük bir masaüstü robot yoldaşsın: arkadaş canlısı, meraklı, oyuncu, dışa vurumlu ve ara sıra tatlı bir mizahı olan bir karakter. Müşteri temsilcisi gibi değil, doğal ve sıcak konuş. Sesli yanıtlarını kısa tut; genellikle bir veya iki cümle yeterli. Gerçekte sahip olmadığın fiziksel yetenekleri, duyuları, eylemleri, anıları veya deneyimleri varmış gibi anlatma. Bilmediğin konularda dürüst ol.";
  const languageRule = language === "en-US"
    ? "Reply in English unless the user explicitly asks for another language."
    : "Kullanıcı açıkça başka bir dil istemedikçe Türkçe yanıt ver.";
  return `${configuredPersonality || defaultPersonality}\n\n${languageRule}`;
}

function parseHistory(rawHistory) {
  if (rawHistory === undefined) return [];
  if (!Array.isArray(rawHistory)) {
    throw new ApiError("invalid_history", 400, "history must be an array of prior messages.");
  }
  return rawHistory.slice(-MAX_HISTORY_MESSAGES).map((entry) => {
    if (!entry || !["user", "assistant"].includes(entry.role)) {
      throw new ApiError("invalid_history", 400, "Each history role must be user or assistant.");
    }
    if (typeof entry.content !== "string" || !entry.content.trim() || entry.content.length > MAX_MESSAGE_CHARS) {
      throw new ApiError("invalid_history", 400, "Each history message must contain between 1 and 1800 characters.");
    }
    return { role: entry.role, content: entry.content.trim() };
  });
}

function validateConversationBody(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new ApiError("invalid_request", 400, "A JSON object is required.");
  }
  if (typeof value.message !== "string" || !value.message.trim() || value.message.length > MAX_MESSAGE_CHARS) {
    throw new ApiError("invalid_message", 400, "message must contain between 1 and 1800 characters.");
  }
  const language = value.language === undefined ? "tr-TR" : value.language;
  if (language !== "tr-TR" && language !== "en-US") {
    throw new ApiError("unsupported_language", 400, "language must be tr-TR or en-US.");
  }
  return {
    message: value.message.trim(),
    language,
    history: parseHistory(value.history)
  };
}

function trimReply(value) {
  const trimmed = value.trim();
  if (trimmed.length <= MAX_REPLY_CHARS) return trimmed;
  const excerpt = trimmed.slice(0, MAX_REPLY_CHARS);
  const lastSpace = excerpt.lastIndexOf(" ");
  return `${excerpt.slice(0, lastSpace > 0 ? lastSpace : MAX_REPLY_CHARS).trimEnd()}…`;
}

function sendJson(response, status, value) {
  if (response.destroyed || response.writableEnded) return;
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff"
  });
  response.end(JSON.stringify(value));
}

function sendError(response, error) {
  const apiError = error instanceof ApiError || error instanceof ProviderError
    ? error
    : new ApiError("internal_error", 500, "The request could not be completed.");
  sendJson(response, apiError.status, {
    error: { code: apiError.code, message: apiError.message }
  });
}

async function readJsonBody(request) {
  const contentType = String(request.headers["content-type"] ?? "").toLowerCase();
  if (!contentType.startsWith("application/json")) {
    throw new ApiError("unsupported_content_type", 415, "Content-Type must be application/json.");
  }

  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw new ApiError("request_too_large", 413, "The request body is too large.");
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw new ApiError("invalid_json", 400, "The request body is not valid JSON.");
  }
}

function createRateLimiter(limit, now) {
  const clients = new Map();
  return (key) => {
    const currentTime = now();
    let state = clients.get(key);
    if (!state || currentTime - state.startedAt >= RATE_WINDOW_MS) {
      if (!state && clients.size >= 2_000) {
        for (const [client, entry] of clients) {
          if (currentTime - entry.startedAt >= RATE_WINDOW_MS) clients.delete(client);
        }
        if (clients.size >= 2_000) clients.delete(clients.keys().next().value);
      }
      state = { startedAt: currentTime, count: 0 };
      clients.set(key, state);
    }
    state.count += 1;
    return state.count <= limit;
  };
}

export function createConversationServer({ env = process.env, fetchImpl = globalThis.fetch, now = Date.now } = {}) {
  const config = readRuntimeConfig(env);
  let provider;
  try {
    provider = createProvider(config.provider, fetchImpl);
  } catch (error) {
    provider = null;
  }
  const allowRequest = createRateLimiter(config.rateLimitPerMinute, now);
  let activeGenerations = 0;

  return createNodeServer(async (request, response) => {
    if (request.method === "GET" && request.url === "/healthz") {
      const configIssue = configurationError(config);
      if (configIssue) {
        sendJson(response, configIssue.status, {
          status: "not_configured",
          error: { code: configIssue.code, message: configIssue.message }
        });
      } else {
        sendJson(response, 200, { status: "ok", provider: config.provider, model: config.model });
      }
      return;
    }

    const isConfigRequest = request.method === "GET" && request.url === "/v1/config";
    const isConversationRequest = request.method === "POST" && request.url === "/v1/conversations";
    if (!isConfigRequest && !isConversationRequest) {
      sendError(response, new ApiError("not_found", 404, "The requested endpoint does not exist."));
      return;
    }

    const clientKey = request.socket.remoteAddress ?? "unknown";
    if (!allowRequest(clientKey)) {
      sendError(response, new ApiError("rate_limited", 429, "Too many requests. Try again shortly."));
      return;
    }

    if (isConfigRequest) {
      const configIssue = configurationError(config);
      if (configIssue) {
        sendError(response, configIssue);
        return;
      }
      sendJson(response, 200, {
        provider: config.provider,
        model: config.model,
        language: "tr-TR",
        maxHistoryMessages: MAX_HISTORY_MESSAGES
      });
      return;
    }

    if (activeGenerations >= 4) {
      sendError(response, new ApiError("server_busy", 503, "The conversation service is busy. Try again shortly."));
      return;
    }
    activeGenerations += 1;

    const abortController = new AbortController();
    const handleDisconnect = () => {
      if (!response.writableEnded) abortController.abort();
    };
    response.on("close", handleDisconnect);

    try {
      const body = validateConversationBody(await readJsonBody(request));
      const configIssue = configurationError(config);
      if (configIssue) throw configIssue;
      const messages = [
        { role: "system", content: systemPrompt(body.language, config.personality) },
        ...body.history,
        { role: "user", content: body.message }
      ];
      const apiKey = config.provider === "openai"
        ? config.openAiKey
        : config.provider === "anthropic"
          ? config.anthropicKey
          : config.geminiKey;
      const rawReply = await provider.generate({
        model: config.model,
        apiKey,
        language: body.language,
        messages,
        timeoutMs: config.timeoutMs,
        signal: abortController.signal
      });
      const reply = trimReply(rawReply);
      if (!reply) throw new ProviderError("empty_provider_response", 502, "The AI provider returned no text.");
      sendJson(response, 200, {
        reply,
        provider: config.provider,
        model: config.model
      });
    } catch (error) {
      if (!abortController.signal.aborted) sendError(response, error);
    } finally {
      response.removeListener("close", handleDisconnect);
      activeGenerations -= 1;
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const port = Number.parseInt(process.env.PORT ?? "10000", 10);
  const host = process.env.HOST ?? "0.0.0.0";
  const server = createConversationServer();
  server.listen(port, host, () => {
    console.log(`Conversation API listening on ${host}:${port}`);
  });
  server.on("error", (error) => {
    console.error(`Conversation API failed to start: ${error.message}`);
    process.exitCode = 1;
  });
}
