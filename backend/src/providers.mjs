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

async function requestJson(fetchImpl, url, init, timeoutMs, callerSignal) {
  const controller = new AbortController();
  const onCallerAbort = () => controller.abort();
  if (callerSignal?.aborted) onCallerAbort();
  else callerSignal?.addEventListener("abort", onCallerAbort, { once: true });
  const timeout = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetchImpl(url, { ...init, signal: controller.signal });
    if (!response.ok) throw providerErrorForStatus(response.status);
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
    default:
      throw new ProviderError("unsupported_provider", 503, "The configured AI provider is not supported.");
  }
}
