'use strict';
/*
 * 从 Kotlin 源里渲染出 filePage 的真实 HTML。
 *
 * ## 为什么要在跑 E2E 之前现场渲染，而不是读一份导出的 HTML
 * 网页是 `WebUi.kt` 里的一段原始字符串，Kotlin 编译器不会校验里面任何一个字符。
 * 如果 E2E 读的是一份"上次导出的 file.html"，那么这一轮改了页面、忘了重新导出，
 * E2E 会**全部通过**——它测的是上一版的页面，而且是悄悄地测。
 * 现场渲染把这条错误的可能从流程里去掉：跑到的永远是源文件里此刻的那份。
 *
 * 替换规则与 Kotlin 一致：`$BASE_CSS` 注入样式表，`${esc(deviceName)}` 注入设备名。
 */
const fs = require('fs');
const path = require('path');

const WEBUI = path.resolve(
  __dirname, '..', '..',
  'app', 'src', 'main', 'java', 'com', 'pocketserver', 'net', 'http', 'WebUi.kt'
);

/** 取 `anchor` 之后第一个 Kotlin 原始字符串 `"""` 的内容。 */
function rawStringAfter(text, anchor, from) {
  const i = text.indexOf(anchor, from || 0);
  if (i < 0) throw new Error('找不到标记：' + anchor);
  const start = text.indexOf('"""', i) + 3;
  const body = text[start] === '\n' ? start + 1 : start;
  const end = text.indexOf('\n"""', body);
  if (end < 0) throw new Error('原始字符串没有闭合：' + anchor);
  return text.slice(body, end);
}

function renderFilePage(opts) {
  const o = opts || {};
  const src = fs.readFileSync(WEBUI, 'utf8');
  const css = rawStringAfter(src, 'private const val BASE_CSS');
  let html = rawStringAfter(src, 'fun filePage(');

  html = html.split('${esc(deviceName)}').join(o.deviceName || '客厅小米电视');
  html = html.split('$BASE_CSS').join(css);
  html = html.split('$canWrite').join(o.canWrite === false ? 'false' : 'true');
  html = html.split('$canDelete').join('true');
  html = html.split('$canRename').join('true');

  // 漏一个插值就说明渲染规则和 Kotlin 的写法脱节了，此时继续跑 E2E 只会得到一堆假结论
  const left = html.match(/\$[A-Za-z{][^\s<]*/g);
  if (left) throw new Error('还有没替换掉的 Kotlin 插值：' + left.slice(0, 5).join(', '));
  return html;
}

module.exports = { renderFilePage };
