const MAX_BODY_BYTES = 256_000;
const MAX_EVENTS = 200;
const MIN_MODEL_SAMPLES = 50;
const MIN_MODEL_WIN_RATE = 0.60;
const BREAK_EVEN_92 = 1 / 1.92;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, service: "az-learning-gateway", schema: 1 });
    }
    if (request.method === "GET" && url.pathname === "/license-admin") {
      return new Response(adminPage(), { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
    }
    if (request.method === "POST" && url.pathname === "/v1/license/verify")
      return verifyLicense(request, env);
    if (request.method === "POST" && url.pathname === "/v1/license/admin/create")
      return createLicense(request, env);
    if (request.method === "GET" && url.pathname === "/v1/license/admin/list")
      return listLicenses(request, env);
    if (request.method === "POST" && url.pathname === "/v1/license/admin/revoke")
      return revokeLicense(request, env);
    if (request.method === "POST" && url.pathname === "/v1/license/admin/extend")
      return extendLicense(request, env);
    if (request.method === "POST" && url.pathname === "/v1/learning/batch")
      return acceptBatch(request, env);
    return json({ error: "not_found" }, 404);
  },

  async scheduled(_event, env, ctx) {
    ctx.waitUntil(publishModel(env));
  }
};

async function verifyLicense(request, env) {
  let body;
  try { body = await request.json(); } catch { return json({ error: "invalid_json" }, 400); }
  const key = String(body?.key || "").trim().toUpperCase();
  const installId = String(body?.install_id || "").trim();
  if (!/^AZ-[A-Z0-9]{4}(?:-[A-Z0-9]{4}){3}$/.test(key) || !/^[a-f0-9-]{20,64}$/i.test(installId))
    return json({ valid: false, error: "invalid_request" }, 400);
  const hash = await sha256(key);
  let row = await env.AZ_LEARNING_DB.prepare(
    "SELECT key_hash,months,status,install_id,activated_at,expires_at FROM licenses WHERE key_hash=?"
  ).bind(hash).first();
  if (!row || row.status !== "active") return json({ valid: false, error: "invalid_key" }, 403);
  const now = Date.now();
  if (!row.install_id) {
    const expiresAt = addMonths(now, Number(row.months));
    await env.AZ_LEARNING_DB.prepare(
      "UPDATE licenses SET install_id=?,activated_at=?,expires_at=? WHERE key_hash=? AND install_id IS NULL"
    ).bind(installId, now, expiresAt, hash).run();
    row.install_id = installId; row.activated_at = now; row.expires_at = expiresAt;
  }
  if (row.install_id !== installId) return json({ valid: false, error: "already_activated" }, 403);
  if (Number(row.expires_at) <= now) return json({ valid: false, error: "expired", expires_at: Number(row.expires_at) }, 403);
  return json({ valid: true, months: Number(row.months), expires_at: Number(row.expires_at) });
}

async function createLicense(request, env) {
  if (!isAdmin(request, env))
    return json({ error: "unauthorized" }, 401);
  let body;
  try { body = await request.json(); } catch { return json({ error: "invalid_json" }, 400); }
  const months = Number(body?.months);
  if (!Number.isInteger(months) || months < 1 || months > 12)
    return json({ error: "months_must_be_1_to_12" }, 400);
  const key = makeLicenseKey();
  const hash = await sha256(key);
  await env.AZ_LEARNING_DB.prepare(
    "INSERT INTO licenses(key_hash,months,status,created_at) VALUES(?,?,'active',?)"
  ).bind(hash, months, Date.now()).run();
  return json({ ok: true, key, months });
}

function isAdmin(request, env) {
  const auth = request.headers.get("authorization") || "";
  return Boolean(env.LICENSE_ADMIN_TOKEN) && auth === `Bearer ${env.LICENSE_ADMIN_TOKEN}`;
}

async function listLicenses(request, env) {
  if (!isAdmin(request, env)) return json({ error: "unauthorized" }, 401);
  const rows = await env.AZ_LEARNING_DB.prepare(
    "SELECT key_hash,months,status,created_at,install_id,activated_at,expires_at FROM licenses ORDER BY created_at DESC LIMIT 200"
  ).all();
  return json({ ok: true, licenses: (rows.results || []).map(row => ({
    id: String(row.key_hash), months: Number(row.months), status: String(row.status),
    created_at: Number(row.created_at), activated: Boolean(row.install_id),
    activated_at: row.activated_at == null ? null : Number(row.activated_at),
    expires_at: row.expires_at == null ? null : Number(row.expires_at)
  })) });
}

async function revokeLicense(request, env) {
  if (!isAdmin(request, env)) return json({ error: "unauthorized" }, 401);
  const body = await readAdminBody(request);
  if (!body) return json({ error: "invalid_json" }, 400);
  const hash = await resolveLicenseHash(body);
  if (!hash) return json({ error: "invalid_key" }, 400);
  const changed = await env.AZ_LEARNING_DB.prepare(
    "UPDATE licenses SET status='revoked' WHERE key_hash=?"
  ).bind(hash).run();
  if (!changed.meta?.changes) return json({ error: "not_found" }, 404);
  return json({ ok: true, status: "revoked" });
}

async function extendLicense(request, env) {
  if (!isAdmin(request, env)) return json({ error: "unauthorized" }, 401);
  const body = await readAdminBody(request);
  if (!body) return json({ error: "invalid_json" }, 400);
  const extra = Number(body.months);
  if (!Number.isInteger(extra) || extra < 1 || extra > 12)
    return json({ error: "months_must_be_1_to_12" }, 400);
  const hash = await resolveLicenseHash(body);
  if (!hash) return json({ error: "invalid_key" }, 400);
  const row = await env.AZ_LEARNING_DB.prepare(
    "SELECT months,expires_at FROM licenses WHERE key_hash=?"
  ).bind(hash).first();
  if (!row) return json({ error: "not_found" }, 404);
  const expiresAt = row.expires_at == null ? null : addMonths(Math.max(Date.now(), Number(row.expires_at)), extra);
  await env.AZ_LEARNING_DB.prepare(
    "UPDATE licenses SET months=?,expires_at=?,status='active' WHERE key_hash=?"
  ).bind(Number(row.months) + extra, expiresAt, hash).run();
  return json({ ok: true, months: Number(row.months) + extra, expires_at: expiresAt, status: "active" });
}

async function readAdminBody(request) {
  try { return await request.json(); } catch { return null; }
}

async function resolveLicenseHash(body) {
  const id = String(body?.id || "").trim().toLowerCase();
  if (/^[a-f0-9]{64}$/.test(id)) return id;
  const key = String(body?.key || "").trim().toUpperCase();
  if (!/^AZ-[A-Z0-9]{4}(?:-[A-Z0-9]{4}){3}$/.test(key)) return null;
  return sha256(key);
}

function makeLicenseKey() {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  let out = "AZ";
  for (let group = 0; group < 4; group++) {
    out += "-";
    for (let i = 0; i < 4; i++) out += alphabet[bytes[group * 4 + i] % alphabet.length];
  }
  return out;
}
async function sha256(text) {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(bytes)].map(b => b.toString(16).padStart(2, "0")).join("");
}
function addMonths(epoch, months) {
  const d = new Date(epoch); d.setUTCMonth(d.getUTCMonth() + months); return d.getTime();
}
function adminPage() {
  return `<!doctype html><meta name="viewport" content="width=device-width"><title>AZ Licence Admin</title>
<style>*{box-sizing:border-box}body{font-family:system-ui,sans-serif;background:#020617;color:#e2e8f0;max-width:900px;margin:24px auto;padding:16px}h1{color:#22d3ee}.card{background:#0f172a;border:1px solid #334155;border-radius:16px;padding:16px;margin:14px 0}.grid{display:grid;grid-template-columns:1fr 160px;gap:10px}input,select,button{width:100%;padding:13px;border-radius:10px;border:1px solid #475569;background:#1e293b;color:#fff}input[type=checkbox]{width:auto;accent-color:#22d3ee}.remember{display:flex;align-items:center;gap:9px;margin:12px 2px;color:#fff}.secondary{background:#334155}button{background:#0891b2;font-weight:700;cursor:pointer}.danger{background:#b91c1c}.muted{color:#94a3b8}.key{font-size:20px;color:#86efac;word-break:break-all}table{width:100%;border-collapse:collapse;font-size:13px}th,td{text-align:left;padding:9px 5px;border-bottom:1px solid #334155}.actions{display:flex;gap:5px}.actions button{padding:7px;font-size:12px}.active{color:#86efac}.revoked,.expired{color:#fca5a5}@media(max-width:600px){.grid{grid-template-columns:1fr}table{font-size:11px}.hide-mobile{display:none}}</style>
<h1>AZ Licence Admin</h1><p class="muted">Generate and manage private AZ licences.</p>
<div class="card"><input id="token" type="password" placeholder="Cloudflare admin token"><label class="remember"><input id="remember" type="checkbox"> Remember admin token on this device</label><div class="grid"><button onclick="load()">LOGIN / REFRESH</button><button class="secondary" onclick="forgetToken()">REMOVE SAVED TOKEN</button></div><div id="msg" class="muted" style="margin-top:9px"></div></div>
<div class="card"><h2>Create licence</h2><div class="grid"><select id="months">${Array.from({length:12},(_,i)=>`<option value="${i+1}">${i+1} month${i?"s":""}</option>`).join("")}</select><button onclick="createKey()">GENERATE</button></div><div id="newKey" class="key" style="margin-top:12px"></div><button id="copy" style="display:none;margin-top:8px" onclick="copyKey()">COPY KEY</button></div>
<div class="card"><h2>Licences</h2><div style="overflow-x:auto"><table><thead><tr><th>ID</th><th>Status</th><th>Months</th><th class="hide-mobile">Created</th><th>Expires</th><th>Actions</th></tr></thead><tbody id="rows"><tr><td colspan="6" class="muted">Enter your admin token and tap Login.</td></tr></tbody></table></div></div>
<script>
const el=id=>document.getElementById(id);
const tokenEl=el("token"),rememberEl=el("remember"),monthsEl=el("months"),msgEl=el("msg"),newKeyEl=el("newKey"),copyEl=el("copy"),rowsEl=el("rows");
const tokenStore="az_admin_token";const savedToken=localStorage.getItem(tokenStore)||"";if(savedToken){tokenEl.value=savedToken;rememberEl.checked=true}
let lastKey="";const auth=()=>({"content-type":"application/json","authorization":"Bearer "+tokenEl.value.trim()});
const date=x=>x?new Date(x).toLocaleDateString():"Not activated";
async function api(path,options={}){const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),15000);try{const r=await fetch(path,{...options,headers:auth(),signal:controller.signal});const text=await r.text();let j;try{j=JSON.parse(text)}catch{throw Error("Server returned an invalid response")};if(!r.ok)throw Error(j.error||("HTTP "+r.status));return j}finally{clearTimeout(timer)}}
async function createKey(){try{msgEl.textContent="Creating…";const j=await api("/v1/license/admin/create",{method:"POST",body:JSON.stringify({months:+monthsEl.value})});lastKey=j.key;newKeyEl.textContent=j.key+" • "+j.months+" month(s)";copyEl.style.display="block";msgEl.textContent="Licence created.";await load()}catch(e){msgEl.textContent="Error: "+(e.name==="AbortError"?"request timed out":e.message)}}
async function load(){if(!tokenEl.value.trim()){msgEl.textContent="Enter your Cloudflare admin token.";return}try{msgEl.textContent="Loading…";const j=await api("/v1/license/admin/list");if(rememberEl.checked)localStorage.setItem(tokenStore,tokenEl.value.trim());else localStorage.removeItem(tokenStore);rowsEl.innerHTML=j.licenses.length?j.licenses.map(rowHtml).join(""):"<tr><td colspan=6>No licences yet.</td></tr>";msgEl.textContent=j.licenses.length+" licence(s) loaded."}catch(e){msgEl.textContent="Error: "+(e.name==="AbortError"?"request timed out":e.message)}}
function forgetToken(){localStorage.removeItem(tokenStore);rememberEl.checked=false;tokenEl.value="";rowsEl.innerHTML='<tr><td colspan="6" class="muted">Enter your admin token and tap Login.</td></tr>';msgEl.textContent="Saved token removed from this device."}
function rowHtml(x){const expired=x.expires_at&&x.expires_at<=Date.now();const state=expired?"expired":x.status;return '<tr><td>'+x.id.slice(0,8)+'…</td><td class="'+state+'">'+state+'</td><td>'+x.months+'</td><td class="hide-mobile">'+date(x.created_at)+'</td><td>'+date(x.expires_at)+'</td><td><div class="actions"><button onclick="extend(\''+x.id+'\')">+ Month</button><button class="danger" onclick="revoke(\''+x.id+'\')">Revoke</button></div></td></tr>'}
async function extend(id){const n=Number(prompt("Add how many months (1–12)?","1"));if(!Number.isInteger(n)||n<1||n>12)return;try{await api("/v1/license/admin/extend",{method:"POST",body:JSON.stringify({id,months:n})});await load()}catch(e){msgEl.textContent="Error: "+e.message}}
async function revoke(id){if(!confirm("Revoke this licence now?"))return;try{await api("/v1/license/admin/revoke",{method:"POST",body:JSON.stringify({id})});await load()}catch(e){msgEl.textContent="Error: "+e.message}}
async function copyKey(){try{await navigator.clipboard.writeText(lastKey);msgEl.textContent="Key copied."}catch{msgEl.textContent="Press and hold the key to copy it."}}
</script>`;
}

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
    const winRate = correct / n;
    if (winRate < MIN_MODEL_WIN_RATE) continue;
    const lower = wilsonLower(correct, n);
    const observed = clamp(Number(row.outcome_up) / n, .02, .98);
    const averageRaw = clamp(Number(row.raw_probability_sum) / n, .02, .98);
    const bias = clamp(logit(observed) - logit(averageRaw), -.60, .60);
    assets[row.asset] ||= {};
    assets[row.asset][`M${row.timeframe_minutes}`] = {
      samples: n,
      bias: round(bias, 6),
      scale: 1.0,
      win_rate: round(winRate, 6),
      lower_95: round(lower, 6)
    };
  }
  const now = new Date().toISOString();
  const model = { schema: 2, version: `gateway-${now.slice(0, 10)}`,
    generated_at: now, max_weight: .05, assets };
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
