import test from "node:test";
import assert from "node:assert/strict";
import { buildSystemPrompt, PERSONALITY_PROFILE } from "../src/personality.mjs";

test("personality profile is explicitly versioned", () => {
  assert.deepEqual(PERSONALITY_PROFILE, {
    id: "masa-arkadasi",
    version: 1,
    defaultLanguage: "tr-TR",
    traits: ["sıcak", "meraklı", "oyuncu", "dürüst"]
  });
});

test("Turkish is the default language and real feelings are not claimed", () => {
  const prompt = buildSystemPrompt();
  assert.match(prompt, /Masa Arkadaşı/);
  assert.match(prompt, /Türkçe yanıt ver/);
  assert.match(prompt, /gerçek duyguların/);
});

test("English personality and language rules remain optional", () => {
  const prompt = buildSystemPrompt("en-US");
  assert.match(prompt, /Reply in English/);
  assert.match(prompt, /real human-like feelings/);
  assert.match(prompt, /5–25 spoken words/);
  assert.match(prompt, /explicitly asks for it/);
});

test("Turkish default stays concise unless the user explicitly requests detail", () => {
  const prompt = buildSystemPrompt();
  assert.match(prompt, /5–25 konuşma sözcüğü/);
  assert.match(prompt, /Kullanıcı açıkça ayrıntı isterse daha uzun açıkla/);
});

test("configured style cannot remove fixed language and truthful capability boundaries", () => {
  const prompt = buildSystemPrompt("tr-TR", "Use an energetic, playful voice.");
  assert.ok(prompt.startsWith("Use an energetic, playful voice."));
  assert.match(prompt, /Türkçe yanıt ver/);
  assert.match(prompt, /öznel deneyimlerin/);
  assert.match(prompt, /sahip olmadığın fiziksel yetenekleri/);
});
