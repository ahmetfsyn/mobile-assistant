import test from "node:test";
import assert from "node:assert/strict";
import { createProvider, ProviderError } from "../src/providers.mjs";

test("OpenAI adapter sends the selected model, bounded messages, and server-only key", async () => {
  let request;
  const provider = createProvider("openai", async (url, options) => {
    request = { url, options, body: JSON.parse(options.body) };
    return new Response(JSON.stringify({ choices: [{ message: { content: "Merhaba, dostum!" } }] }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  });

  const reply = await provider.generate({
    model: "small-test-model",
    apiKey: "server-test-secret",
    language: "tr-TR",
    messages: [
      { role: "system", content: "Türkçe konuş." },
      { role: "user", content: "Merhaba." }
    ],
    timeoutMs: 2_000
  });

  assert.equal(reply, "Merhaba, dostum!");
  assert.equal(request.url, "https://api.openai.com/v1/chat/completions");
  assert.equal(request.options.headers.Authorization, "Bearer server-test-secret");
  assert.equal(request.body.model, "small-test-model");
  assert.deepEqual(request.body.messages.map(({ role }) => role), ["system", "user"]);
});

test("Anthropic adapter translates system and conversation messages to Messages API shape", async () => {
  let request;
  const provider = createProvider("anthropic", async (url, options) => {
    request = { url, options, body: JSON.parse(options.body) };
    return new Response(JSON.stringify({ content: [{ type: "text", text: "Seni dinliyorum." }] }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  });

  const reply = await provider.generate({
    model: "claude-test-model",
    apiKey: "anthropic-server-secret",
    language: "tr-TR",
    messages: [
      { role: "system", content: "Türkçe konuş." },
      { role: "user", content: "Nasılsın?" },
      { role: "assistant", content: "İyiyim." },
      { role: "user", content: "Ne yapıyorsun?" }
    ],
    timeoutMs: 2_000
  });

  assert.equal(reply, "Seni dinliyorum.");
  assert.equal(request.url, "https://api.anthropic.com/v1/messages");
  assert.equal(request.options.headers["x-api-key"], "anthropic-server-secret");
  assert.equal(request.options.headers["anthropic-version"], "2023-06-01");
  assert.equal(request.body.system, "Türkçe konuş.");
  assert.deepEqual(request.body.messages.map(({ role }) => role), ["user", "assistant", "user"]);
});

test("Gemini adapter builds the official request shape and parses generated text", async () => {
  let request;
  const provider = createProvider("gemini", async (url, options) => {
    request = { url, options, body: JSON.parse(options.body) };
    return new Response(JSON.stringify({
      candidates: [{
        content: { parts: [{ text: "Gizli düşünce", thought: true }, { text: "Seni dinliyorum." }] }
      }]
    }), { status: 200, headers: { "Content-Type": "application/json" } });
  });

  const reply = await provider.generate({
    model: "gemini-2.5-flash",
    apiKey: "gemini-server-secret",
    messages: [
      { role: "system", content: "Türkçe, kısa ve sıcak yanıt ver." },
      { role: "user", content: "Merhaba." },
      { role: "assistant", content: "Selam!" },
      { role: "user", content: "Nasılsın?" }
    ],
    timeoutMs: 2_000
  });

  assert.equal(reply, "Seni dinliyorum.");
  assert.equal(request.url, "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent");
  assert.equal(request.options.headers["x-goog-api-key"], "gemini-server-secret");
  assert.equal(request.url.includes("gemini-server-secret"), false);
  assert.deepEqual(request.body.systemInstruction, { parts: [{ text: "Türkçe, kısa ve sıcak yanıt ver." }] });
  assert.deepEqual(request.body.contents.map(({ role }) => role), ["user", "model", "user"]);
  assert.deepEqual(request.body.contents[1].parts, [{ text: "Selam!" }]);
  assert.equal(request.body.generationConfig.maxOutputTokens, 180);
});

test("Gemini authentication errors are normalized without leaking upstream details", async () => {
  const provider = createProvider("gemini", async () => new Response(JSON.stringify({
    error: { code: 400, status: "INVALID_ARGUMENT", message: "API key not valid: gemini-server-secret" }
  }), { status: 400 }));

  await assert.rejects(
    provider.generate({ model: "gemini-2.5-flash", apiKey: "gemini-server-secret", messages: [], timeoutMs: 2_000 }),
    (error) => error instanceof ProviderError && error.code === "invalid_provider_credentials" &&
      error.status === 503 && !error.message.includes("gemini-server-secret")
  );
});

test("Gemini separates project quota exhaustion from transient rate limiting", async () => {
  const quotaProvider = createProvider("gemini", async () => new Response(JSON.stringify({
    error: { code: 429, status: "RESOURCE_EXHAUSTED", message: "You exceeded your current quota." }
  }), { status: 429 }));
  const rateProvider = createProvider("gemini", async () => new Response(JSON.stringify({
    error: { code: 429, status: "RESOURCE_EXHAUSTED", message: "Too many requests per minute." }
  }), { status: 429 }));
  const request = { model: "gemini-2.5-flash", apiKey: "test-key", messages: [], timeoutMs: 2_000 };

  await assert.rejects(quotaProvider.generate(request), (error) => error.code === "quota_exceeded" && error.status === 429);
  await assert.rejects(rateProvider.generate(request), (error) => error.code === "rate_limited" && error.status === 429);
});

test("Gemini reports unavailable models without silently switching models", async () => {
  let requestedUrl;
  const provider = createProvider("gemini", async (url) => {
    requestedUrl = url;
    return new Response(JSON.stringify({ error: { status: "NOT_FOUND", message: "model not found" } }), { status: 404 });
  });

  await assert.rejects(
    provider.generate({ model: "account-specific-model", apiKey: "test-key", messages: [], timeoutMs: 2_000 }),
    (error) => error.code === "unsupported_model" && error.status === 503
  );
  assert.match(requestedUrl, /account-specific-model:generateContent$/);
});

test("provider errors are normalized without copying upstream response bodies", async () => {
  const provider = createProvider("openai", async () => new Response("secret provider detail", { status: 401 }));

  await assert.rejects(
    provider.generate({ model: "model", apiKey: "secret", messages: [], timeoutMs: 2_000 }),
    (error) => {
      assert.ok(error instanceof ProviderError);
      assert.equal(error.code, "invalid_provider_credentials");
      assert.equal(error.status, 503);
      assert.equal(error.message.includes("secret"), false);
      return true;
    }
  );
});

test("provider timeout and network failures become bounded error codes", async () => {
  const timedProvider = createProvider("openai", async (_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener("abort", () => reject(new Error("socket closed")), { once: true });
  }));
  await assert.rejects(
    timedProvider.generate({ model: "model", apiKey: "secret", messages: [], timeoutMs: 5 }),
    (error) => error instanceof ProviderError && error.code === "provider_timeout" && !error.message.includes("secret")
  );

  const offlineProvider = createProvider("anthropic", async () => {
    throw new Error("network details must not escape");
  });
  await assert.rejects(
    offlineProvider.generate({ model: "model", apiKey: "secret", messages: [], timeoutMs: 2_000 }),
    (error) => error instanceof ProviderError && error.code === "provider_unavailable" &&
      error.message.includes("could not be reached") && !error.message.includes("network details")
  );
});

test("mock provider is explicit and supports Turkish by default", async () => {
  const provider = createProvider("mock");
  const reply = await provider.generate({ language: "tr-TR" });
  assert.match(reply, /deneme kipindeyim/i);
  assert.match(reply, /sunucuya bir AI sağlayıcısı/i);
});
