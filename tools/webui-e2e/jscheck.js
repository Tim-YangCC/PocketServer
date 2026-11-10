'use strict';
/*
 * 内嵌网页 JS 的语法校验。
 *
 * Kotlin 里的网页是一段原始字符串，编译器对里面的 JS 一个字符都不校验：少一个括号、
 * 多一个逗号，编译照样通过、单测照样通过，只有用户点开网页才会白屏。
 * 这里把 filePage 现场渲染出来，抠出每个 <script> 块交给 `node --check` 过一遍。
 *
 * 用法：node tools/webui-e2e/jscheck.js
 * 退出码 0 = 全部通过。
 */
const vm = require('vm');
const { renderFilePage } = require('./render');

function scriptsOf(html) {
  const out = [];
  const re = /<script\b[^>]*>([\s\S]*?)<\/script>/gi;
  let m;
  while ((m = re.exec(html)) !== null) out.push(m[1]);
  return out;
}

const html = renderFilePage({ deviceName: '客厅小米电视' });
const blocks = scriptsOf(html);

if (blocks.length === 0) {
  console.error('FAIL 页面里没有找到任何 <script> 块');
  process.exit(1);
}

let bad = 0;
blocks.forEach(function (code, i) {
  const label = 'script' + (i + 1) + '.js（' + code.split('\n').length + ' 行）';
  try {
    new vm.Script(code, { filename: label });
    console.log('PASS ' + label);
  } catch (e) {
    bad++;
    console.error('FAIL ' + label + ' → ' + e.message);
  }
});

const total = blocks.reduce(function (n, c) { return n + c.split('\n').length; }, 0);
console.log('脚本块 ' + blocks.length + ' 个，合计 ' + total + ' 行');
console.log(bad === 0 ? 'ALL_PASS' : 'FAILED=' + bad);
process.exit(bad === 0 ? 0 : 1);
