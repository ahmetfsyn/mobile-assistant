import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { MemoryRepository } from "../src/memory-repository.mjs";
import { MemoryService } from "../src/memory-service.mjs";
import { createConversationServer } from "../src/server.mjs";

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

const auth = (token) => ({ Authorization: `Bearer ${token}` });
const jsonHeaders = (token) => ({ "Content-Type": "application/json", ...auth(token) });

test("SQLite migrations preserve records across repository restarts and upsert instead of duplicating", (t) => {
  const directory = mkdtempSync(join(tmpdir(), "companion-memory-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const databasePath = join(directory, "memory.sqlite");
  const sourceRef = randomUUID();
  let repository = new MemoryRepository(databasePath);
  const first = repository.upsert({
    category: "preference", key: "kahve_tercihi", value: "şekersiz", confidence: 0.98, importance: 4, sourceRef
  });
  repository.close();

  repository = new MemoryRepository(databasePath);
  const updated = repository.upsert({
    category: "preference", key: "kahve_tercihi", value: "şekerli", confidence: 0.98, importance: 4, sourceRef
  });
  assert.equal(updated.id, first.id);
  assert.equal(updated.value, "şekerli");
  assert.equal(repository.countActive(), 1);
  repository.close();
});

test("Turkish extraction saves clear statements, updates contradictions, and ignores questions or hypotheticals", () => {
  const repository = new MemoryRepository(":memory:");
  const service = new MemoryService(repository);
  const interactionId = randomUUID();
  const first = service.processTurn({
    message: "Ben kahveyi şekersiz içerim.", enabled: true, interactionId
  });
  assert.equal(first.savedCount, 1);
  assert.equal(repository.listActive()[0].value, "şekersiz");

  const update = service.processTurn({
    message: "Artık kahvemi şekerli içiyorum.", enabled: true, interactionId: randomUUID()
  });
  assert.equal(update.savedCount, 1);
  assert.equal(repository.countActive(), 1);
  assert.equal(repository.listActive()[0].value, "şekerli");

  const hypothetical = service.processTurn({
    message: "Acaba kahveyi şekersiz mi içsem?", enabled: true, interactionId: randomUUID()
  });
  assert.equal(hypothetical.savedCount, 0);
  assert.equal(repository.listActive()[0].value, "şekerli");
  repository.close();
});

test("explicit remember stores safe facts, rejects sensitive content, and list commands respect consent", () => {
  const repository = new MemoryRepository(":memory:");
  const service = new MemoryService(repository);
  const interactionId = randomUUID();
  const saved = service.processTurn({
    message: "Bunu hatırla: Pazartesi günleri piyano dersine gidiyorum.", enabled: true, interactionId
  });
  assert.match(saved.directReply, /kaydet/i);
  assert.equal(repository.countActive(), 1);

  const sensitive = service.processTurn({
    message: "Bunu hatırla: Şifrem kod-1234.", enabled: true, interactionId: randomUUID()
  });
  assert.match(sensitive.directReply, /sınıflandıramadım/i);
  assert.equal(repository.countActive(), 1);

  const listWhenDisabled = service.processTurn({
    message: "Benim hakkımda neler hatırlıyorsun?", enabled: false, interactionId: randomUUID()
  });
  assert.match(listWhenDisabled.directReply, /kapalı/i);
  assert.equal(listWhenDisabled.directReply.includes("piyano"), false);
  repository.close();
});

test("retrieval is relevance-ranked and bounded; deletion clears personal content", () => {
  const repository = new MemoryRepository(":memory:");
  const service = new MemoryService(repository);
  for (const [key, value] of [
    ["kahve_tercihi", "şekersiz"],
    ["en_sevdiğim_oyun", "satranç"],
    ["hobi", "piyano"]
  ]) {
    repository.upsert({ category: key === "hobi" ? "profile" : "preference", key, value, confidence: 0.96, importance: 3 });
  }
  const matches = repository.findRelevant("Bir kahve içesim var", 2);
  assert.equal(matches.length, 1);
  assert.equal(matches[0].memory.value, "şekersiz");

  const id = matches[0].memory.id;
  assert.equal(repository.delete(id), true);
  assert.equal(repository.get(id), null);
  const deletedRow = repository.database.prepare("SELECT status, memory_key, value, source_ref FROM memories WHERE id = ?").get(id);
  assert.deepEqual(deletedRow, { status: "deleted", memory_key: "", value: "", source_ref: null });
  assert.equal(repository.countActive(), 2);

  repository.upsert({ category: "preference", key: "hassas_konu", value: "düşük güvenli", confidence: 0.4, importance: 5 });
  assert.equal(repository.findRelevant("hassas konu", 5).length, 0);
  repository.close();
});

test("memory API requires auth and consent to create, validates input, supports edit/delete/clear", async () => {
  const token = "local-memory-token-0123456789abcdef";
  await withServer({ env: { AI_PROVIDER: "mock", COMPANION_ACCESS_TOKEN: token } }, async (baseUrl) => {
    const unauthorized = await fetch(`${baseUrl}/v1/memories/status`);
    assert.equal(unauthorized.status, 401);
    assert.equal((await unauthorized.json()).error.code, "companion_auth_required");

    const noConsent = await fetch(`${baseUrl}/v1/memories`, {
      method: "POST",
      headers: jsonHeaders(token),
      body: JSON.stringify({ category: "preference", key: "kahve_tercihi", value: "şekersiz" })
    });
    assert.equal(noConsent.status, 403);
    assert.equal((await noConsent.json()).error.code, "memory_consent_required");

    const invalid = await fetch(`${baseUrl}/v1/memories`, {
      method: "POST",
      headers: jsonHeaders(token),
      body: JSON.stringify({ memoryEnabled: true, category: "unknown", key: "x", value: "y" })
    });
    assert.equal(invalid.status, 400);

    const created = await fetch(`${baseUrl}/v1/memories`, {
      method: "POST",
      headers: jsonHeaders(token),
      body: JSON.stringify({ memoryEnabled: true, category: "preference", key: "kahve_tercihi", value: "şekersiz" })
    });
    assert.equal(created.status, 201);
    const { memory } = await created.json();
    assert.equal(memory.status, "active");
    assert.ok(memory.createdAt);
    assert.equal(memory.lastAccessedAt, null);

    const duplicate = await fetch(`${baseUrl}/v1/memories`, {
      method: "POST",
      headers: jsonHeaders(token),
      body: JSON.stringify({ memoryEnabled: true, category: "preference", key: "kahve_tercihi", value: "şekerli" })
    });
    assert.equal(duplicate.status, 409);
    assert.equal((await duplicate.json()).error.code, "memory_conflict");

    const updated = await fetch(`${baseUrl}/v1/memories/${memory.id}`, {
      method: "PATCH",
      headers: jsonHeaders(token),
      body: JSON.stringify({ value: "sütlü" })
    });
    assert.equal(updated.status, 200);
    assert.equal((await updated.json()).memory.value, "sütlü");

    const status = await fetch(`${baseUrl}/v1/memories/status`, { headers: auth(token) }).then((response) => response.json());
    assert.deepEqual(status, { available: true, activeCount: 1 });

    const deleted = await fetch(`${baseUrl}/v1/memories/${memory.id}`, { method: "DELETE", headers: auth(token) });
    assert.equal(deleted.status, 200);
    assert.deepEqual(await deleted.json(), { deleted: true });
    assert.equal((await fetch(`${baseUrl}/v1/memories/status`, { headers: auth(token) }).then((response) => response.json())).activeCount, 0);

    for (const key of ["tercih_a", "tercih_b"]) {
      const response = await fetch(`${baseUrl}/v1/memories`, {
        method: "POST",
        headers: jsonHeaders(token),
        body: JSON.stringify({ memoryEnabled: true, category: "fact", key, value: "test fact" })
      });
      assert.equal(response.status, 201);
    }
    const cleared = await fetch(`${baseUrl}/v1/memories`, { method: "DELETE", headers: auth(token) });
    assert.deepEqual(await cleared.json(), { deletedCount: 2 });
    assert.equal((await fetch(`${baseUrl}/v1/memories/status`, { headers: auth(token) }).then((response) => response.json())).activeCount, 0);
  });
});

test("conversation memory is opt-in, relevant-only, and never logged", async () => {
  const token = "local-memory-token-0123456789abcdef";
  const entries = [];
  const providerBodies = [];
  const fetchImpl = async (_url, options) => {
    providerBodies.push(JSON.parse(options.body));
    return new Response(JSON.stringify({ choices: [{ message: { content: "Kahveni şekersiz tercih ettiğini not ettim." } }] }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  };

  await withServer({
    env: { AI_PROVIDER: "openai", AI_MODEL: "test-model", OPENAI_API_KEY: "provider-secret", COMPANION_ACCESS_TOKEN: token },
    fetchImpl,
    requestLogger: (entry) => entries.push(entry)
  }, async (baseUrl) => {
    const create = await fetch(`${baseUrl}/v1/memories`, {
      method: "POST",
      headers: jsonHeaders(token),
      body: JSON.stringify({ memoryEnabled: true, category: "preference", key: "kahve_tercihi", value: "şekersiz", confidence: 0.98 })
    });
    assert.equal(create.status, 201);

    const ask = (memoryEnabled) => fetch(`${baseUrl}/v1/conversations`, {
      method: "POST",
      headers: { ...jsonHeaders(token), "X-Interaction-ID": randomUUID() },
      body: JSON.stringify({ message: "Bir kahve içesim var.", language: "tr-TR", history: [], memoryEnabled })
    });
    assert.equal((await ask(false)).status, 200);
    const disabledPrompt = providerBodies[0].messages[0].content;
    assert.equal(disabledPrompt.includes("şekersiz"), false);
    assert.match(disabledPrompt, /No relevant stored user memories/);

    assert.equal((await ask(true)).status, 200);
    const enabledPrompt = providerBodies[1].messages[0].content;
    assert.match(enabledPrompt, /şekersiz/);
    assert.equal(JSON.stringify(entries).includes("şekersiz"), false);
    assert.equal(JSON.stringify(entries).includes("provider-secret"), false);
  });
});

test("conversation remains available when the memory database cannot open", async (t) => {
  const directory = mkdtempSync(join(tmpdir(), "companion-memory-blocked-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const blocker = join(directory, "not-a-directory");
  writeFileSync(blocker, "local test fixture");
  let providerCalls = 0;

  await withServer({
    memoryDatabasePath: join(blocker, "memory.sqlite"),
    env: { AI_PROVIDER: "openai", AI_MODEL: "test-model", OPENAI_API_KEY: "test-provider-key" },
    fetchImpl: async () => {
      providerCalls += 1;
      return new Response(JSON.stringify({ choices: [{ message: { content: "Yedek yanıt." } }] }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }
  }, async (baseUrl) => {
    const memoryStatus = await fetch(`${baseUrl}/v1/memories/status`);
    assert.equal(memoryStatus.status, 503);
    const response = await fetch(`${baseUrl}/v1/conversations`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message: "Merhaba.", language: "tr-TR", history: [], memoryEnabled: true })
    });
    assert.equal(response.status, 200);
    assert.equal((await response.json()).reply, "Yedek yanıt.");
    assert.equal(providerCalls, 1);
  });
});
