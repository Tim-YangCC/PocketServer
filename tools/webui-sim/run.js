#!/usr/bin/env node
/*
 * 网页端「传输任务」仿真测试（零依赖，只用 Node 内置模块）
 *
 * ## 为什么需要它
 * 本项目的主通道是电脑浏览器，而 [WebUi] 里的整页逻辑是**一个 Kotlin 原始字符串里的 JS**：
 * 编译器只能保证这个字符串是合法 Kotlin，一个字都校验不了里面的 JS ——
 * 语法错、函数名写错、id 对不上，全都要等用户在浏览器里点出来。
 * 这一轮加的"暂停 / 开始 / 取消"又恰好是状态机最密的地方（服务端意图、断点续传、
 * 本地行与服务端行的归并），靠肉眼读代码是读不出问题的。
 *
 * 所以这里做一件很直接的事：**把那段 JS 原样抠出来跑**。
 * 抠的是 Kotlin 源文件里的字符串，不是副本 —— 改了页面就一定会被这个脚本跑到。
 *
 * ## 怎么跑
 *   node tools/webui-sim/run.js
 * 退出码 0 表示全部通过。
 *
 * ## 它测什么（都是"用户真会碰到"的路径）
 *   1. 四路并行传到一半按暂停 → 状态不落成"失败"、进度留在原地、按钮变成「开始」
 *   2. 按开始 → 只补传没传完的那几路（已完成的路不重传，断点续传真的生效）
 *   3. 按取消 → 在飞的分片收到 410 后收手，整条上传记为"已取消"而不是"失败"
 *   4. 概览能看见**别的客户端**发起的传输（手机推给电视、另一台电脑在下）
 *   5. 只读会话不给上传控制按钮，但下载仍可取消
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..', '..');
const WEBUI = path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'pocketserver', 'net', 'http', 'WebUi.kt');

// ==================== 1. 从 Kotlin 源里抠出真正的页面 ====================

/** 取 `marker` 之后那个 Kotlin 原始字符串的内容（到行首的 """ 为止）。 */
function rawStringAfter(src, marker, from) {
  const i = src.indexOf(marker, from || 0);
  if (i < 0) throw new Error('找不到标记：' + marker);
  const start = i + marker.length;
  const end = src.indexOf('\n"""', start);
  if (end < 0) throw new Error('原始字符串没有闭合：' + marker);
  return src.slice(start, end);
}

function filePageHtml(src) {
  const f = src.indexOf('fun filePage(');
  if (f < 0) throw new Error('找不到 filePage()');
  return rawStringAfter(src, '): String = """', f);
}

/**
 * 把 Kotlin 的插值替换成字面量。[canWrite] 对应页面上的 CAN_WRITE。
 *
 * 替换完还要检查一遍有没有漏网的 `$` —— 漏一个就说明这段 HTML 不是我们以为的那一份，
 * 后面所有断言都会建立在错的前提上，宁可直接报错。
 */
function render(src, opts) {
  const o = opts || {};
  let html = filePageHtml(src);
  html = html.split('${esc(deviceName)}').join('仿真手机');
  html = html.split('$BASE_CSS').join('');           // 样式对逻辑没有影响
  html = html.split('$canWrite').join(o.canWrite === false ? 'false' : 'true');
  html = html.split('$canDelete').join('true');
  html = html.split('$canRename').join('true');
  const left = html.match(/\$[A-Za-z{][^\s<]*/g);
  if (left) throw new Error('还有没替换掉的 Kotlin 插值：' + left.slice(0, 5).join(', '));
  return html;
}

// ==================== 2. 最小 DOM ====================

class El {
  constructor(tag, cls, text) {
    this.tagName = tag;
    this.className = cls || '';
    this.children = [];
    this.parentNode = null;
    this.style = {};
    this.hidden = false;
    this._text = text === undefined || text === null ? '' : String(text);
    this._listeners = {};
    this.scrollTop = 0;
    this.scrollHeight = 0;
  }
  get textContent() { return this._text; }
  set textContent(v) { this._text = v === undefined || v === null ? '' : String(v); }
  set innerHTML(v) { if (v === '') this.children = []; }
  get innerHTML() { return ''; }
  appendChild(c) { this.children.push(c); c.parentNode = this; return c; }
  removeChild(c) {
    const i = this.children.indexOf(c);
    if (i >= 0) this.children.splice(i, 1);
    c.parentNode = null;
    return c;
  }
  get childElementCount() { return this.children.length; }
  get firstChild() { return this.children.length ? this.children[0] : null; }
  addEventListener(t, fn) { (this._listeners[t] = this._listeners[t] || []).push(fn); }
  /* 测试用：把这一支下的所有文字拼出来，便于断言"这一行显示了什么" */
  text() {
    let out = this._text;
    for (const c of this.children) out += ' ' + c.text();
    return out.replace(/\s+/g, ' ').trim();
  }
  /* 测试用：按按钮文字点击 */
  click(label) {
    for (const c of this.children) {
      if (c.tagName === 'button' && c._text === label) { c.onclick(); return true; }
      if (c.click(label)) return true;
    }
    return false;
  }
  /* 测试用：这一支下的按钮文字 */
  buttons() {
    const out = [];
    for (const c of this.children) {
      if (c.tagName === 'button') out.push(c._text);
      out.push(...c.buttons());
    }
    return out;
  }
}

function makeDom(html) {
  const byIdMap = {};
  const tagRe = /<(\w+)([^>]*)\bid="([^"]+)"([^>]*)>/g;
  let m;
  while ((m = tagRe.exec(html)) !== null) {
    const attrs = m[2] + m[4];
    const node = new El(m[1]);
    const cls = /class="([^"]*)"/.exec(attrs);
    if (cls) node.className = cls[1];
    node.hidden = /\bhidden\b/.test(attrs);
    byIdMap[m[3]] = node;
  }
  const document = {
    getElementById: (id) => byIdMap[id] || null,
    createElement: (tag) => new El(tag)
  };
  // 表单那两个元素后面会被绑事件，缺了就会在加载时报错
  for (const id of ['picker', 'folderPicker', 'browse']) if (!byIdMap[id]) byIdMap[id] = new El('input');
  return { byId: byIdMap, document };
}

// ==================== 3. 假服务端（照搬真服务端的语义） ====================

const MB = 1024 * 1024;

class FakeServer {
  constructor() {
    this.gates = new Map();      // path -> 控制闸门（含意图）
    this.sessions = new Map();   // path -> 续传会话
    this.log = [];               // 收到的控制指令，用来断言"真的下发了"
  }

  /* 别的客户端（手机、另一台电脑）发起的传输：这是网页要能**看见**的那一类 */
  external(path, name, direction, total, state) {
    const g = { path, name, direction, total, transferred: 0, state: state || 'RUNNING' };
    this.gates.set(path, g);
    return g;
  }

  reserve(body) {
    const target = body.dir ? body.dir + '/' + body.name : body.name;
    const lanes = body.lanes || 1;
    let session = this.sessions.get(target);
    let resumed = false;
    if (session && session.size === body.size && session.lanes === lanes) {
      resumed = session.written.some((w) => w > 0);
    }
    if (!session || session.size !== body.size || session.lanes !== lanes) {
      const chunk = Math.floor(body.size / lanes);
      const starts = [];
      for (let i = 0; i < lanes; i++) starts.push(i * chunk);
      session = { size: body.size, lanes, starts, written: new Array(lanes).fill(0) };
      this.sessions.set(target, session);
    }
    this.gates.set(target, { path: target, name: body.name, direction: 'UPLOAD', total: body.size, transferred: 0, state: 'RUNNING' });
    return { status: 200, body: { ok: true, path: target, renamed: false, resume: resumed, size: session.size, lanes: session.lanes, done: this.ends(session) } };
  }

  ends(s) { return s.starts.map((st, i) => st + s.written[i]); }
  received(s) { return s.written.reduce((a, b) => a + b, 0); }

  /** PUT /api/fs/file —— 与真服务端一致：先看闸门意图，再落盘。 */
  put(q, blob) {
    const g = this.gates.get(q.path);
    if (g && g.state === 'PAUSED') return { status: 423, body: { ok: false, paused: true, path: q.path, error: '这条上传已被暂停，等待继续' } };
    if (g && g.state === 'CANCELED') return { status: 410, body: { ok: false, canceled: true, path: q.path, error: '这条上传已被取消' } };

    const s = this.sessions.get(q.path);
    if (!s) return { status: 500, body: { ok: false, error: '没有会话' } };
    const lane = q.lane || 0;
    const start = s.starts[lane];
    const abs = (q.offset == null ? start : q.offset) + blob.size;
    s.written[lane] = Math.max(s.written[lane], abs - start);
    const done = this.received(s) >= s.size;
    if (g) g.transferred = this.received(s);
    if (done) this.sessions.delete(q.path);
    return { status: 201, body: { ok: true, path: q.path, complete: done, overallReceived: this.received(s) } };
  }

  uploadStatus(p) {
    const s = this.sessions.get(p);
    if (!s) return { status: 200, body: { ok: true, resumable: false } };
    return { status: 200, body: { ok: true, resumable: true, size: s.size, lanes: s.lanes, done: this.ends(s), received: this.received(s), complete: false } };
  }

  tasks() {
    const items = [];
    for (const g of this.gates.values()) {
      items.push({
        id: 0, path: g.path, name: g.name, direction: g.direction, state: g.state,
        transferred: g.transferred || 0, total: g.total,
        percent: g.total ? Math.min(100, Math.round((g.transferred || 0) / g.total * 100)) : 0
      });
    }
    return { status: 200, body: { ok: true, count: items.length, items } };
  }

  control(action, p) {
    this.log.push(action + ' ' + p);
    const g = this.gates.get(p);
    if (!g) return { status: 409, body: { ok: false, message: '这条传输已经不在了，没有可控制的对象' } };
    if (action === 'pause') g.state = 'PAUSED';
    else if (action === 'resume') g.state = 'RUNNING';
    else if (action === 'cancel') { g.state = 'CANCELED'; this.sessions.delete(p); }
    return { status: 200, body: { ok: true, message: '已' + (action === 'pause' ? '暂停' : action === 'resume' ? '继续' : '取消') + '「' + g.name + '」', path: p } };
  }
}

// ==================== 4. 假网络（XHR 与 fetch） ====================

function parseUrl(url) {
  const qIdx = url.indexOf('?');
  const out = { path: '', offset: null, lane: 0 };
  if (qIdx < 0) return out;
  for (const part of url.slice(qIdx + 1).split('&')) {
    const eq = part.indexOf('=');
    const k = part.slice(0, eq);
    const v = decodeURIComponent(part.slice(eq + 1).replace(/\+/g, ' '));
    if (k === 'path') out.path = v;
    else if (k === 'offset') out.offset = Number(v);
    else if (k === 'lane') out.lane = Number(v);
  }
  return out;
}

function makeNet(server) {
  const net = {
    pending: [],   // 已经发出、还没"到达"服务端的 PUT
    sent: [],      // 所有 PUT 的历史（用来断言"哪几路被重传了"）
    /* 让前 [n] 个在飞的请求到达服务端。默认全部。 */
    flush(n) {
      const take = n === undefined ? net.pending.length : Math.min(n, net.pending.length);
      const batch = net.pending.splice(0, take);
      for (const x of batch) {
        // 先报一次 in-flight 进度：真实浏览器就是这样给的，
        // 页面里"传了多少"这条路径必须能接住它。
        if (x.upload && x.upload.onprogress) x.upload.onprogress({ lengthComputable: true, loaded: x._blobSize });
        const req = parseUrl(x._url);
        const res = server.put(req, { size: x._blobSize });
        x.status = res.status;
        if (x.onload) x.onload();
      }
      return batch.length;
    }
  };

  function XHR() {
    this.upload = {};
    this.status = 0;
    this._url = '';
    this._blobSize = 0;
    this._body = null;
    this.onload = null;
    this.onerror = null;
    this.onabort = null;
  }
  XHR.prototype.open = function (method, url) { this._url = url; };
  XHR.prototype.setRequestHeader = function () {};
  XHR.prototype.send = function (blob) {
    this._body = blob;
    this._blobSize = blob ? blob.size : 0;
    net.sent.push(parseUrl(this._url));
    net.pending.push(this);
  };

  function fetchImpl(url, opts) {
    const body = opts && opts.body ? JSON.parse(opts.body) : null;
    let res;
    if (url.indexOf('/api/fs/reserve') === 0) res = server.reserve(body);
    else if (url.indexOf('/api/fs/upload-status') === 0) res = server.uploadStatus(parseUrl(url).path);
    else if (url.indexOf('/api/fs/tasks') === 0) res = server.tasks();
    else if (url.indexOf('/api/fs/control') === 0) res = server.control(body.action, body.path);
    else if (url.indexOf('/api/fs/mkdir') === 0) res = { status: 200, body: { ok: true } };
    else if (url.indexOf('/api/fs/list') === 0) res = { status: 200, body: { ok: true, path: '', items: [] } };
    else if (url.indexOf('/api/stats') === 0) res = { status: 200, body: { ok: true, tx: '0 B', rx: '0 B' } };
    else res = { status: 404, body: { ok: false, error: '未实现：' + url } };
    return Promise.resolve({
      status: res.status,
      ok: res.status >= 200 && res.status < 300,
      json: () => Promise.resolve(res.body)
    });
  }

  return { net, XHR, fetchImpl };
}

// ==================== 5. 组装一个页面实例 ====================

async function build(opts) {
  const o = opts || {};
  const src = fs.readFileSync(WEBUI, 'utf8');
  const html = render(src, { canWrite: o.canWrite });
  const dom = makeDom(html);
  const server = new FakeServer();
  const { net, XHR, fetchImpl } = makeNet(server);
  const intervals = [];

  const sandbox = {
    document: dom.document,
    window: { addEventListener: () => {} },
    location: { href: '' },
    fetch: fetchImpl,
    XMLHttpRequest: XHR,
    Promise,
    console,
    confirm: () => true,
    prompt: () => null,
    alert: () => {},
    setTimeout: () => 0,
    clearTimeout: () => {},
    setInterval: (fn) => { intervals.push(fn); return intervals.length; },
    clearInterval: () => {},
    encodeURIComponent,
    decodeURIComponent,
    JSON,
    Math,
    Date,
    parseInt,
    parseFloat,
    isNaN,
    Object,
    Array,
    String,
    Number,
    Boolean
  };
  vm.createContext(sandbox);
  vm.runInContext(html.slice(html.lastIndexOf('<script>') + 8, html.lastIndexOf('</script>')), sandbox, { filename: 'WebUi.filePage.js' });

  const p = {
    ctx: sandbox,
    dom,
    server,
    net,
    intervals,
    /* 等所有微任务跑完 */
    async settle(rounds) {
      for (let i = 0; i < (rounds || 12); i++) await new Promise((r) => setImmediate(r));
    },
    /* 让页面像平时那样轮询一次服务端快照 */
    async poll() {
      sandbox.pollTasks();
      await this.settle();
    },
    /* 造一个假文件：只需要 size 与 slice，页面从不真的读内容 */
    file(name, size) {
      return { name, size, slice: (a, b) => ({ size: (b === undefined ? size : b) - a }) };
    },
    rowOf(p) { return sandbox.rowByPath[p]; },
    /* 把概览条读成结构化数据 */
    overview() {
      const box = dom.byId['oview'];
      const cells = box.children.filter((c) => c.className.indexOf('ostat') === 0);
      const out = {};
      for (const c of cells) out[c.children[0].textContent] = Number(c.children[1].textContent);
      const note = box.children.filter((c) => c.className === 'oview-note')[0];
      out.note = note ? note.textContent : '';
      return out;
    }
  };

  /*
   * 页面加载时自己会立刻轮询一次（这是刻意的：不能让用户等 2 秒才看到已有的传输）。
   * 这里必须等那一轮跑完再交出去 —— 否则 pollBusy 还是 true，
   * 测试里紧接着调的那次 poll() 会被"上一轮没回来就跳过"这条规则挡掉，
   * 断言就会拿到一份过期的快照。
   */
  await p.settle();
  return p;
}

/* 一行任务上的按钮文字。按钮挂在行的 DOM 上，句柄本身只存状态。 */
function btn(row) { return row.el.buttons(); }

// ==================== 6. 断言与用例 ====================

let passed = 0;
const failures = [];

function ok(cond, msg) {
  if (cond) { passed++; return; }
  failures.push(msg);
  console.log('  ✗ ' + msg);
}

function eq(actual, expected, msg) {
  ok(actual === expected, msg + '（期望 ' + JSON.stringify(expected) + '，实际 ' + JSON.stringify(actual) + '）');
}

async function case1_pauseResume() {
  console.log('\n[用例 1] 四路并行传到一半暂停，再开始 —— 断点续传');
  const p = await build();
  const size = 256 * MB;
  const f = p.file('电影.mkv', size);

  p.ctx.runPlan({ files: [{ file: f, dir: '' }], dirs: [] });
  await p.settle();
  const target = '电影.mkv';

  eq(p.net.pending.length, 4, '256MB 的文件应该开出 4 路');
  eq(p.server.gates.get(target).direction, 'UPLOAD', '服务端登记的是上传方向');

  // 先让两路传完，另外两路还挂在半空中
  p.net.flush(2);
  await p.settle();
  eq(p.server.received(p.server.sessions.get(target)), size / 2, '两路完成后服务端已收到一半');

  await p.poll();
  const row = p.rowOf(target);
  ok(!!row, '轮询之后应该有一行任务');
  eq(p.overview()['进行中'], 1, '概览：进行中 1');
  eq(btn(row).join(','), '暂停,取消', '进行中给的是「暂停 / 取消」');

  // ---- 按暂停 ----
  ok(row.el.click('暂停'), '点得到「暂停」按钮');
  await p.settle();
  eq(p.server.gates.get(target).state, 'PAUSED', '服务端闸门已置为暂停');

  p.net.flush();   // 剩下两路此刻到达服务端，会收到 423
  await p.settle();
  await p.poll();

  eq(p.ctx.stat.done, 0, '暂停不是完成');
  eq(p.ctx.stat.failed, 0, '暂停**不能**被算成失败');
  eq(p.ctx.stat.canceled, 0, '暂停不是取消');
  eq(p.rowOf(target).state, 'paused', '那一行显示为已暂停');
  ok(!!p.ctx.stopped[target], '这一行被登记为"等继续"');
  const ovPaused = p.overview();
  eq(ovPaused['已暂停'], 1, '概览：已暂停 1');
  eq(ovPaused['进行中'], 0, '概览：进行中 0');
  ok(ovPaused.note.indexOf('还剩') === 0, '概览说明是"还剩 X 没传完"');
  eq(btn(p.rowOf(target)).join(','), '开始,取消', '暂停后给的是「开始 / 取消」');

  // ---- 按开始：只补传没传完的那两路 ----
  const before = p.net.sent.length;
  const row2 = p.rowOf(target);
  ok(row2.el.click('开始'), '点得到「开始」按钮');
  await p.settle();
  eq(p.server.gates.get(target).state, 'RUNNING', '服务端闸门已恢复运行');
  const resent = p.net.sent.slice(before);
  eq(resent.length, 2, '只重发没传完的 2 路（已经传完的两路不重传）');
  eq(resent[0].lane, 2, '重发的是第 3 路');
  eq(resent[1].lane, 3, '重发的是第 4 路');
  eq(resent[0].offset, 2 * Math.floor(size / 4), '第 3 路从它自己的起点接着发');

  p.net.flush();
  await p.settle();
  await p.poll();
  eq(p.ctx.stat.done, 1, '续传完成后算 1 个成功');
  eq(p.ctx.stat.failed, 0, '没有任何失败');
  eq(p.server.received(p.server.sessions.get(target) || { written: [size] }), size, '服务端收到了完整文件');
  ok(p.rowOf(target).state === 'done', '那一行最终是完成态');
  ok(!p.ctx.stopped[target], '不再有"等继续"的残留');
  ok(p.intervals.length >= 1, '页面注册了轮询定时器');
}

async function case2_cancel() {
  console.log('\n[用例 2] 传到一半取消 —— 记「已取消」，不记「失败」');
  const p = await build();
  const size = 128 * MB;
  const f = p.file('大包.zip', size);

  p.ctx.runPlan({ files: [{ file: f, dir: '' }], dirs: [] });
  await p.settle();
  const target = '大包.zip';
  eq(p.net.pending.length, 2, '128MB 开 2 路');

  await p.poll();
  const row = p.rowOf(target);
  ok(row.el.click('取消'), '点得到「取消」按钮');
  await p.settle();
  eq(p.server.gates.get(target).state, 'CANCELED', '服务端闸门已置为取消');
  ok(p.server.log.indexOf('cancel ' + target) >= 0, '取消指令真的发给了服务端');

  p.net.flush();   // 在飞的分片收到 410
  await p.settle();
  await p.poll();

  eq(p.ctx.stat.canceled, 1, '算 1 个已取消');
  eq(p.ctx.stat.failed, 0, '取消**不是**失败');
  eq(p.ctx.stat.done, 0, '也没有完成');
  eq(p.rowOf(target).state, 'canceled', '那一行显示为已取消');
  eq(btn(p.rowOf(target)).length, 0, '取消之后不再有按钮（终态）');
  ok(p.overview().note.indexOf('已取消 1 个') >= 0, '概览里说了"已取消 1 个"');
  ok(!p.ctx.stopped[target], '取消之后不会留在"等继续"里');
}

async function case3_external() {
  console.log('\n[用例 3] 概览要能看见别的客户端发起的传输');
  const p = await build();
  p.server.external('相册/1.jpg', '1.jpg', 'DOWNLOAD', 40 * MB, 'RUNNING');
  p.server.external('片子.mp4', '片子.mp4', 'UPLOAD', 800 * MB, 'PAUSED');

  await p.poll();
  const ov = p.overview();
  eq(ov['进行中'], 1, '别的客户端的下载计入进行中');
  eq(ov['已暂停'], 1, '别的客户端的暂停计入已暂停');
  ok(ov.note.indexOf('840 MB') >= 0 || ov.note.indexOf('840.0 MB') >= 0, '还剩的字节数是两条之和');

  ok(p.dom.byId['tasks'].hidden === false, '有传输时面板自动亮出来');
  const dl = p.rowOf('相册/1.jpg');
  ok(!!dl, '为外部传输建了一行');
  ok(dl.el.text().indexOf('下载') >= 0, '下载方向有标识');
  eq(btn(dl).join(','), '取消', '下载只给「取消」：浏览器这一侧没法挂起再续');

  ok(dl.el.click('取消'), '能取消别的客户端发起的下载');
  await p.settle();
  eq(p.server.gates.get('相册/1.jpg').state, 'CANCELED', '服务端确实收到了取消');
}

async function case4_readonly() {
  console.log('\n[用例 4] 只读会话：不给上传控制按钮，但下载仍可取消');
  const p = await build({ canWrite: false });
  p.server.external('共享/a.bin', 'a.bin', 'UPLOAD', 10 * MB, 'RUNNING');
  p.server.external('共享/b.bin', 'b.bin', 'DOWNLOAD', 10 * MB, 'RUNNING');

  await p.poll();
  eq(btn(p.rowOf('共享/a.bin')).length, 0, '只读时上传行没有按钮');
  eq(btn(p.rowOf('共享/b.bin')).join(','), '取消', '只读时下载行仍可取消');
  ok(p.dom.byId['thint'].textContent.indexOf('只读') >= 0, '页面上写明了只读限制');
}

async function main() {
  console.log('WebUi 仿真测试（直接跑 Kotlin 源里的那段 JS）');
  for (const c of [case1_pauseResume, case2_cancel, case3_external, case4_readonly]) {
    try {
      await c();
    } catch (e) {
      failures.push(c.name + ' 抛异常：' + (e && e.message));
      console.log('  ✗ ' + c.name + ' 抛异常：' + (e && e.stack));
    }
  }
  console.log('\n通过 ' + passed + ' 项断言' + (failures.length ? '，失败 ' + failures.length + ' 项' : '，全部通过'));
  if (failures.length) {
    for (const f of failures) console.log(' - ' + f);
    process.exit(1);
  }
}

main();
