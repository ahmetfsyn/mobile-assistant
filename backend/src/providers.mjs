export class ProviderError extends Error {
  constructor(code, status, message) {
    super(message);
    this.name = "ProviderError";
    this.code = code;
    this.status = status;
  }
}

function providerErrorForStatus(status) {
  if (status === 401 || status === 403) {
    return new ProviderError(
      "invalid_provider_credentials",
      503,
      "The AI provider credentials configured on the server were rejected."
    );
  }
  if (status === 400 || status === 404) {
    return new ProviderError(
      "unsupported_model",
      503,
      "The configured model is unavailable for this provider account."
    );
  }
  if (status === 429) {
    return new ProviderError("rate_limited", 429, "The AI provider is rate limiting requests.");
  }
  return new ProviderError(
    "provider_unavailable",
    502,
    "The configured AI provider could not complete the request."
  );
}

async function requestJson(fetchImpl, url, init, timeoutMs, callerSignal, mapError = providerErrorForStatus) {
  const controller = new AbortController();
  const onCallerAbort = () => controller.abort();
  if (callerSignal?.aborted) onCallerAbort();
  else callerSignal?.addEventListener("abort", onCallerAbort, { once: true });
  const timeout = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetchImpl(url, { ...init, signal: controller.signal });
    if (!response.ok) {
      let errorBody;
      if (mapError !== providerErrorForStatus) {
        try {
          errorBody = await response.json();
        } catch {
          errorBody = undefined;
        }
      }
      throw mapError(response.status, errorBody);
    }
    try {
      return await response.json();
    } catch {
      throw new ProviderError("invalid_provider_response", 502, "The AI provider returned an invalid response.");
    }
  } catch (error) {
    if (error instanceof ProviderError) throw error;
    if (controller.signal.aborted) {
      throw new ProviderError("provider_timeout", 504, "The AI provider response timed out.");
    }
    throw new ProviderError("provider_unavailable", 502, "The configured AI provider could not be reached.");
  } finally {
    clearTimeout(timeout);
    callerSignal?.removeEventListener("abort", onCallerAbort);
  }
}

function requireText(value) {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new ProviderError("empty_provider_response", 502, "The AI provider returned no text.");
  }
  return value.trim();
}

function createOpenAiProvider(fetchImpl) {
  return {
    async generate({ model, apiKey, messages, timeoutMs, signal }) {
      const response = await requestJson(
        fetchImpl,
        "https://api.openai.com/v1/chat/completions",
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${apiKey}`,
            "Content-Type": "application/json",
            Accept: "application/json"
          },
          body: JSON.stringify({
            model,
            messages,
            max_completion_tokens: 180
          })
        },
        timeoutMs,
        signal
      );
      return requireText(response?.choices?.[0]?.message?.content);
    }
  };
}

function createAnthropicProvider(fetchImpl) {
  return {
    async generate({ model, apiKey, messages, timeoutMs, signal }) {
      const system = messages
        .filter((message) => message.role === "system")
        .map((message) => message.content)
        .join("\n\n");
      const conversation = messages.filter((message) => message.role !== "system");
      const response = await requestJson(
        fetchImpl,
        "https://api.anthropic.com/v1/messages",
        {
          method: "POST",
          headers: {
            "x-api-key": apiKey,
            "anthropic-version": "2023-06-01",
            "Content-Type": "application/json",
            Accept: "application/json"
          },
          body: JSON.stringify({
            model,
            max_tokens: 180,
            system,
            messages: conversation
          })
        },
        timeoutMs,
        signal
      );
      const text = Array.isArray(response?.content)
        ? response.content.filter((part) => part?.type === "text").map((part) => part.text).join("\n")
        : "";
      return requireText(text);
    }
  };
}

function geminiErrorForResponse(status, body) {
  const upstream = body?.error ?? {};
  const providerCode = String(upstream.status ?? "").toUpperCase();
  const message = String(upstream.message ?? "");
  const detail = `${providerCode} ${message} ${JSON.stringify(upstream.details ?? [])}`.toLowerCase();
  const modelUnavailable =
    status === 404 || providerCode === "NOT_FOUND" ||
    /model.{0,80}(not found|unavailable|does not exist|not supported|not enabled|access denied|permission)/i.test(message) ||
    /(not available|no access to|permission to use).{0,60}model/i.test(message);

  if (modelUnavailable) {
    return new ProviderError("unsupported_model", 503, "The configured Gemini model is unavailable for this account.");
  }
  if (
    status === 401 || (status === 403 && !modelUnavailable) ||
    ["API_KEY_INVALID", "UNAUTHENTICATED", "PERMISSION_DENIED"].includes(providerCode) ||
    /api.?key.{0,40}(invalid|not valid|unauthorized|permission)/i.test(message)
  ) {
    return new ProviderError("invalid_provider_credentials", 503, "The Gemini API key was rejected. Check GEMINI_API_KEY on the server.");
  }
  if (status === 429) {
    if (/quota|billing|daily limit/.test(detail)) {
      return new ProviderError("quota_exceeded", 429, "The Gemini project quota is exhausted. Check its AI Studio limits and billing settings.");
    }
    return new ProviderError("rate_limited", 429, "Gemini is rate limiting requests. Try again shortly.");
  }
  if (status >= 500) {
    return new ProviderError("provider_unavailable", 502, "Gemini is temporarily unavailable.");
  }
  return new ProviderError("provider_request_failed", 502, "Gemini could not process the configured request.");
}

function createGeminiProvider(fetchImpl) {
  return {
    async generate({ model, apiKey, messages, timeoutMs, signal }) {
      const systemInstruction = messages
        .filter((message) => message.role === "system")
        .map((message) => message.content)
        .join("\n\n");
      const contents = messages
        .filter((message) => message.role !== "system")
        .map((message) => ({
          role: message.role === "assistant" ? "model" : "user",
          parts: [{ text: message.content }]
        }));
      const generationConfig = { maxOutputTokens: 1_024 };
      if (model === "gemini-3.8-flash") {
        generationConfig.thinkingConfig = { thinkingLevel: "low" };
      }
      const requestBody = {
        contents,
        generationConfig
      };
      if (systemInstruction) requestBody.systemInstruction = { parts: [{ text: systemInstruction }] };
      const response = await requestJson(
        fetchImpl,
        `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`,
        {
          method: "POST",
          headers: {
            "x-goog-api-key": apiKey,
            "Content-Type": "application/json",
            Accept: "application/json"
          },
          body: JSON.stringify(requestBody)
        },
        timeoutMs,
        signal,
        geminiErrorForResponse
      );
      if (response?.candidates?.[0]?.finishReason === "MAX_TOKENS") {
        throw new ProviderError("incomplete_provider_response", 502, "Gemini stopped before completing its reply.");
      }
      const text = Array.isArray(response?.candidates?.[0]?.content?.parts)
        ? response.candidates[0].content.parts
          .filter((part) => typeof part?.text === "string" && part.thought !== true)
          .map((part) => part.text)
          .join("\n")
        : "";
      return requireText(text);
    }
  };
}

function createMockProvider() {
  return {
    async generate({ language }) {
      return language === "en-US"
        ? "I’m in test mode right now, but I heard you. Add a provider key on the backend to turn on my AI brain."
        : "Şu an deneme kipindeyim ama seni duydum. Gerçek sohbet için sunucuya bir AI sağlayıcısı eklenmesi gerekiyor.";
    }
  };
}

export function createProvider(providerName, fetchImpl = globalThis.fetch) {
  switch (providerName) {
    case "mock":
      return createMockProvider();
    case "openai":
      return createOpenAiProvider(fetchImpl);
    case "anthropic":
      return createAnthropicProvider(fetchImpl);
    case "gemini":
      return createGeminiProvider(fetchImpl);
    default:
      throw new ProviderError("unsupported_provider", 503, "The configured AI provider is not supported.");
  }
}
