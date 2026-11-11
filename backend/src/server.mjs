import { createServer as createNodeServer } from "node:http";
import { createHash, randomUUID, timingSafeEqual } from "node:crypto";
import { existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createProvider, ProviderError } from "./providers.mjs";
import { buildSystemPrompt } from "./personality.mjs";
import { MemoryConflictError, MemoryNotFoundError, MemoryRepository } from "./memory-repository.mjs";
import { MemoryInputError, MemoryService, renderMemoryContext } from "./memory-service.mjs";

const localEnvPath = resolve(dirname(fileURLToPath(import.meta.url)), "../.env");

const MAX_BODY_BYTES = 24 * 1024;
const MAX_HISTORY_MESSAGES = 8;
const MAX_MESSAGE_CHARS = 1_800;
const MAX_REPLY_CHARS = 900;
const RATE_WINDOW_MS = 60_000;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const REMINDER_RECURRENCES = new Set(["none", "daily", "weekdays", "weekly:MO", "weekly:TU", "weekly:WE", "weekly:TH", "weekly:FR", "weekly:SA", "weekly:SU"]);

export function conversationFingerprint(interactionId, message) {
  return createHash("sha256").update(`${interactionId}\0${message}`).digest("hex").slice(0, 16);
}

function redactedPreview(value, maxWords = 5) {
  const words = String(value).trim().split(/\s+/u).filter(Boolean);
  if (words.length === 0) return "[empty]";
  const preview = words.slice(0, maxWords).map((word) => {
    const [first] = Array.from(word);
    if (!first || !/\p{L}/u.test(first)) return `[redacted:${Array.from(word).length}]`;
    const length = Array.from(word).length;
    return `${first}${"•".repeat(Math.min(length - 1, 5))}${length > 6 ? "…" : ""}`;
  }).join(" ");
  return words.length > maxWords ? `${preview} …` : preview;
}

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
  const defaultTimeoutMs = provider === "gemini" ? 40_000 : 20_000;
  const timeout = Number.parseInt(env.AI_TIMEOUT_MS ?? String(defaultTimeoutMs), 10);
  const rateLimit = Number.parseInt(env.API_RATE_LIMIT_PER_MINUTE ?? "40", 10);
  const defaultModel = provider === "mock" ? "companion-mock" : provider === "gemini" ? "gemini-2.5-flash" : "";

  return {
    provider,
    model: modelFromEnvironment || defaultModel,
    openAiKey: String(env.OPENAI_API_KEY ?? "").trim(),
    anthropicKey: String(env.ANTHROPIC_API_KEY ?? "").trim(),
    geminiKey: String(env.GEMINI_API_KEY ?? "").trim(),
    companionAccessToken: String(env.COMPANION_ACCESS_TOKEN ?? "").trim(),
    personality: String(env.COMPANION_PERSONALITY ?? "").trim(),
    timeoutMs: Number.isFinite(timeout) ? Math.max(2_000, Math.min(timeout, 60_000)) : defaultTimeoutMs,
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
  if (config.companionAccessToken && (config.companionAccessToken.length < 32 || config.companionAccessToken.length > 512)) {
    return new ApiError("invalid_companion_access_token", 503, "COMPANION_ACCESS_TOKEN must contain 32 to 512 characters.");
  }
  return null;
}

function hasValidCompanionToken(request, expectedToken) {
  if (!expectedToken) return true;
  const authorization = request.headers.authorization;
  if (typeof authorization !== "string") return false;
  const match = /^Bearer\s+([^\s]+)$/i.exec(authorization);
  if (!match) return false;
  const provided = Buffer.from(match[1], "utf8");
  const expected = Buffer.from(expectedToken, "utf8");
  return provided.length === expected.length && timingSafeEqual(provided, expected);
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
  if (value.memoryEnabled !== undefined && typeof value.memoryEnabled !== "boolean") {
    throw new ApiError("invalid_memory_consent", 400, "memoryEnabled must be a boolean.");
  }
  return {
    message: value.message.trim(),
    language,
    history: parseHistory(value.history),
    // Older clients stay safe: persistent memory is opt-in and defaults off.
    memoryEnabled: value.memoryEnabled === true
  };
}

function validateReminder(value, { partial = false } = {}) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new ApiError("invalid_reminder", 400, "A reminder JSON object is required.");
  }
  const result = {};
  if (value.id !== undefined) {
    if (!UUID_PATTERN.test(value.id)) throw new ApiError("invalid_reminder_id", 400, "id must be a UUID.");
    result.id = value.id.toLowerCase();
  }
  if (!partial || value.message !== undefined) {
    if (typeof value.message !== "string" || !value.message.trim() || value.message.trim().length > 240) {
      throw new ApiError("invalid_reminder_message", 400, "message must contain between 1 and 240 characters.");
    }
    result.message = value.message.trim();
  }
  if (!partial || value.scheduledAt !== undefined) {
    if (typeof value.scheduledAt !== "string" || !Number.isFinite(Date.parse(value.scheduledAt))) {
      throw new ApiError("invalid_reminder_time", 400, "scheduledAt must be a valid ISO date-time.");
    }
    result.scheduledAt = new Date(value.scheduledAt).toISOString();
  }
  if (value.nextScheduledAt !== undefined) {
    if (typeof value.nextScheduledAt !== "string" || !Number.isFinite(Date.parse(value.nextScheduledAt))) {
      throw new ApiError("invalid_reminder_time", 400, "nextScheduledAt must be a valid ISO date-time.");
    }
    result.nextScheduledAt = new Date(value.nextScheduledAt).toISOString();
  }
  if (value.lastDeliveredAt !== undefined) {
    if (value.lastDeliveredAt !== null && (typeof value.lastDeliveredAt !== "string" || !Number.isFinite(Date.parse(value.lastDeliveredAt)))) {
      throw new ApiError("invalid_reminder_time", 400, "lastDeliveredAt must be null or a valid ISO date-time.");
    }
    result.lastDeliveredAt = value.lastDeliveredAt === null ? null : new Date(value.lastDeliveredAt).toISOString();
  }
  if (!partial || value.timeZone !== undefined) {
    if (typeof value.timeZone !== "string" || value.timeZone.length > 80) {
      throw new ApiError("invalid_reminder_timezone", 400, "timeZone must be a valid IANA time zone.");
    }
    try { new Intl.DateTimeFormat("en-US", { timeZone: value.timeZone }); }
    catch { throw new ApiError("invalid_reminder_timezone", 400, "timeZone must be a valid IANA time zone."); }
    result.timeZone = value.timeZone;
  }
  if (value.recurrence !== undefined || !partial) {
    const recurrence = value.recurrence ?? "none";
    if (typeof recurrence !== "string" || !REMINDER_RECURRENCES.has(recurrence)) {
      throw new ApiError("invalid_reminder_recurrence", 400, "recurrence is not supported.");
    }
    result.recurrence = recurrence;
  }
  if (value.enabled !== undefined) {
    if (typeof value.enabled !== "boolean") throw new ApiError("invalid_reminder_status", 400, "enabled must be a boolean.");
    result.enabled = value.enabled;
    result.status = value.enabled ? "scheduled" : "paused";
  }
  if (value.status !== undefined) {
    if (!["scheduled", "paused", "completed"].includes(value.status)) {
      throw new ApiError("invalid_reminder_status", 400, "status must be scheduled, paused, or completed.");
    }
    result.status = value.status;
    result.enabled = value.status === "scheduled";
  }
  return result;
}

function validateAutonomySettings(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new ApiError("invalid_autonomy_settings", 400, "A settings JSON object is required.");
  }
  const result = {};
  for (const key of ["autonomyEnabled", "proactiveSpeechEnabled", "quietHoursEnabled"]) {
    if (value[key] !== undefined) {
      if (typeof value[key] !== "boolean") throw new ApiError("invalid_autonomy_settings", 400, `${key} must be a boolean.`);
      result[key] = value[key];
    }
  }
  for (const key of ["quietHoursStart", "quietHoursEnd"]) {
    if (value[key] !== undefined) {
      if (typeof value[key] !== "string" || !/^([01]\d|2[0-3]):[0-5]\d$/u.test(value[key])) {
        throw new ApiError("invalid_autonomy_settings", 400, `${key} must use HH:mm format.`);
      }
      result[key] = value[key];
    }
  }
  return result;
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
  if (!response.destroyed && !response.writableEnded) response.setHeader("X-Error-Code", apiError.code);
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

export function createConversationServer({
  env = process.env,
  fetchImpl = globalThis.fetch,
  now = Date.now,
  requestLogger = (entry) => console.info(JSON.stringify(entry)),
  memoryDatabasePath = String(env.MEMORY_DB_PATH ?? "./data/memories.sqlite").trim()
} = {}) {
  const config = readRuntimeConfig(env);
  let provider;
  try {
    provider = createProvider(config.provider, fetchImpl);
  } catch (error) {
    provider = null;
  }
  const allowRequest = createRateLimiter(config.rateLimitPerMinute, now);
  let activeGenerations = 0;
  let memoryRepository = null;
  try {
    memoryRepository = new MemoryRepository(memoryDatabasePath || "./data/memories.sqlite");
  } catch (error) {
    requestLogger({ event: "memory_store_unavailable", errorType: error?.constructor?.name ?? "Error" });
  }
  const memoryService = new MemoryService(memoryRepository);

  const server = createNodeServer(async (request, response) => {
    const suppliedId = request.headers["x-request-id"];
    const requestId = typeof suppliedId === "string" &&
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(suppliedId)
      ? suppliedId.toLowerCase()
      : randomUUID();
    const suppliedInteractionId = request.headers["x-interaction-id"];
    const interactionId = typeof suppliedInteractionId === "string" && UUID_PATTERN.test(suppliedInteractionId)
      ? suppliedInteractionId.toLowerCase()
      : randomUUID();
    response.setHeader("X-Request-ID", requestId);
    response.setHeader("X-Interaction-ID", interactionId);
    const pathname = new URL(request.url ?? "/", "http://localhost").pathname;
    const memoryItemMatch = /^\/v1\/memories\/([0-9a-f-]{36})$/iu.exec(pathname);
    const isMemoryStatus = request.method === "GET" && pathname === "/v1/memories/status";
    const isMemoryCollection = pathname === "/v1/memories" && ["GET", "POST", "DELETE"].includes(request.method);
    const isMemoryItem = memoryItemMatch !== null && ["GET", "PATCH", "DELETE"].includes(request.method);
    const isMemoryRequest = isMemoryStatus || isMemoryCollection || isMemoryItem;
    const isConfigRequest = request.method === "GET" && pathname === "/v1/config";
    const isConversationRequest = request.method === "POST" && pathname === "/v1/conversations";
    const reminderItemMatch = /^\/v1\/reminders\/([0-9a-f-]{36})$/iu.exec(pathname);
    const isReminderCollection = pathname === "/v1/reminders" && ["GET", "POST"].includes(request.method);
    const isReminderItem = reminderItemMatch !== null && ["PATCH", "DELETE"].includes(request.method);
    const isAutonomyRequest = pathname === "/v1/autonomy" && ["GET", "PUT"].includes(request.method);
    const isReminderRequest = isReminderCollection || isReminderItem || isAutonomyRequest;
    const route = isConversationRequest ? "conversation"
      : isConfigRequest ? "config"
        : isMemoryRequest ? "memory" : isReminderRequest ? "reminders" : pathname === "/healthz" ? "health" : "other";
    if (route !== "health") {
      const startedAt = Date.now();
      requestLogger({ event: "request_received", interactionId, requestId, route, method: request.method });
      let reported = false;
      const report = (outcome) => {
        if (reported) return;
        reported = true;
        requestLogger({
          event: "http_request",
          interactionId,
          requestId,
          route,
          method: request.method,
          outcome,
          status: outcome === "completed" ? response.statusCode : null,
          errorCode: response.getHeader("X-Error-Code") ?? null,
          durationMs: Date.now() - startedAt
        });
      };
      response.once("finish", () => report("completed"));
      response.once("close", () => report("disconnected"));
    }
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

    if (!isConfigRequest && !isConversationRequest && !isMemoryRequest && !isReminderRequest) {
      sendError(response, new ApiError("not_found", 404, "The requested endpoint does not exist."));
      return;
    }

    const clientKey = request.socket.remoteAddress ?? "unknown";
    if (!allowRequest(clientKey)) {
      sendError(response, new ApiError("rate_limited", 429, "Too many requests. Try again shortly."));
      return;
    }

    if (!hasValidCompanionToken(request, config.companionAccessToken)) {
      requestLogger({ event: "companion_auth_rejected", interactionId, requestId, route });
      sendError(response, new ApiError("companion_auth_required", 401, "A valid companion access token is required."));
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

    if (isReminderRequest) {
      try {
        if (!memoryRepository) throw new ApiError("reminder_store_unavailable", 503, "The local reminder store is unavailable.");
        if (isAutonomyRequest && request.method === "GET") {
          sendJson(response, 200, { settings: memoryRepository.getSettings() });
          return;
        }
        if (isAutonomyRequest && request.method === "PUT") {
          const settings = memoryRepository.updateSettings(validateAutonomySettings(await readJsonBody(request)));
          sendJson(response, 200, { settings });
          return;
        }
        if (isReminderCollection && request.method === "GET") {
          sendJson(response, 200, { reminders: memoryRepository.listReminders() });
          return;
        }
        if (isReminderCollection && request.method === "POST") {
          const body = validateReminder(await readJsonBody(request));
          const reminder = memoryRepository.createReminder(body);
          sendJson(response, 201, { reminder });
          return;
        }
        if (isReminderItem && request.method === "PATCH") {
          const changes = validateReminder(await readJsonBody(request), { partial: true });
          const reminder = memoryRepository.updateReminder(reminderItemMatch[1], changes);
          if (!reminder) throw new ApiError("reminder_not_found", 404, "The requested reminder does not exist.");
          sendJson(response, 200, { reminder });
          return;
        }
        if (isReminderItem && request.method === "DELETE") {
          const deleted = memoryRepository.deleteReminder(reminderItemMatch[1]);
          if (!deleted) throw new ApiError("reminder_not_found", 404, "The requested reminder does not exist.");
          sendJson(response, 200, { deleted: true });
          return;
        }
      } catch (error) {
        if (error instanceof ApiError) sendError(response, error);
        else {
          requestLogger({ event: "reminder_store_request_failed", requestId, interactionId, errorType: error?.constructor?.name ?? "Error" });
          sendError(response, new ApiError("reminder_store_unavailable", 503, "The local reminder store could not complete the request."));
        }
      }
      return;
    }

    if (isMemoryRequest) {
      try {
        if (!memoryRepository) throw new ApiError("memory_unavailable", 503, "The local memory store is unavailable.");
        if (isMemoryStatus) {
          sendJson(response, 200, memoryService.status());
          return;
        }
        if (isMemoryCollection && request.method === "GET") {
          const requestedLimit = Number.parseInt(new URL(request.url, "http://localhost").searchParams.get("limit") ?? "100", 10);
          sendJson(response, 200, { memories: memoryService.list(Number.isFinite(requestedLimit) ? requestedLimit : 100) });
          return;
        }
        if (isMemoryCollection && request.method === "POST") {
          const body = await readJsonBody(request);
          const memory = memoryService.create(body, { consentEnabled: body?.memoryEnabled === true });
          sendJson(response, 201, { memory });
          return;
        }
        if (isMemoryCollection && request.method === "DELETE") {
          const deletedCount = memoryService.clearAll();
          sendJson(response, 200, { deletedCount });
          return;
        }
        if (isMemoryItem && request.method === "GET") {
          const memory = memoryService.get(memoryItemMatch[1]);
          if (!memory) throw new ApiError("memory_not_found", 404, "The requested memory does not exist.");
          sendJson(response, 200, { memory });
          return;
        }
        if (isMemoryItem && request.method === "PATCH") {
          const body = await readJsonBody(request);
          const memory = memoryService.update(memoryItemMatch[1], body);
          if (!memory) throw new ApiError("memory_not_found", 404, "The requested memory does not exist.");
          sendJson(response, 200, { memory });
          return;
        }
        if (isMemoryItem && request.method === "DELETE") {
          const deleted = memoryService.delete(memoryItemMatch[1]);
          if (!deleted) throw new ApiError("memory_not_found", 404, "The requested memory does not exist.");
          sendJson(response, 200, { deleted: true });
          return;
        }
      } catch (error) {
        if (error instanceof ApiError) {
          sendError(response, error);
          return;
        }
        if (error instanceof MemoryInputError) {
          const status = error.code === "memory_consent_required" ? 403 : 400;
          const message = error.code === "memory_consent_required"
            ? "Enable memory consent before saving a memory."
            : "The memory data is invalid or contains disallowed sensitive information.";
          sendError(response, new ApiError(error.code, status, message));
          return;
        }
        if (error instanceof MemoryConflictError) {
          sendError(response, new ApiError("memory_conflict", 409, "An active memory already exists for this category and key."));
          return;
        }
        if (error instanceof MemoryNotFoundError) {
          sendError(response, new ApiError("memory_not_found", 404, "The requested memory does not exist."));
          return;
        }
        requestLogger({ event: "memory_store_request_failed", requestId, interactionId, errorType: error?.constructor?.name ?? "Error" });
        sendError(response, new ApiError("memory_unavailable", 503, "The local memory store could not complete the request."));
      }
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
      const suppliedFingerprint = request.headers["x-message-fingerprint"];
      const fingerprintHeaderProvided = suppliedFingerprint !== undefined;
      const expectedFingerprint = conversationFingerprint(interactionId, body.message);
      const clientFingerprintProvided = typeof suppliedFingerprint === "string" && /^[0-9a-f]{16}$/i.test(suppliedFingerprint);
      const clientFingerprintMatches = clientFingerprintProvided &&
        suppliedFingerprint.toLowerCase() === expectedFingerprint;
      requestLogger({
        event: "conversation_message_received",
        interactionId,
        requestId,
        messageChars: body.message.length,
        messageFingerprint: expectedFingerprint,
        clientFingerprintProvided,
        clientFingerprintMatches,
        messagePreview: redactedPreview(body.message)
      });
      if (fingerprintHeaderProvided && !clientFingerprintProvided) {
        throw new ApiError("invalid_message_fingerprint", 400, "The conversation message fingerprint is invalid.");
      }
      if (clientFingerprintProvided && !clientFingerprintMatches) {
        throw new ApiError("message_fingerprint_mismatch", 400, "The conversation message did not match its request fingerprint.");
      }

      const memoryOutcome = memoryService.processTurn({
        message: body.message,
        history: body.history,
        enabled: body.memoryEnabled,
        interactionId
      });
      if (memoryOutcome.storageUnavailable) {
        requestLogger({ event: "memory_store_operation_unavailable", requestId, interactionId });
      }
      if (memoryOutcome.directReply) {
        sendJson(response, 200, {
          reply: memoryOutcome.directReply,
          provider: "local-memory",
          model: "memory-policy",
          interactionId
        });
        return;
      }
      const configIssue = configurationError(config);
      if (configIssue) throw configIssue;
      const memoryPrompt = renderMemoryContext(memoryOutcome.memories ?? [], { savedCount: memoryOutcome.savedCount ?? 0 });
      const messages = [
        { role: "system", content: `${buildSystemPrompt(body.language, config.personality)}\n\n${memoryPrompt}` },
        ...body.history,
        { role: "user", content: body.message }
      ];
      const apiKey = config.provider === "openai"
        ? config.openAiKey
        : config.provider === "anthropic"
          ? config.anthropicKey
          : config.geminiKey;
      const providerEvent = config.provider === "gemini" ? "gemini" : "provider";
      requestLogger({
        event: `${providerEvent}_request_started`,
        interactionId,
        requestId,
        provider: config.provider,
        model: config.model,
        messageChars: body.message.length,
        messageFingerprint: expectedFingerprint
      });
      let rawReply;
      try {
        rawReply = await provider.generate({
          model: config.model,
          apiKey,
          language: body.language,
          messages,
          timeoutMs: config.timeoutMs,
          signal: abortController.signal
        });
      } catch (error) {
        const errorCode = error?.code && /^[a-z0-9_]{1,64}$/i.test(error.code) ? error.code : "unknown";
        requestLogger({
          event: `${providerEvent}_request_failed`,
          interactionId,
          requestId,
          provider: config.provider,
          model: config.model,
          errorCode
        });
        throw error;
      }
      const reply = trimReply(rawReply);
      if (!reply) throw new ProviderError("empty_provider_response", 502, "The AI provider returned no text.");
      requestLogger({
        event: `${providerEvent}_response_received`,
        interactionId,
        requestId,
        provider: config.provider,
        model: config.model,
        replyChars: reply.length,
        replyFingerprint: conversationFingerprint(interactionId, reply),
        replyPreview: redactedPreview(reply)
      });
      sendJson(response, 200, {
        reply,
        provider: config.provider,
        model: config.model,
        interactionId
      });
    } catch (error) {
      if (!abortController.signal.aborted) sendError(response, error);
    } finally {
      response.removeListener("close", handleDisconnect);
      activeGenerations -= 1;
    }
  });
  server.once("close", () => memoryRepository?.close());
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  if (existsSync(localEnvPath)) process.loadEnvFile(localEnvPath);
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
