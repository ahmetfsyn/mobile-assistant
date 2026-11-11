import Database from "better-sqlite3";
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { randomUUID } from "node:crypto";

const MAX_ACTIVE_MEMORIES = 1_000;
const MAX_SEARCH_CANDIDATES = 500;

export class MemoryNotFoundError extends Error {
  constructor() {
    super("The requested memory does not exist.");
    this.name = "MemoryNotFoundError";
  }
}

export class MemoryConflictError extends Error {
  constructor() {
    super("An active memory already exists for this category and key.");
    this.name = "MemoryConflictError";
  }
}

function normalizeKey(value) {
  return value.trim().toLocaleLowerCase("tr-TR").normalize("NFKC").replace(/\s+/gu, " ");
}

function rowToMemory(row) {
  if (!row) return null;
  return {
    id: row.id,
    category: row.category,
    key: row.memory_key,
    value: row.value,
    confidence: row.confidence,
    importance: row.importance,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    lastAccessedAt: row.last_accessed_at,
    sourceRef: row.source_ref,
    status: row.status
  };
}

function reminderFromRow(row) {
  if (!row) return null;
  return {
    id: row.id,
    message: row.message,
    scheduledAt: row.scheduled_at,
    timeZone: row.time_zone,
    recurrence: row.recurrence,
    enabled: row.enabled === 1,
    status: row.status,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    lastDeliveredAt: row.last_delivered_at,
    nextScheduledAt: row.next_scheduled_at,
    deliveryAttempts: row.delivery_attempts
  };
}

const STOP_WORDS = new Set([
  "acaba", "ama", "ben", "bana", "bunu", "da", "de", "diye", "en", "gibi", "için", "ile", "mi", "mı",
  "mu", "mü", "ne", "neler", "niye", "o", "şu", "ve", "ya", "yani"
]);

function normalizeToken(token) {
  let normalized = token.toLocaleLowerCase("tr-TR").normalize("NFKC");
  if (normalized.length > 5) {
    for (const suffix of ["larımız", "lerimiz", "larımızı", "lerimizi", "larından", "lerinden", "larını", "lerini", "larımız", "lerimiz", "ları", "leri", "ının", "inin", "unun", "ünün", "ımız", "imiz", "umuz", "ümüz", "ları", "leri", "dan", "den", "tan", "ten", "dır", "dir", "dur", "dür", "lar", "ler", "ım", "im", "um", "üm", "yi", "yı", "yu", "yü", "in", "ın", "un", "ün", "i", "ı", "u", "ü"]) {
      if (normalized.endsWith(suffix) && normalized.length - suffix.length >= 4) {
        normalized = normalized.slice(0, -suffix.length);
        break;
      }
    }
  }
  return normalized;
}

function searchTerms(value) {
  return [...new Set(String(value).match(/[\p{L}\p{N}]{3,}/gu) ?? [])]
    .map(normalizeToken)
    .filter((term) => term.length >= 3 && !STOP_WORDS.has(term));
}

function scoreMemory(memory, terms) {
  if (terms.length === 0) return 0;
  const text = `${memory.category} ${memory.key.replace(/[._-]/gu, " ")} ${memory.value}`;
  const memoryTerms = new Set(searchTerms(text));
  const matched = terms.filter((term) => memoryTerms.has(term));
  if (matched.length === 0) return 0;
  return matched.length * 3 + (matched.length === terms.length ? 2 : 0);
}

export class MemoryRepository {
  constructor(databasePath = "./data/memories.sqlite") {
    if (databasePath !== ":memory:") {
      mkdirSync(dirname(resolve(databasePath)), { recursive: true });
    }
    this.database = new Database(databasePath);
    this.database.pragma("foreign_keys = ON");
    this.database.pragma("busy_timeout = 5000");
    if (databasePath !== ":memory:") {
      this.database.pragma("journal_mode = WAL");
      this.database.pragma("synchronous = NORMAL");
    }
    this.migrate();
    this.statements = this.prepareStatements();
  }

  migrate() {
    this.database.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
    const hasMigration = (version) => Boolean(
      this.database.prepare("SELECT 1 FROM schema_migrations WHERE version = ?").get(version)
    );

    if (!hasMigration(1)) {
      const migration = this.database.transaction(() => {
        this.database.exec(`
        CREATE TABLE memories (
          id TEXT PRIMARY KEY,
          category TEXT NOT NULL CHECK (category IN ('profile', 'preference', 'fact', 'routine', 'summary')),
          memory_key TEXT NOT NULL,
          normalized_key TEXT NOT NULL,
          value TEXT NOT NULL,
          confidence REAL NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
          importance INTEGER NOT NULL CHECK (importance >= 1 AND importance <= 5),
          created_at TEXT NOT NULL,
          updated_at TEXT NOT NULL,
          last_accessed_at TEXT,
          status TEXT NOT NULL CHECK (status IN ('active', 'deleted')),
          source_ref TEXT
        );
        CREATE UNIQUE INDEX memories_active_key_unique
          ON memories(category, normalized_key) WHERE status = 'active';
        CREATE INDEX memories_active_updated
          ON memories(status, updated_at DESC);
        `);
        this.database.prepare("INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)")
          .run(1, new Date().toISOString());
      });
      migration();
    }

    if (!hasMigration(2)) {
      const migration = this.database.transaction(() => {
        this.database.exec(`
          CREATE TABLE reminders (
            id TEXT PRIMARY KEY,
            message TEXT NOT NULL CHECK (length(message) BETWEEN 1 AND 240),
            scheduled_at TEXT NOT NULL,
            time_zone TEXT NOT NULL,
            recurrence TEXT NOT NULL CHECK (recurrence IN ('none', 'daily', 'weekdays', 'weekly:MO', 'weekly:TU', 'weekly:WE', 'weekly:TH', 'weekly:FR', 'weekly:SA', 'weekly:SU')),
            enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
            status TEXT NOT NULL CHECK (status IN ('scheduled', 'paused', 'completed', 'deleted')),
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            last_delivered_at TEXT,
            next_scheduled_at TEXT NOT NULL,
            delivery_attempts INTEGER NOT NULL DEFAULT 0 CHECK (delivery_attempts BETWEEN 0 AND 3)
          );
          CREATE INDEX reminders_due ON reminders(status, enabled, next_scheduled_at);
          CREATE TABLE companion_settings (
            singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
            settings_json TEXT NOT NULL,
            updated_at TEXT NOT NULL
          );
        `);
        this.database.prepare("INSERT INTO companion_settings(singleton_id, settings_json, updated_at) VALUES (1, ?, ?)")
          .run(JSON.stringify({ autonomyEnabled: false, proactiveSpeechEnabled: false, quietHoursEnabled: true, quietHoursStart: "23:00", quietHoursEnd: "08:00" }), new Date().toISOString());
        this.database.prepare("INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)")
          .run(2, new Date().toISOString());
      });
      migration();
    }
  }

  createReminder(input) {
    if (input.id) {
      const existing = this.database.prepare("SELECT * FROM reminders WHERE id = ?").get(input.id);
      if (existing) return reminderFromRow(existing);
    }
    const now = new Date().toISOString();
    const reminder = {
      id: input.id ?? randomUUID(),
      message: input.message,
      scheduledAt: input.scheduledAt,
      timeZone: input.timeZone,
      recurrence: input.recurrence ?? "none",
      enabled: input.status ? input.status === "scheduled" : input.enabled !== false,
      status: input.status ?? (input.enabled === false ? "paused" : "scheduled"),
      createdAt: now,
      updatedAt: now,
      lastDeliveredAt: input.lastDeliveredAt ?? null,
      nextScheduledAt: input.nextScheduledAt ?? input.scheduledAt,
      deliveryAttempts: 0
    };
    this.database.prepare(`INSERT INTO reminders(id, message, scheduled_at, time_zone, recurrence, enabled, status,
      created_at, updated_at, last_delivered_at, next_scheduled_at, delivery_attempts)
      VALUES (@id, @message, @scheduledAt, @timeZone, @recurrence, @enabled, @status, @createdAt, @updatedAt,
      @lastDeliveredAt, @nextScheduledAt, @deliveryAttempts)`).run({ ...reminder, enabled: reminder.enabled ? 1 : 0 });
    return reminder;
  }

  listReminders({ includeDeleted = false } = {}) {
    const rows = this.database.prepare(`SELECT * FROM reminders ${includeDeleted ? "" : "WHERE status != 'deleted'"}
      ORDER BY next_scheduled_at ASC LIMIT 100`).all();
    return rows.map(reminderFromRow);
  }

  getReminder(id) {
    return reminderFromRow(this.database.prepare("SELECT * FROM reminders WHERE id = ? AND status != 'deleted'").get(id));
  }

  updateReminder(id, changes) {
    const existing = this.getReminder(id);
    if (!existing) return null;
    const updated = { ...existing, ...changes, updatedAt: new Date().toISOString() };
    if (changes.scheduledAt !== undefined && changes.nextScheduledAt === undefined) {
      updated.nextScheduledAt = changes.scheduledAt;
    }
    if (updated.status === "paused" || updated.status === "completed" || updated.status === "deleted") updated.enabled = false;
    else if (changes.status === "scheduled") updated.enabled = true;
    this.database.prepare(`UPDATE reminders SET message = @message, scheduled_at = @scheduledAt, time_zone = @timeZone,
      recurrence = @recurrence, enabled = @enabled, status = @status, updated_at = @updatedAt,
      last_delivered_at = @lastDeliveredAt, next_scheduled_at = @nextScheduledAt,
      delivery_attempts = @deliveryAttempts WHERE id = @id`).run({
      ...updated,
      enabled: updated.enabled ? 1 : 0
    });
    return updated;
  }

  deleteReminder(id) {
    return Boolean(this.updateReminder(id, { status: "deleted", enabled: false }));
  }

  getSettings() {
    const row = this.database.prepare("SELECT settings_json, updated_at FROM companion_settings WHERE singleton_id = 1").get();
    return { ...JSON.parse(row.settings_json), updatedAt: row.updated_at };
  }

  updateSettings(settings) {
    const existing = this.getSettings();
    const updated = { ...existing, ...settings };
    delete updated.updatedAt;
    const updatedAt = new Date().toISOString();
    this.database.prepare("UPDATE companion_settings SET settings_json = ?, updated_at = ? WHERE singleton_id = 1")
      .run(JSON.stringify(updated), updatedAt);
    return { ...updated, updatedAt };
  }

  prepareStatements() {
    return {
      getActive: this.database.prepare("SELECT * FROM memories WHERE id = ? AND status = 'active'"),
      getActiveKey: this.database.prepare("SELECT * FROM memories WHERE category = ? AND normalized_key = ? AND status = 'active'"),
      insert: this.database.prepare(`
        INSERT INTO memories(id, category, memory_key, normalized_key, value, confidence, importance,
          created_at, updated_at, last_accessed_at, status, source_ref)
        VALUES (@id, @category, @key, @normalizedKey, @value, @confidence, @importance,
          @createdAt, @updatedAt, NULL, 'active', @sourceRef)
      `),
      update: this.database.prepare(`
        UPDATE memories SET category = @category, memory_key = @key, normalized_key = @normalizedKey,
          value = @value, confidence = @confidence, importance = @importance,
          updated_at = @updatedAt, source_ref = @sourceRef
        WHERE id = @id AND status = 'active'
      `),
      list: this.database.prepare("SELECT * FROM memories WHERE status = 'active' ORDER BY importance DESC, updated_at DESC LIMIT ?"),
      count: this.database.prepare("SELECT COUNT(*) AS count FROM memories WHERE status = 'active'"),
      touch: this.database.prepare("UPDATE memories SET last_accessed_at = ? WHERE id = ? AND status = 'active'"),
      delete: this.database.prepare(`
        UPDATE memories SET status = 'deleted', memory_key = '', normalized_key = 'deleted:' || id,
          value = '', confidence = 0, importance = 1, updated_at = ?, last_accessed_at = NULL, source_ref = NULL
        WHERE id = ? AND status = 'active'
      `),
      clear: this.database.prepare(`
        UPDATE memories SET status = 'deleted', memory_key = '', normalized_key = 'deleted:' || id,
          value = '', confidence = 0, importance = 1, updated_at = ?, last_accessed_at = NULL, source_ref = NULL
        WHERE status = 'active'
      `)
    };
  }

  close() {
    if (this.database.open) this.database.close();
  }

  countActive() {
    return this.statements.count.get().count;
  }

  get(id) {
    return rowToMemory(this.statements.getActive.get(id));
  }

  listActive(limit = 100) {
    const memories = this.statements.list.all(Math.max(1, Math.min(Number(limit) || 100, 100))).map(rowToMemory);
    if (memories.length > 0) {
      const accessedAt = new Date().toISOString();
      memories.forEach((memory) => this.statements.touch.run(accessedAt, memory.id));
      memories.forEach((memory) => { memory.lastAccessedAt = accessedAt; });
    }
    return memories;
  }

  create(input) {
    const normalizedKey = normalizeKey(input.key);
    if (this.statements.getActiveKey.get(input.category, normalizedKey)) throw new MemoryConflictError();
    if (this.countActive() >= MAX_ACTIVE_MEMORIES) throw new Error("The active memory limit has been reached.");
    const now = new Date().toISOString();
    const record = {
      id: randomUUID(), ...input, normalizedKey, createdAt: now, updatedAt: now,
      sourceRef: input.sourceRef ?? null
    };
    this.statements.insert.run(record);
    return this.get(record.id);
  }

  upsert(input) {
    const normalizedKey = normalizeKey(input.key);
    const existing = this.statements.getActiveKey.get(input.category, normalizedKey);
    if (!existing) return this.create(input);
    const record = {
      id: existing.id, ...input, normalizedKey, updatedAt: new Date().toISOString(),
      sourceRef: input.sourceRef ?? existing.source_ref
    };
    this.statements.update.run(record);
    return this.get(existing.id);
  }

  update(id, input) {
    const transaction = this.database.transaction(() => {
      const existing = this.statements.getActive.get(id);
      if (!existing) throw new MemoryNotFoundError();
      const normalizedKey = normalizeKey(input.key);
      const conflicting = this.statements.getActiveKey.get(input.category, normalizedKey);
      if (conflicting && conflicting.id !== id) throw new MemoryConflictError();
      this.statements.update.run({
        id,
        ...input,
        normalizedKey,
        updatedAt: new Date().toISOString(),
        sourceRef: input.sourceRef ?? existing.source_ref
      });
      return this.get(id);
    });
    return transaction();
  }

  findRelevant(query, limit = 5) {
    const terms = searchTerms(query);
    if (terms.length === 0) return [];
    const ranked = this.statements.list.all(MAX_SEARCH_CANDIDATES)
      .map(rowToMemory)
      .filter((memory) => memory.confidence >= 0.65)
      .map((memory) => ({ memory, relevance: scoreMemory(memory, terms) }))
      .filter((result) => result.relevance > 0)
      .sort((left, right) => right.relevance - left.relevance || right.memory.importance - left.memory.importance || right.memory.updatedAt.localeCompare(left.memory.updatedAt))
      .slice(0, Math.max(1, Math.min(Number(limit) || 5, 10)));
    if (ranked.length > 0) {
      const accessedAt = new Date().toISOString();
      ranked.forEach(({ memory }) => this.statements.touch.run(accessedAt, memory.id));
    }
    return ranked;
  }

  delete(id) {
    return this.statements.delete.run(new Date().toISOString(), id).changes > 0;
  }

  clearAll() {
    return this.statements.clear.run(new Date().toISOString()).changes;
  }
}
