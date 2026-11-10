package com.pocketserver.net.http

/**
 * 电脑端网页界面（FR-3：免安装主通道）。
 *
 * ## 这是整个产品用户感知最强的部分
 * 用户在电脑上打开 `pocketserver.local`，看到的就是这个页面。它必须做到：
 *  - 零依赖、单文件、不请求任何外部资源（局域网内常常没有外网，引 CDN 会白屏）
 *  - 移动/桌面浏览器都能用（用户也可能用平板访问）
 *  - 拖拽上传、进度可见、大文件不占内存
 *
 * ## 实现要点
 *  - 上传用 `fetch` + `PUT` 原始字节体，而不是 `multipart/form-data`：
 *    multipart 需要在服务端做边界解析，还会让浏览器把整个表单包装一遍，大文件下更慢。
 *    原始字节体可以直通 `FileChannel`/缓冲池，是 O-2 优化能生效的前提。
 *  - 大文件（≥64MB）在网页端就用切片并发上传，一条连接跑满不了 5GHz Wi-Fi，
 *    四路并行才能把 55MB/s 打满（O-3）。
 *  - 所有字符串拼接用 `+`，模板字面量里的 `$` 在 Kotlin 原始字符串里需要转义，易错。
 */
object WebUi {

    /**
     * 配对页：电脑打开域名后看到的第一屏（FR-2.3）。
     *
     * @param prefill 扫码进入时预填的配对码。手机扫了电视上的二维码后会用浏览器打开
     *                `http://<主机名>:<端口>/pair?c=123456`，这里把它填进输入框并自动提交，
     *                用户扫完就直接进去了，不用再手抄 6 位数字。
     *
     *                **调用方必须先把它过滤成纯数字**：这个值来自 URL，任何人可改。
     *                直接拼进 HTML 属性会造成 XSS（一个引号就能闭合属性再插入 script 标签）。
     */
    fun pairPage(
        mdnsHost: String,
        port: Int,
        deviceName: String,
        codeTtlSeconds: Long,
        prefill: String = ""
    ): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>接入 ${esc(deviceName)}</title>
<style>$BASE_CSS</style>
</head>
<body class="center">
<main class="card pair">
  <div class="logo">[(•)]</div>
  <h1>连接到 ${esc(deviceName)}</h1>
  <p class="muted">请查看手机或电视屏幕上显示的 6 位数字，填在下面。</p>
  <form id="f" autocomplete="off">
    <input id="code" class="code-input" inputmode="numeric" pattern="[0-9]*" maxlength="6"
           value="$prefill" placeholder="······" autofocus>
    <label class="remember"><input type="checkbox" id="trust" checked> 记住这台电脑，下次免输</label>
    <button type="submit" id="btn">接入</button>
  </form>
  <p id="msg" class="msg"></p>
  <p class="muted small">配对码还有 <span id="ttl">$codeTtlSeconds</span> 秒失效 · 地址 $mdnsHost:$port</p>
</main>
<script>
var f = document.getElementById('f');
var code = document.getElementById('code');
var msg = document.getElementById('msg');
var btn = document.getElementById('btn');
var ttlEl = document.getElementById('ttl');
var ttl = $codeTtlSeconds;

// 设备列表只在浏览器本地保存。跨设备切换时通过 URL 片段传递，片段不会发送给服务器。
var DEVICE_LIST_KEY = 'pocketserver:web:devices:v1';
function importDeviceListFromHash() {
  try {
    var marker = '#psdevices=';
    if (location.hash.indexOf(marker) !== 0) return;
    var incoming = JSON.parse(decodeURIComponent(location.hash.substring(marker.length)));
    var existing = JSON.parse(localStorage.getItem(DEVICE_LIST_KEY) || '[]');
    if (!Array.isArray(existing)) existing = [];
    if (Array.isArray(incoming)) incoming.forEach(function (item) {
      if (!item || typeof item.url !== 'string' || typeof item.name !== 'string') return;
      var url = new URL(item.url);
      if (url.protocol !== 'http:' && url.protocol !== 'https:') return;
      var origin = url.origin;
      var name = item.name.substring(0, 60) || url.hostname;
      var old = existing.findIndex(function (saved) { return saved && saved.url === origin; });
      var entry = { url: origin, name: name, id: typeof item.id === 'string' ? item.id : '' };
      if (old >= 0) existing[old] = entry; else existing.push(entry);
    });
    localStorage.setItem(DEVICE_LIST_KEY, JSON.stringify(existing.slice(-20)));
  } catch (_) { /* 忽略无效或不可用的浏览器本地数据 */ }
  history.replaceState(null, '', location.pathname + location.search);
}
importDeviceListFromHash();

setInterval(function () {
  if (ttl > 0) { ttl--; ttlEl.textContent = ttl; }
}, 1000);

code.addEventListener('input', function () {
  code.value = code.value.replace(/[^0-9]/g, '');
  if (code.value.length === 6) f.requestSubmit();
});

// 扫码进入的情况：配对码已经预填好了，直接提交，省掉用户再点一次「接入」
if (code.value.length === 6) f.requestSubmit();

f.addEventListener('submit', function (e) {
  e.preventDefault();
  if (code.value.length !== 6) { msg.textContent = '请输入 6 位数字'; return; }
  btn.disabled = true;
  msg.textContent = '正在验证…';
  fetch('/api/pair', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code: code.value, trust: document.getElementById('trust').checked })
  }).then(function (r) { return r.json(); }).then(function (d) {
    if (d.ok) {
      msg.className = 'msg ok';
      msg.textContent = '接入成功，正在打开文件列表…';
      location.href = '/';
    } else {
      msg.className = 'msg err';
      msg.textContent = d.error || '配对码不正确';
      btn.disabled = false;
      code.value = '';
      code.focus();
    }
  }).catch(function () {
    msg.className = 'msg err';
    msg.textContent = '网络中断，请重试';
    btn.disabled = false;
  });
});
</script>
</body>
</html>
"""

    /**
     * 文件管理主页面（FR-3）。
     *
     * ## 三个不能省的实现要点
     *
     * 1. **拖拽必须在 document/window 层接管。**
     *    早期版本只给列表区域挂了 `dragover`/`drop`。用户把文件拖到页头、面包屑、
     *    任务区或页面空白处时，没有任何监听器拦默认动作，浏览器就把本地文件当成
     *    导航目标直接打开（拖 html 文件尤其明显 —— 它会变成一次页面跳转）。
     *    现在的做法是 window 级 `dragover` + `drop` 无条件 `preventDefault`，
     *    再用 dragenter/dragleave 计数控制遮罩的显隐。
     *
     * 2. **要支持整个文件夹。**
     *    两条通路：`webkitdirectory` 输入框（选目录）和 `webkitGetAsEntry()`
     *    递归遍历（拖目录）。注意 `readEntries()` **单次最多返回 100 条**，
     *    必须循环读到空为止 —— 只调一次的话，超过 100 个文件的文件夹会静默丢文件。
     *
     * 3. **任何失败都要说出来。**
     *    以前 `load()` 只处理 401，其余 `ok:false` 一律按空数组渲染，于是
     *    「没有权限」和「空目录」在界面上长得一模一样。现在错误、提示、共享位置
     *    都直接展示，用户不需要猜。
     *
     * ## 性能相关
     *  - 上传走 `PUT` 原始字节体，不用 multipart，让服务端能直通 FileChannel（O-2）
     *  - ≥64MB 的文件在网页端就分 4 路并发，单条 TCP 跑不满 5GHz（O-3）
     *  - SAF 通道拿不到随机写，服务端会回 409；网页端据列表返回的 storageMode
     *    提前把路数降为 1，不做无谓的失败重试
     *  - 批量上传有并发上限。一个文件夹 500 张照片时同时开 500 个 XHR 会把
     *    浏览器与服务端一起拖垮，而且进度条会变成一片抖动
     */
    fun filePage(
        deviceName: String,
        canWrite: Boolean,
        canDelete: Boolean,
        canRename: Boolean,
        deviceId: String = ""
    ): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(deviceName)} · 文件</title>
<style>$BASE_CSS</style>
</head>
<body>
<header class="top" id="topHeader" data-device-name="${esc(deviceName)}" data-device-id="${esc(deviceId)}">
  <div class="brand">[(•)] ${esc(deviceName)}</div>
  <div class="device-switch-wrap">
    <button id="deviceSwitchButton" class="ghost" type="button" aria-expanded="false">切换设备 <span id="deviceCount"></span></button>
    <div id="deviceMenu" class="device-menu" hidden>
      <div class="device-menu-title">局域网设备</div>
      <div id="deviceList" class="device-list"></div>
      <form id="deviceAddForm" class="device-add-form">
        <input id="deviceAddress" type="url" placeholder="设备地址，如 http://pocketserver-2.local:8080" autocomplete="url" required>
        <input id="deviceLabel" type="text" placeholder="设备名称（可选）" maxlength="60">
        <button class="primary" type="submit">添加并切换</button>
      </form>
      <div class="device-menu-hint">当前设备会自动记住；其他设备首次访问需要配对。</div>
    </div>
  </div>
  <div class="spacer"></div>
  <div id="rate" class="rate"></div>
  <button id="reload" class="ghost" title="刷新">刷新</button>
  <button id="logout" class="ghost" title="断开">断开</button>
</header>

<div id="banner" class="banner" hidden></div>

<div class="toolbar">
  <button id="btnFiles" class="primary">上传文件</button>
  <button id="btnFolder" class="primary">上传文件夹</button>
  <button id="btnNewDir" class="ghost">新建文件夹</button>
  <button id="btnFavorite" class="ghost" hidden>☆ 收藏当前文件夹</button>
  <div class="spacer"></div>
  <div class="view-switch" role="group" aria-label="文件显示方式">
    <button data-view="small" class="active" title="小图">小图</button>
    <button data-view="medium" title="中图">中图</button>
    <button data-view="large" title="大图">大图</button>
    <button data-view="sidebar" title="侧边栏预览">侧边栏</button>
  </div>
  <span id="rootinfo" class="muted small"></span>
</div>

<section id="favoritesHome" class="favorites-home" hidden aria-label="快捷文件夹">
  <div class="favorites-heading"><strong>快捷直达</strong><span>手动收藏和最常访问的文件夹</span></div>
  <div id="favoriteList" class="favorite-list"></div>
</section>

<div id="selectionBar" class="selection-bar" hidden>
  <label class="select-all"><input id="selectAll" type="checkbox"> 全选当前页文件</label>
  <span id="selectedCount" class="muted small">已选 0 个</span>
  <div class="spacer"></div>
  <button id="downloadSelected" class="primary" disabled>批量下载</button>
  <button id="deleteSelected" class="danger-button" disabled>批量删除</button>
  <button id="clearSelected" class="ghost">取消选择</button>
</div>

<div class="breadbar">
  <div class="breadbar-inner">
    <nav id="crumbs" class="crumbs" aria-label="文件夹路径"></nav>
    <div class="sort-controls">
      <label for="sortBy">排序</label>
      <select id="sortBy" aria-label="排序依据">
        <option value="name">名称</option>
        <option value="size">大小</option>
        <option value="mtime">修改时间</option>
      </select>
      <button id="sortDirection" class="sort-direction" type="button" aria-label="切换排序方向">名称 · 正序 ↑</button>
    </div>
  </div>
</div>

<main id="drop" class="list-wrap">
  <div id="browser-content" class="browser-content">
    <div id="list" class="list view-small"></div>
    <aside id="previewSidebar" class="preview-sidebar" hidden>
      <div class="preview-head"><strong id="previewName">预览</strong><div class="spacer"></div>
        <a id="previewDownload" class="act" href="#">下载</a>
        <button id="previewClose" class="ghost" aria-label="关闭预览">关闭</button>
      </div>
      <div id="previewContent" class="preview-content"><div class="preview-empty">选择一个文件查看预览</div></div>
      <div id="previewMeta" class="preview-meta"></div>
    </aside>
  </div>
  <div id="empty" class="empty" hidden>
    这个文件夹是空的
    <div class="muted small">把文件或整个文件夹拖到页面任意位置即可上传</div>
  </div>
</main>

<section id="tasks" class="tasks" hidden>
  <div class="trow">
    <h3>传输任务</h3>
    <div class="spacer"></div>
    <span id="thint" class="muted small"></span>
    <button id="tclear" class="ghost" title="清掉已经结束的行">清空</button>
  </div>
  <div id="oview" class="oview"></div>
  <div class="bar wide"><i id="tbar"></i></div>
  <div id="tasklist"></div>
</section>

<div id="overlay" class="overlay" hidden>
  <div class="overlay-card">
    <div class="big">松开鼠标即可上传</div>
    <div id="ovtext" class="muted small"></div>
  </div>
</div>

<div id="toast" class="toast" hidden></div>

<input id="picker" type="file" multiple hidden>
<input id="folderPicker" type="file" webkitdirectory directory multiple hidden>

<script>
var CAN_WRITE = $canWrite;
var CAN_DELETE = $canDelete;
var CAN_RENAME = $canRename;
var cwd = '';
var directoryLoadGeneration = 0;
var folderRefreshBusy = false;
var folderContentSignature = '';
var LARGE = 64 * 1024 * 1024;
var LANES = 4;
// 并发上限：太大浏览器与服务端都会抖，太小则大文件夹传得慢
var MAX_PARALLEL = 2;
var MAX_ROWS = 300;
var selectedPaths = Object.create(null);
var viewMode = 'small';
var lastGridMode = 'small';
var hoverPreviewTimer = null;
var sortKey = 'name';
var sortDescending = false;
var currentItems = [];
var FAVORITES_KEY = 'pocketserver:web:favorites:v1';
var VISITS_KEY = 'pocketserver:web:folder-visits:v1';
var DEVICE_LIST_KEY = 'pocketserver:web:devices:v1';
var LAST_PATH_KEY = 'pocketserver:web:last-path:v1';
function importDeviceListFromHash() {
  try {
    var marker = '#psdevices=';
    if (location.hash.indexOf(marker) !== 0) return;
    var incoming = JSON.parse(decodeURIComponent(location.hash.substring(marker.length)));
    var existing = JSON.parse(localStorage.getItem(DEVICE_LIST_KEY) || '[]');
    if (!Array.isArray(existing)) existing = [];
    if (Array.isArray(incoming)) incoming.forEach(function (item) {
      if (!item || typeof item.url !== 'string' || typeof item.name !== 'string') return;
      var url = new URL(item.url);
      if (url.protocol !== 'http:' && url.protocol !== 'https:') return;
      var incomingId = typeof item.id === 'string' ? item.id : '';
      var index = incomingId ? existing.findIndex(function (saved) { return saved && saved.id === incomingId; }) : -1;
      if (index < 0) index = existing.findIndex(function (saved) { return saved && saved.url === url.origin; });
      var old = index >= 0 ? existing[index] : null;
      var entry = { url: url.origin, name: item.name.substring(0, 60) || url.hostname, id: incomingId || (old && old.id) || '' };
      if (index >= 0) existing[index] = entry; else existing.push(entry);
    });
    localStorage.setItem(DEVICE_LIST_KEY, JSON.stringify(existing.slice(-20)));
  } catch (_) { /* 忽略无效或不可用的浏览器本地数据 */ }
  history.replaceState(null, '', location.pathname + location.search);
}
importDeviceListFromHash();
var favorites = readLocalObject(FAVORITES_KEY, []);
var folderVisits = readLocalObject(VISITS_KEY, {});
var savedDevices = readSavedDevices();
var nearbyDevices = [];
var currentDeviceId = '';

function byId(id) { return document.getElementById(id); }

function enc(p) { return encodeURIComponent(p); }

function fmt(n) {
  if (!n && n !== 0) return '';
  var u = ['B', 'KB', 'MB', 'GB', 'TB'], i = 0;
  while (n >= 1024 && i < u.length - 1) { n = n / 1024; i++; }
  return (i === 0 ? n : n.toFixed(1)) + ' ' + u[i];
}

// 路径拼接。空串代表共享根，不能拼出前导斜杠 —— 服务端会把它当成越权路径拒绝。
function combine(a, b) {
  if (!a) return b || '';
  if (!b) return a;
  return a + '/' + b;
}

function readLocalObject(key, fallback) {
  try {
    var value = JSON.parse(localStorage.getItem(key) || 'null');
    if (Array.isArray(fallback)) return Array.isArray(value) ? value.filter(function (item) {
      return item && typeof item.path === 'string' && typeof item.name === 'string';
    }) : fallback;
    return value && typeof value === 'object' && !Array.isArray(value) ? value : fallback;
  } catch (_) { return fallback; }
}

function saveFolderPreferences() {
  try {
    localStorage.setItem(FAVORITES_KEY, JSON.stringify(favorites));
    localStorage.setItem(VISITS_KEY, JSON.stringify(folderVisits));
  } catch (_) { /* 浏览器禁用本地存储时，当前页仍可继续使用 */ }
}

function readSavedDevices() {
  try {
    var list = JSON.parse(localStorage.getItem(DEVICE_LIST_KEY) || '[]');
    if (!Array.isArray(list)) return [];
    return list.filter(function (item) {
      if (!item || typeof item.url !== 'string' || typeof item.name !== 'string') return false;
      try { var url = new URL(item.url); return url.protocol === 'http:' || url.protocol === 'https:'; }
      catch (_) { return false; }
    }).map(function (item) { return { url: new URL(item.url).origin, name: item.name.substring(0, 60), id: typeof item.id === 'string' ? item.id : '' }; }).slice(-20);
  } catch (_) { return []; }
}

function saveDevices() {
  try { localStorage.setItem(DEVICE_LIST_KEY, JSON.stringify(savedDevices.slice(-20))); }
  catch (_) { toast('浏览器未允许保存设备列表', 'err'); }
}

function rememberCurrentDevice() {
  var origin = location.origin;
  var header = byId('topHeader');
  var name = header.getAttribute('data-device-name') || location.hostname;
  var id = header.getAttribute('data-device-id') || '';
  currentDeviceId = id;
  var index = id ? savedDevices.findIndex(function (item) { return item.id === id; }) : -1;
  if (index < 0) index = savedDevices.findIndex(function (item) { return item.url === origin; });
  var old = index >= 0 ? savedDevices[index] : null;
  var entry = { url: origin, name: name, id: id || (old && old.id) || '' };
  if (index >= 0) savedDevices[index] = entry; else savedDevices.push(entry);
  savedDevices = savedDevices.slice(-20);
  saveDevices();
  renderDeviceMenu();
}

function renderDeviceMenu() {
  var box = byId('deviceList');
  box.innerHTML = '';
  var devices = savedDevices.slice().sort(function (a, b) {
    var aCurrent = (currentDeviceId && a.id ? a.id === currentDeviceId : a.url === location.origin) ? 1 : 0;
    var bCurrent = (currentDeviceId && b.id ? b.id === currentDeviceId : b.url === location.origin) ? 1 : 0;
    if (aCurrent !== bCurrent) return bCurrent - aCurrent;
    var aOnline = nearbyDevices.some(function (d) { return d.id && d.id === a.id && d.reachable; });
    var bOnline = nearbyDevices.some(function (d) { return d.id && d.id === b.id && d.reachable; });
    return Number(bOnline) - Number(aOnline) || a.name.localeCompare(b.name, 'zh-CN');
  });
  var onlineCount = nearbyDevices.filter(function (device) { return device.reachable; }).length + 1;
  byId('deviceCount').textContent = '· ' + onlineCount + ' 在线';
  devices.forEach(function (device) {
    var row = el('div', 'device-row');
    var open = el('button', 'device-open');
    open.type = 'button';
    var name = el('strong', null, device.name);
    var address = el('small', null, new URL(device.url).host);
    var nearby = nearbyDevices.find(function (d) { return (device.id && d.id === device.id) || d.url === device.url; });
    var isCurrent = currentDeviceId && device.id ? device.id === currentDeviceId : device.url === location.origin;
    var isOnline = !!(nearby && nearby.reachable);
    var status = el('span', 'device-status' + (isCurrent || isOnline ? ' is-current' : ''), isCurrent ? '当前在线' : (isOnline ? '在线' : (nearby ? '离线' : '已保存')));
    open.appendChild(name); open.appendChild(address); open.appendChild(status);
    open.onclick = function () { switchDevice(device); };
    row.appendChild(open);
    if (!isCurrent && !isOnline) {
      var remove = el('button', 'device-remove', '移除');
      remove.type = 'button';
      remove.onclick = function () {
        savedDevices = savedDevices.filter(function (item) { return item.url !== device.url; });
        saveDevices(); renderDeviceMenu();
      };
      row.appendChild(remove);
    }
    box.appendChild(row);
  });
}

function refreshNearbyDevices() {
  if (document.hidden) return;
  fetch('/api/devices', { cache: 'no-store' }).then(function (r) {
    if (r.status === 401) { location.href = '/pair'; return null; }
    return r.json();
  }).then(function (data) {
    if (!data || !data.ok || !Array.isArray(data.items)) return;
    nearbyDevices = data.items.filter(function (d) { return d && d.id && (d.mdnsHost || d.host) && Number(d.port) > 0; }).map(function (d) {
      var host = String(d.mdnsHost || d.host);
      if (host.indexOf(':') >= 0 && host.charAt(0) !== '[') host = '[' + host + ']';
      return { id: String(d.id), name: String(d.name || d.id).substring(0, 60), url: 'http://' + host + ':' + Number(d.port), reachable: !!d.reachable };
    });
    nearbyDevices.forEach(function (device) {
      var index = savedDevices.findIndex(function (saved) { return saved.id && saved.id === device.id; });
      if (index < 0) index = savedDevices.findIndex(function (saved) { return saved.url === device.url; });
      var entry = { url: device.url, name: device.name, id: device.id };
      if (index >= 0) savedDevices[index] = entry; else savedDevices.push(entry);
    });
    savedDevices = savedDevices.slice(-20);
    saveDevices();
    renderDeviceMenu();
  }).catch(function () { /* 暂时无法发现设备时，仍显示已保存列表 */ });
}

function switchDevice(device) {
  try { localStorage.setItem(LAST_PATH_KEY, cwd || ''); } catch (_) {}
  var payload = encodeURIComponent(JSON.stringify(savedDevices));
  location.href = device.url + '/#psdevices=' + payload;
}

function normalizeDeviceAddress(value) {
  var url;
  try { url = new URL(value.indexOf('://') < 0 ? 'http://' + value : value); }
  catch (_) { return null; }
  if ((url.protocol !== 'http:' && url.protocol !== 'https:') || !url.hostname || url.username || url.password) return null;
  return url.origin;
}

byId('deviceSwitchButton').addEventListener('click', function () {
  var menu = byId('deviceMenu');
  menu.hidden = !menu.hidden;
  this.setAttribute('aria-expanded', String(!menu.hidden));
});
document.addEventListener('click', function (event) {
  if (!event.target.closest('.device-switch-wrap')) {
    byId('deviceMenu').hidden = true;
    byId('deviceSwitchButton').setAttribute('aria-expanded', 'false');
  }
});
byId('deviceAddForm').addEventListener('submit', function (event) {
  event.preventDefault();
  var origin = normalizeDeviceAddress(byId('deviceAddress').value.trim());
  if (!origin) { toast('请输入有效的局域网设备地址', 'err'); return; }
  var label = byId('deviceLabel').value.trim() || new URL(origin).hostname;
  var index = savedDevices.findIndex(function (item) { return item.url === origin; });
  var entry = { url: origin, name: label.substring(0, 60), id: '' };
  if (index >= 0) savedDevices[index] = entry; else savedDevices.push(entry);
  savedDevices = savedDevices.slice(-20);
  saveDevices();
  switchDevice(entry);
});
rememberCurrentDevice();

function folderDisplayName(path) { return (path || '').split('/').filter(Boolean).pop() || path; }

function frequentFolders() {
  var pinned = Object.create(null);
  favorites.forEach(function (item) { pinned[item.path] = true; });
  return Object.keys(folderVisits).map(function (path) {
    return { path: path, name: folderDisplayName(path), count: Number(folderVisits[path]) || 0 };
  }).filter(function (item) { return item.count > 0 && !pinned[item.path]; })
    .sort(function (a, b) { return b.count - a.count || a.name.localeCompare(b.name, 'zh-CN', { numeric: true }); })
    .slice(0, 3);
}

function renderFavoritesHome() {
  var home = byId('favoritesHome');
  home.hidden = cwd !== '';
  if (home.hidden) return;
  var box = byId('favoriteList');
  box.innerHTML = '';
  var items = favorites.map(function (item) { return { path: item.path, name: item.name, kind: '已收藏', manual: true }; })
    .concat(frequentFolders().map(function (item) { return { path: item.path, name: item.name, kind: '常用', manual: false }; }));
  if (!items.length) {
    box.appendChild(el('div', 'favorite-empty', '打开一个常用文件夹，点“收藏当前文件夹”固定到这里；访问过的文件夹也会自动出现在常用列表。'));
    return;
  }
  items.forEach(function (item) {
    var card = el('div', 'favorite-card');
    var open = el('button', 'favorite-open');
    open.type = 'button';
    open.appendChild(el('span', 'favorite-icon', item.manual ? '★' : '↗'));
    var text = el('span', 'favorite-text');
    text.appendChild(el('strong', null, item.name || folderDisplayName(item.path)));
    text.appendChild(el('small', null, item.kind + ' · ' + item.path));
    open.appendChild(text);
    open.onclick = function () { load(item.path, true); };
    card.appendChild(open);
    if (item.manual) {
      var remove = el('button', 'favorite-remove', '移除');
      remove.type = 'button';
      remove.title = '从快捷直达中移除';
      remove.onclick = function () {
        favorites = favorites.filter(function (saved) { return saved.path !== item.path; });
        saveFolderPreferences(); renderFavoritesHome(); syncFavoriteButton();
      };
      card.appendChild(remove);
    }
    box.appendChild(card);
  });
}

function recordFolderVisit(path) {
  folderVisits[path] = (Number(folderVisits[path]) || 0) + 1;
  saveFolderPreferences();
  renderFavoritesHome();
}

function syncFavoriteButton() {
  var button = byId('btnFavorite');
  button.hidden = !cwd;
  var saved = favorites.some(function (item) { return item.path === cwd; });
  button.textContent = saved ? '★ 已收藏（点击移除）' : '☆ 收藏当前文件夹';
}

function toggleCurrentFavorite() {
  if (!cwd) return;
  var existing = favorites.some(function (item) { return item.path === cwd; });
  if (existing) {
    favorites = favorites.filter(function (item) { return item.path !== cwd; });
    toast('已从快捷直达移除');
  } else {
    favorites.push({ path: cwd, name: folderDisplayName(cwd) });
    toast('已收藏到首页快捷直达');
  }
  saveFolderPreferences();
  syncFavoriteButton();
}

function renameSavedFolder(oldPath, newPath) {
  var prefix = oldPath + '/';
  favorites = favorites.map(function (item) {
    if (item.path === oldPath || item.path.indexOf(prefix) === 0) {
      var path = newPath + item.path.substring(oldPath.length);
      return { path: path, name: folderDisplayName(path) };
    }
    return item;
  });
  Object.keys(folderVisits).forEach(function (path) {
    if (path === oldPath || path.indexOf(prefix) === 0) {
      folderVisits[newPath + path.substring(oldPath.length)] = folderVisits[path];
      delete folderVisits[path];
    }
  });
  saveFolderPreferences();
}

function removeSavedFolder(path) {
  var prefix = path + '/';
  favorites = favorites.filter(function (item) { return item.path !== path && item.path.indexOf(prefix) !== 0; });
  Object.keys(folderVisits).forEach(function (visited) {
    if (visited === path || visited.indexOf(prefix) === 0) delete folderVisits[visited];
  });
  saveFolderPreferences();
}

function parentOf(p) {
  var i = (p || '').lastIndexOf('/');
  return i < 0 ? '' : p.substring(0, i);
}

function el(tag, cls, text) {
  var e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined && text !== null) e.textContent = text;
  return e;
}

var toastTimer = null;
function toast(text, kind) {
  var t = byId('toast');
  t.textContent = text;
  t.className = 'toast ' + (kind || '');
  t.hidden = false;
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(function () { t.hidden = true; }, 5000);
}

function postJson(url, body) {
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  }).then(function (r) {
    if (r.status === 401) {
      location.href = '/pair';
      return { ok: false, error: '会话已失效，正在跳转', __httpStatus: r.status };
    }
    return r.json().catch(function () {
      return { ok: false, error: '服务端返回异常', __httpStatus: r.status };
    }).then(function (data) {
      if (!data || typeof data !== 'object') data = { ok: false, error: '服务端返回了无效响应' };
      data.__httpStatus = r.status;
      return data;
    });
  });
}

/* 目录创建是幂等操作；文件预留带 requestId，由服务端保证重试仍返回同一路径。 */
function postJsonRetry(url, body, maxRetries, onRetry) {
  var transient = { 408: true, 425: true, 429: true, 500: true, 502: true, 503: true, 504: true };
  function wait(ms) { return new Promise(function (resolve) { setTimeout(resolve, ms); }); }
  function attempt(n) {
    return postJson(url, body).then(function (data) {
      if (n >= maxRetries || !transient[data && data.__httpStatus]) return data;
      var delay = Math.min(1400, 350 * Math.pow(2, n)) + Math.floor(Math.random() * 180);
      if (onRetry) onRetry(n + 1, delay, data.error || ('HTTP ' + data.__httpStatus));
      return wait(delay).then(function () { return attempt(n + 1); });
    }, function (error) {
      if (n >= maxRetries) throw error;
      var delay = Math.min(1400, 350 * Math.pow(2, n)) + Math.floor(Math.random() * 180);
      if (onRetry) onRetry(n + 1, delay, error && error.message ? error.message : '网络连接中断');
      return wait(delay).then(function () { return attempt(n + 1); });
    });
  }
  return attempt(0);
}

// ==================== 共享位置与错误提示 ====================

function renderBanner(d) {
  var b = byId('banner');
  var notice = d && d.notice ? d.notice : '';

  /*
   * 黄色横幅只在"有事要处理"时出现。
   *
   * 早期版本只要拿到了 rootPath 就显示横幅，于是整盘共享、一切正常的页面上
   * 也常驻着一条警告色 —— 用户会以为哪里出错了。颜色本身是有语义的：
   * 黄色 = 需要你去处理，灰色 = 只是信息。两者不能混在一起。
   */
  if (!notice) {
    b.hidden = true;
    b.innerHTML = '';
  } else {
    b.hidden = false;
    b.innerHTML = '';
    b.appendChild(el('div', null, notice));
  }

  // "我共享的是哪儿"始终可见，但用灰色小字，不制造焦虑
  var label = d && d.rootLabel ? d.rootLabel : '';
  var path = d && d.rootPath ? d.rootPath : '';
  byId('rootinfo').textContent = (label || path)
    ? '当前共享：' + label + (path ? ' · ' + path : '')
    : '';
}

// ==================== 列目录 ====================

function load(path, countVisit) {
  var target = path || '';
  var generation = ++directoryLoadGeneration;
  fetch('/api/fs/list?path=' + enc(target), { cache: 'no-store' }).then(function (r) {
    if (r.status === 401) { location.href = '/pair'; return null; }
    return r.json();
  }).then(function (d) {
    if (!d || generation !== directoryLoadGeneration) return;
    // 关键：服务端说不行的时候必须原样告诉用户。
    // 早期版本在这里把 ok:false 也当成"空数组"渲染，于是"没权限"看起来就是"空文件夹"。
    if (d.ok === false) {
      renderBanner(d);
      byId('list').innerHTML = '';
      byId('empty').hidden = true;
      toast(d.error || '无法读取这个目录', 'err');
      return;
    }
    applyDirectoryData(d);
    if (countVisit && cwd) recordFolderVisit(cwd);
  }).catch(function () {
    if (generation === directoryLoadGeneration) toast('网络中断，未能读取文件列表', 'err');
  });
}

function directorySignature(items) {
  return (items || []).map(function (it) {
    return [it.path || '', it.name || '', it.dir ? 'd' : 'f', Number(it.size) || 0, Number(it.mtime) || 0].join('|');
  }).sort().join('\n');
}

function applyDirectoryData(d) {
  cwd = d.path || '';
  currentItems = d.items || [];
  try { localStorage.setItem(LAST_PATH_KEY, cwd); } catch (_) {}
  folderContentSignature = directorySignature(currentItems);
  if (d.storageMode === 'saf') LANES = 1;
  renderBanner(d);
  renderCrumbs(cwd);
  renderFavoritesHome();
  syncFavoriteButton();
  syncSortControls();
  renderList(currentItems);
}

function refreshCurrentFolder() {
  if (document.hidden || folderRefreshBusy) return;
  var path = cwd;
  var generation = directoryLoadGeneration;
  folderRefreshBusy = true;
  fetch('/api/fs/list?path=' + enc(path), { cache: 'no-store' }).then(function (r) {
    if (r.status === 401) { location.href = '/pair'; return null; }
    return r.json();
  }).then(function (d) {
    if (!d || d.ok === false || generation !== directoryLoadGeneration || cwd !== path || (d.path || '') !== path) return;
    if (directorySignature(d.items || []) !== folderContentSignature) applyDirectoryData(d);
  }).catch(function () {
    // 轮询失败时保留现有列表，下一轮会自动重试。
  }).then(function () {
    folderRefreshBusy = false;
  });
}

function renderCrumbs(path) {
  var parts = path ? path.split('/') : [];
  var box = byId('crumbs');
  box.innerHTML = '';

  var root = el('a', null, '根目录');
  root.href = '#';
  root.onclick = function (e) { e.preventDefault(); load(''); };
  box.appendChild(root);

  var acc = '';
  for (var i = 0; i < parts.length; i++) {
    acc = combine(acc, parts[i]);
    box.appendChild(el('span', null, '/'));
    // IIFE 固定每一级的路径：直接在循环里闭包会拿到最后一级的值
    (function (p, name) {
      var a = el('a', null, name);
      a.href = '#';
      a.onclick = function (e) { e.preventDefault(); load(p, true); };
      box.appendChild(a);
    })(acc, parts[i]);
  }
}

function syncSortControls() {
  var labels = { name: '名称', size: '大小', mtime: '修改时间' };
  byId('sortBy').value = sortKey;
  byId('sortDirection').textContent = labels[sortKey] + ' · ' + (sortDescending ? '倒序 ↓' : '正序 ↑');
}

function renderList(items) {
  var list = byId('list');
  list.innerHTML = '';
  list.className = 'list view-' + (viewMode === 'sidebar' ? lastGridMode : viewMode);
  byId('empty').hidden = items.length > 0;
  var files = items.filter(function (it) { return !it.dir; });
  byId('selectionBar').hidden = files.length === 0;
  var visible = Object.create(null);
  files.forEach(function (it) { visible[it.path] = true; });
  Object.keys(selectedPaths).forEach(function (p) { if (!visible[p]) delete selectedPaths[p]; });

  var sortedItems = items.slice().sort(function (a, b) {
    if (!!a.dir !== !!b.dir) return a.dir ? -1 : 1;
    var order;
    if (sortKey === 'size') order = (Number(a.size) || 0) - (Number(b.size) || 0);
    else if (sortKey === 'mtime') order = (Number(a.mtime) || 0) - (Number(b.mtime) || 0);
    else order = String(a.name || '').localeCompare(String(b.name || ''), 'zh-CN', { numeric: true, sensitivity: 'base' });
    return sortDescending ? -order : order;
  });
  sortedItems.forEach(function (it) {
    var row = el('div', 'row');
    if (!it.dir) {
      var check = el('input', 'file-select');
      check.type = 'checkbox';
      check.dataset.path = it.path;
      check.dataset.name = it.name;
      check.checked = !!selectedPaths[it.path];
      check.setAttribute('aria-label', '选择 ' + it.name);
      check.onchange = function () {
        if (check.checked) selectedPaths[it.path] = it.name;
        else delete selectedPaths[it.path];
        updateSelection(files);
      };
      row.appendChild(check);
    } else {
      row.appendChild(el('div', 'select-spacer'));
    }
    row.appendChild(el('div', 'ico ' + (it.dir ? 'folder' : 'file'), it.dir ? '文件夹' : '文件'));
    if (!it.dir) {
      var ext = (it.name.split('.').pop() || '').toLowerCase();
      var thumb = el('div', 'thumb');
      if (/^(avif|bmp|gif|heic|heif|jpeg|jpg|png|webp)$/.test(ext)) {
        var image = el('img', null);
        image.alt = '';
        image.loading = 'lazy';
        image.src = '/view?path=' + enc(it.path);
        thumb.appendChild(image);
      } else {
        thumb.appendChild(el('span', 'thumb-glyph', /^(mp4|m4v|mov|webm|mkv|avi)$/.test(ext) ? '▶' : fileGlyph(ext)));
      }
      row.appendChild(thumb);
    } else {
      row.appendChild(el('div', 'thumb folder-thumb', '📁'));
    }

    var nameBox = el('div', 'name');
    if (it.dir) {
      var a = el('a', 'nm dir', it.name);
      a.href = '#';
      a.onclick = function (e) { e.preventDefault(); load(it.path, true); };
      nameBox.appendChild(a);
    } else {
      // 文件名一律用 textContent 写入。拼 innerHTML 的话，一个叫
      // `<img src=x onerror=...>` 的文件就能在这台电脑上执行脚本并读走会话 Cookie。
      var v = el('a', 'nm', it.name);
      v.href = '/view?path=' + enc(it.path);
      v.target = '_blank';
      v.rel = 'noopener';
      nameBox.appendChild(v);
    }
    row.appendChild(nameBox);
    row.appendChild(el('div', 'filemeta', it.dir ? '文件夹' : fmt(it.size || 0)));

    var acts = el('div', 'acts');
    if (!it.dir) {
      var open = el('a', 'act', '打开');
      open.href = '/view?path=' + enc(it.path);
      open.target = '_blank';
      open.rel = 'noopener';
      acts.appendChild(open);
      var dl = el('a', 'act', '下载');
      dl.href = '/dl?path=' + enc(it.path);
      acts.appendChild(dl);
    }
    if (CAN_RENAME) {
      var rn = el('button', 'act', '重命名');
      rn.onclick = function () { renameEntry(it); };
      acts.appendChild(rn);
    }
    if (CAN_DELETE) {
      var del = el('button', 'act danger', '删除');
      del.onclick = function () { deleteEntry(it); };
      acts.appendChild(del);
    }
    row.appendChild(acts);
    if (!it.dir) {
      row.addEventListener('mouseenter', function () {
        if (hoverPreviewTimer) clearTimeout(hoverPreviewTimer);
        hoverPreviewTimer = setTimeout(function () { hoverPreviewTimer = null; openPreview(it); }, 260);
      });
      row.addEventListener('mouseleave', function () {
        if (hoverPreviewTimer) { clearTimeout(hoverPreviewTimer); hoverPreviewTimer = null; }
      });
    }
    list.appendChild(row);
  });
  updateSelection(files);
}

function updateSelection(files) {
  var paths = Object.keys(selectedPaths);
  var visible = files || [];
  var count = paths.filter(function (p) {
    return visible.some(function (it) { return it.path === p; });
  }).length;
  byId('selectedCount').textContent = '已选 ' + count + ' 个';
  byId('downloadSelected').disabled = count === 0;
  byId('deleteSelected').disabled = count === 0 || !CAN_DELETE;
  byId('selectAll').checked = visible.length > 0 && count === visible.length;
  byId('selectAll').indeterminate = count > 0 && count < visible.length;
}

function openPreview(it) {
  byId('previewName').textContent = it.name;
  var ext = (it.name.split('.').pop() || '').toLowerCase();
  var src = '/view?path=' + enc(it.path);
  var content = byId('previewContent');
  content.innerHTML = '';
  if (/^(avif|bmp|gif|heic|heif|jpeg|jpg|png|webp)$/.test(ext)) {
    var image = el('img', 'preview-image'); image.src = src; image.alt = it.name; content.appendChild(image);
  } else if (/^(mp4|m4v|mov|webm|mkv|avi)$/.test(ext)) {
    var video = el('video', 'preview-video'); video.src = src; video.controls = true; video.preload = 'metadata'; content.appendChild(video);
  } else if (/^(mp3|m4a|ogg|wav|flac|aac)$/.test(ext)) {
    var audio = el('audio', 'preview-audio'); audio.src = src; audio.controls = true; content.appendChild(audio);
  } else if (ext === 'pdf') {
    var pdf = el('iframe', 'preview-document'); pdf.src = src; pdf.title = 'PDF 预览'; content.appendChild(pdf);
  } else if (/^(txt|text|log|csv|json|md)$/.test(ext)) {
    var text = el('iframe', 'preview-document'); text.src = src; text.title = '文本预览'; content.appendChild(text);
  } else {
    content.appendChild(el('div', 'preview-empty', '此格式暂不支持网页预览，请点击右上角下载。'));
  }
  byId('previewDownload').href = '/dl?path=' + enc(it.path);
  byId('previewMeta').textContent = fmt(it.size || 0) + (it.mime ? ' · ' + it.mime : '');
  byId('previewSidebar').hidden = false;
  byId('browser-content').classList.add('with-preview');
}

function closePreview() {
  byId('previewContent').innerHTML = '';
  byId('previewSidebar').hidden = true;
  byId('browser-content').classList.remove('with-preview');
}

function fileGlyph(ext) {
  if (ext === 'pdf') return 'PDF';
  if (/^(doc|docx|odt|rtf)$/.test(ext)) return '文';
  if (/^(xls|xlsx|csv)$/.test(ext)) return '表';
  if (/^(ppt|pptx)$/.test(ext)) return '演';
  if (/^(zip|7z|rar|gz|tar)$/.test(ext)) return '压';
  if (/^(mp3|m4a|ogg|wav|flac|aac)$/.test(ext)) return '♫';
  return '文档';
}

byId('selectAll').onchange = function () {
  var checks = document.querySelectorAll('#list .file-select');
  Array.prototype.forEach.call(checks, function (check) {
    check.checked = byId('selectAll').checked;
    var path = check.dataset.path;
    if (path) {
      if (check.checked) selectedPaths[path] = check.dataset.name;
      else delete selectedPaths[path];
    }
  });
  updateSelection(Array.prototype.map.call(checks, function (c) {
    return { path: c.dataset.path };
  }));
};

byId('clearSelected').onclick = function () {
  selectedPaths = Object.create(null);
  document.querySelectorAll('#list .file-select').forEach(function (c) { c.checked = false; });
  updateSelection([]);
};

byId('downloadSelected').onclick = function () {
  var paths = Object.keys(selectedPaths);
  if (!paths.length) return;
  var form = document.createElement('form');
  form.method = 'POST';
  form.action = '/api/fs/zip';
  form.style.display = 'none';
  var field = document.createElement('input');
  field.type = 'hidden'; field.name = 'paths'; field.value = JSON.stringify(paths);
  form.appendChild(field); document.body.appendChild(form); form.submit(); form.remove();
  toast('正在打包 ' + paths.length + ' 个文件为 ZIP…');
};

byId('deleteSelected').onclick = function () {
  var paths = Object.keys(selectedPaths);
  if (!paths.length || !CAN_DELETE) return;
  var names = paths.map(function (p) { return selectedPaths[p]; });
  var sample = names.slice(0, 5).join('、') + (names.length > 5 ? ' 等' : '');
  if (!confirm('确定永久删除选中的 ' + paths.length + ' 个文件吗？\n' + sample + '\n此操作无法撤销。')) return;
  byId('deleteSelected').disabled = true;
  postJson('/api/fs/batch-delete', { paths: paths }).then(function (d) {
    (d.deleted || []).forEach(function (p) { delete selectedPaths[p]; });
    if (d.ok) toast('已删除 ' + (d.deleted || []).length + ' 个文件');
    else toast((d.error || '部分文件删除失败') + '，已成功删除 ' + (d.deleted || []).length + ' 个', 'err');
    load(cwd);
  }).catch(function () {
    byId('deleteSelected').disabled = false;
    toast('批量删除请求失败，请重试', 'err');
  });
};

byId('previewClose').onclick = closePreview;
Array.prototype.forEach.call(document.querySelectorAll('.view-switch button'), function (button) {
  button.onclick = function () {
    viewMode = button.dataset.view;
    if (viewMode !== 'sidebar') lastGridMode = viewMode;
    Array.prototype.forEach.call(document.querySelectorAll('.view-switch button'), function (b) {
      b.classList.toggle('active', b === button);
    });
    if (viewMode === 'sidebar') {
      byId('previewSidebar').hidden = false;
      byId('browser-content').classList.add('with-preview');
    } else if (!byId('previewContent').firstChild) {
      byId('previewSidebar').hidden = true;
      byId('browser-content').classList.remove('with-preview');
    }
    if (cwd !== null) renderCurrentView();
  };
});
function renderCurrentView() {
  var list = byId('list');
  list.className = 'list view-' + (viewMode === 'sidebar' ? lastGridMode : viewMode);
}
document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && !byId('previewSidebar').hidden) closePreview(); });

function deleteEntry(it) {
  if (!confirm('确定删除「' + it.name + '」？此操作不可撤销。')) return;
  postJson('/api/fs/delete', { path: it.path }).then(function (d) {
    if (d.ok) { if (it.dir) removeSavedFolder(it.path); toast('已删除'); renderFavoritesHome(); load(cwd); }
    else toast(d.error || '删除失败', 'err');
  });
}

function renameEntry(it) {
  var name = prompt('重命名为', it.name);
  if (!name) return;
  var clean = name.trim();
  if (!clean || clean === it.name) return;
  postJson('/api/fs/rename', { path: it.path, to: combine(parentOf(it.path), clean) }).then(function (d) {
    if (d.ok) { if (it.dir) renameSavedFolder(it.path, combine(parentOf(it.path), clean)); toast('已重命名'); renderFavoritesHome(); load(cwd); }
    else toast(d.error || '重命名失败', 'err');
  });
}

function newFolder() {
  var name = prompt('新建文件夹名称');
  if (!name) return;
  var clean = name.trim();
  if (!clean) return;
  postJson('/api/fs/mkdir', { path: combine(cwd, clean) }).then(function (d) {
    if (d.ok) { toast('已创建 ' + clean); load(cwd); }
    else toast(d.error || '创建失败', 'err');
  });
}

// ==================== 拖拽接管（window 级） ====================

var dragDepth = 0;

function isFileDrag(e) {
  var dt = e.dataTransfer;
  if (!dt || !dt.types) return false;
  return Array.prototype.indexOf.call(dt.types, 'Files') >= 0;
}

function showOverlay(on) {
  byId('overlay').hidden = !on;
}

window.addEventListener('dragenter', function (e) {
  if (!isFileDrag(e)) return;
  e.preventDefault();
  dragDepth++;
  if (dragDepth === 1) {
    byId('ovtext').textContent = CAN_WRITE
      ? '支持整个文件夹，会保留里面的目录结构'
      : '当前是只读模式，无法上传';
    showOverlay(true);
  }
});

// dragover 必须 preventDefault，否则 drop 事件根本不会触发，
// 浏览器会退回默认行为：把本地文件当成导航目标打开。
window.addEventListener('dragover', function (e) {
  if (!isFileDrag(e)) return;
  e.preventDefault();
  e.dataTransfer.dropEffect = CAN_WRITE ? 'copy' : 'none';
});

window.addEventListener('dragleave', function (e) {
  if (!isFileDrag(e)) return;
  dragDepth--;
  if (dragDepth <= 0) { dragDepth = 0; showOverlay(false); }
});

window.addEventListener('drop', function (e) {
  // 无条件拦掉默认动作：拖进来的 html 被浏览器直接打开，就是因为这里漏了
  e.preventDefault();
  dragDepth = 0;
  showOverlay(false);
  if (!CAN_WRITE) { toast('当前为只读模式，无法上传', 'err'); return; }
  collectPlan(e.dataTransfer).then(function (plan) {
    if (!plan.files.length && !plan.dirs.length) { toast('没有识别到文件或文件夹', 'err'); return; }
    runPlan(plan);
  });
});

/*
 * 把 DataTransfer 变成一份"目录 + 文件"的清单。
 *
 * 必须**同步**把 DataTransferItem 转成 entry：事件处理函数返回之后，
 * DataTransferItemList 就失效了，之后再调 webkitGetAsEntry() 只会得到 null。
 */
function collectPlan(dt) {
  var out = { files: [], dirs: [] };
  var items = dt.items;
  if (items && items.length) {
    var entries = [];
    for (var i = 0; i < items.length; i++) {
      if (items[i].kind !== 'file') continue;
      var entry = items[i].webkitGetAsEntry ? items[i].webkitGetAsEntry() : null;
      if (entry) entries.push(entry);
    }
    if (entries.length) return walkEach(entries, '', out);
  }
  return Promise.resolve(addPlainFiles(dt.files, '', out));
}

function walkEach(entries, prefix, out) {
  var chain = Promise.resolve();
  entries.forEach(function (entry) {
    chain = chain.then(function () { return walkOne(entry, prefix, out); });
  });
  return chain.then(function () { return out; });
}

function walkOne(entry, prefix, out) {
  if (entry.isFile) {
    return new Promise(function (resolve) {
      entry.file(function (f) {
        out.files.push({ file: f, dir: prefix });
        resolve();
      }, function () { resolve(); });
    });
  }
  if (!entry.isDirectory) return Promise.resolve();
  var dir = combine(prefix, entry.name);
  out.dirs.push(dir);
  return readAll(entry.createReader()).then(function (children) {
    return walkEach(children, dir, out);
  });
}

/**
 * 循环读取目录。
 *
 * readEntries() 每次最多返回 100 条，读完必须再调一次直到返回空数组。
 * 只调一次是这个 API 最经典的坑：100 个文件以内一切正常，
 * 超了就**静默少文件**，而且不报任何错。
 */
function readAll(reader) {
  return new Promise(function (resolve) {
    var all = [];
    function step() {
      reader.readEntries(function (batch) {
        if (!batch.length) { resolve(all); return; }
        all = all.concat(Array.prototype.slice.call(batch));
        step();
      }, function () { resolve(all); });
    }
    step();
  });
}

/** 输入框选出来的文件；从 webkitdirectory 来的还带相对路径。 */
function addPlainFiles(fileList, prefix, out) {
  if (!fileList) return out;
  for (var i = 0; i < fileList.length; i++) {
    var f = fileList[i];
    var dir = prefix;
    if (f.webkitRelativePath && f.webkitRelativePath.indexOf('/') >= 0) {
      var rel = f.webkitRelativePath;
      dir = combine(prefix, rel.substring(0, rel.lastIndexOf('/')));
    }
    out.files.push({ file: f, dir: dir });
  }
  return out;
}

// ==================== 上传 ====================

/*
 * 本轮计划（一次拖拽 / 一次选择）的计数。
 *
 * 刻意不在这里记"已暂停"：暂停是**服务端意图**，谁按的都算，
 * 只有 /api/fs/tasks 那一份快照才知道全貌（手机按的暂停也在里面）。
 * 本地再记一份的结果一定是两边对不上，界面上就会出现"概览说暂停 1 个，列表里 0 个"。
 */
var stat = { done: 0, failed: 0, canceled: 0, bytes: 0, total: 0 };

/* 服务端权威快照：path -> {name,direction,state,transferred,total,percent} */
var liveByPath = {};
/* path -> 行句柄。同一个路径只允许有一行，本地行和服务端行共用它 */
var rowByPath = {};
/* 所有行句柄，用于"清空"时逐行判断该不该留 */
var rowsAll = [];
/* path -> 上一次画上去的按钮签名，避免每 2 秒把 DOM 重建一遍 */
var lastActs = {};
/* path -> 因为用户暂停（或退避用尽）而停下来的上传，等"开始"之后由本页接着传 */
var stopped = {};

/* 本轮上传的调度状态。running 是"正在传的文件数"，新传与续传共用同一个上限。 */
var up = { queue: [], idx: 0, running: 0, resumed: [], reported: false, earlyPoll: false, batchId: '' };

// ==================== 概览 ====================

function statCell(label, value, cls) {
  var box = el('div', 'ostat ' + cls);
  box.appendChild(el('div', 'k', label));
  box.appendChild(el('div', 'v', String(value)));
  return box;
}

/*
 * 传输任务概览。
 *
 * "进行中 / 已暂停"取自服务端快照，所以它包含**所有**客户端发起的传输 ——
 * 手机往电视推的文件、另一台电脑正在下的文件，在这台电脑的页面上同样看得见。
 * "已完成 / 失败"是本轮计划的结果：服务端不留历史（那是手机端「传输记录」的事），
 * 把它算进来是因为用户刚拖完一批文件时最想知道的正是"成没成"。
 */
function renderOverview() {
  var box = byId('oview');
  var running = 0, paused = 0, left = 0, liveTotal = 0, hasLive = false;
  var items = liveList();
  for (var i = 0; i < items.length; i++) {
    var t = items[i];
    // 墓碑期的已取消任务不算"在传"，它只是还挡着迟到的分片
    if (t.state === 'CANCELED') continue;
    hasLive = true;
    if (t.state === 'PAUSED') paused++; else running++;
    left += Math.max(0, (t.total || 0) - (t.transferred || 0));
    liveTotal += (t.total || 0);
  }

  box.innerHTML = '';
  box.appendChild(statCell('进行中', running, 'run'));
  box.appendChild(statCell('已暂停', paused, 'pause'));
  box.appendChild(statCell('已完成', stat.done, 'done'));
  box.appendChild(statCell('失败', stat.failed, 'fail'));

  var note;
  if (hasLive) {
    note = '还剩 ' + fmt(left) + ' 没传完（共 ' + fmt(liveTotal) + '）';
  } else if (stat.total) {
    note = '本轮 ' + (stat.done + stat.failed + stat.canceled) + ' / ' + stat.total +
      ' 已处理 · 已传 ' + fmt(stat.bytes);
  } else {
    note = '现在没有正在进行的传输';
  }
  if (stat.canceled) note += ' · 已取消 ' + stat.canceled + ' 个';
  box.appendChild(el('div', 'oview-note', note));

  // 总进度条：有在传的就按字节算（那才是"还剩多少"），否则退回本轮计划的任务数
  var pct = 0;
  if (hasLive && liveTotal > 0) {
    pct = Math.round((liveTotal - left) / liveTotal * 100);
  } else if (stat.total) {
    pct = Math.round((stat.done + stat.failed + stat.canceled) / stat.total * 100);
  }
  byId('tbar').style.width = pct + '%';
  byId('thint').textContent = CAN_WRITE ? '' : '只读：可以看进度，但不能控制上传';
}

function liveList() {
  var out = [];
  for (var p in liveByPath) {
    if (Object.prototype.hasOwnProperty.call(liveByPath, p)) out.push(liveByPath[p]);
  }
  return out;
}

function taskState(path) {
  var t = liveByPath[path];
  return t ? t.state : '';
}

/* 已经收尾的状态：传完、失败、取消。这些行不该再长出按钮来。 */
function isTerminal(state) {
  return state === 'done' || state === 'failed' || state === 'canceled';
}

// ==================== 暂停 / 开始 / 取消 ====================

/*
 * 网页端的按钮为什么必须把意图交给服务端，而不是自己记一份状态
 *
 * 这个页面上传的执行者是这个浏览器 —— 服务端够不着它。但反过来，手机端也够不着
 * 浏览器。如果"暂停"只改本页的一个变量，手机上看到的还是"进行中"，
 * 手机按的取消也拦不住已经开始的那条连接。所以意图只有一个存放点：服务端。
 * 本页每 2 秒读一次它，读完照着做：该停就停，该从断点接着传就接着传。
 *
 * 于是"用户在手机上点了开始，电脑这边继续传"这件事不需要任何额外机制 ——
 * 两台设备都只是在读同一个意图。
 */
function sendControl(action, path) {
  return postJson('/api/fs/control', { action: action, path: path || '' }).then(function (d) {
    if (!d) return false;
    if (d.ok) return true;
    toast(d.error || d.message || '这条传输现在动不了', 'err');
    return false;
  });
}

function onTaskAction(key, h) {
  if (!h.path) return;
  sendControl(key, h.path).then(function (ok) {
    if (!ok) return;
    // 乐观地改一下本页的按钮，等服务端快照到来再以它为准：
    // 这一步让按钮的手感是"立刻生效"，而不是"最慢要等 2 秒"。
    var t = liveByPath[h.path];
    if (t) t.state = key === 'pause' ? 'PAUSED' : (key === 'resume' ? 'RUNNING' : 'CANCELED');

    if (key === 'pause') {
      h.mark('paused', 'hold', '已暂停 ' + (h.p || 0) + '%');
      h.actions([{ key: 'resume', label: '开始' }, { key: 'cancel', label: '取消', cls: 'stop' }]);
    } else if (key === 'cancel') {
      h.canceled();
      delete stopped[h.path];
    } else {
      h.mark('', '', (h.p || 0) + '%', (h.p || 0) + '%');
      h.actions([{ key: 'pause', label: '暂停' }, { key: 'cancel', label: '取消', cls: 'stop' }]);
      // 服务端已经改成运行态了（POST 是同步处理的），所以现在就叫醒本页这条上传。
      // 即便叫早了也不会传错：闸门还没开的话，第一个分片会收到 423，
      // 它会自己回到"已暂停"，等下一次轮询再接着来。
      wakeLocal(h.path);
    }
    renderOverview();
  });
}

/*
 * 哪几个按钮该出现，只由三件事决定：方向、服务端意图、本会话的权限。
 *
 *  - 服务端意图是唯一的真相（用户可能在手机上按的暂停）；
 *  - 上传要写权限才给按钮 —— 只读会话按了也会被服务端拒掉，不如不显示；
 *  - 下载只给"取消"。服务端的发送循环一旦被暂停就断了供流，浏览器这一侧
 *    没法从断点续上；重新点一次下载才是真正可行的恢复方式（服务端支持 Range，
 *    浏览器会带着 `Range: bytes=N-` 回来接着下）。
 */
function actionsFor(t, row) {
  if (!t || !row || !t.path) return [];
  if (t.direction === 'UPLOAD') {
    if (!CAN_WRITE) return [];
    if (t.state === 'PAUSED') {
      return [{ key: 'resume', label: '开始' }, { key: 'cancel', label: '取消', cls: 'stop' }];
    }
    if (t.state === 'RUNNING') {
      return [{ key: 'pause', label: '暂停' }, { key: 'cancel', label: '取消', cls: 'stop' }];
    }
    // CANCELED 是终态，不再给按钮
    return [];
  }
  if (t.state === 'RUNNING' || t.state === 'PAUSED') {
    return [{ key: 'cancel', label: '取消', cls: 'stop' }];
  }
  return [];
}

/**
 * 把一行任务从列表与索引里摘干净。
 *
 * 索引必须一起删：`rowByPath` 里留着一个已经不在 DOM 上的句柄，
 * 之后同名的传输就会去更新一个看不见的行 —— 界面上表现为"传了但什么都没显示"。
 */
function dropHandle(h) {
  if (!h) return;
  if (h.path && rowByPath[h.path] === h) {
    delete rowByPath[h.path];
    delete lastActs[h.path];
  }
  var i = rowsAll.indexOf(h);
  if (i >= 0) rowsAll.splice(i, 1);
}

function removeRow(h) {
  if (!h) return;
  if (h.el && h.el.parentNode) h.el.parentNode.removeChild(h.el);
  dropHandle(h);
}

function hasStopped() {
  for (var p in stopped) {
    if (Object.prototype.hasOwnProperty.call(stopped, p)) return true;
  }
  return false;
}

function countStopped() {
  var n = 0;
  for (var p in stopped) {
    if (Object.prototype.hasOwnProperty.call(stopped, p)) n++;
  }
  return n;
}

function runPlan(plan) {
  var base = cwd;
  var dirs = [];
  plan.dirs.forEach(function (d) {
    var p = combine(base, d);
    if (p && dirs.indexOf(p) < 0) dirs.push(p);
  });
  plan.files.forEach(function (it) { it.target = combine(base, it.dir); });

  byId('tasks').hidden = false;
  beginPlan(plan.files.length + plan.dirs.length);
  byId('ovtext').textContent = '正在处理 ' + plan.files.length + ' 个文件';

  var dirRow = plan.dirs.length ? addTaskRow('创建 ' + plan.dirs.length + ' 个文件夹', '') : null;

  // 先建目录再传文件。顺序不能反：服务端 PUT 时也要能建出父目录，
  // 但先把空目录建好才能保留"文件夹里本来就没有文件"的结构。
  mkdirAll(dirs).then(function (failedDirs) {
    if (dirRow) {
      stat.done += dirs.length - failedDirs;
      stat.failed += failedDirs;
      if (failedDirs) dirRow.fail('有 ' + failedDirs + ' 个文件夹没能创建');
      else dirRow.done('完成');
      renderOverview();
    }
    enqueuePlan(plan.files);
    pumpAll();
  });
}

function mkdirAll(dirs) {
  var failed = 0;
  var chain = Promise.resolve();
  dirs.forEach(function (p) {
    chain = chain.then(function () {
      return postJsonRetry('/api/fs/mkdir', { path: p }, 2).then(function (d) {
        if (!d || d.ok === false) { failed++; toast('建目录失败：' + p, 'err'); }
      }).catch(function () {
        failed++;
        toast('建目录请求失败：' + p, 'err');
      });
    });
  });
  return chain.then(function () { return failed; });
}

/**
 * 上传调度泵。
 *
 * ## 为什么要排队，而不是一次全发
 * 一个大文件夹会瞬间开出几百个 XHR，浏览器连接数上限只有 6，
 * 结果是所有任务的进度都停在 0% 附近抖动，看起来像卡死。
 * 排队之后至少前几个文件是一个一个稳稳完成的。
 *
 * ## 为什么"接着传"要和"新传"共用一个上限
 * 用户按下开始，十几个暂停的文件会同时被叫醒。各开四路的话一下子就是几十条连接 ——
 * 与"一次全发"是同一种病。所以续传也从这个泵里领名额。
 */
function pumpAll() {
  while (up.running < MAX_PARALLEL) {
    var job = nextJob();
    if (!job) break;
    up.running++;
    // IIFE 固定住本轮这个 job：`var` 没有块级作用域，不用它的话
    // 每个回调都会看到最后一次循环赋的值，收尾时扣的就是别人的账。
    (function (j) {
      runJob(j).catch(function (error) {
        var row = j.item.row;
        if (!row && j.item.file) {
          row = addTaskRow(j.item.file.name, '', '', 'UPLOAD');
          j.item.row = row;
        }
        if (row) row.fail('上传异常：' + (error && error.message ? error.message : '未知错误'));
        return 'fail';
      }).then(function (res) {
        up.running--;
        settleResult(j.item, res);
        pumpAll();
      });
    })(job);
  }
  maybeFinish();
}

function nextJob() {
  if (up.idx < up.queue.length) {
    var item = up.queue[up.idx++];
    return { item: item, resume: false };
  }
  if (up.resumed.length) {
    var it = up.resumed.shift();
    // 已经被取消的不用再传了（它可能是在队列里排队时被取消的）
    if (!it.row || it.row.state === 'canceled') return nextJob();
    return { item: it, resume: true };
  }
  return null;
}

/*
 * 续传必须重问一次服务端的位点，不能拿"我上次发到哪了"接着发。
 * 后者看着更省事，其实是错的：`upload.onprogress` 报的是"交给了 socket 缓冲"，
 * 服务端落盘了多少并不知道 —— 而且用户还可能是在手机上点的开始，
 * 这一侧对进度的了解完全来自服务端的续传会话。
 */
function runJob(job) {
  if (!job.resume) return uploadFile(job.item);
  var it = job.item;
  it.row.mark('', '', (it.row.p || 0) + '%', (it.row.p || 0) + '%');
  it.row.actions(actionsFor(liveByPath[it.target], it.row));
  return queryResume(it.target).then(function (info) {
    return putFile(it.file, it.target, it.row,
      (info && info.ok) ? info : { ok: true, path: it.target, lanes: laneCount(it.file) });
  });
}

/* 一次上传的最终归宿。四态而不是布尔：暂停和取消都不是失败，
   把它们算进"失败"会让用户以为自己传坏了一个文件。 */
function settleResult(item, res) {
  if (res === 'ok') {
    stat.done++;
    stat.bytes += item.file.size;
  } else if (res === 'fail') {
    stat.failed++;
  } else if (res === 'canceled') {
    stat.canceled++;
  }
  renderOverview();
}

function maybeFinish() {
  if (up.running > 0 || up.idx < up.queue.length || up.resumed.length) {
    renderOverview();
    return;
  }
  // 还有暂停中的就不算结束：它们在等用户按开始，不是失败，也不是完成
  if (hasStopped()) {
    renderOverview();
    return;
  }
  if (!stat.total || up.reported) {
    renderOverview();
    return;
  }
  finishPlan();
}

function finishPlan() {
  up.reported = true;
  renderOverview();
  var tail = (stat.canceled ? '，' + stat.canceled + ' 个已取消' : '') +
    (hasStopped() ? '，' + countStopped() + ' 个暂停中' : '');
  toast('上传结束：' + stat.done + ' 个成功' + (stat.failed ? '，' + stat.failed + ' 个失败' : '') + tail,
    stat.failed ? 'err' : 'ok');
  load(cwd);
}

/* 记下"暂停/中断时这一行还没传完"，等用户点开始或者下一次轮询再接着传。 */
function stopItem(target, file, row) {
  stopped[target] = { file: file, target: target, row: row };
}

/*
 * 用户点了"开始"：本页那条上传自己接着干。
 *
 * 之所以在轮询里也会调它（而不是只在按钮回调里），是为了覆盖"用户在**手机**上
 * 点的开始"：意图存在服务端，两边都只是读它 —— 电脑这一侧读到运行态就自己续上，
 * 不需要两台设备互相通信。
 */
function wakeLocal(path) {
  var it = stopped[path];
  if (!it) return;
  delete stopped[path];
  if (!it.row || it.row.state === 'canceled') return;
  up.resumed.push(it);
  pumpAll();
}

function beginPlan(total) {
  var batchId = (window.crypto && crypto.randomUUID) ? crypto.randomUUID() :
    ('batch-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2));
  up = { queue: [], idx: 0, running: 0, resumed: [], reported: false, earlyPoll: false, batchId: batchId };
  stopped = {};
  stat = { done: 0, failed: 0, canceled: 0, bytes: 0, total: total };
  byId('tasklist').innerHTML = '';
  rowByPath = {};
  rowsAll = [];
  lastActs = {};
  byId('tasks').hidden = false;
  renderOverview();
}

function enqueuePlan(files) {
  up.queue = files || [];
  up.idx = 0;
  up.queue.forEach(function (item, index) {
    // 同一条任务的预留重试必须复用 ID，避免网络丢响应时重复占用重名路径。
    item.requestId = up.batchId + '-' + index;
  });
}

/**
 * 造一行任务。
 *
 * [path] 与 [direction] 是可选的，传了这行才能被控制 —— 按钮要按路径去服务端
 * 找那条传输的闸门。建文件夹这类**纯本地**动作没有路径，它永远只有进度没有按钮，
 * 这也提醒了我们一件事实：控制按钮不是装饰，它对应的是服务端的一条真实连接。
 */
function addTaskRow(name, sub, path, direction) {
  var list = byId('tasklist');
  // 行数封顶：一个上万文件的目录不能把 DOM 撑爆
  while (list.childElementCount >= MAX_ROWS) {
    var first = list.firstChild;
    if (first && first.__h) dropHandle(first.__h);
    if (first) list.removeChild(first);
  }

  var row = el('div', 'task');
  if (direction) row.appendChild(el('span', 'dirbadge', direction === 'DOWNLOAD' ? '下载' : '上传'));
  row.appendChild(el('div', 'tn', name));
  var barBox = el('div', 'bar');
  var fill = el('i');
  barBox.appendChild(fill);
  row.appendChild(barBox);
  var pct = el('div', 'tp', sub || '0%');
  row.appendChild(pct);
  var acts = el('div', 'tacts');
  row.appendChild(acts);
  list.appendChild(row);
  list.scrollTop = list.scrollHeight;

  var h = {
    el: row,
    path: path || '',
    dir: direction || '',
    p: 0,
    /* 状态：'' 进行中 / 'paused' / 'canceled' / 'done' / 'failed' */
    state: '',
    /* 本页正在传的行：进度由 XHR 的 onprogress 驱动，比 2 秒轮询平滑得多。
       服务端驱动的行（别的客户端发起的传输）只能靠轮询。 */
    local: false,

    /* 原始写法：文案与进度条宽度都自己给。 */
    set: function (text, width) {
      // 暂停/取消/结束之后，本地还在飞的 XHR 回调不该再把文案改回百分比 ——
      // 用户看到"已暂停"又跳成"43%"，会以为按钮没生效。
      if (h.state) return;
      pct.textContent = text;
      if (width) fill.style.width = width;
    },

    /* 运行中的百分比。[suffix] 用来补"（4 路并行）"这类说明。 */
    progress: function (p, suffix) {
      h.p = p;
      h.set(p + '%' + (suffix || ''), p + '%');
    },

    /*
     * 状态切换。文案给出去了就不再被百分比覆盖（见 set 里的守卫），
     * 因为"已暂停"这三个字比"43%"重要得多。
     */
    mark: function (state, cls, text, width) {
      h.state = state || '';
      row.className = 'task' + (h.state ? ' state-' + h.state : '');
      if (cls !== undefined && cls !== null) fill.className = cls;
      if (width) fill.style.width = width;
      if (text !== undefined && text !== null) pct.textContent = text;
    },

    /*
     * 终态：标记状态并**撤掉所有按钮**。
     *
     * 不撤的话，一条已经传完的任务上还挂着「暂停 / 取消」；点下去服务端只会回一句
     * "这条传输已经不在了，没有可控制的对象" —— 而用户根本无从理解这句话。
     * 这正是这一轮要消灭的那种假按钮：看起来能用，用起来只给一句莫名其妙的报错。
     */
    finish: function (state, cls, text, width) {
      h.mark(state, cls, text, width);
      h.actions([]);
    },

    done: function (text) {
      h.finish('done', 'done', text || '完成', '100%');
    },

    fail: function (text) {
      row.title = text || '失败';
      pct.title = text || '失败';
      h.finish('failed', 'fail', text || '失败', '100%');
    },

    canceled: function (text, width) {
      h.finish('canceled', 'fail', text || '已取消', width);
    },

    /*
     * 按服务端状态对齐按钮。
     * 只有签名变了才重建 DOM —— 每 2 秒无条件重建的话，用户的手指可能正好
     * 落在被替换掉的那个按钮上，点击会丢。
     */
    actions: function (list2) {
      var key = h.path || '';
      var sig = list2.map(function (a) { return a.key; }).join(',');
      if (lastActs[key] === sig) return;
      lastActs[key] = sig;
      acts.innerHTML = '';
      list2.forEach(function (a) {
        var b = el('button', a.cls || '', a.label);
        b.type = 'button';
        b.onclick = function () { onTaskAction(a.key, h); };
        acts.appendChild(b);
      });
    }
  };

  row.__h = h;
  rowsAll.push(h);
  if (h.path) rowByPath[h.path] = h;
  return h;
}

/* 分几路传。
   服务端要靠它判断"这个文件传完了没有"（分块上传会先按最终大小预分配，
   磁盘大小在第一个分片到达时就等于整个文件了，不能用它判完成），
   而且服务端的续传会话就是按这个路数切段的，
   所以 reserve 与 putFile 必须用同一套算法 —— 两处不一致的话，
   要么服务端一直等一个等不到的分片数，要么续传的段位整体错开。 */
function laneCount(file) {
  if (file.size < LARGE || LANES <= 1) return 1;
  return Math.min(LANES, Math.max(2, Math.ceil(file.size / LARGE)));
}

/* 网络抖一下就放弃太脆了：局域网里传几个 GB，中途断一次是常态而不是异常。
   三次退避之后还失败就说明是别的问题（拔网线、服务停了），再试只是浪费用户时间。 */
var MAX_LANE_RETRY = 3;

/* 问服务端"这个文件你那边写到哪了"。
   拿不到、或服务端说不可续传，一律返回 null，调用方据此从头传 ——
   续传是优化，不能变成前置条件：它挂了也得能正常上传。 */
function queryResume(path) {
  return fetch('/api/fs/upload-status?path=' + enc(path))
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (d) {
      if (!d || !d.ok || !d.resumable) return null;
      /*
       * 两个字段名说的是同一件事，但来源不同：reserve 那边回的是 `resume`，
       * upload-status 回的是 `resumable`。这里统一补齐，免得下游按其中一个判空 ——
       * 单路续传正是按 `info.resume` 决定从哪儿接着发的，漏了它会从头重传。
       */
      d.resume = true;
      return d;
    })
    .catch(function () { return null; });
}

/* 发一段字节。返回 {ok, fallback, paused, canceled}：
     ok       —— 这一段服务端已经收下了
     fallback —— 服务端明确说不支持按位点写（SAF 存储通道），重试也没用
     paused   —— 这条传输被用户暂停了（423）：不要重试，静静等着
     canceled —— 这条传输被用户取消了（410）：就此收手
   后两个必须与"失败"分开：把用户主动按下的暂停当成网络错误去退避重试，
   服务端会被同一批分片反复敲门，而界面上"已暂停"会显示成"失败"。 */
function putSlice(target, lane, offset, blob, onProgress) {
  return new Promise(function (resolve) {
    var xhr = new XMLHttpRequest();
    var url = '/api/fs/file?path=' + enc(target);
    /* offset 省略 = "从零开始写一个新文件"。
       注意 offset=0 与"不带 offset"**不是一回事**：带上就会走随机写通道，
       而 SAF 通道不支持随机写，会直接 409 —— 首传绝不能带 offset=0。 */
    if (offset !== null && offset !== undefined) {
      url += '&offset=' + offset + '&lane=' + (lane || 0);
    }
    xhr.open('PUT', url);
    if (onProgress) {
      xhr.upload.onprogress = function (e) {
        if (!e.lengthComputable) return;
        onProgress(e.loaded);
      };
    }
    xhr.onload = function () {
      var code = xhr.status;
      // 423 LOCKED = 已暂停，410 GONE = 已取消；语义由服务端的控制闸门给出。
      // 另一种情形是"暂停恰好发生在请求体传到一半时"：那时连接被掐断，
      // 这一侧看到的会是 500 或者一个断开的连接 —— 那一支靠轮询到的意图兜底。
      if (code === 423) { resolve({ ok: false, paused: true }); return; }
      if (code === 410) { resolve({ ok: false, canceled: true }); return; }
      if (code === 409) { resolve({ ok: false, fallback: true }); return; }
      resolve({ ok: code >= 200 && code < 300, fallback: false });
    };
    xhr.onerror = function () { resolve({ ok: false, fallback: false }); };
    xhr.onabort = function () { resolve({ ok: false, fallback: false }); };
    xhr.send(blob);
  });
}

/* 发一整条"路"：[fromAbs, endAbs)。

   断线之后**回头问服务端写到哪了**再接着发，而不是拿"自己发出去了多少"接着发。
   后者看着更省事，其实是错的：`upload.onprogress` 报的是"交给了 socket 缓冲"，
   服务端落盘了多少并不知道，拿它当续传起点会跳过一段没落盘的字节 ——
   文件大小完全正常，打开却是坏的。这是断点续传里最容易做错、
   也最难被发现的一处，所以这里宁可多问一次服务端。 */
function runLane(file, target, lane, fromAbs, endAbs, onBytes) {
  var attempts = 0;
  function step(from) {
    if (from >= endAbs) return Promise.resolve({ ok: true });
    return putSlice(target, lane, from, file.slice(from, endAbs), function (loaded) {
      onBytes(from - fromAbs + loaded);
    }).then(function (r) {
      if (r.ok) { onBytes(endAbs - fromAbs); return { ok: true }; }
      if (r.fallback) return { ok: false, fallback: true };

      /*
       * 用户意图优先于网络重试。
       *
       * 服务端的意图就存在那条闸门里，本页从 2 秒一次的轮询快照里读它；
       * 如果这一发直接拿到了 423/410，连快照都不用等。
       * 判定的顺序不能反：先看用户说了什么，再看网络怎么样 ——
       * 反过来的话，用户按下暂停之后要白等三次退避才肯停，进度条会又往前走一段。
       */
      var intent = r.canceled ? 'CANCELED' : (r.paused ? 'PAUSED' : taskState(target));
      if (intent === 'CANCELED') return { ok: false, canceled: true };
      if (intent === 'PAUSED') return { ok: false, paused: true };

      attempts++;
      if (attempts > MAX_LANE_RETRY) return { ok: false };
      return queryResume(target).then(function (info) {
        var persisted = (info && info.done && info.done[lane] != null) ? info.done[lane] : fromAbs;
        // 必须落在 [fromAbs, endAbs] 内：服务端可能因为别的分片把位点推得更远
        return step(Math.max(fromAbs, Math.min(persisted, endAbs)));
      });
    });
  }
  return step(fromAbs);
}

function uploadFile(item) {
  var row = addTaskRow(item.file.name, '占位中', '', 'UPLOAD');
  item.row = row;
  row.local = true;
  return postJsonRetry('/api/fs/reserve', {
    name: item.file.name,
    dir: item.target,
    size: item.file.size,
    lanes: laneCount(item.file),
    batchId: up.batchId,
    requestId: item.requestId
  }, 2, function (attempt, delay, reason) {
    row.mark('', '', '网络波动，正在重试预留（' + attempt + '/2）', '0%');
    row.el.title = '重试原因：' + reason + '；约 ' + (delay / 1000).toFixed(1) + ' 秒后重试';
  }).then(null, function (error) {
    return {
      ok: false,
      __reserveRequestFailed: true,
      error: error && error.message ? error.message : '网络连接中断或服务端无响应'
    };
  }).then(function (d) {
    if (!d || d.ok === false || !d.path) {
      var status = d && d.__httpStatus ? '（HTTP ' + d.__httpStatus + '）' : '';
      var prefix = d && d.__reserveRequestFailed ? '预留请求失败：' : '预留失败' + status + '：';
      row.fail(prefix + (d && d.error ? d.error : '无法创建文件'));
      return 'fail';
    }
    /*
     * 目标路径到手，这一行从现在起可以被控制了：把它挂到路径索引上，
     * 之后轮询到的服务端状态会送到同一行 —— 用户不会看到"传输任务"里
     * 出现两行同一个文件（一行本地记的，一行服务端报的）。
     */
    row.path = d.path;
    rowByPath[d.path] = row;
    lastActs[d.path] = null;
    item.target = d.path;

    /*
     * 补一次快照。
     *
     * 用户刚把文件拖进来时最想看到的就是"它在传、我也能停它"，而 2 秒的轮询周期
     * 会让概览在这段时间里显示成"现在没有正在进行的传输"，按钮也要等下一轮才冒出来。
     * 只补一次（不是每个文件一次）：一个 500 个文件的文件夹不该顺手多发 500 个请求。
     */
    if (!up.earlyPoll) {
      up.earlyPoll = true;
      setTimeout(pollTasks, 350);
    }
    return putFile(item.file, d.path, row, d);
  });
}

/* [info] 是 reserve 的响应。它带着"上次传到哪了"，所以续传不需要用户做任何操作 ——
   重新把同一个文件拖进来，就会自动从断点继续。 */
function putFile(file, target, row, info) {
  var lanes = (info && info.lanes > 0) ? info.lanes : laneCount(file);
  if (lanes <= 1) return putSingle(file, target, row, info);

  var chunk = Math.floor(file.size / lanes);
  var specs = [];
  var doneBytes = [];
  var resumed = 0;
  for (var i = 0; i < lanes; i++) {
    var start = i * chunk;
    var end = (i === lanes - 1) ? file.size : start + chunk;
    var from = start;
    if (info && info.done && info.done[i] != null) {
      from = Math.max(start, Math.min(info.done[i], end));
    }
    if (from > start) resumed++;
    specs.push({ start: start, end: end, from: from });
    doneBytes.push(from - start);
  }

  function bump() {
    var sum = 0;
    for (var j = 0; j < doneBytes.length; j++) sum += doneBytes[j];
    var p = file.size ? Math.round(sum / file.size * 100) : 100;
    var tail = resumed > 0 ? '，已从断点继续 ' + resumed + ' 路' : '';
    row.progress(p, '（' + lanes + ' 路' + tail + '）');
  }

  var tasks = [];
  for (var k = 0; k < lanes; k++) {
    (function (idx) {
      tasks.push(runLane(file, target, idx, specs[idx].from, specs[idx].end, function (bytes) {
        doneBytes[idx] = bytes;
        bump();
      }));
    })(k);
  }
  // 同 putSingle：不依赖 onprogress 来脱离"占位中"状态
  bump();

  return Promise.all(tasks).then(function (results) {
    var fallback = false;
    var allOk = true;
    var paused = false;
    var canceled = false;
    for (var m = 0; m < results.length; m++) {
      var r = results[m];
      if (!r.ok) allOk = false;
      if (r.fallback) fallback = true;
      if (r.paused) paused = true;
      if (r.canceled) canceled = true;
    }
    if (allOk) {
      row.done('完成（' + lanes + ' 路并行）');
      return 'ok';
    }
    /*
     * 用户意图优先于这一路的成败 —— 与手机端、与记录落库用的是同一条规则。
     * 四路里只要有一路是被用户停掉的，整条上传就该显示"已暂停 / 已取消"，
     * 而不是"失败"：把用户自己按的按钮说成失败，他会以为文件传坏了。
     */
    if (canceled) {
      row.canceled();
      return 'canceled';
    }
    if (paused) {
      row.mark('paused', 'hold', '已暂停 ' + row.p + '%');
      stopItem(target, file, row);
      return 'paused';
    }
    if (fallback) {
      // 服务端说这条存储通道不支持按位点写（SAF）。不是错误，是能力差异：
      // 多路并发的**前提**就是随机写，这条路走不通就退化成单路整传。
      // 直接判失败是错的 —— "请重新选择该文件"照做一遍还是同一个结果，
      // 用户会以为是自己操作错了。
      // 此刻磁盘上一个字节都还没落盘（每一路都是带 offset 的写，
      // 服务端在写入前就拒了），所以整文件重传不会留下半截数据。
      toast('当前存储通道不支持分块，改为整文件上传', 'warn');
      return putSingle(file, target, row, { ok: true, path: target, lanes: 1 });
    }
    row.fail('已中断，重新选择该文件会从断点继续');
    return 'fail';
  });
}

function putSingle(file, target, row, info) {
  var from = 0;
  if (info && info.resume && info.done && info.done.length) {
    from = Math.max(0, Math.min(info.done[0] || 0, file.size));
  }
  var attempts = 0;

  function show(cur) {
    row.progress(file.size ? Math.round(cur / file.size * 100) : 0);
  }

  function step(start) {
    if (start >= file.size) { row.done('完成'); return Promise.resolve('ok'); }
    /* 首传（start === 0）不带 offset：SAF 通道只支持"追加写"，
       带上 offset=0 会走随机写通道并被拒绝。 */
    var offset = start > 0 ? start : null;
    show(start);
    return putSlice(target, 0, offset, file.slice(start), function (loaded) {
      show(start + loaded);
    }).then(function (r) {
      if (r.ok) { row.done('完成'); return 'ok'; }

      // 同 runLane：先看用户说了什么，再看网络怎么样
      var intent = r.canceled ? 'CANCELED' : (r.paused ? 'PAUSED' : taskState(target));
      if (intent === 'CANCELED') { row.canceled(); return 'canceled'; }
      if (intent === 'PAUSED') {
        row.mark('paused', 'hold', '已暂停 ' + row.p + '%');
        stopItem(target, file, row);
        return 'paused';
      }

      attempts++;
      if (r.fallback && start > 0) {
        // 服务端不支持按位点写，退一步整文件重传。
        // 用户看不懂"409"，但他能看懂"改为从头重传"。
        toast('当前存储通道不支持断点续传，正在从头重传', 'warn');
        return step(0);
      }
      if (attempts > MAX_LANE_RETRY) { row.fail('网络中断'); return 'fail'; }
      return queryResume(target).then(function (again) {
        var persisted = (again && again.done && again.done[0] != null) ? again.done[0] : 0;
        return step(Math.max(0, Math.min(persisted, file.size)));
      });
    });
  }

  return step(from);
}

// ==================== 服务端任务快照（轮询） ====================

/*
 * 每 2 秒读一次服务端的任务快照。它同时管三件事：
 *  1. 概览上的"进行中 / 已暂停"；
 *  2. 每一行该出现哪几个按钮；
 *  3. 用户点了"开始"之后本页这条上传要不要接着传 —— 网页这一侧的执行者是自己，
 *     服务端只能表达意图，真正动手必须由这里发起。
 *
 * 为什么不做推送（WebSocket / SSE）：局域网里这是唯一需要"实时"的东西，
 * 2 秒的粒度对"看一眼还剩多少、想停就停"完全够用。多一条长连接要处理重连、
 * 心跳和半开连接，换来的只是进度条快两秒，不划算。
 */
var TASK_POLL_MS = 2000;
var pollBusy = false;

function pollTasks() {
  // 上一次还没回来就跳过这一轮：服务端正忙时，不要把它按在地上排队
  if (pollBusy) return;
  pollBusy = true;
  fetch('/api/fs/tasks').then(function (r) {
    if (r.status === 401) { location.href = '/pair'; return null; }
    return r.json();
  }).then(function (d) {
    if (d && d.ok !== false) applyTasks(d.items || []);
  }).catch(function () {
    // 一次拉不到不算事，下一个周期会补上。这里刻意不弹提示 ——
    // 网络抖一下就报错的话，用户会被毫无意义的红条淹没。
  }).then(function () { pollBusy = false; });
}

/* 服务端驱动的行（别的客户端发起的传输）：进度只能靠轮询。 */
function paintServerRow(row, t) {
  var p = (t.percent || 0) + '%';
  if (t.state === 'PAUSED') row.mark('paused', 'hold', '已暂停 ' + p, p);
  else if (t.state === 'CANCELED') row.canceled('已取消', p);
  else row.mark('', '', p, p);
}

function taskRowFromServer(t) {
  var row = addTaskRow(t.name, '', t.path, t.direction);
  paintServerRow(row, t);
  return row;
}

function applyTasks(items) {
  var seen = {};
  var liveCount = 0;

  for (var i = 0; i < items.length; i++) {
    var t = items[i];
    if (!t.path) continue;
    seen[t.path] = true;
    if (t.state !== 'CANCELED') liveCount++;

    liveByPath[t.path] = t;
    var row = rowByPath[t.path];
    if (!row) row = taskRowFromServer(t);
    if (!row) continue;

    if (row.local) {
      /*
       * 本页正在传的行：进度由 XHR 的 onprogress 驱动（比轮询平滑），
       * 这里只跟服务端对齐**状态**与按钮。否则每 2 秒来一次的回溯值
       * 会把进度条往回拉一下，看起来像卡住。
       */
      if (t.state === 'PAUSED') {
        row.mark('paused', 'hold', '已暂停 ' + t.percent + '%');
      } else if (t.state === 'CANCELED') {
        row.canceled();
        // 用户在**手机**上取消了这条上传：本页那个等着的执行者要一并作废，
        // 否则它会一直挂在"暂停中"，上传结束的提示永远不来。
        delete stopped[t.path];
      }

      /*
       * 已经收尾的行不再根据服务端状态摆按钮：终态就该是终态。
       *
       * 这一步不能省。收尾（传完/失败/取消）发生在页面这一侧，而服务端那边
       * 那条闸门还要过一会儿才注销 —— 在这段窗口里如果不加判断，
       * 轮询会把「暂停 / 取消」重新画到一条已经传完的任务上，
       * 而它们点下去只会得到一句"这条传输已经不在了"。
       * （暂停中的行不在此列：它还在等用户点开始。）
       */
      if (!isTerminal(row.state)) row.actions(actionsFor(t, row));

      // 服务端说可以传了，而本页这一行正停着：叫醒它。
      // 这正是"在手机上点开始，电脑继续传"能成立的机制。
      if (t.state === 'RUNNING') wakeLocal(t.path);
    } else {
      row.actions(actionsFor(t, row));
      paintServerRow(row, t);
    }
  }

  /*
   * 服务端列表里已经没有的路径，说明那条传输结束了（传完、失败、或暂停超时）。
   * 怎么处理要分三种情况，不能一律删掉：
   */
  for (var p in liveByPath) {
    if (!Object.prototype.hasOwnProperty.call(liveByPath, p)) continue;
    if (seen[p]) continue;
    delete liveByPath[p];
    var gone = rowByPath[p];
    if (!gone) continue;

    if (gone.local) {
      /*
       * 本页自己的行：一律留着（它要么已完成、要么已取消，都是用户想看的结论）。
       * 但如果它还在"暂停中"等着，而服务端已经不要它了，那就说明暂停超过了
       * 服务端愿意等的时长（半小时），闸门已经退休 —— 再点"开始"也传不动，
       * 必须如实说清楚，不能让用户对着一个永远等不到的开始按钮。
       */
      if (stopped[p]) {
        delete stopped[p];
        gone.fail('已中断：暂停太久，服务端已放手');
        stat.failed++;
      }
      continue;
    }

    // 已取消多留一会儿，让用户看得见自己按下过什么
    if (gone.state === 'canceled') continue;
    removeRow(gone);
  }

  // 有东西在传就把面板亮出来 —— 用户可能是从别的设备发起的，
  // 这台电脑上不主动说一声，他会以为自己在另一台机器上的操作没生效。
  if (liveCount > 0) byId('tasks').hidden = false;
  renderOverview();
}

// ==================== 入口绑定 ====================

byId('btnFiles').onclick = function () { byId('picker').click(); };
byId('btnFolder').onclick = function () { byId('folderPicker').click(); };
byId('btnNewDir').onclick = newFolder;
byId('btnFavorite').onclick = toggleCurrentFavorite;
byId('reload').onclick = function () { load(cwd); };
byId('sortBy').onchange = function () {
  sortKey = byId('sortBy').value;
  syncSortControls();
  renderList(currentItems);
};
byId('sortDirection').onclick = function () {
  sortDescending = !sortDescending;
  syncSortControls();
  renderList(currentItems);
};
byId('logout').onclick = function () {
  fetch('/api/logout', { method: 'POST' }).then(function () { location.href = '/pair'; });
};
/*
 * 清空 = 清掉"已经结束"的行。
 *
 * 刻意**不清**还在传或已暂停的任务：那些行是用户唯一能点开始/取消的地方，
 * 把它们清掉等于把控制入口藏起来 —— 用户会在概览上看到"已暂停 1"，
 * 却在列表里找不到对应的那一行。
 */
byId('tclear').onclick = function () {
  var kept = 0;
  for (var i = rowsAll.length - 1; i >= 0; i--) {
    var h = rowsAll[i];
    var t = h.path ? liveByPath[h.path] : null;
    var waiting = h.path ? !!stopped[h.path] : false;
    if ((t && t.state !== 'CANCELED') || waiting) { kept++; continue; }
    removeRow(h);
  }
  if (!kept) byId('tasks').hidden = true;
  toast(kept ? '保留了 ' + kept + ' 条进行中或暂停中的任务' : '已清空传输任务');
  renderOverview();
};

byId('picker').addEventListener('change', function () {
  var plan = addPlainFiles(this.files, '', { files: [], dirs: [] });
  this.value = '';
  if (plan.files.length) runPlan(plan);
});

byId('folderPicker').addEventListener('change', function () {
  var plan = addPlainFiles(this.files, '', { files: [], dirs: [] });
  this.value = '';
  if (!plan.files.length) { toast('这个文件夹里没有文件（空文件夹无法用选择器上传）', 'err'); return; }
  runPlan(plan);
});

if (!CAN_WRITE) {
  byId('btnFiles').disabled = true;
  byId('btnFolder').disabled = true;
  byId('btnNewDir').disabled = true;
}

setInterval(function () {
  fetch('/api/stats').then(function (r) { return r.json(); }).then(function (d) {
    if (!d || !d.ok) return;
    byId('rate').textContent = '↑ ' + d.tx + '/s  ↓ ' + d.rx + '/s';
  }).catch(function () {});
}, 2000);

/* 当前目录内容自动刷新：页面切到后台时停轮询，回来后立即检查一次。 */
document.addEventListener('visibilitychange', function () {
  if (!document.hidden) { refreshCurrentFolder(); refreshNearbyDevices(); }
});
setInterval(refreshCurrentFolder, 3000);
refreshNearbyDevices();
setInterval(refreshNearbyDevices, 5000);

/* 任务快照：先立刻拉一次，别让用户等了 2 秒才看到已经存在的传输 */
pollTasks();
setInterval(pollTasks, TASK_POLL_MS);

var rememberedPath = '';
try { rememberedPath = localStorage.getItem(LAST_PATH_KEY) || ''; } catch (_) {}
load(rememberedPath);
</script>
</body>
</html>
"""

    private const val BASE_CSS = """
*{box-sizing:border-box}
/* 必须显式声明，且必须带 !important。
   浏览器默认样式里的 `[hidden]{display:none}` 是**作者样式以下的**规则：
   只要某个类写了 display（比如 .overlay 的 display:flex），它就会盖过 [hidden]，
   于是 `el.hidden = true` 完全不起作用 —— 遮罩会常驻在页面上挡住所有内容。
   这种"属性失效"的现象很难从 JS 那一侧看出来，所以在 CSS 这一侧兜住。 */
[hidden]{display:none !important}
body{margin:0;font:15px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;color:#1b1f24;background:#f4f6f9}
body.center{display:flex;align-items:center;justify-content:center;min-height:100vh}
a{color:#1f6feb;text-decoration:none}
a:hover{text-decoration:underline}
button:disabled{opacity:.5;cursor:not-allowed}
.top{display:flex;align-items:center;gap:12px;padding:12px 18px;background:#fff;border-bottom:1px solid #e3e8ef;position:sticky;top:0;z-index:5}
.brand{font-weight:600}
.spacer{flex:1}
.rate{font-variant-numeric:tabular-nums;color:#5b6472;font-size:13px}
.ghost{border:1px solid #d5dbe4;background:#fff;border-radius:8px;padding:6px 12px;cursor:pointer;font-size:13px}
.ghost:hover:not(:disabled){background:#f0f3f7}
.toolbar{display:flex;align-items:center;gap:10px;padding:12px 18px 0;flex-wrap:wrap}
.toolbar .primary{border:0;background:#1f6feb;color:#fff;border-radius:8px;padding:8px 14px;cursor:pointer;font-size:13px}
.toolbar .primary:hover:not(:disabled){background:#1a5fd0}
.banner{margin:12px 18px 0;padding:11px 14px;border-radius:10px;background:#fff7e6;border:1px solid #f0d9a8;color:#7a5b16;font-size:13px}
.crumbs{padding:10px 18px;font-size:13px;color:#5b6472;display:flex;gap:6px;flex-wrap:wrap}
.crumbs a{color:#33405a}
.list-wrap{padding:0 18px 40px;position:relative;min-height:60vh}
.list{background:#fff;border:1px solid #e3e8ef;border-radius:12px;overflow:hidden}
.row{display:flex;align-items:center;gap:14px;padding:11px 16px;border-bottom:1px solid #eef1f6}
.file-select{width:17px;height:17px;flex:0 0 auto;accent-color:#246bfd;cursor:pointer}
.select-spacer{width:17px;flex:0 0 auto}
.selection-bar{display:flex;align-items:center;gap:12px;padding:10px 18px;background:#f7f9fc;border-bottom:1px solid #e8edf5}
.selection-bar .primary{min-height:36px;border:0;border-radius:9px;padding:7px 14px;background:#246bfd;color:#fff;font-weight:600;cursor:pointer}
.selection-bar .danger-button{min-height:36px;border:1px solid #f1d1ce;border-radius:9px;padding:7px 14px;background:#fff6f5;color:#bd423a;font-weight:600;cursor:pointer}
.selection-bar .danger-button:hover:not(:disabled){background:#ffebe9;border-color:#e9b8b3}
.selection-bar .danger-button:disabled{opacity:.45;cursor:not-allowed}
.select-all{display:flex;align-items:center;gap:8px;font-size:13px;cursor:pointer}
.file-select{width:17px;height:17px;flex:0 0 auto;accent-color:#246bfd;cursor:pointer}
.select-spacer{width:17px;flex:0 0 auto}
.selection-bar{display:flex;align-items:center;gap:12px;padding:10px 18px;background:#f7f9fc;border-bottom:1px solid #e8edf5}
.select-all{display:flex;align-items:center;gap:8px;font-size:13px;cursor:pointer}
.row:last-child{border-bottom:0}
.row:hover{background:#f8fafc}
.ico{width:84px;color:#8a94a3;font-size:12px;flex:0 0 auto}
.name{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.nm.dir{font-weight:600}
.acts{display:flex;gap:8px;flex:0 0 auto}
.act{border:0;background:transparent;color:#1f6feb;cursor:pointer;font-size:13px;padding:4px 6px;border-radius:6px}
.act:hover{background:#eaf1ff}
.act.danger{color:#c0392b}
.act.danger:hover{background:#fdecea}
.empty{padding:40px;text-align:center;color:#8a94a3}
.empty .small{margin-top:8px}
.tasks{margin:0 18px 40px;background:#fff;border:1px solid #e3e8ef;border-radius:12px;padding:14px 16px}
.trow{display:flex;align-items:center;gap:10px;margin-bottom:8px}
.trow h3{margin:0;font-size:14px;color:#5b6472;font-weight:600}
/* 概览条：四个计数一眼看完，"还剩多少没传完"写在下面一行小字里。
   计数用颜色区分状态，但不靠颜色单独表意 —— 每格都有中文标签，
   色盲用户和不看颜色的人读到的信息完全一致。 */
.oview{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:12px}
.ostat{flex:1 1 88px;min-width:88px;background:#f7f9fc;border:1px solid #e8edf4;border-radius:10px;padding:8px 11px}
.ostat .k{font-size:12px;color:#6b7686}
.ostat .v{font-size:18px;font-weight:600;font-variant-numeric:tabular-nums;line-height:1.3;color:#1b1f24}
.ostat.run .v{color:#1f6feb}
.ostat.pause .v{color:#b7791f}
.ostat.done .v{color:#18a058}
.ostat.fail .v{color:#c0392b}
.oview-note{width:100%;font-size:12px;color:#6b7686;margin-top:-2px}
#tasklist{max-height:280px;overflow:auto}
.task{display:flex;align-items:center;gap:12px;padding:7px 0}
.tn{flex:1;min-width:0;font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.dirbadge{flex:0 0 auto;font-size:11px;line-height:1.6;padding:0 6px;border-radius:6px;background:#eef2f8;color:#5b6472}
.task.state-paused .tn{color:#b7791f}
.task.state-canceled .tn{color:#8a94a3;text-decoration:line-through}
.tacts{display:flex;gap:6px;flex:0 0 auto}
.tacts button{border:1px solid #d5dbe4;background:#fff;border-radius:7px;padding:3px 10px;font-size:12px;cursor:pointer}
.tacts button:hover:not(:disabled){background:#f0f3f7}
.tacts button.stop{color:#c0392b;border-color:#f0c8c2}
.tacts button.stop:hover:not(:disabled){background:#fdecea}
.bar{width:180px;height:6px;background:#eef1f6;border-radius:99px;overflow:hidden;flex:0 0 auto}
.bar.wide{width:100%;margin-bottom:10px}
.bar i{display:block;height:100%;width:0;background:#1f6feb;transition:width .2s}
.bar i.done{background:#18a058}
.bar i.fail{background:#c0392b}
.bar i.hold{background:#e0a92b}
.tp{width:112px;text-align:right;font-size:12px;color:#5b6472;flex:0 0 auto;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.overlay{position:fixed;inset:0;background:rgba(20,40,80,.35);z-index:20;display:flex;align-items:center;justify-content:center;pointer-events:none}
.overlay-card{background:#fff;border-radius:16px;padding:28px 40px;text-align:center;box-shadow:0 12px 40px rgba(20,40,80,.22)}
.overlay-card .big{font-size:18px;font-weight:600;margin-bottom:6px}
.preview{position:fixed;inset:0;background:rgba(12,22,40,.62);z-index:40;display:flex;align-items:center;justify-content:center;padding:24px}
.preview-panel{width:min(1120px,100%);height:min(820px,94vh);background:#fff;border-radius:16px;overflow:hidden;display:flex;flex-direction:column;box-shadow:0 22px 80px rgba(0,0,0,.28)}
.preview-head{display:flex;align-items:center;gap:10px;min-height:54px;padding:8px 14px;border-bottom:1px solid #e9edf4}
.preview-head strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.preview-panel iframe{border:0;flex:1;width:100%;background:#f2f4f8}
body.preview-open{overflow:hidden}
.toast{position:fixed;left:50%;transform:translateX(-50%);bottom:28px;z-index:30;background:#1b1f24;color:#fff;padding:11px 18px;border-radius:10px;font-size:13px;max-width:80vw;box-shadow:0 6px 24px rgba(0,0,0,.25)}
.toast.err{background:#c0392b}
.toast.ok{background:#18a058}
.card{background:#fff;border:1px solid #e3e8ef;border-radius:16px;padding:32px;width:min(420px,92vw);text-align:center;box-shadow:0 8px 30px rgba(20,40,80,.06)}
.card h1{font-size:19px;margin:10px 0 6px}
.logo{font-size:26px;color:#1f6feb;letter-spacing:2px}
.muted{color:#6b7686;font-size:13px}
.small{font-size:12px}
.code-input{width:100%;margin:18px 0 12px;padding:14px;font-size:30px;letter-spacing:14px;text-align:center;border:1px solid #d5dbe4;border-radius:12px;font-variant-numeric:tabular-nums}
.code-input:focus{outline:2px solid #1f6feb;border-color:transparent}
.remember{display:block;font-size:13px;color:#5b6472;margin-bottom:16px}
button[type=submit],.card button{width:100%;padding:13px;border:0;border-radius:12px;background:#1f6feb;color:#fff;font-size:16px;cursor:pointer}
button[type=submit]:disabled{background:#9dbcf5;cursor:default}
.msg{min-height:20px;font-size:13px;margin:10px 0 4px}
.msg.err{color:#c0392b}
.msg.ok{color:#18a058}

/* Refined PocketServer web UI */
body{color:#17243a;background:#f3f6fb;letter-spacing:.01em}
body.center{background:radial-gradient(ellipse at 50% 8%,#e5f0ff 0,transparent 48%),#f3f6fb}
.top{min-height:68px;padding:12px max(22px,calc((100vw - 1440px)/2));gap:10px;border-bottom:1px solid #e6ebf3;box-shadow:0 2px 12px rgba(28,54,92,.04)}
.brand{display:flex;align-items:center;gap:10px;font-size:15px;color:#17243a}
.brand:before{content:'↗';display:grid;place-items:center;width:34px;height:34px;border-radius:11px;color:#fff;font-size:22px;font-weight:700;background:linear-gradient(145deg,#1889ff,#16c6be);box-shadow:0 5px 12px rgba(31,111,235,.2)}
.device-switch-wrap{position:relative;flex:0 0 auto}
.device-menu{position:absolute;right:0;top:calc(100% + 10px);z-index:30;width:min(380px,calc(100vw - 28px));padding:14px;background:#fff;border:1px solid #e2e9f3;border-radius:14px;box-shadow:0 16px 42px rgba(28,48,80,.18)}
.device-menu-title{margin:0 0 9px;font-size:13px;font-weight:700;color:#23344e}
.device-list{display:grid;gap:7px;max-height:260px;overflow:auto}
.device-row{display:flex;align-items:center;gap:8px;padding:7px;border:1px solid #edf1f6;border-radius:10px;background:#fbfcfe}
.device-open{min-width:0;flex:1;display:grid;gap:2px;text-align:left;padding:3px;border:0;background:transparent;color:#263750;cursor:pointer}
.device-open strong,.device-open small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.device-open strong{font-size:13px}.device-open small{font-size:11px;color:#8290a5}
.device-status{font-size:10px;color:#8090a5}.device-status.is-current{color:#16834c}
.device-remove{flex:0 0 auto;border:0;background:transparent;color:#8793a4;font-size:11px;cursor:pointer;padding:6px}
.device-add-form{display:grid;gap:8px;margin-top:12px;padding-top:12px;border-top:1px solid #edf1f6}
.device-add-form input{width:100%;min-width:0;padding:9px 10px;border:1px solid #dce4ef;border-radius:8px;font:inherit;font-size:12px}
.device-add-form .primary{border:0;border-radius:8px;padding:9px 12px;background:#246bfd;color:#fff;font-weight:600;cursor:pointer}
.device-menu-hint{margin-top:9px;color:#8491a4;font-size:11px;line-height:1.45}
.connected{display:inline-flex;align-items:center;gap:6px;margin-left:4px;padding:4px 9px;border-radius:999px;background:#ecf9f2;color:#16834c;font-size:11px;font-weight:600}
.connected i{width:6px;height:6px;border-radius:50%;background:#20b56b;box-shadow:0 0 0 3px rgba(32,181,107,.12)}
.rate{padding:7px 10px;border-radius:9px;background:#f5f8fc;color:#66758b;font-size:12px}
.ghost,.toolbar .primary,.toolbar .secondary{min-height:38px;border-radius:9px;padding:8px 14px;font-size:13px;font-weight:600;transition:background .16s,border-color .16s,transform .16s}
.ghost{border-color:#dce4ef;color:#465670}
.ghost:hover:not(:disabled){background:#f3f7fd;border-color:#c5d4e8}
.toolbar{max-width:1440px;margin:0 auto;padding:22px 24px 0;gap:10px}
.toolbar .primary{background:linear-gradient(135deg,#2878f0,#1764d7);box-shadow:0 5px 12px rgba(31,111,235,.18)}
.toolbar .primary:hover:not(:disabled){background:#145bc8;transform:translateY(-1px)}
.toolbar .secondary{border:1px solid #d7e4f7;background:#edf4ff;color:#2464bc;cursor:pointer}
.toolbar .secondary:hover:not(:disabled){background:#e1edff}
.toolbar .ghost{background:#fff}
.banner{max-width:1392px;margin:14px auto 0;padding:12px 16px;border-radius:11px}
.crumbs{max-width:1440px;margin:0 auto;padding:16px 24px 12px;align-items:center;color:#91a0b4;font-size:12px}
.crumbs a{color:#49617f;font-weight:500}
.crumbs a:first-of-type{padding:5px 9px;border-radius:7px;background:#eaf1fb;color:#2866b4}
.list-wrap{max-width:1440px;margin:0 auto;padding:0 24px 24px;min-height:clamp(260px,44vh,480px)}
.list{border:1px solid #e5eaf2;border-radius:14px;box-shadow:0 5px 20px rgba(34,57,91,.04)}
.row{min-height:64px;padding:10px 18px;gap:14px;border-bottom-color:#f0f3f8;transition:background .14s}
.row:hover{background:#f8faff}
.ico{width:42px;height:42px;display:grid;place-items:center;border-radius:12px;font-size:10px;font-weight:700;color:#3977c9;background:#edf4ff}
.ico.folder{color:#b77b19;background:#fff5df}
.name{font-size:14px}
.nm{color:#253b58;font-weight:500}
.nm:hover{color:#1f6feb}
.nm.dir{color:#244c7d;font-weight:650}
.filemeta{width:104px;flex:0 0 auto;color:#8492a6;font-size:12px;font-variant-numeric:tabular-nums}
.acts{gap:4px;padding:3px;border-radius:9px;background:#f6f8fb}
.act{padding:6px 9px;color:#41648f;font-size:12px}
.act:hover{background:#e8f0fc;color:#1f6feb}
.act.danger{color:#b64a43}
.act.danger:hover{background:#fff0ee}
.empty{padding:70px 24px;color:#566984}
.empty:before{content:'▤';display:grid;place-items:center;width:54px;height:54px;margin:0 auto 14px;border-radius:16px;background:#edf4ff;color:#3977c9;font-size:26px}
.tasks{max-width:1392px;margin:0 auto 32px;padding:18px 20px;border-color:#e5eaf2;border-radius:14px;box-shadow:0 5px 20px rgba(34,57,91,.045)}
.trow{margin-bottom:14px}
.trow h3{font-size:15px;color:#263952}
.trow .ghost{min-height:32px;padding:5px 11px}
.oview{gap:10px;margin-bottom:14px}
.ostat{background:#f7f9fc;border-color:#edf0f5;border-radius:11px;padding:10px 13px}
.ostat .k{color:#718198;font-size:11px}
.ostat .v{font-size:21px;margin-top:2px}
#tasklist{max-height:260px;padding:0 2px}
.task{min-height:42px;gap:12px;border-bottom:1px solid #f1f3f7}
.task:last-child{border-bottom:0}
.tn{color:#344963;font-size:12px}
.dirbadge{padding:2px 8px;background:#edf4ff;color:#3972bd}
.bar{background:#edf1f6}
.tp{font-variant-numeric:tabular-nums}
.card{border:1px solid #e2e9f3;border-radius:20px;padding:36px;width:min(440px,92vw);box-shadow:0 18px 55px rgba(40,76,128,.12)}
.logo{width:54px;height:54px;margin:0 auto 10px;display:grid;place-items:center;border-radius:17px;background:linear-gradient(145deg,#1889ff,#16c6be);color:#fff;box-shadow:0 8px 18px rgba(31,111,235,.2);font-size:0;letter-spacing:0}
.logo:before{content:'↗';font-size:30px;font-weight:700}
.card h1{font-size:21px;color:#1d2e46}
.card .muted{line-height:1.7}
.code-input{border-color:#dce5f1;background:#fbfcfe;color:#1c3554;font-weight:600}
.code-input:focus{outline:3px solid rgba(31,111,235,.14);border-color:#4387ef}
.remember{color:#5d6d83}
button[type=submit],.card button{background:linear-gradient(135deg,#2878f0,#1764d7);box-shadow:0 6px 14px rgba(31,111,235,.18);font-weight:600}
.overlay-card{border:1px solid #e4eaf2;box-shadow:0 18px 50px rgba(20,40,80,.18)}
.preview{position:fixed;inset:0;background:rgba(12,22,40,.62);z-index:40;display:flex;align-items:center;justify-content:center;padding:24px}
.preview-panel{width:min(1120px,100%);height:min(820px,94vh);background:#fff;border-radius:16px;overflow:hidden;display:flex;flex-direction:column;box-shadow:0 22px 80px rgba(0,0,0,.28)}
.preview-head{display:flex;align-items:center;gap:10px;min-height:54px;padding:8px 14px;border-bottom:1px solid #e9edf4}
.preview-head strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.preview-panel iframe{border:0;flex:1;width:100%;background:#f2f4f8}
body.preview-open{overflow:hidden}
.toast{border-radius:12px}
@media(max-width:760px){
  .top{padding:10px 14px;gap:7px;flex-wrap:wrap}
  .brand{flex:1 1 100%;font-size:14px}
  .brand:before{width:30px;height:30px}
  .rate{margin-right:auto;padding:6px 8px}
  .toolbar{padding:16px 14px 0;gap:8px}
  .selection-bar{padding:9px 14px;gap:7px;flex-wrap:wrap}
  .preview{padding:8px}
  .preview-panel{height:96vh;border-radius:12px}
  .toolbar .primary,.toolbar .secondary,.toolbar .ghost{padding:8px 10px;font-size:12px}
  #rootinfo{flex:1 0 100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .banner{margin:12px 14px 0}
  .crumbs{padding:13px 14px 10px}
  .list-wrap{padding:0 14px 20px;min-height:280px}
  .row{padding:9px 10px;gap:9px;min-height:58px}
  .ico{width:36px;height:36px;border-radius:10px}
  .filemeta{display:none}
  .acts{gap:0;background:transparent}
  .act{padding:6px 7px;font-size:11px}
  .tasks{margin:0 14px 22px;padding:14px 12px}
  .oview{gap:6px}
  .ostat{flex-basis:calc(50% - 6px);min-width:calc(50% - 6px);padding:8px 10px}
  .task{flex-wrap:wrap;gap:6px 9px;padding:9px 0}
  .tn{flex:1 1 calc(100% - 60px)}
  .bar{width:100%;order:3}
  .tp{width:auto;order:2}
  .tacts{order:4}
  .trow{flex-wrap:wrap}
}
@media(max-width:420px){
  .connected{font-size:10px;padding:3px 7px}
  .toolbar .primary,.toolbar .secondary,.toolbar .ghost{padding:8px;font-size:11px}
  .row{gap:7px}
  .ico{width:32px;height:32px;font-size:9px}
  .act{padding:5px;font-size:10px}
}
.view-switch{display:flex;align-items:center;padding:3px;border:1px solid #dce4ef;background:#fff;border-radius:10px;gap:2px}
.breadbar{position:sticky;top:68px;z-index:4;background:rgba(255,255,255,.96);border-top:1px solid #edf1f6;border-bottom:1px solid #e5eaf2;box-shadow:0 4px 12px rgba(34,57,91,.055);backdrop-filter:blur(12px)}
.breadbar-inner{max-width:1440px;min-height:54px;margin:0 auto;padding:6px 24px;display:flex;align-items:center;gap:14px}
.crumbs{flex:1;min-width:0;max-width:none;margin:0;padding:8px 0;align-items:center;gap:7px;flex-wrap:nowrap;overflow-x:auto;white-space:nowrap;scrollbar-width:thin}
.sort-controls{display:flex;align-items:center;gap:7px;flex:0 0 auto;color:#77869a;font-size:12px}
.sort-controls select,.sort-direction{height:34px;border:1px solid #dce4ef;border-radius:8px;background:#fff;color:#425671;padding:0 9px;font-size:12px}
.sort-controls select{cursor:pointer}
.sort-direction{cursor:pointer;font-weight:600;color:#2866b4}
.sort-direction:hover{background:#f2f7ff;border-color:#c8d9f1}
.favorites-home{max-width:1392px;margin:14px auto 0;padding:13px 15px;border:1px solid #e5eaf2;border-radius:13px;background:linear-gradient(120deg,#fff 0,#f7faff 100%);box-shadow:0 4px 16px rgba(34,57,91,.045)}
.favorites-heading{display:flex;align-items:baseline;gap:9px;margin-bottom:10px;color:#263952}
.favorites-heading strong{font-size:14px}
.favorites-heading span{font-size:11px;color:#8a98ab}
.favorite-list{display:flex;gap:9px;flex-wrap:wrap}
.favorite-card{display:flex;align-items:center;min-width:190px;max-width:310px;flex:1 1 210px;overflow:hidden;border:1px solid #e5ebf3;border-radius:10px;background:#fff;transition:border-color .16s,box-shadow .16s}
.favorite-card:hover{border-color:#c8daf3;box-shadow:0 5px 14px rgba(35,81,145,.08)}
.favorite-open{display:flex;align-items:center;gap:10px;min-width:0;flex:1;padding:10px 11px;border:0;background:transparent;text-align:left;cursor:pointer;color:#2d4565}
.favorite-icon{display:grid;place-items:center;width:34px;height:34px;flex:0 0 auto;border-radius:9px;background:#edf4ff;color:#3477d2;font-weight:700}
.favorite-text{display:flex;flex-direction:column;min-width:0;gap:2px}
.favorite-text strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px}
.favorite-text small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#8a98ab;font-size:10px}
.favorite-remove{margin:0 7px;padding:5px 7px;border:0;border-radius:7px;background:transparent;color:#8b98aa;font-size:11px;cursor:pointer}
.favorite-remove:hover{background:#fff0ee;color:#b64a43}
.favorite-empty{padding:6px 2px;color:#8492a6;font-size:12px}
.toolbar #btnFavorite{border-color:#d6e5f8;background:#f2f7ff;color:#2866b4}
.toolbar #btnFavorite:hover:not(:disabled){background:#e5f0ff}
.view-switch button{border:0;background:transparent;color:#65758c;padding:7px 10px;border-radius:7px;font-size:12px;cursor:pointer}
.view-switch button.active{background:#eaf2ff;color:#1f67cf;font-weight:650}
.selection-bar{max-width:1392px;margin:10px auto 12px;border:1px solid #e5eaf2;border-radius:12px;background:#fff;box-shadow:0 3px 12px rgba(34,57,91,.035)}
.browser-content{display:flex;align-items:stretch;gap:14px;min-width:0}
.browser-content.with-preview #list{flex:1;min-width:0}
.preview-sidebar{position:sticky;top:82px;width:360px;max-height:calc(100vh - 98px);flex:0 0 360px;align-self:flex-start;display:flex;flex-direction:column;overflow:hidden;background:#fff;border:1px solid #e5eaf2;border-radius:14px;box-shadow:0 5px 20px rgba(34,57,91,.05)}
.preview-head{display:flex;align-items:center;gap:8px;min-height:52px;padding:8px 12px;border-bottom:1px solid #edf0f5}
.preview-head strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px;color:#2a3c55}
.preview-content{height:min(55vh,520px);min-height:220px;flex:1 1 auto;display:grid;place-items:center;overflow:auto;background:#f5f7fa}
.preview-image,.preview-video{display:block;max-width:100%;max-height:100%;object-fit:contain}
.preview-video{width:100%;max-height:340px;background:#10151d}
.preview-audio{width:calc(100% - 28px)}
.preview-document{width:100%;height:100%;border:0;background:#fff}
.preview-empty{padding:24px;color:#8492a6;text-align:center;font-size:13px}
.preview-meta{padding:10px 13px;color:#8190a5;font-size:11px;border-top:1px solid #edf0f5;overflow-wrap:anywhere}
.list.view-small,.list.view-medium,.list.view-large{display:grid;align-content:start;gap:12px;padding:12px;background:transparent;border:0;box-shadow:none;overflow:visible}
.list.view-small{grid-template-columns:repeat(auto-fill,minmax(142px,1fr))}
.list.view-medium{grid-template-columns:repeat(auto-fill,minmax(210px,1fr))}
.list.view-large{grid-template-columns:repeat(auto-fill,minmax(300px,1fr))}
.list.view-small .row,.list.view-medium .row,.list.view-large .row{position:relative;display:flex;flex-direction:column;align-items:stretch;gap:7px;min-width:0;height:100%;padding:10px;background:#fff;border:1px solid #e6ebf2;border-radius:12px;box-shadow:0 3px 12px rgba(34,57,91,.035)}
.list.view-small .row:hover,.list.view-medium .row:hover,.list.view-large .row:hover{border-color:#cbdcf4;box-shadow:0 7px 20px rgba(34,57,91,.09)}
.list.view-small .row .ico,.list.view-medium .row .ico,.list.view-large .row .ico{display:none}
.list.view-small .file-select,.list.view-medium .file-select,.list.view-large .file-select{position:absolute;z-index:2;top:14px;left:14px;width:19px;height:19px;background:#fff;border-radius:4px}
.thumb{display:grid;place-items:center;width:100%;height:92px;overflow:hidden;border-radius:8px;background:linear-gradient(145deg,#eff4fb,#e8eef7);color:#52749f;cursor:pointer}
.view-medium .thumb{height:146px}
.view-large .thumb{height:220px}
.thumb img{width:100%;height:100%;object-fit:cover;transition:transform .2s}
.row:hover .thumb img{transform:scale(1.035)}
.thumb-glyph{font-size:26px;font-weight:700;color:#6684ab}
.folder-thumb{color:#e3aa38;font-size:27px;cursor:default}
.list.view-small .name,.list.view-medium .name,.list.view-large .name{width:100%;font-size:12px;line-height:1.45;white-space:normal;overflow-wrap:anywhere;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical}
.list.view-medium .name,.list.view-large .name{font-size:13px}
.list.view-small .filemeta,.list.view-medium .filemeta,.list.view-large .filemeta{width:auto;font-size:11px}
.list.view-small .acts,.list.view-medium .acts,.list.view-large .acts{justify-content:flex-end;flex-wrap:wrap;padding:0;background:transparent}
.list.view-small .act,.list.view-medium .act,.list.view-large .act{padding:5px 7px}
@media(max-width:900px){
  .selection-bar{margin-left:14px;margin-right:14px}
  .browser-content.with-preview{flex-direction:column}
  .preview-sidebar{position:sticky;top:8px;width:100%;max-height:calc(75vh - 16px);flex-basis:auto}
  .preview-content{height:min(55vh,420px)}
}
@media(max-width:760px){
  .breadbar{top:96px}
  .breadbar-inner{min-height:48px;padding:5px 14px;gap:8px}
  .sort-controls{gap:4px}
  .sort-controls select,.sort-direction{height:32px;padding:0 6px;font-size:11px}
  .sort-controls label{display:none}
  .favorites-home{margin:11px 14px 0;padding:11px}
  .favorite-card{min-width:calc(50% - 5px);flex-basis:calc(50% - 5px)}
  .favorite-open{gap:7px;padding:8px}
  .favorite-icon{width:30px;height:30px}
  .favorite-remove{margin:0 3px;padding:5px}
  .view-switch{order:3}
  .view-switch button{padding:7px 8px}
  .list.view-small{grid-template-columns:repeat(auto-fill,minmax(118px,1fr));gap:8px;padding:8px}
  .list.view-medium{grid-template-columns:repeat(auto-fill,minmax(165px,1fr));gap:8px;padding:8px}
  .list.view-large{grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:8px;padding:8px}
  .list.view-small .row,.list.view-medium .row,.list.view-large .row{padding:8px}
  .list.view-small .thumb{height:82px}
  .view-medium .thumb{height:120px}
  .view-large .thumb{height:170px}
}
@media(max-width:420px){
  .sort-direction{max-width:88px;overflow:hidden;white-space:nowrap}
  .favorite-card{min-width:100%;flex-basis:100%}
}
"""

    /**
     * HTML 文本转义。
     *
     * 只用于把**服务端字符串**（设备名、文件名）嵌进 HTML 的那几处。页面里那些
     * 动态内容一律走 DOM API + `textContent`，不拼字符串 —— 拼 innerHTML 的话，
     * 一个名叫 `<img src=x onerror=...>` 的文件就能在访问者的浏览器里执行脚本。
     */
    private fun esc(value: String): String = buildString(value.length + 16) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    /** 简易错误页，避免浏览器显示空白。 */
    fun errorPage(title: String, message: String): String = """
<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title><style>$BASE_CSS</style></head>
<body class="center"><main class="card">
<div class="logo">[(•)]</div><h1>${esc(title)}</h1><p class="muted">${esc(message)}</p>
</main></body></html>
"""
}
