// SPDX-License-Identifier: GPL-3.0-or-later
//
// ReveriePaint 崩溃遥测接收端 (Cloudflare Worker + D1)。
// 契约见 docs/TELEMETRY.md §7。设计要点:
//   1. 不读 CF-Connecting-IP,不落任何 IP 或 IP 哈希
//   2. 配额按 install 维度(5 条/天)与签名维度(3 条/天),超限仍回 200(客户端不重试)
//   3. DISABLED=1 时全局 410,客户端收到后永久停用(kill switch,不必发版)

const CONTRACT_VERSION = 1;
const MAX_BODY_BYTES = 128 * 1024;
const DAILY_PER_INSTALL = 5;
const DAILY_PER_SIG = 3;
const MAX_STACK = 16 * 1024;
const MAX_LOGCAT = 32 * 1024;
const MAX_CRUMBS = 32;
const MAX_FRAMES = 24;
const RETENTION_DAYS = 180;
const KINDS = new Set(['java_crash', 'native_crash', 'anr']);

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';
    try {
      if (path === '/t/v1/health') {
        return json({ ok: true, version: CONTRACT_VERSION, disabled: isDisabled(env) });
      }
      if (path === '/t/v1/crash' && request.method === 'POST') {
        return await handleIngest(request, env);
      }
      if (path === '/t/v1/stats' && request.method === 'GET') {
        return await handleStats(request, url, env);
      }
      return json({ ok: false, error: 'not_found' }, 404);
    } catch (err) {
      if (err instanceof HttpError) {
        return json({ ok: false, error: err.message }, err.status);
      }
      return json({ ok: false, error: 'internal' }, 500);
    }
  },

  /**
   * 每日定时清理。保留期写在 docs/PRIVACY.md 里, 这里是它的执行体 ——
   * 声明了"存 180 天"就必须真的有东西在删。
   */
  async scheduled(event, env) {
    if (!env.DB) return;
    const cutoff = Math.floor(Date.now() / 1000) - RETENTION_DAYS * 86400;
    await env.DB.prepare('DELETE FROM crashes WHERE received < ?').bind(cutoff).run();
  },
};

function isDisabled(env) {
  return String(env.DISABLED ?? '0') === '1';
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  });
}

function checkKey(request, env, url) {
  const expected = env.INGEST_KEY ?? '';
  if (!expected) return; // 未配置 key 时放行(仅本地开发)
  const given = request.headers.get('x-rp-key') ?? url.searchParams.get('key') ?? '';
  if (given !== expected) throw new HttpError(401, 'bad_key');
}

async function readBody(request) {
  const ab = await request.arrayBuffer();
  if (ab.byteLength > MAX_BODY_BYTES) throw new HttpError(413, 'too_large');
  const bytes = new Uint8Array(ab);
  const enc = (request.headers.get('content-encoding') ?? '').toLowerCase();
  // 只有真的以 gzip magic(1f 8b) 开头才解压: 某些边缘/运行时会在交给 Worker 前
  // 透明解掉 body, 那时再尝试解压会 500 —— 宁可容忍明文
  const isGzip = bytes.length > 1 && bytes[0] === 0x1f && bytes[1] === 0x8b;
  if (!enc.includes('gzip') || !isGzip) return new TextDecoder().decode(bytes);
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
  return await new Response(stream).text();
}

function clampText(value, max) {
  if (typeof value !== 'string') return '';
  return value.length > max ? value.slice(0, max) : value;
}

function clampInt(value, fallback = 0) {
  const n = Number(value);
  return Number.isFinite(n) ? Math.trunc(n) : fallback;
}

function dayStartUtc(nowMs) {
  const d = new Date(nowMs);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()) / 1000;
}

/** 归一化上报体: 只保留契约里出现的字段, 其余丢弃(纵深防御: 客户端错了也不该落库奇怪字段) */
function normalize(raw, nowSec) {
  if (!raw || typeof raw !== 'object') throw new HttpError(400, 'bad_body');
  const kind = String(raw.kind ?? '');
  if (!KINDS.has(kind)) throw new HttpError(400, 'bad_kind');
  const install = clampText(raw.install, 64);
  const sig = clampText(raw.sig, 128);
  if (!install || !sig) throw new HttpError(400, 'missing_install_or_sig');

  const app = raw.app ?? {};
  const dev = raw.dev ?? {};
  const doc = raw.doc ?? {};

  let at = clampInt(raw.at, 0);
  if (at <= 0 || at > nowSec + 300) at = nowSec; // 容忍设备时钟漂移

  const crumbs = Array.isArray(raw.crumbs) ? raw.crumbs.slice(-MAX_CRUMBS) : [];
  const native = raw.native ?? null;

  return {
    v: CONTRACT_VERSION,
    kind,
    install,
    sig,
    at,
    app: {
      ver: clampText(app.ver, 32),
      code: clampInt(app.code, 0),
      abi: clampText(app.abi, 32),
    },
    dev: {
      mfr: clampText(dev.mfr, 48),
      model: clampText(dev.model, 64),
      dev: clampText(dev.dev, 64),
      sdk: clampInt(dev.sdk, 0),
      rel: clampText(dev.rel, 32),
      fp: clampText(dev.fp, 160),
      ram_mb: clampInt(dev.ram_mb, 0),
      harmony: clampText(dev.harmony, 160),
    },
    doc: {
      w: clampInt(doc.w, 0),
      h: clampInt(doc.h, 0),
      layers: clampInt(doc.layers, 0),
      page: clampText(doc.page, 32),
      tool: clampText(doc.tool, 48),
      stylus: clampText(doc.stylus, 48),
    },
    crumbs: crumbs
      .filter((c) => c && typeof c === 'object')
      .map((c) => ({
        t: clampInt(c.t, 0),
        c: clampText(c.c, 24),
        e: clampText(c.e, 32),
      })),
    stack: clampText(raw.stack, MAX_STACK),
    logcat: clampText(raw.logcat, MAX_LOGCAT),
    native: native
      ? {
          signal: clampText(native.signal, 24),
          code: clampInt(native.code, 0),
          fault: clampText(native.fault, 24),
          frames: (Array.isArray(native.frames) ? native.frames : [])
            .slice(0, MAX_FRAMES)
            .map((f) => clampText(f, 120)),
        }
      : null,
  };
}

async function handleIngest(request, env) {
  const url = new URL(request.url);
  checkKey(request, env, url);
  if (isDisabled(env)) throw new HttpError(410, 'gone');

  const nowMs = Date.now();
  const nowSec = Math.floor(nowMs / 1000);
  const body = await readBody(request);

  let raw;
  try {
    raw = JSON.parse(body);
  } catch {
    throw new HttpError(400, 'bad_json');
  }
  if (clampInt(raw?.v, 0) !== CONTRACT_VERSION) throw new HttpError(400, 'bad_version');

  const payload = normalize(raw, nowSec);
  const db = env.DB;

  const since = dayStartUtc(nowMs);
  const byInstall = await db
    .prepare('SELECT COUNT(*) AS n FROM crashes WHERE install = ? AND received >= ?')
    .bind(payload.install, since)
    .first();
  if ((byInstall?.n ?? 0) >= DAILY_PER_INSTALL) {
    return json({ ok: true, dropped: 'install_daily_cap' });
  }

  const bySig = await db
    .prepare('SELECT COUNT(*) AS n FROM crashes WHERE install = ? AND sig = ? AND received >= ?')
    .bind(payload.install, payload.sig, since)
    .first();
  if ((bySig?.n ?? 0) >= DAILY_PER_SIG) {
    return json({ ok: true, dropped: 'sig_daily_cap' });
  }

  await db
    .prepare(
      `INSERT INTO crashes (sig, kind, at, received, app_code, app_ver, abi, sdk, model, install, payload)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    )
    .bind(
      payload.sig,
      payload.kind,
      payload.at,
      nowSec,
      payload.app.code,
      payload.app.ver,
      payload.app.abi,
      payload.dev.sdk,
      payload.dev.model,
      payload.install,
      JSON.stringify(payload),
    )
    .run();

  return json({ ok: true });
}

function escapeHtml(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function fmtTime(sec) {
  if (!sec) return '-';
  return new Date(sec * 1000).toISOString().replace('T', ' ').slice(0, 16) + 'Z';
}

async function handleStats(request, url, env) {
  checkKey(request, env, url);
  const days = Math.min(Math.max(clampInt(url.searchParams.get('days'), 7), 1), 90);
  const sigFilter = url.searchParams.get('sig');
  const db = env.DB;
  const since = Math.floor(Date.now() / 1000) - days * 86400;

  if (sigFilter) {
    const rows = await db
      .prepare('SELECT received, app_ver, model, payload FROM crashes WHERE sig = ? AND at >= ? ORDER BY at DESC LIMIT 20')
      .bind(sigFilter, since)
      .all();
    const items = rows.results ?? [];
    const body = items
      .map(
        (r) => `<details><summary>${fmtTime(r.received)} · ${escapeHtml(r.app_ver)} · ${escapeHtml(
          r.model,
        )}</summary><pre>${escapeHtml(prettyPayload(r.payload))}</pre></details>`,
      )
      .join('\n');
    return htmlPage(`签名 ${escapeHtml(sigFilter)} · 最近 ${items.length} 条`, `<p><a href="?days=${days}">返回聚合</a></p>${body || '<p>无记录</p>'}`);
  }

  const agg = await db
    .prepare(
      `SELECT sig, kind, COUNT(*) AS n, COUNT(DISTINCT install) AS people,
              MIN(at) AS first_at, MAX(at) AS last_at, MAX(app_ver) AS last_ver
         FROM crashes WHERE at >= ?
        GROUP BY sig, kind
        ORDER BY people DESC, n DESC
        LIMIT 100`,
    )
    .bind(since)
    .all();

  const models = await db
    .prepare(
      `SELECT model, sdk, COUNT(*) AS n FROM crashes WHERE at >= ?
        GROUP BY model, sdk ORDER BY n DESC LIMIT 15`,
    )
    .bind(since)
    .all();

  const total = await db.prepare('SELECT COUNT(*) AS n, COUNT(DISTINCT install) AS i FROM crashes WHERE at >= ?').bind(since).first();

  const rows = (agg.results ?? [])
    .map(
      (r) => `<tr>
        <td><a href="?days=${days}&sig=${encodeURIComponent(r.sig)}">${escapeHtml(r.kind)} · ${escapeHtml(
          r.sig.slice(0, 12),
        )}…</a></td>
        <td class="n">${r.n}</td><td class="n">${r.people}</td>
        <td>${escapeHtml(r.last_ver)}</td><td>${fmtTime(r.first_at)} → ${fmtTime(r.last_at)}</td>
      </tr>`,
    )
    .join('\n');

  const modelRows = (models.results ?? [])
    .map((m) => `<tr><td>${escapeHtml(m.model)}</td><td class="n">${m.sdk}</td><td class="n">${m.n}</td></tr>`)
    .join('\n');

  const body = `
    <p>近 ${days} 天: <b>${total?.n ?? 0}</b> 条上报 · <b>${total?.i ?? 0}</b> 台设备 · <b>${
      (agg.results ?? []).length
    }</b> 个签名</p>
    <h2>按签名聚合</h2>
    <table><thead><tr><th>签名</th><th>次数</th><th>设备数</th><th>末见版本</th><th>首末见到</th></tr></thead>
    <tbody>${rows || '<tr><td colspan="5">近 ' + days + ' 天无上报</td></tr>'}</tbody></table>
    <h2>机型分布</h2>
    <table><thead><tr><th>机型</th><th>SDK</th><th>次数</th></tr></thead>
    <tbody>${modelRows || '<tr><td colspan="3">无</td></tr>'}</tbody></table>`;
  return htmlPage(`崩溃聚合 · 近 ${days} 天`, body);
}

function prettyPayload(text) {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return String(text ?? '');
  }
}

function htmlPage(title, body) {
  return new Response(
    `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escapeHtml(title)}</title>
<style>
body{font:14px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",sans-serif;
     max-width:1100px;margin:32px auto;padding:0 16px;color:#2b3038;background:#f6f6f4}
h1{font-size:20px;font-weight:600}h2{font-size:15px;font-weight:600;margin-top:28px;color:#5a6e8a}
table{border-collapse:collapse;width:100%;background:#fff;border-radius:10px;overflow:hidden;
      box-shadow:0 1px 2px rgba(0,0,0,.06)}
th,td{padding:8px 12px;border-bottom:1px solid #ececea;text-align:left;vertical-align:top}
th{background:#f0f0ee;font-weight:600;font-size:12px;color:#5a6e8a}
td.n{text-align:right;font-variant-numeric:tabular-nums}
a{color:#5a6e8a}
pre{background:#fff;padding:12px;border-radius:8px;overflow:auto;font-size:12px;max-height:60vh}
details{margin:6px 0}
</style></head><body><h1>${escapeHtml(title)}</h1>${body}</body></html>`,
    { headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' } },
  );
}
