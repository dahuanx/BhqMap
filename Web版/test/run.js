/**
 * 加载器：拼接 config.js + labelengine.js + body.test.js 为临时文件运行。
 * （strict eval 的 class 不外泄作用域，拼接是 Node 下最省事的无头加载方式）
 */
'use strict';
const fs = require('fs');
const path = require('path');

const srcDir = path.join(__dirname, '..');
const body = fs.readFileSync(path.join(__dirname, 'body.test.js'), 'utf8');
const combined = [
  fs.readFileSync(path.join(srcDir, 'js/config.js'), 'utf8'),
  fs.readFileSync(path.join(srcDir, 'js/labelengine.js'), 'utf8'),
  fs.readFileSync(path.join(srcDir, 'js/layers.js'), 'utf8'),
  fs.readFileSync(path.join(srcDir, 'js/excel.js'), 'utf8'),
  body,
].join('\n');

const tmp = path.join(__dirname, '.tmp.combined.js');
fs.writeFileSync(tmp, combined);
try {
  require(tmp);
} finally {
  try { fs.unlinkSync(tmp); } catch (_) {}
}
process.exitCode = global.__SMOKE_EXIT__ || 0;
