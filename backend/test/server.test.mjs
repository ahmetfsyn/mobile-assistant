import test from "node:test";
import assert from "node:assert/strict";
import { conversationFingerprint, createConversationServer, readRuntimeConfig } from "../src/server.mjs";

async function withServer(options, run) {
  const server = createConversationServer({ memoryDatabasePath: ":memory:", requestLogger: () => {}, ...options });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  try {
    await run(`http://127.0.0.1:${address.port}`);
  } finally {
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
}

test("request IDs and diagnostic logs expose status without secrets or message text", async () => {
  const entries = [];
  const requestIds = [
    "a1b2c3d4-0000-4000-8000-123456789abc",
    "a1b2c3d4-0000-4000-8000-123456789abd"
  ];
  const interactionId = "b1b2c3d4-0000-4000-8000-123456789abc";
  await withServer({
    env: { AI_PROVIDER: "mock", GEMINI_API_KEY: "server-only-secret" },
    requestLogger: (entry) => entries.push(entry)
  }, async (baseUrl) => {
    const config = await fetch(`${baseUrl}/v1/config`, {
      headers: { "X-Request-ID": requestIds[0], "X-Interaction-ID": interactionId }
    });
    assert.equal(config.headers.get("X-Request-ID"), requestIds[0]);
    assert.equal(config.headers.get("X-Interaction-ID"), interactionId);
    assert.equal(config.status, 200);

    const invalid = await fetch(`${baseUrl}/v1/conversations`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Request-ID": requestIds[1],
        "X-Interaction-ID": interactionId
      },
      body: JSON.stringify({ message: "private synthetic message", language: "unsupported" })
    });
    assert.equal(invalid.status, 400);
    assert.equal(invalid.headers.get("X-Error-Code"), "unsupported_language");
  });

  const completed = entries.filter((entry) => entry.event === "http_request");
  assert.equal(completed.length, 2);
  assert.deepEqual(completed.map((entry) => [entry.route, entry.status, entry.errorCode]), [
    ["config", 200, null],
    ["conversation", 400, "unsupported_language"]
  ]);
  assert.deepEqual(completed.map((entry) => entry.requestId), requestIds);
  assert.ok(completed.every((entry) => entry.interactionId === interactionId && entry.durationMs >= 0));
  assert.equal(entries.filter((entry) => entry.event === "request_received").length, 2);
  assert.equal(JSON.stringify(entries).includes("server-only-secret"), false);
  assert.equal(JSON.stringify(entries).includes("private synthetic message"), false);
});

test("health and config endpoints expose active provider/model but never credentials", async () => {
  await withServer({
    env: { AI_PROVIDER: "mock", AI_MODEL: "desk-companion-test", OPENAI_API_KEY: "must-not-leak" }
  }, async (baseUrl) => {
    const health = await fetch(`${baseUrl}/healthz`).then((response) => response.json());
    const configResponse = await fetch(`${baseUrl}/v1/config`);
    const config = await configResponse.json();

    assert.deepEqual(health, { status: "ok", provider: "mock", model: "desk-companion-test" });
    assert.equal(configResponse.status, 200);
    assert.deepEqual(config, {
      provider: "mock",
      model: "desk-companion-test",
      language: "tr-TR",
      maxHistoryMessages: 8
    });
    assert.equal(JSON.stringify(config).includes("must-not-leak"), false);
  });
});

test("configured companion access token gates config and conversation routes without leaking credentials", async () => {
  const accessToken = "c".repeat(48);
  const entries = [];
  let providerCalls = 0;
  const fetchImpl = async () => {
    providerCalls += 1;
    return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: "Yetkili yanıt." }] } }] }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  };

  await withServer({
    env: { AI_PROVIDER: "gemini", GEMINI_API_KEY: "server-provider-key", COMPANION_ACCESS_TOKEN: accessToken },
    fetchImpl,
    requestLogger: (entry) => entries.push(entry)
  }, async (baseUrl) => {
    const configDenied = await fetch(`${baseUrl}/v1/config`);
    assert.equal(configDenied.status, 401);
    assert.equal((await configDenied.json()).error.code, "companion_auth_required");

    const conversationDenied = await fetch(`${baseUrl}/v1/conversations`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: "Bearer wrong-token" },
      body: JSON.stringify({ message: "Yetkisiz istek" })
    });
    assert.equal(conversationDenied.status, 401);
    assert.equal((await conversationDenied.json()).error.code, "companion_auth_required");
    assert.equal(providerCalls, 0);

    const configAllowed = await fetch(`${baseUrl}/v1/config`, {
      headers: { Authorization: `Bearer ${accessToken}` }
    });
    assert.equal(configAllowed.status, 200);

    const conversationAllowed = await fetch(`${baseUrl}/v1/conversations`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${accessToken}` },
      body: JSON.stringify({ message: "Yetkili istek" })
    });
    assert.equal(conversationAllowed.status, 200);
    assert.equal((await conversationAllowed.json()).reply, "Yetkili yanıt.");
    assert.equal(providerCalls, 1);
  });

  const serializedLogs = JSON.stringify(entries);
  assert.equal(serializedLogs.includes(accessToken), false);
  assert.equal(serializedLogs.includes("wrong-token"), false);
  assert.equal(serializedLogs.includes("server-provider-key"), false);
  assert.equal(entries.filter((entry) => entry.event === "companion_auth_rejected").length, 2);
});

test("too-short companion access token disables service configuration", async () => {
  await withServer({
    env: { AI_PROVIDER: "mock", COMPANION_ACCESS_TOKEN: "short" }
  }, async (baseUrl) => {
    const response = await fetch(`${baseUrl}/healthz`);
    assert.equal(response.status, 503);
    assert.equal((await response.json()).error.code, "invalid_companion_access_token");
  });
});

test("Gemini is the default provider and reports missing credentials without secrets", async () => {
  const config = readRuntimeConfig({});
  assert.equal(config.provider, "gemini");
  assert.equal(config.model, "gemini-2.5-flash");
  assert.equal(config.geminiKey, "");
  assert.equal(config.timeoutMs, 40_000);

  await withServer({ env: {} }, async (baseUrl) => {
    const healthResponse = await fetch(`${baseUrl}/healthz`);
    const health = await healthResponse.json();
    const configResponse = await fetch(`${baseUrl}/v1/config`);
    const configError = await configResponse.json();

    assert.equal(healthResponse.status, 503);
    assert.equal(health.status, "not_configured");
    assert.equal(health.error.code, "missing_provider_credentials");
    assert.match(health.error.message, /GEMINI_API_KEY/);
    assert.equal(configResponse.status, 503);
    assert.equal(configError.error.code, "missing_provider_credentials");
    assert.equal(JSON.stringify(health).includes("secret"), false);
  });
});

test("conversation uses server-selected provider/model, Turkish personality and bounded history", async () => {
  let providerRequest;
  const serverFetch = async (url, options) => {
    providerRequest = { url, options, body: JSON.parse(options.body) };
    return new Response(JSON.stringify({ choices: [{ message: { content: "Güzel soru! Ben de merak ettim." } }] }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  };

  await withServer({
    env: {
      AI_PROVIDER: "openai",
      AI_MODEL: "configured-model",
      OPENAI_API_KEY: "server-only-key"
    },
    fetchImpl: serverFetch
  }, async (baseUrl) => {
    const history = Array.from({ length: 10 }, (_, index) => ({
      role: index % 2 === 0 ? "user" : "assistant",
      content: `Önceki mesaj ${index}`
    }));
    const response = await fetch(`${baseUrl}/v1/conversations`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message: "Bugün ne keşfedelim?", history })
    });
    const result = await response.json();

    assert.equal(response.status, 200);
    assert.equal(result.reply, "Güzel soru! Ben de merak ettim.");
    assert.equal(result.provider, "openai");
    assert.equal(result.model, "configured-model");
    assert.equal(providerRequest.options.headers.Authorization, "Bearer server-only-key");
    assert.equal(providerRequest.body.model, "configured-model");
    assert.match(providerRequest.body.messages[0].content, /Türkçe yanıt ver/);
    assert.equal(providerRequest.body.messages.length, 10);
    assert.equal(providerRequest.body.messages.at(-1).content, "Bugün ne keşfedelim?");
    assert.equal(JSON.stringify(result).includes("server-only-key"), false);
  });
});

test("Gemini selection uses the configured model and server-only credential", async () => {
  let providerRequest;
  const serverFetch = async (url, options) => {
    providerRequest = { url, options, body: JSON.parse(options.body) };
    return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: "Merhaba!" }] } }] }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  };

  await withServer({
    env: { AI_PROVIDER: "gemini", GEMINI_API_KEY: "server-only-gemini-key" },
    fetchImpl: serverFetch
  }, async (baseUrl) => {
    const response = await fetch(`${baseUrl}/v1/conversations`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        message: "Nasılsın?",
        history: [{ role: "assistant", content: "Selam!" }]
      })
    });
    const result = await response.json();

    assert.equal(response.status, 200);
    assert.equal(result.reply, "Merhaba!");
    assert.equal(result.provider, "gemini");
    assert.equal(result.model, "gemini-2.5-flash");
    assert.match(result.interactionId, /^[0-9a-f-]{36}$/);
    assert.equal(providerRequest.url, "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent");
    assert.equal(providerRequest.options.headers["x-goog-api-key"], "server-only-gemini-key");
    assert.match(providerRequest.body.systemInstruction.parts[0].text, /Türkçe yanıt ver/);
    assert.deepEqual(providerRequest.body.contents.map(({ role }) => role), ["model", "user"]);
    assert.equal(JSON.stringify(result).includes("server-only-gemini-key"), false);
  });
});

test("three distinct conversation turns become three correlated Gemini requests and matching replies", async () => {
  const inputs = [
    "Türkiye'nin başkenti neresidir?",
    "Bana bir uzay hikayesi anlat.",
    "En sevdiğin yemek nedir?"
  ];
  const expectedReplies = [
    "Türkiye'nin başkenti Ankara'dır.",
    "Küçük bir uzay gemisi Satürn'ün halkalarında kayıp bir yıldız buldu.",
    "Yemek yiyemem ama menemen kulağa güzel geliyor."
  ];
  const responseByMessage = new Map(inputs.map((input, index) => [input, expectedReplies[index]]));
  const providerCalls = [];
  const logEntries = [];
  const fetchGemini = async (url, options) => {
    const body = JSON.parse(options.body);
    const message = body.contents.at(-1).parts[0].text;
    providerCalls.push({ url, message, apiKey: options.headers["x-goog-api-key"] });
    const reply = responseByMessage.get(message);
    assert.ok(reply, `unexpected Gemini input: ${message}`);
    return new Response(JSON.stringify({
      candidates: [{ content: { parts: [{ text: reply }] } }]
    }), { status: 200, headers: { "Content-Type": "application/json" } });
  };

  await withServer({
    env: { AI_PROVIDER: "gemini", AI_MODEL: "gemini-2.5-flash", GEMINI_API_KEY: "synthetic-gemini-test-key" },
    fetchImpl: fetchGemini,
    requestLogger: (entry) => logEntries.push(entry)
  }, async (baseUrl) => {
    const results = [];
    for (let index = 0; index < inputs.length; index += 1) {
      const interactionId = `b000000${index}-0000-4000-8000-00000000000${index}`;
      const requestId = `a000000${index}-0000-4000-8000-00000000000${index}`;
      const message = inputs[index];
      const response = await fetch(`${baseUrl}/v1/conversations`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Request-ID": requestId,
          "X-Interaction-ID": interactionId,
          "X-Message-Fingerprint": conversationFingerprint(interactionId, message)
        },
        body: JSON.stringify({ message, language: "tr-TR", history: [] })
      });
      const result = await response.json();
      assert.equal(response.status, 200);
      assert.equal(response.headers.get("Cache-Control"), "no-store");
      assert.equal(response.headers.get("X-Request-ID"), requestId);
      assert.equal(response.headers.get("X-Interaction-ID"), interactionId);
      assert.equal(result.interactionId, interactionId);
      results.push(result.reply);
    }

    assert.deepEqual(results, expectedReplies);
  });

  assert.equal(providerCalls.length, 3);
  assert.deepEqual(providerCalls.map((call) => call.message), inputs);
  assert.equal(new Set(providerCalls.map((call) => call.message)).size, 3);
  assert.ok(providerCalls.every((call) => call.url.endsWith("/gemini-2.5-flash:generateContent")));
  assert.ok(providerCalls.every((call) => call.apiKey === "synthetic-gemini-test-key"));

  const geminiStarts = logEntries.filter((entry) => entry.event === "gemini_request_started");
  const geminiReplies = logEntries.filter((entry) => entry.event === "gemini_response_received");
  const receivedMessages = logEntries.filter((entry) => entry.event === "conversation_message_received");
  assert.equal(geminiStarts.length, 3);
  assert.equal(geminiReplies.length, 3);
  assert.equal(receivedMessages.length, 3);
  assert.deepEqual(receivedMessages.map((entry) => entry.clientFingerprintMatches), [true, true, true]);
  assert.equal(new Set(geminiStarts.map((entry) => entry.requestId)).size, 3);
  assert.deepEqual(geminiStarts.map((entry) => entry.interactionId), [
    "b0000000-0000-4000-8000-000000000000",
    "b0000001-0000-4000-8000-000000000001",
    "b0000002-0000-4000-8000-000000000002"
  ]);
  const serializedLogs = JSON.stringify(logEntries);
  assert.ok(inputs.every((input) => !serializedLogs.includes(input)));
  assert.equal(serializedLogs.includes("synthetic-gemini-test-key"), false);
});

test("backend rejects a conversation body that does not match its transcript fingerprint", async () => {
  const interactionId = "b1000000-0000-4000-8000-000000000000";
  await withServer({ env: { AI_PROVIDER: "mock" } }, async (baseUrl) => {
    const response = await fetch(`${baseUrl}/v1/conversations`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Interaction-ID": interactionId,
        "X-Message-Fingerprint": conversationFingerprint(interactionId, "previous phrase")
      },
      body: JSON.stringify({ message: "current phrase", language: "tr-TR" })
    });
    const result = await response.json();
    assert.equal(response.status, 400);
    assert.equal(result.error.code, "message_fingerprint_mismatch");
  });
});

test("invalid request, missing key and provider failures return safe errors", async () => {
  await withServer({ env: { AI_PROVIDER: "openai", AI_MODEL: "model" } }, async (baseUrl) => {
    const configResponse = await fetch(`${baseUrl}/v1/config`);
    const configError = await configResponse.json();
    assert.equal(configResponse.status, 503);
    assert.equal(configError.error.code, "missing_provider_credentials");
    assert.equal(JSON.stringify(configError).includes("server-key"), false);

    const invalidResponse = await fetch(`${baseUrl}/v1/conversations`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message: "   ", language: "tr-TR" })
    });
    const invalid = await invalidResponse.json();
    assert.equal(invalidResponse.status, 400);
    assert.equal(invalid.error.code, "invalid_message");
  });

  await withServer({
    env: { AI_PROVIDER: "anthropic", AI_MODEL: "missing-model", ANTHROPIC_API_KEY: "server-key" },
    fetchImpl: async () => new Response("provider detail with secret=server-key", { status: 404 })
  }, async (baseUrl) => {
    const response = await fetch(`${baseUrl}/v1/conversations`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message: "Merhaba" })
    });
    const result = await response.json();
    assert.equal(response.status, 503);
    assert.equal(result.error.code, "unsupported_model");
    assert.equal(JSON.stringify(result).includes("server-key"), false);
    assert.equal(JSON.stringify(result).includes("provider detail"), false);
  });
});

test("request validation rejects unsupported languages and oversized messages", async () => {
  await withServer({ env: { AI_PROVIDER: "mock" } }, async (baseUrl) => {
    const unsupported = await fetch(`${baseUrl}/v1/conversations`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message: "Hello", language: "fr-FR" })
    });
    assert.equal(unsupported.status, 400);
    assert.equal((await unsupported.json()).error.code, "unsupported_language");

    const tooLong = await fetch(`${baseUrl}/v1/conversations`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message: "x".repeat(1_801) })
    });
    assert.equal(tooLong.status, 400);
    assert.equal((await tooLong.json()).error.code, "invalid_message");
  });
});

test("rate limit rejects requests after configured per-minute budget", async () => {
  await withServer({ env: { AI_PROVIDER: "mock", API_RATE_LIMIT_PER_MINUTE: "1" } }, async (baseUrl) => {
    const first = await fetch(`${baseUrl}/v1/config`);
    const second = await fetch(`${baseUrl}/v1/config`);
    assert.equal(first.status, 200);
    assert.equal(second.status, 429);
    assert.equal((await second.json()).error.code, "rate_limited");
  });
});
