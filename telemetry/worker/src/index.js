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

/**
 * 看板的只读鉴权。与上报用的 INGEST_KEY 分开:
 * 看板凭据会进浏览器地址栏与书签, 绝不能复用写入凭据。
 * 未配置 STATS_KEY 时退回 INGEST_KEY(兼容旧部署)。
 *
 * 三种凭据形态: ?key= / x-stats-key 头 / rp_stats Cookie。
 * 用 Cookie 是为了让"书签链接"固定 —— 首次带 key 访问后跳转到干净 URL, 之后只靠 Cookie。
 */
async function checkStatsKey(request, env, url) {
  const secret = env.STATS_KEY || env.INGEST_KEY || '';
  if (!secret) return;
  const given =
    url.searchParams.get('key') ??
    request.headers.get('x-stats-key') ??
    cookieValue(request, 'rp_stats') ??
    '';
  if (given === secret) return;
  if (given && given === (await statsToken(env))) return;
  throw new HttpError(401, given ? 'bad_key' : 'need_key');
}

function cookieValue(request, name) {
  const raw = request.headers.get('cookie') ?? '';
  for (const part of raw.split(';')) {
    const i = part.indexOf('=');
    if (i > 0 && part.slice(0, i).trim() === name) return part.slice(i + 1).trim();
  }
  return null;
}

/** Cookie 里放的不是 key 本身, 而是 sha256("revp-stats-v1|"+key), 泄了也拿不到写入凭据 */
async function statsToken(env) {
  const secret = env.STATS_KEY || env.INGEST_KEY || '';
  if (!secret) return '';
  const data = new TextEncoder().encode('revp-stats-v1|' + secret);
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', data));
  let out = '';
  for (const b of digest) out += b.toString(16).padStart(2, '0');
  return out;
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

const MORANDI = {
  native_crash: { color: '#C0705F', label: 'Native' },
  java_crash: { color: '#B99A5B', label: 'Java' },
  anr: { color: '#8B7FA8', label: 'ANR' },
};

async function handleStats(request, url, env) {
  await checkStatsKey(request, env, url);

  const headers = new Headers({ 'cache-control': 'no-store' });
  const token = await statsToken(env);
  if (token) {
    // 每次通过鉴权都续期一年(滑动窗口): 书签链接才真的"固定"
    headers.set('set-cookie', `rp_stats=${token}; Path=/t/v1; HttpOnly; Secure; SameSite=Lax; Max-Age=31536000`);
  }

  // 首次带 ?key= 访问: 种下 Cookie 后跳转到不带 key 的干净 URL
  if (url.searchParams.get('key')) {
    const clean = new URL(url);
    clean.searchParams.delete('key');
    headers.set('location', clean.pathname + clean.search);
    return new Response(null, { status: 302, headers });
  }

  const days = Math.min(Math.max(clampInt(url.searchParams.get('days'), 7), 1), 90);
  const ctx = {
    db: env.DB,
    days,
    since: Math.floor(Date.now() / 1000) - days * 86400,
    model: clampText(url.searchParams.get('model'), 64),
    ver: clampText(url.searchParams.get('ver'), 32),
  };
  const sig = clampText(url.searchParams.get('sig'), 128);

  const html = sig ? await renderSignature(ctx, sig) : await renderOverview(ctx);
  headers.set('content-type', 'text/html; charset=utf-8');
  return new Response(html, { status: 200, headers });
}

/** 时间窗 + 可选机型/版本筛选, 所有查询共用 */
function whereClause(ctx, extra = []) {
  const clauses = ['at >= ?'];
  const params = [ctx.since];
  if (ctx.model) {
    clauses.push('model = ?');
    params.push(ctx.model);
  }
  if (ctx.ver) {
    clauses.push('app_ver = ?');
    params.push(ctx.ver);
  }
  for (const e of extra) clauses.push(e);
  return { sql: ' WHERE ' + clauses.join(' AND '), params };
}

async function q(ctx, sql, params) {
  const res = await ctx.db.prepare(sql).bind(...params).all();
  return res.results ?? [];
}

async function q1(ctx, sql, params) {
  return (await ctx.db.prepare(sql).bind(...params).first()) ?? {};
}

function link(ctx, extra = {}) {
  const qs = new URLSearchParams();
  qs.set('days', String(ctx.days));
  if (ctx.model) qs.set('model', ctx.model);
  if (ctx.ver) qs.set('ver', ctx.ver);
  for (const [k, v] of Object.entries(extra)) {
    if (v === null || v === undefined || v === '') continue;
    qs.set(k, String(v));
  }
  return '?' + qs.toString();
}

function prettyPayload(text) {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return String(text ?? '');
  }
}

function kindChip(kind) {
  const m = MORANDI[kind] ?? { color: '#8A8A8E', label: kind };
  return `<span class="chip"><i style="background:${m.color}"></i>${escapeHtml(m.label)}</span>`;
}

function fillTrend(points, days) {
  const map = new Map(points.map((p) => [p.d, p.n]));
  const now = Date.now();
  const out = [];
  for (let i = days - 1; i >= 0; i--) {
    const key = new Date(now - i * 86400000).toISOString().slice(0, 10);
    out.push({ d: key, n: map.get(key) ?? 0 });
  }
  return out;
}

function trendChart(points) {
  const max = Math.max(1, ...points.map((p) => p.n));
  const every = Math.ceil(points.length / 7);
  const cols = points
    .map((p, i) => {
      const h = p.n === 0 ? 2 : Math.max(6, Math.round((p.n / max) * 100));
      const label = i % every === 0 || i === points.length - 1 ? p.d.slice(5) : '';
      return `<div class="col" title="${escapeHtml(p.d)}: ${p.n} 条">
        <div class="track"><i style="height:${h}%" class="${p.n ? '' : 'zero'}"></i></div>
        <span>${label}</span>
      </div>`;
    })
    .join('');
  const total = points.reduce((a, p) => a + p.n, 0);
  return `<div class="trend">${cols}</div>
    <p class="foot">峰值 ${max} · 合计 ${total} 条 · ${escapeHtml(points[0]?.d ?? '')} → ${escapeHtml(
      points[points.length - 1]?.d ?? '',
    )}</p>`;
}

function barRows(ctx, items, keyLabel, valueOf, filterKey) {
  if (items.length === 0) return '<p class="empty">暂无数据</p>';
  const max = Math.max(1, ...items.map(valueOf));
  return items
    .map((it) => {
      const v = valueOf(it);
      const pct = Math.max(3, Math.round((v / max) * 100));
      const label = keyLabel(it);
      const href = filterKey ? link(ctx, { [filterKey]: label }) : null;
      const name = href ? `<a href="${href}">${escapeHtml(label || '(未知)')}</a>` : escapeHtml(label);
      return `<div class="bar-row"><span class="k">${name}</span>
        <span class="track"><i style="width:${pct}%"></i></span>
        <span class="v">${v}</span></div>`;
    })
    .join('');
}

function versionSpan(head) {
  const a = escapeHtml(head.first_ver ?? '-');
  const b = escapeHtml(head.last_ver ?? '-');
  return a === b ? a : `${a} → ${b}`;
}

function metrics(items) {
  return `<div class="metrics">${items
    .map(
      (m) =>
        `<div class="metric"><span class="n${m.small ? ' small' : ''}">${m.value}</span><span class="l">${escapeHtml(
          m.label,
        )}</span></div>`,
    )
    .join('')}</div>`;
}

function filterChips(ctx) {
  const parts = [];
  if (ctx.model) parts.push(`机型 <b>${escapeHtml(ctx.model)}</b>`);
  if (ctx.ver) parts.push(`版本 <b>${escapeHtml(ctx.ver)}</b>`);
  if (parts.length === 0) return '';
  return `<div class="chips">${parts.join(' · ')} <a href="${link(ctx, { model: '', ver: '' })}">清除</a></div>`;
}

async function renderOverview(ctx) {
  const w = whereClause(ctx);
  const total = await q1(ctx, `SELECT COUNT(*) AS n, COUNT(DISTINCT install) AS i FROM crashes${w.sql}`, w.params);
  const sigRows = await q(
    ctx,
    `SELECT sig, kind, COUNT(*) AS n, COUNT(DISTINCT install) AS people,
            MIN(at) AS first_at, MAX(at) AS last_at, MAX(app_ver) AS last_ver
       FROM crashes${w.sql} GROUP BY sig, kind ORDER BY people DESC, n DESC LIMIT 100`,
    w.params,
  );
  const models = await q(
    ctx,
    `SELECT model, sdk, COUNT(*) AS n FROM crashes${w.sql} GROUP BY model, sdk ORDER BY n DESC LIMIT 10`,
    w.params,
  );
  const vers = await q(
    ctx,
    `SELECT app_ver, COUNT(*) AS n FROM crashes${w.sql} GROUP BY app_ver ORDER BY n DESC LIMIT 8`,
    w.params,
  );
  const trendRows = await q(
    ctx,
    `SELECT date(received,'unixepoch') AS d, COUNT(*) AS n FROM crashes${w.sql} GROUP BY d ORDER BY d`,
    w.params,
  );

  const sigTable = sigRows
    .map(
      (r) => `<tr>
        <td><a class="sig" href="${link(ctx, { sig: r.sig })}">${kindChip(r.kind)}<code>${escapeHtml(
          r.sig.slice(0, 10),
        )}</code></a></td>
        <td class="num">${r.n}</td>
        <td class="num">${r.people}</td>
        <td class="mono">${escapeHtml(r.last_ver)}</td>
        <td class="dim">${fmtTime(r.first_at)} → ${fmtTime(r.last_at)}</td>
      </tr>`,
    )
    .join('');

  const body = `
    ${filterChips(ctx)}
    ${metrics([
      { value: total.n ?? 0, label: `上报 · 近 ${ctx.days} 天` },
      { value: total.i ?? 0, label: '受影响设备' },
      { value: sigRows.length, label: '崩溃签名' },
    ])}
    <section class="card">
      <h2>每日上报</h2>
      ${trendChart(fillTrend(trendRows, ctx.days))}
    </section>
    <section class="card">
      <h2>按签名聚合</h2>
      <div class="scroll"><table>
        <thead><tr><th>签名</th><th class="num">次数</th><th class="num">设备</th><th>末见版本</th><th>首末见到</th></tr></thead>
        <tbody>${sigTable || `<tr><td colspan="5"><p class="empty">近 ${ctx.days} 天没有崩溃上报</p></td></tr>`}</tbody>
      </table></div>
    </section>
    <div class="split">
      <section class="card">
        <h2>版本分布</h2>
        ${barRows(ctx, vers, (v) => v.app_ver, (v) => v.n, 'ver')}
      </section>
      <section class="card">
        <h2>机型分布</h2>
        ${barRows(ctx, models, (m) => m.model, (m) => m.n, 'model')}
      </section>
    </div>
    <p class="foot">点机型或版本可下钻 · 点签名查看原始样本 · 保留 180 天</p>`;
  return layout(`近 ${ctx.days} 天`, ctx, body);
}

async function renderSignature(ctx, sig) {
  const w = whereClause(ctx, ['sig = ?']);
  w.params.push(sig);
  const head = await q1(
    ctx,
    `SELECT COUNT(*) AS n, COUNT(DISTINCT install) AS people, MIN(at) AS first_at, MAX(at) AS last_at,
            MIN(app_ver) AS first_ver, MAX(app_ver) AS last_ver, MAX(kind) AS kind
       FROM crashes${w.sql}`,
    w.params,
  );
  const models = await q(
    ctx,
    `SELECT model, sdk, COUNT(*) AS n FROM crashes${w.sql} GROUP BY model, sdk ORDER BY n DESC LIMIT 10`,
    w.params,
  );
  const samples = await q(
    ctx,
    `SELECT received, app_ver, model, sdk, payload FROM crashes${w.sql} ORDER BY at DESC LIMIT 20`,
    w.params,
  );

  const sampleHtml = samples
    .map(
      (r, i) => `<details class="sample" ${i === 0 ? 'open' : ''}>
        <summary>${fmtTime(r.received)} ・ ${escapeHtml(r.app_ver)} ・ ${escapeHtml(r.model)} ・ SDK ${r.sdk}</summary>
        <pre>${escapeHtml(prettyPayload(r.payload))}</pre>
      </details>`,
    )
    .join('');

  const body = `
    <a class="back" href="${link(ctx, { sig: '' })}">← 返回聚合</a>
    <div class="hero">
      ${kindChip(head.kind ?? '')}
      <code class="full">${escapeHtml(sig)}</code>
    </div>
    ${metrics([
      { value: head.n ?? 0, label: `上报 · 近 ${ctx.days} 天` },
      { value: head.people ?? 0, label: '受影响设备' },
      { value: versionSpan(head), label: '版本跨度', small: true },
    ])}
    <section class="card">
      <h2>机型分布</h2>
      ${barRows(ctx, models, (m) => m.model, (m) => m.n, null)}
    </section>
    <section class="card">
      <h2>最近 ${samples.length} 条样本</h2>
      ${sampleHtml || '<p class="empty">无记录</p>'}
    </section>`;
  return layout(`签名 ${sig.slice(0, 10)}`, ctx, body);
}

function rangeNav(ctx) {
  return [7, 14, 30]
    .map(
      (d) =>
        `<a class="pill${ctx.days === d ? ' on' : ''}" href="?days=${d}${
          ctx.model ? '&model=' + encodeURIComponent(ctx.model) : ''
        }${ctx.ver ? '&ver=' + encodeURIComponent(ctx.ver) : ''}">${d} 天</a>`,
    )
    .join('');
}

function layout(title, ctx, body) {
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ReveriePaint 崩溃看板 · ${escapeHtml(title)}</title>
<style>
:root{
  --bg:#F2F2F7; --panel:#F7F7F9; --panel-hi:#EAEAEE; --text:#242426; --sub:#8A8A8E;
  --accent:#5A6E8A; --icon:#636366; --line:rgba(0,0,0,.07);
  --shadow:0 1px 2px rgba(0,0,0,.04), 0 12px 28px -18px rgba(20,20,30,.35);
}
@media (prefers-color-scheme: dark){
  :root{--bg:#141416; --panel:#1E1E22; --panel-hi:#2A2A2E; --text:#F5F5F7; --sub:#8E8E93;
        --accent:#8FA1B8; --icon:#98989D; --line:rgba(255,255,255,.08);
        --shadow:0 1px 2px rgba(0,0,0,.4), 0 16px 32px -22px rgba(0,0,0,.8);}
}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);
     font:14px/1.65 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC","Noto Sans CJK SC",sans-serif;
     -webkit-font-smoothing:antialiased}
.wrap{max-width:1000px;margin:0 auto;padding:28px 20px 64px}
.top{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:22px;flex-wrap:wrap}
.brand{display:flex;align-items:center;gap:10px;font-weight:650;letter-spacing:.2px}
.brand .mark{width:26px;height:26px;border-radius:9px;background:var(--accent);opacity:.9;
             display:inline-block;position:relative}
.brand .mark::after{content:"";position:absolute;inset:7px 7px auto auto;width:8px;height:8px;
                    border-radius:50%;background:var(--panel)}
.brand small{color:var(--sub);font-weight:500;letter-spacing:.4px}
.range{display:flex;gap:6px;background:var(--panel-hi);padding:4px;border-radius:999px}
.pill{padding:5px 12px;border-radius:999px;font-size:12.5px;color:var(--sub);text-decoration:none}
.pill.on{background:var(--panel);color:var(--text);font-weight:600;box-shadow:0 1px 2px rgba(0,0,0,.08)}
.metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-bottom:16px}
.metric{background:var(--panel);border-radius:16px;padding:14px 16px;box-shadow:var(--shadow)}
.metric .n.small{font-size:17px;letter-spacing:0}
.metric .n{display:block;font-size:24px;font-weight:650;letter-spacing:-.4px;font-variant-numeric:tabular-nums}
.metric .l{color:var(--sub);font-size:12px}
.card{background:var(--panel);border-radius:18px;padding:16px 18px;box-shadow:var(--shadow);margin-bottom:14px}
.card h2{margin:0 0 12px;font-size:13px;font-weight:650;color:var(--sub);letter-spacing:.6px;text-transform:uppercase}
.split{display:grid;grid-template-columns:1fr 1fr;gap:14px}
.scroll{overflow:auto}
table{width:100%;border-collapse:collapse}
th,td{padding:9px 8px;text-align:left;border-bottom:1px solid var(--line);vertical-align:middle}
th{font-size:11.5px;font-weight:600;color:var(--sub);letter-spacing:.5px;text-transform:uppercase}
tbody tr:last-child td{border-bottom:none}
td.num,th.num{text-align:right;font-variant-numeric:tabular-nums}
td.dim{color:var(--sub);font-size:12.5px;white-space:nowrap}
td.mono{font-variant-numeric:tabular-nums;font-size:12.5px;color:var(--sub)}
a{color:var(--accent);text-decoration:none}
a:hover{text-decoration:underline}
a.sig{display:inline-flex;align-items:center;gap:8px}
code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;color:var(--sub)}
.chip{display:inline-flex;align-items:center;gap:6px;background:var(--panel-hi);border-radius:999px;
      padding:2px 9px 2px 7px;font-size:11.5px;color:var(--text);font-weight:600;letter-spacing:.3px}
.chip i{width:6px;height:6px;border-radius:50%;display:inline-block}
.trend{display:flex;align-items:flex-end;gap:6px;height:130px;padding-top:6px;
       border-bottom:1px solid var(--line)}
.trend .col{flex:1;display:flex;flex-direction:column;justify-content:flex-end;height:100%;gap:6px}
.trend .track{flex:1;display:flex;align-items:flex-end;border-radius:6px 6px 0 0;overflow:hidden}
.trend .track i{display:block;width:100%;background:var(--accent);border-radius:5px 5px 0 0;min-height:2px}
.trend .track i.zero{background:var(--panel-hi)}
.trend .col span{font-size:10.5px;color:var(--sub);text-align:center;height:12px}
.bar-row{display:grid;grid-template-columns:minmax(64px,38%) 1fr auto;gap:10px;align-items:center;padding:5px 0}
.bar-row .k{font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.bar-row .track{height:7px;background:var(--panel-hi);border-radius:999px;overflow:hidden}
.bar-row .track i{display:block;height:100%;background:var(--accent);opacity:.85;border-radius:999px}
.bar-row .v{font-variant-numeric:tabular-nums;font-size:12.5px;color:var(--sub);min-width:2ch;text-align:right}
.foot{color:var(--sub);font-size:12px;margin:10px 0 0}
.empty{color:var(--sub);text-align:center;padding:26px 0;margin:0}
.chips{background:var(--panel);border-radius:14px;padding:9px 14px;margin-bottom:14px;font-size:13px;box-shadow:var(--shadow)}
.chips a{margin-left:8px}
.back{display:inline-block;margin-bottom:14px;font-size:13px}
.hero{display:flex;align-items:center;gap:12px;margin-bottom:14px;flex-wrap:wrap}
.hero .full{font-size:12.5px;background:var(--panel);padding:5px 10px;border-radius:8px;border:1px solid var(--line)}
details.sample{background:var(--panel);border-radius:14px;box-shadow:var(--shadow);margin-bottom:10px;overflow:hidden}
details.sample summary{cursor:pointer;padding:11px 14px;font-size:12.5px;color:var(--sub);list-style:none}
details.sample summary::-webkit-details-marker{display:none}
details.sample summary::before{content:"▸ ";color:var(--icon)}
details.sample[open] summary::before{content:"▾ "}
details.sample pre{margin:0;padding:0 14px 14px;font-size:11.5px;line-height:1.55;overflow:auto;max-height:46vh;
                   font-family:ui-monospace,SFMono-Regular,Menlo,monospace;color:var(--text);opacity:.86}
@media (max-width:760px){
  .wrap{padding:20px 14px 48px}
  .metrics{grid-template-columns:1fr 1fr}
  .split{grid-template-columns:1fr}
  td.dim{font-size:11.5px}
}
</style></head><body><div class="wrap">
<header class="top">
  <div class="brand"><span class="mark"></span>ReveriePaint <small>崩溃看板</small></div>
  <nav class="range">${rangeNav(ctx)}</nav>
</header>
${body}
</div></body></html>`;
}

