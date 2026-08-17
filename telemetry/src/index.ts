/**
 * InnerDesk anonymous telemetry ingest.
 *
 * POST /v1/ping — heartbeat / crash / error (JSON body)
 * GET /v1/stats — aggregate counts (Bearer ADMIN_TOKEN if set)
 * GET /health — liveness
 */

export interface Env {
  DB: D1Database;
  ADMIN_TOKEN?: string;
}

type EventType = "ping" | "crash" | "error";

interface PingBody {
  uuid?: string;
  type?: string;
  version?: string;
  versionCode?: number;
  androidSdk?: number;
  locale?: string;
  payload?: string;
}

const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MAX_PAYLOAD = 48_000;
const CORS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS });
    }

    const url = new URL(request.url);
    try {
      if (url.pathname === "/health" && request.method === "GET") {
        return json({ ok: true });
      }
      if (url.pathname === "/v1/ping" && request.method === "POST") {
        return await handlePing(request, env);
      }
      if (url.pathname === "/v1/stats" && request.method === "GET") {
        return await handleStats(request, env);
      }
      return json({ error: "not_found" }, 404);
    } catch (e) {
      return json({ error: "server", detail: String(e) }, 500);
    }
  },
};

async function handlePing(request: Request, env: Env): Promise<Response> {
  let body: PingBody;
  try {
    body = (await request.json()) as PingBody;
  } catch {
    return json({ error: "bad_json" }, 400);
  }

  const uuid = (body.uuid || "").trim().toLowerCase();
  if (!UUID_RE.test(uuid)) return json({ error: "bad_uuid" }, 400);

  const type = normalizeType(body.type);
  const now = Date.now();
  const version = clip(body.version, 32);
  const versionCode =
    typeof body.versionCode === "number" && Number.isFinite(body.versionCode)
      ? Math.trunc(body.versionCode)
      : null;
  const androidSdk =
    typeof body.androidSdk === "number" && Number.isFinite(body.androidSdk)
      ? Math.trunc(body.androidSdk)
      : null;
  const locale = clip(body.locale, 16);
  const payload =
    type === "ping" ? null : clip(body.payload || "", MAX_PAYLOAD) || null;

  await env.DB.prepare(
    `INSERT INTO devices (uuid, first_seen, last_seen, version, version_code, android_sdk, locale, ping_count)
     VALUES (?, ?, ?, ?, ?, ?, ?, 1)
     ON CONFLICT(uuid) DO UPDATE SET
       last_seen = excluded.last_seen,
       version = COALESCE(excluded.version, devices.version),
       version_code = COALESCE(excluded.version_code, devices.version_code),
       android_sdk = COALESCE(excluded.android_sdk, devices.android_sdk),
       locale = COALESCE(excluded.locale, devices.locale),
       ping_count = devices.ping_count + 1`,
  )
    .bind(uuid, now, now, version, versionCode, androidSdk, locale)
    .run();

  if (type !== "ping") {
    await env.DB.prepare(
      `INSERT INTO events (uuid, type, created_at, version, payload)
       VALUES (?, ?, ?, ?, ?)`,
    )
      .bind(uuid, type, now, version, payload)
      .run();
  }

  return json({ ok: true, type });
}

async function handleStats(request: Request, env: Env): Promise<Response> {
  if (env.ADMIN_TOKEN) {
    const auth = request.headers.get("Authorization") || "";
    if (auth !== `Bearer ${env.ADMIN_TOKEN}`) {
      return json({ error: "unauthorized" }, 401);
    }
  }

  const day = Date.now() - 24 * 60 * 60 * 1000;
  const week = Date.now() - 7 * 24 * 60 * 60 * 1000;

  const devices = await env.DB.prepare(`SELECT COUNT(*) AS n FROM devices`).first<{ n: number }>();
  const active24h = await env.DB.prepare(
    `SELECT COUNT(*) AS n FROM devices WHERE last_seen >= ?`,
  )
    .bind(day)
    .first<{ n: number }>();
  const active7d = await env.DB.prepare(
    `SELECT COUNT(*) AS n FROM devices WHERE last_seen >= ?`,
  )
    .bind(week)
    .first<{ n: number }>();
  const crashes = await env.DB.prepare(
    `SELECT COUNT(*) AS n FROM events WHERE type = 'crash'`,
  ).first<{ n: number }>();
  const errors = await env.DB.prepare(
    `SELECT COUNT(*) AS n FROM events WHERE type = 'error'`,
  ).first<{ n: number }>();
  const versions = await env.DB.prepare(
    `SELECT version, COUNT(*) AS n FROM devices
     WHERE version IS NOT NULL
     GROUP BY version ORDER BY n DESC LIMIT 20`,
  ).all<{ version: string; n: number }>();

  return json({
    devices: devices?.n ?? 0,
    active_24h: active24h?.n ?? 0,
    active_7d: active7d?.n ?? 0,
    crashes: crashes?.n ?? 0,
    errors: errors?.n ?? 0,
    versions: versions.results ?? [],
  });
}

function normalizeType(t: string | undefined): EventType {
  if (t === "crash" || t === "error") return t;
  return "ping";
}

function clip(s: string | null | undefined, max: number): string | null {
  if (s == null) return null;
  const t = String(s).trim();
  if (!t) return null;
  return t.length > max ? t.slice(0, max) : t;
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...CORS },
  });
}
