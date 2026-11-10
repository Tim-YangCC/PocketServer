/*
 * 网页端真跑验证。
 *
 * 这一段 JS 写在 Kotlin 原始字符串里，编译期完全不受检查，而它又是用户唯一
 * 直接接触的界面。所以这里用一个"假后端"（Playwright 路由拦截）把页面真正
 * 跑起来，验证四件事：
 *   1. 目录列表能渲染，且文件名里的 HTML 不会被执行（注入防护）
 *   2. 服务端返回 ok:false 时，界面**不能**显示成"空文件夹"
 *   3. 拖拽落到 window 上也能被接管（这正是"拖 html 被浏览器打开"的修复点）
 *   4. 文件夹上传保留层级；目录读取超过 100 条不会丢文件
 */
const fs = require('fs');
const { chromium } = require('playwright');

/*
 * 页面**现场**从 Kotlin 源渲染，而不是读一份预先导出的 HTML。
 * 读导出件有一个致命的失效方式：改了 WebUi.kt 却忘了重新导出，E2E 会全部通过 ——
 * 它测的是上一版的页面，而且不会有任何提示。
 */
const { renderFilePage } = require('./render');
const HTML = renderFilePage();
const ORIGIN = 'http://localhost:9999';

let pass = 0;
let fail = 0;
function check(name, ok, extra) {
  if (ok) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (extra ? '  -> ' + extra : '')); }
}

/** 轮询直到条件成立，最多等 15 秒。比裸 waitForTimeout 可靠。 */
async function waitFor(cond) {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    if (cond()) return true;
    await new Promise(r => setTimeout(r, 120));
  }
  return false;
}

const XSS_NAME = '<img src=x onerror="window.__xss=1">.txt';

function itemsFor(mode) {
  // degraded = 共享根退到了应用私有目录，那里本来就没有用户文件（这正是真实现象）
  if (mode === 'degraded') return [];
  return [
    { name: 'DCIM', dir: true, path: 'DCIM', size: 0 },
    { name: 'Download', dir: true, path: 'Download', size: 0 },
    { name: XSS_NAME, dir: false, path: 'x.txt', size: 12 },
    { name: '报告.pdf', dir: false, path: '报告.pdf', size: 1258291 }
  ];
}

(async () => {
  // 不依赖 playwright 自带的浏览器下载（本机缓存里没有 1.49.1 对应的修订版），
  // 直接用系统安装的 Chrome。数据源是本机真实浏览器，比内置 chromium 更有说服力。
  const chromePath = process.env.PS_CHROME ||
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
  const browser = await chromium.launch({ executablePath: chromePath, headless: true });
  const page = await browser.newPage();

  const consoleErrors = [];
  const failedRequests = [];
  // 浏览器会把"HTTP 4xx 响应"也打成 console error，那是我自己 mock 出来的预期行为。
  // 只关心真正的脚本异常：pageerror 与非资源类的 console error。
  const isNoise = t => /Failed to load resource/i.test(t);
  page.on('console', m => { if (m.type() === 'error' && !isNoise(m.text())) consoleErrors.push(m.text()); });
  page.on('pageerror', e => consoleErrors.push('pageerror: ' + e.message));
  page.on('requestfailed', r => failedRequests.push(r.url() + '  ' + (r.failure() || {}).errorText));

  let mode = 'normal';
  const calls = [];
  // 断点续传用的服务端状态。默认"不可续传"，这样既有用例的行为完全不受影响。
  let resumeStatus = { ok: true, resumable: false };
  let put409 = false;    // 模拟 SAF 通道：凡是带 offset 的写一律拒绝
  let putFailOnce = 0;   // 模拟"网络断了一次"

  // ---- 传输控制（暂停 / 继续 / 取消）用的服务端状态 ----
  // gateState 就是服务端那条"控制闸门"的意图。网页按下暂停之后，下一个 PUT 会收到 423；
  // 取消之后收到 410。这两个状态码是网页端把"用户意图"与"网络故障"分开的唯一依据 ——
  // 分不开的话，用户主动按的暂停会被界面报成"失败"。
  let gateState = 'RUNNING';
  let controlCalls = [];   // 收到的控制指令，用来断言"按钮真的把意图送到了服务端"
  let liveTask = null;     // /api/fs/tasks 里那一条在传的任务
  let session = null;      // 续传会话：每一路已经写到哪了
  let parkPuts = false;    // 把 PUT 挂住不发响应 —— 用来精确摆出"有几个分片还在飞"的时序
  const parked = [];
  let useSession = false;  // 让 upload-status 按会话回答（而不是按 resumeStatus 这个固定值）

  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    const p = url.pathname;
    const method = route.request().method();

    if (p === '/favicon.ico') return route.fulfill({ status: 204, body: '' });

    if (p === '/' || p === '/index.html') {
      return route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: HTML });
    }

    const body = route.request().postData() || '';
    calls.push({ method, path: p, search: url.search, body });

    const json = (obj, status = 200) =>
      route.fulfill({ status, contentType: 'application/json; charset=utf-8', body: JSON.stringify(obj) });

    if (p === '/api/fs/list') {
      if (mode === 'error') {
        return json({
          ok: false,
          error: '手机端还没有拿到存储访问权限，暂时读不到文件',
          rootLabel: '应用内置空间',
          rootPath: '/storage/emulated/0/Android/data/com.pocketserver/files',
          fullStorage: false,
          storageMode: 'direct',
          notice: '当前只共享了应用自己的空间，请在手机上授予「所有文件访问权限」。'
        }, 403);
      }
      const degraded = mode === 'degraded';
      return json({
        ok: true, path: '', parent: '', writable: true, canDelete: true, canRename: true, free: 1073741824,
        rootLabel: degraded ? '应用内置空间' : '手机内部存储',
        rootPath: degraded ? '/storage/emulated/0/Android/data/com.pocketserver/files' : '/storage/emulated/0',
        fullStorage: !degraded,
        storageMode: 'direct',
        notice: degraded ? '当前只共享了应用自己的空间，手机里的照片、下载都在别的位置。' : undefined,
        items: itemsFor(mode)
      });
    }

    if (p === '/api/fs/reserve') {
      let j = {};
      try { j = JSON.parse(body); } catch (e) { /* 忽略 */ }
      const name = (j.dir ? j.dir + '/' : '') + j.name;
      if (!useSession) return json({ ok: true, path: name, renamed: false });
      const lanes = j.lanes || 1;
      const hit = session && session.path === name && session.size === j.size;
      if (!hit) {
        sessionStart(name, j.size || 0, lanes);
        // 新路径 = 新闸门，意图从"运行中"开始（真服务端就是在 register 时这样建的）
        gateState = 'RUNNING';
      }
      liveTask = { path: name, name: j.name, total: session.size, transferred: 0 };
      return json({
        ok: true, path: name, renamed: false, resume: !!hit,
        size: session.size, lanes: session.lanes, done: doneList()
      });
    }
    if (p === '/api/fs/upload-status') {
      if (useSession && session) {
        return json({
          ok: true, resumable: true, size: session.size, lanes: session.lanes,
          done: doneList(), received: receivedBytes(), complete: false
        });
      }
      return json(resumeStatus);
    }
    if (p === '/api/fs/file') {
      const hasOffset = url.search.indexOf('offset=') >= 0;
      const q = new URLSearchParams(url.search);
      const lane = Number(q.get('lane') || 0);
      const off = q.has('offset') ? Number(q.get('offset'))
        : (session ? session.starts[lane] : 0);
      const buf = route.request().postDataBuffer();
      const len = buf ? buf.length : 0;
      if (parkPuts) { parked.push({ route, lane, offset: off, len, path: q.get('path') }); return; }
      if (gateState === 'PAUSED') return json({ ok: false, paused: true, path: q.get('path'), error: '这条上传已被暂停，等待继续' }, 423);
      if (gateState === 'CANCELED') return json({ ok: false, canceled: true, path: q.get('path'), error: '这条上传已被取消' }, 410);
      if (putFailOnce > 0) { putFailOnce--; return json({ ok: false, error: '模拟网络中断' }, 500); }
      if (put409 && hasOffset) return json({ ok: false, error: '该存储模式不支持按位点写入' }, 409);
      return route.fulfill({ status: 201, contentType: 'application/json', body: '{"ok":true}' });
    }
    if (p === '/api/fs/tasks') {
      const items = [];
      if (liveTask) {
        items.push({
          id: 0, path: liveTask.path, name: liveTask.name, direction: 'UPLOAD',
          // 状态直接取自那条控制闸门：它才是"用户想让它怎样"的唯一真相
          state: gateState, transferred: liveTask.transferred, total: liveTask.total,
          percent: liveTask.total ? Math.round(liveTask.transferred / liveTask.total * 100) : 0
        });
      }
      return json({ ok: true, count: items.length, items: items });
    }
    if (p === '/api/fs/control') {
      let j = {};
      try { j = JSON.parse(body); } catch (e) { /* 忽略 */ }
      controlCalls.push({ action: j.action, path: j.path });
      if (!liveTask || liveTask.path !== j.path) {
        return json({ ok: false, message: '这条传输已经不在了，没有可控制的对象' }, 409);
      }
      /*
       * 这里必须改的是**闸门状态**（[gateState]），而不是那条任务视图。
       * 真服务端就是这样：PUT 处理循环在每个缓冲区之间查一次闸门，
       * 所以"暂停"要生效就必须落在闸门上。只改任务视图的话，
       * 界面上显示成"已暂停"、数据却继续往磁盘里写 —— 那正是这个按钮最容易变成假的地方。
       */
      if (j.action === 'pause') gateState = 'PAUSED';
      else if (j.action === 'resume') gateState = 'RUNNING';
      else if (j.action === 'cancel') gateState = 'CANCELED';
      return json({ ok: true, message: '已下达指令', path: j.path });
    }
    if (p === '/api/fs/mkdir') return json({ ok: true });
    if (p === '/api/fs/rename') return json({ ok: true });
    if (p === '/api/fs/delete') return json({ ok: true });
    if (p === '/api/stats') return json({ ok: true, tx: '0 B', rx: '0 B' });

    return json({ ok: false, error: 'unmocked ' + p }, 404);
  });

  // ---- 假服务端的行为（与真服务端的语义一一对应） ----

  /** 开一份续传会话：记下每一路的起点与已写字节。 */
  function sessionStart(p2, size, lanes) {
    const chunk = Math.floor(size / lanes);
    const starts = [];
    for (let i = 0; i < lanes; i++) starts.push(i * chunk);
    session = { path: p2, size: size, lanes: lanes, starts: starts, written: new Array(lanes).fill(0) };
  }
  const doneList = () => session.starts.map((st, i) => st + session.written[i]);
  const receivedBytes = () => session.written.reduce((a, b) => a + b, 0);

  /*
   * 让挂住的 PUT 真的到达服务端。
   *
   * 这是这一段的全部技巧：不挂住的话，四个分片会在一瞬间全部传完，根本来不及按暂停。
   * 挂住之后，"两路已传完、另两路还在半空中"这种时序可以被精确摆出来 ——
   * 而那正是用户按下暂停那一刻的真实样子。
   */
  async function arrive(n) {
    const batch = parked.splice(0, n === undefined ? parked.length : n);
    for (const item of batch) {
      const send = (obj, status) => item.route.fulfill({
        status: status, contentType: 'application/json; charset=utf-8', body: JSON.stringify(obj)
      });
      if (gateState === 'PAUSED') {
        await send({ ok: false, paused: true, path: item.path, error: '这条上传已被暂停，等待继续' }, 423);
        continue;
      }
      if (gateState === 'CANCELED') {
        await send({ ok: false, canceled: true, path: item.path, error: '这条上传已被取消' }, 410);
        continue;
      }
      if (session && session.path === item.path) {
        session.written[item.lane] = Math.max(
          session.written[item.lane], (item.offset + item.len) - session.starts[item.lane]);
      }
      if (liveTask && liveTask.path === item.path) {
        liveTask.transferred += item.len;
        if (receivedBytes() >= session.size) liveTask = null;   // 传完了就不再算"在传"
      }
      await send({ ok: true, path: item.path }, 201);
    }
    return batch.length;
  }

  /** 让页面立刻按一次服务端快照。页面的定时器是 2 秒一次，测试等不起。 */
  async function poll() {
    await page.evaluate(() => { pollBusy = false; pollTasks(); });
    await page.waitForTimeout(250);
  }

  async function overviewOf() {
    return page.evaluate(() => {
      const box = document.getElementById('oview');
      const out = {};
      const kids = box ? [...box.children] : [];
      for (const c of kids) {
        if (c.className.indexOf('ostat') === 0) out[c.children[0].textContent] = Number(c.children[1].textContent);
        if (c.className === 'oview-note') out.note = c.textContent;
      }
      return out;
    });
  }

  async function rowButtons(p2) {
    return page.evaluate(pp => {
      const h = rowByPath[pp];
      if (!h) return [];
      const acts = [...h.el.children].find(c => c.className === 'tacts');
      return acts ? [...acts.children].map(b => b.textContent) : [];
    }, p2);
  }

  async function rowTextOf(p2) {
    return page.evaluate(pp => { const h = rowByPath[pp]; return h ? h.el.textContent : ''; }, p2);
  }

  async function clickInRow(p2, label) {
    const hit = await page.evaluate(([pp, l]) => {
      const h = rowByPath[pp];
      if (!h) return false;
      const acts = [...h.el.children].find(c => c.className === 'tacts');
      const btn = acts && [...acts.children].find(b => b.textContent === l);
      if (!btn) return false;
      btn.click();       // 真点击，走的是页面自己绑的那个 onclick
      return true;
    }, [p2, label]);
    await page.waitForTimeout(200);
    return hit;
  }

  async function dropFile(name, size) {
    await page.evaluate(([n, sz]) => {
      const dt = new DataTransfer();
      dt.items.add(new File([new Uint8Array(sz)], n, { type: 'application/octet-stream' }));
      window.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true }));
    }, [name, size]);
  }

  // ---------- 1. 正常模式 ----------
  console.log('\n[1] 正常模式：列表渲染 + 注入防护');
  await page.goto(ORIGIN + '/', { waitUntil: 'load' });
  await page.waitForSelector('#list .row');

  const rowCount = await page.locator('#list .row').count();
  check('渲染出 4 个条目', rowCount === 4, 'count=' + rowCount);

  const names = await page.locator('#list .row .name').allTextContents();
  check('中文文件名正确显示', names.some(n => n.indexOf('报告.pdf') >= 0), JSON.stringify(names));
  check('恶意文件名以纯文本呈现', names.some(n => n.indexOf(XSS_NAME) >= 0));

  const xss = await page.evaluate(() => window.__xss);
  check('文件名里的脚本没有被执行', xss === undefined, 'window.__xss=' + xss);

  const bannerHidden = await page.locator('#banner').isHidden();
  check('整盘共享时不显示黄色横幅', bannerHidden === true);

  const rootInfo = await page.locator('#rootinfo').innerText();
  check('工具栏以灰色小字标明共享位置', rootInfo.indexOf('手机内部存储') >= 0 && rootInfo.indexOf('/storage/emulated/0') >= 0, rootInfo);

  // dragover 必须被 preventDefault —— 少了这一步，浏览器会把拖入的本地文件
  // 当成导航目标打开（用户拖 html 文件的现象就是这么来的）
  check('初始时拖拽遮罩不可见', await page.locator('#overlay').isHidden());

  const dragging = await page.evaluate(() => {
    const dt = new DataTransfer();
    dt.items.add(new File(['x'], 'x.txt', { type: 'text/plain' }));
    const over = new DragEvent('dragover', { dataTransfer: dt, bubbles: true, cancelable: true });
    const notCanceled = window.dispatchEvent(over);   // false 表示被 preventDefault 了
    window.dispatchEvent(new DragEvent('dragenter', { dataTransfer: dt, bubbles: true, cancelable: true }));
    return { notCanceled, display: getComputedStyle(byId('overlay')).display };
  });
  check('window 上的 dragover 被拦截', dragging.notCanceled === false);
  // 真实踩过的坑：.overlay 上写了 display:flex，会盖过 UA 样式表的 [hidden]{display:none}，
  // 于是 el.hidden = true 完全不起作用，遮罩常驻页面挡住一切。
  check('拖入时遮罩显示出来', dragging.display !== 'none', 'display=' + dragging.display);

  const dropped = await page.evaluate(() => {
    const dt = new DataTransfer();
    dt.items.add(new File([new Uint8Array(64)], 'probe.txt', { type: 'text/plain' }));
    const ev = new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true });
    const notCanceled = window.dispatchEvent(ev);
    return { notCanceled, display: getComputedStyle(byId('overlay')).display };
  });
  check('window 上的 drop 被拦截', dropped.notCanceled === false);
  check('松手后遮罩收起', dropped.display === 'none', 'display=' + dropped.display);

  // 上面这次 drop 会真的触发一次上传，等它跑完再清账，否则会计入下一项的统计
  await waitFor(() => calls.filter(c => c.method === 'PUT').length >= 1);

  // ---------- 2. 拖拽上传 ----------
  console.log('\n[2] 拖拽落点不在列表区也能上传');
  calls.length = 0;
  await page.evaluate(() => {
    const dt = new DataTransfer();
    dt.items.add(new File([new Uint8Array(120)], 'a.txt', { type: 'text/plain' }));
    dt.items.add(new File([new Uint8Array(240)], 'b.txt', { type: 'text/plain' }));
    window.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true }));
  });

  await waitFor(() => calls.filter(c => c.method === 'PUT' && c.path === '/api/fs/file').length >= 2);
  const reserves = calls.filter(c => c.method === 'POST' && c.path === '/api/fs/reserve').length;
  const puts = calls.filter(c => c.method === 'PUT' && c.path === '/api/fs/file').length;
  check('两个文件都先占位再上传', reserves === 2 && puts === 2, 'reserve=' + reserves + ' put=' + puts);

  // 服务端要靠 reserve 带来的 size / lanes 判断"这个文件传完了没有"
  // （分块上传会先按最终大小预分配，所以磁盘大小不能当完成信号）。
  // 客户端漏发、或者两处算出的 lanes 不一致，后果都是记录永远停在"进行中"，
  // 而且不会有任何报错 —— 所以这一步必须显式断言。
  const reserveBodies = calls
    .filter(c => c.method === 'POST' && c.path === '/api/fs/reserve')
    .map(c => { try { return JSON.parse(c.body); } catch (e) { return {}; } });
  check('reserve 带上了文件大小与分片路数',
    reserveBodies.length === 2 &&
    reserveBodies.every(b => b.size > 0 && b.lanes >= 1) &&
    reserveBodies.map(b => b.size).sort((x, y) => x - y).join(',') === '120,240',
    JSON.stringify(reserveBodies));

  const lanes = await page.evaluate(() => ({
    tiny: laneCount({ size: 1024 }),
    justUnder: laneCount({ size: LARGE - 1 }),
    atThreshold: laneCount({ size: LARGE }),
    huge: laneCount({ size: 400 * 1024 * 1024 })
  }));
  check('laneCount 在阈值两侧行为明确',
    lanes.tiny === 1 && lanes.justUnder === 1 && lanes.atThreshold === 2 && lanes.huge === 4,
    JSON.stringify(lanes));

  // 不能只依赖 xhr.upload.onprogress：它在一些环境下根本不触发（代理、扩展、被拦截的请求）。
  // 只靠进度事件的话，行文案会长时间停在"占位中"，看起来像卡住了。
  const rowTexts = await page.locator('#tasklist .task .tp').allTextContents();
  check('任务行给出了明确状态，没有停在"占位中"',
    rowTexts.length === 2 && rowTexts.every(t => t.indexOf('占位中') < 0), JSON.stringify(rowTexts));

  // ---------- 3. 降级模式 ----------
  console.log('\n[3] 共享到应用私有目录：横幅必须说明位置');
  mode = 'degraded';
  await page.click('#reload');
  await page.waitForTimeout(600);
  const bannerText = await page.locator('#banner').innerText();
  check('横幅可见', await page.locator('#banner').isVisible());
  check('横幅写出提示语', bannerText.indexOf('应用自己的空间') >= 0, bannerText);
  const degradedRoot = await page.locator('#rootinfo').innerText();
  check('工具栏写出真实路径', degradedRoot.indexOf('Android/data') >= 0, degradedRoot);
  check('空目录仍然显示"这个文件夹是空的"', await page.locator('#empty').isVisible());
  console.log('    （诊断）requestfailed = ' + JSON.stringify(failedRequests));

  // ---------- 4. 错误模式 ----------
  console.log('\n[4] 服务端报错：不能显示成"空文件夹"');
  mode = 'error';
  await page.click('#reload');
  await page.waitForTimeout(600);
  const toastText = await page.locator('#toast').innerText();
  check('弹出错误提示', await page.locator('#toast').isVisible() && toastText.indexOf('存储访问权限') >= 0, toastText);
  const emptyVisible = await page.locator('#empty').isVisible();
  check('不把错误伪装成"这个文件夹是空的"', emptyVisible === false);
  check('列表被清空', (await page.locator('#list .row').count()) === 0);

  // ---------- 5. 文件夹层级与百分条 ----------
  console.log('\n[5] 文件夹层级保留 + 目录读取不丢文件');
  mode = 'normal';
  const folder = await page.evaluate(() => {
    const out = { files: [], dirs: [] };
    const mk = (p, content) => {
      const f = new File([content], p.split('/').pop(), { type: 'text/plain' });
      Object.defineProperty(f, 'webkitRelativePath', { value: p });
      return f;
    };
    addPlainFiles([mk('假期照片/2024/海边.jpg', 'a'), mk('假期照片/说明.txt', 'b')], '', out);
    return { files: out.files.map(f => f.dir), dirs: out.dirs };
  });
  check('保留了多级目录', JSON.stringify(folder.files) === '["假期照片/2024","假期照片"]', JSON.stringify(folder));

  const readAllCount = await page.evaluate(async () => {
    // 单次最多 100 条的读取器：只调一次就会静默丢掉后面 105 个。
    // 注意这里必须是"三个批次"，而不是一个 205 条的大数组。
    const mk = (prefix, n) => {
      const a = [];
      for (let i = 0; i < n; i++) a.push({ n: prefix + i });
      return a;
    };
    const batches = [mk('f', 100), mk('g', 100), mk('h', 5)];
    let calls = 0;
    const reader = {
      readEntries: cb => { calls++; cb(batches.shift() || []); }
    };
    const all = await readAll(reader);
    return { count: all.length, calls: calls };
  });
  check('超过 100 条的目录被完整读出（205）', readAllCount.count === 205,
    'got=' + JSON.stringify(readAllCount));

  // ---------- 6. 断点续传 ----------
  // 这一段的立足点：**续传起点必须问服务端要**。
  // 断点续传里最贵的一个错，是拿 `xhr.upload.onprogress` 的已发字节当起点 ——
  // 那只是"交给了 socket 缓冲"，服务端落盘了多少完全不知道。用它当起点会
  // 跳过一段没落盘的字节，结果是文件大小正常、内容坏掉，且不报任何错。
  console.log('\n[6] 断点续传：起点必须来自服务端');
  mode = 'normal';
  putFailOnce = 0;

  // (a) 服务端说有断点 → 把位点带回来
  resumeStatus = { ok: true, resumable: true, size: 1000, lanes: 1, done: [400], received: 400, complete: false };
  const q1 = await page.evaluate(() => queryResume('big.bin').then(d => (d && d.done) ? d.done[0] : null));
  check('queryResume 命中时返回服务端已写位点', q1 === 400, 'got=' + q1);

  // (b) 服务端说不可续传 → null。续传只是优化，它不可用也必须能正常上传。
  resumeStatus = { ok: true, resumable: false };
  const q2 = await page.evaluate(() => queryResume('big.bin').then(d => d === null));
  check('服务端说不可续传时返回 null（不影响正常上传）', q2 === true);

  // (c) 首传必须**不带** offset。带上 offset=0 会走随机写通道，
  //     SAF 通道直接 409 —— 而这本来是一次从头写的新文件。
  calls.length = 0;
  const firstOk = await page.evaluate(() => {
    const row = { set() {}, progress() {}, mark() {}, done() {}, fail() {} };
    const f = new File([new Uint8Array(120)], 'first.bin', { type: 'application/octet-stream' });
    return putSingle(f, 'first.bin', row, { ok: true, path: 'first.bin', lanes: 1 });
  });
  const firstPuts = calls.filter(c => c.method === 'PUT' && c.path === '/api/fs/file');
  check('首传只发一次且 URL 上不带 offset',
    firstOk === 'ok' && firstPuts.length === 1 && firstPuts[0].search.indexOf('offset=') < 0,
    JSON.stringify(firstPuts.map(c => c.search)));

  // (d) 续传起点取服务端的 done[0]
  calls.length = 0;
  const resumeOk = await page.evaluate(() => {
    const row = { set() {}, progress() {}, mark() {}, done() {}, fail() {} };
    const f = new File([new Uint8Array(1000)], 'r.bin', { type: 'application/octet-stream' });
    return putSingle(f, 'r.bin', row, { ok: true, path: 'r.bin', lanes: 1, resume: true, done: [400] });
  });
  const resumePuts = calls.filter(c => c.method === 'PUT' && c.path === '/api/fs/file').map(c => c.search);
  check('续传从服务端位点 400 接着发',
    resumeOk === 'ok' && resumePuts.length === 1 && resumePuts[0].indexOf('offset=400') >= 0,
    JSON.stringify(resumePuts));

  // (e) 断线重试：失败一次后再发，起点**重新问服务端**（服务端已到 700 就发 700）
  calls.length = 0;
  putFailOnce = 1;
  resumeStatus = { ok: true, resumable: true, size: 1000, lanes: 1, done: [700], received: 700, complete: false };
  const retryOk = await page.evaluate(() => {
    const row = { set() {}, progress() {}, mark() {}, done() {}, fail() {} };
    const f = new File([new Uint8Array(1000)], 'retry.bin', { type: 'application/octet-stream' });
    return putSingle(f, 'retry.bin', row, { ok: true, path: 'retry.bin', lanes: 1, resume: true, done: [400] });
  });
  const retryPuts = calls.filter(c => c.method === 'PUT' && c.path === '/api/fs/file').map(c => c.search);
  check('断线重发时改按服务端最新位点 700 续，而不是本地记的 400',
    retryOk === 'ok' && retryPuts.length === 2 &&
    retryPuts[0].indexOf('offset=400') >= 0 && retryPuts[1].indexOf('offset=700') >= 0,
    JSON.stringify(retryPuts));

  // (f) SAF 通道：带 offset 的写被 409 拒绝 → 必须回报 fallback，
  //     而不是当成普通失败去白重试三次。
  put409 = true;
  const slice = await page.evaluate(() => {
    const f = new File([new Uint8Array(50)], 's.bin', { type: 'application/octet-stream' });
    return putSlice('s.bin', 0, 400, f, null);
  });
  check('409 被识别为 fallback（不是普通失败）',
    !!slice && slice.ok === false && slice.fallback === true, JSON.stringify(slice));

  // (g) 单路续传遇 409 → 退一步整文件重传，并给出人能照做的提示
  calls.length = 0;
  const beforeToast = await page.locator('#toast').innerText();
  const fallbackOk = await page.evaluate(() => {
    const row = { set() {}, progress() {}, mark() {}, done() {}, fail() {} };
    const f = new File([new Uint8Array(1000)], 'fb.bin', { type: 'application/octet-stream' });
    return putSingle(f, 'fb.bin', row, { ok: true, path: 'fb.bin', lanes: 1, resume: true, done: [400] });
  });
  const fbToast = await page.locator('#toast').innerText();
  const fbPuts = calls.filter(c => c.method === 'PUT' && c.path === '/api/fs/file').map(c => c.search);
  check('不支持续传时改为整文件重传（最后一步不带 offset）',
    fallbackOk === 'ok' && fbPuts.length >= 2 && fbPuts[fbPuts.length - 1].indexOf('offset=') < 0,
    JSON.stringify(fbPuts));
  check('给出能照做的提示，而不是一个 HTTP 状态码',
    /从头重传|不支持断点续传/.test(fbToast) && fbToast !== beforeToast, fbToast);

  // (h) 大文件 + SAF：多路并发的**前提**就是随机写，走不通必须退化成单路整传。
  //     早先这里直接判失败并提示"请重新选择该文件" —— 用户照做一遍还是同一个结果，
  //     只会以为是自己操作错了。此刻磁盘上一个字节都没落盘（每路都在写入前被拒），
  //     所以整传不会留下半截数据。
  calls.length = 0;
  const degradeOk = await page.evaluate(() => {
    const row = { set() {}, progress() {}, mark() {}, done() {}, fail() {} };
    const f = new File([new Uint8Array(2000)], 'big2.bin', { type: 'application/octet-stream' });
    return putFile(f, 'big2.bin', row, { ok: true, path: 'big2.bin', lanes: 2 });
  });
  const dPuts = calls.filter(c => c.method === 'PUT' && c.path === '/api/fs/file').map(c => c.search);
  const lastPut = dPuts.length ? dPuts[dPuts.length - 1] : '';
  check('多路被 SAF 拒绝后退化成单路整传并成功',
    degradeOk === 'ok' && dPuts.length >= 3 && lastPut.indexOf('offset=') < 0, JSON.stringify(dPuts));

  put409 = false;

  // ---------- 7. 传输任务：概览 + 暂停 / 开始 / 取消 ----------
  /*
   * 这一段的立足点：**上传的执行者是浏览器，但意图存在服务端**。
   * 浏览器是服务端够不着的那一侧，手机端又是浏览器够不着的那一侧 ——
   * 所以"暂停"只能是一份放在服务端的意图，两端都只是它的客户端。
   * 于是"按钮真的生效了"必须验两层：
   *   界面这一层 —— 按钮跟着服务端意图变；
   *   数据这一层 —— 意图真的送到了服务端，而且续传只补没传完的那几路。
   */
  console.log('\n[7] 传输任务：概览 + 暂停 / 开始 / 取消');

  mode = 'normal';
  putFailOnce = 0;
  put409 = false;
  resumeStatus = { ok: true, resumable: false };
  useSession = true;   // 这一节起，upload-status 按会话回答
  parkPuts = true;     // 并让 PUT 挂住，由测试决定什么时候"到达服务端"
  gateState = 'RUNNING';
  session = null;
  liveTask = null;
  parked.length = 0;
  controlCalls = [];
  calls.length = 0;

  // 让"多路并行"这条路真的被走到：LARGE 是页面里的一个顶层变量，把它降到 1KB，
  // 几 KB 的文件就会切成 4 路 —— 不必真的造一个 256MB 的文件来测同一段逻辑。
  await page.evaluate(() => { LARGE = 1024; LANES = 4; });

  await dropFile('movie.bin', 4096);
  await waitFor(() => parked.length >= 4);
  check('4KB 的文件按 4 路并发发出', parked.length === 4, 'parked=' + parked.length);
  check('四路各发自己那一段（0 / 1024 / 2048 / 3072）',
    parked.map(x => x.offset).sort((a, b) => a - b).join(',') === '0,1024,2048,3072',
    JSON.stringify(parked.map(x => x.offset)));

  // 先让两路真的传完，另外两路还挂在半空 —— 这就是用户按暂停那一刻的样子
  await arrive(2);
  await poll();
  const ov1 = await overviewOf();
  check('概览：进行中 1', ov1['进行中'] === 1, JSON.stringify(ov1));
  check('概览：已完成 0、失败 0', ov1['已完成'] === 0 && ov1['失败'] === 0, JSON.stringify(ov1));
  check('概览写出"还剩多少没传完"', /还剩/.test(ov1.note || ''), ov1.note);
  const btns1 = await rowButtons('movie.bin');
  check('进行中那一行给的是「暂停 / 取消」', btns1.join(',') === '暂停,取消', btns1.join(','));

  // ---- 按暂停 ----
  check('点得到「暂停」按钮', await clickInRow('movie.bin', '暂停'));
  await waitFor(() => controlCalls.some(c => c.action === 'pause'));
  const pauseCall = controlCalls.find(c => c.action === 'pause');
  check('暂停指令带着路径发给服务端（路径才是它的主键，记录 id 是异步回填的）',
    !!pauseCall && pauseCall.path === 'movie.bin', JSON.stringify(pauseCall));

  // 剩下两路此刻到达服务端 → 423
  await arrive(2);
  await page.waitForTimeout(300);
  await poll();
  const ov2 = await overviewOf();
  check('暂停不计入失败', ov2['失败'] === 0, JSON.stringify(ov2));
  check('暂停也不计入完成', ov2['已完成'] === 0, JSON.stringify(ov2));
  check('概览：已暂停 1', ov2['已暂停'] === 1, JSON.stringify(ov2));
  const btns2 = await rowButtons('movie.bin');
  check('暂停后给的是「开始 / 取消」', btns2.join(',') === '开始,取消', btns2.join(','));
  const pausedText = await rowTextOf('movie.bin');
  check('那一行写着"已暂停"并保留了进度', /已暂停 50%/.test(pausedText), pausedText);

  // ---- 按开始：只补没传完的那两路 ----
  parked.length = 0;
  check('点得到「开始」按钮', await clickInRow('movie.bin', '开始'));
  await waitFor(() => controlCalls.some(c => c.action === 'resume'));
  await waitFor(() => parked.length >= 2);
  check('续传只重发没传完的 2 路（已经传完的两路不重传）',
    parked.length === 2, 'parked=' + parked.length);
  check('续传从各自断点接着发：2048 / 3072',
    parked.map(x => x.offset).sort((a, b) => a - b).join(',') === '2048,3072',
    JSON.stringify(parked.map(x => x.offset)));
  await arrive(2);
  await page.waitForTimeout(400);
  await poll();
  const ov3 = await overviewOf();
  check('续传跑完之后计入「已完成 1」', ov3['已完成'] === 1, JSON.stringify(ov3));
  check('文件确实完整落盘：服务端收到了全部 4096 字节',
    receivedBytes() === 4096, 'received=' + receivedBytes());
  check('续传不接受"传完了却还显示在传"',
    ov3['进行中'] === 0, JSON.stringify(ov3));

  // ---- 取消：在飞的分片收到 410，整条记为"已取消"而不是"失败" ----
  parked.length = 0;
  await dropFile('holiday.bin', 4096);
  await waitFor(() => parked.length >= 4);
  check('新的一条上传重新开 4 路', parked.length === 4, 'parked=' + parked.length);
  // 按钮是轮询画上去的（页面另有"开传后补一次快照"，但测试不等那 350ms，直接按一次）
  await poll();
  check('点得到「取消」按钮', await clickInRow('holiday.bin', '取消'));
  await waitFor(() => controlCalls.some(c => c.action === 'cancel'));
  const cancelCall = controlCalls.find(c => c.action === 'cancel');
  check('取消指令同样带着路径发出', !!cancelCall && cancelCall.path === 'holiday.bin', JSON.stringify(cancelCall));

  await arrive(4);   // 在飞的分片此刻到达服务端 → 410
  await page.waitForTimeout(300);
  await poll();
  const ov4 = await overviewOf();
  check('取消不计入失败（用户主动做的，不是坏消息）', ov4['失败'] === 0, JSON.stringify(ov4));
  const canceledText = await rowTextOf('holiday.bin');
  check('那一行写着"已取消"', /已取消/.test(canceledText), canceledText);
  check('取消是终态：不再给任何按钮', (await rowButtons('holiday.bin')).length === 0);
  check('概览里说了"已取消 1 个"', /已取消 1 个/.test(ov4.note || ''), ov4.note);
  check('传输任务面板是可见的', await page.locator('#tasks').isVisible());
  check('概览渲染出 4 个计数格', (await page.locator('#oview .ostat').count()) === 4);

  // 收尾：把这一节改过的全局参数还原，免得影响后面的用例
  parkPuts = false;
  useSession = false;

  // ---------- 8. 控制台 ----------
  console.log('\n[8] 浏览器控制台');
  check('没有 JS 报错', consoleErrors.length === 0, consoleErrors.join(' | '));

  await browser.close();
  console.log('\n===== PASS ' + pass + ' / FAIL ' + fail + ' =====');
  process.exit(fail ? 1 : 0);
})().catch(e => {
  console.error('运行异常：', e);
  process.exit(2);
});
