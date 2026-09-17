const MAX_BODY_BYTES = 256_000;
const MAX_EVENTS = 200;
const MIN_MODEL_SAMPLES = 200;
const BREAK_EVEN_92 = 1 / 1.92;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, service: "az-learning-gateway", schema: 1 });
    }
    if (request.method !== "POST" || url.pathname !== "/v1/learning/batch") {
      return json({ error: "not_found" }, 404);
    }
    return acceptBatch(request, env);
  },

  async scheduled(_event, env, ctx) {
    ctx.waitUntil(publishModel(env));
  }
};

async function acceptBatch(request, env) {
  const type = request.headers.get("content-type") || "";
  if (!type.toLowerCase().includes("application/json"))
    return json({ error: "json_required" }, 415);
  const declared = Number(request.headers.get("content-length") || 0);
  if (declared > MAX_BODY_BYTES) return json({ error: "body_too_large" }, 413);

  let payload;
  try {
    const text = await request.text();
    if (new TextEncoder().encode(text).length > MAX_BODY_BYTES)
      return json({ error: "body_too_large" }, 413);
    payload = JSON.parse(text);
  } catch {
    return json({ error: "invalid_json" }, 400);
  }
  if (payload?.schema !== 1 || payload?.source !== env.ALLOWED_APP ||
      !Array.isArray(payload.events) || payload.events.length < 1 ||
      payload.events.length > MAX_EVENTS)
    return json({ error: "invalid_batch" }, 400);

  let accepted = 0, duplicates = 0, rejected = 0;
  for (const raw of payload.events) {
    const event = validateEvent(raw);
    if (!event) { rejected++; continue; }
    const inserted = await env.AZ_LEARNING_DB.prepare(
      "INSERT OR IGNORE INTO seen_events(id,received_at) VALUES(?,?)"
    ).bind(event.id, Date.now()).run();
    if (!inserted.meta?.changes) { duplicates++; continue; }

    await env.AZ_LEARNING_DB.prepare(`
      INSERT INTO aggregates(asset,timeframe_minutes,samples,correct,outcome_up,raw_probability_sum)
      VALUES(?,?,1,?,?,?)
      ON CONFLICT(asset,timeframe_minutes) DO UPDATE SET
        samples=samples+1,
        correct=correct+excluded.correct,
        outcome_up=outcome_up+excluded.outcome_up,
        raw_probability_sum=raw_probability_sum+excluded.raw_probability_sum
    `).bind(event.asset, event.timeframe, event.correct ? 1 : 0,
      event.outcomeUp ? 1 : 0, event.rawP).run();
    accepted++;
  }
  return json({ ok: true, accepted, duplicates, rejected });
}

function validateEvent(e) {
  if (!e || e.schema !== 1 || typeof e.id !== "string" ||
      !/^[0-9a-f-]{20,40}$/i.test(e.id)) return null;
  const asset = String(e.asset || "").toUpperCase();
  if (!/^[A-Z0-9_-]{3,32}$/.test(asset)) return null;
  const timeframe = Number(e.timeframe_minutes);
  if (!Number.isInteger(timeframe) || timeframe < 1 || timeframe > 5) return null;
  const rawP = Number(e.raw_buy_probability);
  if (!Number.isFinite(rawP) || rawP < .02 || rawP > .98) return null;
  if (e.outcome !== "UP" && e.outcome !== "DOWN") return null;
  if (typeof e.correct !== "boolean") return null;
  return { id: e.id, asset, timeframe, rawP,
    outcomeUp: e.outcome === "UP", correct: e.correct };
}

async function publishModel(env) {
  if (!env.AZ_GITHUB_TOKEN) throw new Error("AZ_GITHUB_TOKEN secret is missing");
  const rows = await env.AZ_LEARNING_DB.prepare(
    "SELECT asset,timeframe_minutes,samples,correct,outcome_up,raw_probability_sum FROM aggregates"
  ).all();
  const assets = {};
  for (const row of rows.results || []) {
    const n = Number(row.samples), correct = Number(row.correct);
    if (n < MIN_MODEL_SAMPLES) continue;
    const lower = wilsonLower(correct, n);
    if (lower < BREAK_EVEN_92) continue;
    const observed = clamp(Number(row.outcome_up) / n, .02, .98);
    const averageRaw = clamp(Number(row.raw_probability_sum) / n, .02, .98);
    const bias = clamp(logit(observed) - logit(averageRaw), -.60, .60);
    assets[row.asset] ||= {};
    assets[row.asset][`M${row.timeframe_minutes}`] = {
      samples: n,
      bias: round(bias, 6),
      scale: 1.0,
      win_rate: round(correct / n, 6),
      lower_95: round(lower, 6)
    };
  }
  const now = new Date().toISOString();
  const model = { schema: 2, version: `gateway-${now.slice(0, 10)}`,
    generated_at: now, max_weight: .20, assets };
  await putGitHubFile(env, JSON.stringify(model, null, 2) + "\n");
  await env.AZ_LEARNING_DB.prepare(
    "DELETE FROM seen_events WHERE received_at < ?"
  ).bind(Date.now() - 45 * 86400000).run();
}

async function putGitHubFile(env, content) {
  const repo = env.GITHUB_REPOSITORY;
  const path = env.GITHUB_MODEL_PATH;
  const api = `https://api.github.com/repos/${repo}/contents/${path}`;
  const headers = { "Authorization": `Bearer ${env.AZ_GITHUB_TOKEN}`,
    "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "AZ-Learning-Gateway" };
  const current = await fetch(api, { headers });
  if (!current.ok) throw new Error(`GitHub read ${current.status}`);
  const meta = await current.json();
  const update = await fetch(api, { method: "PUT", headers: { ...headers,
    "Content-Type": "application/json" }, body: JSON.stringify({
      message: "Update validated AZ community model", content: base64Utf8(content),
      sha: meta.sha, branch: "main" }) });
  if (!update.ok) throw new Error(`GitHub write ${update.status}`);
}

function wilsonLower(wins, samples) {
  if (!samples) return 0;
  const z = 1.959963984540054, p = wins / samples, z2 = z * z;
  return clamp((p + z2/(2*samples) - z*Math.sqrt((p*(1-p)+z2/(4*samples))/samples)) /
    (1 + z2/samples), 0, 1);
}
function logit(x) { return Math.log(x / (1 - x)); }
function clamp(x, lo, hi) { return Math.max(lo, Math.min(hi, x)); }
function round(x, places) { const f = 10 ** places; return Math.round(x*f)/f; }
function base64Utf8(text) {
  const bytes = new TextEncoder().encode(text); let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}
function json(value, status = 200) {
  return new Response(JSON.stringify(value), { status,
    headers: { "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store" } });
}
