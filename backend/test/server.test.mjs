import test from "node:test";
import assert from "node:assert/strict";
import { createConversationServer } from "../src/server.mjs";

async function withServer(options, run) {
  const server = createConversationServer(options);
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

test("health and config endpoints expose active provider/model but never credentials", async () => {
  await withServer({
    env: { AI_PROVIDER: "mock", AI_MODEL: "desk-companion-test", OPENAI_API_KEY: "must-not-leak" }
  }, async (baseUrl) => {
    const health = await fetch(`${baseUrl}/healthz`).then((response) => response.json());
    const configResponse = await fetch(`${baseUrl}/v1/config`);
    const config = await configResponse.json();

    assert.equal(health.status, "ok");
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
