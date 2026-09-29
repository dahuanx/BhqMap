/**
 * P3 真实浏览器走查：cia 开关 / 台账 Excel 导入（含下载 data.js）/ 淡入淡出收敛。
 */
const path = require('path');
const fs = require('fs');
const { chromium } = require(path.join(
  'C:', 'Users', 'Administrator', '.workbuddy', 'binaries', 'node', 'workspace', 'node_modules', 'playwright-core'));

const OUT = __dirname;
const LEDGER = path.join(__dirname, '..', '..', '边合区企业台账.xlsx');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE: ' + m.text()); });

  await page.goto('http://127.0.0.1:8123/index.html', { waitUntil: 'load' });
  await page.waitForTimeout(4000);

  // ① 浮层行数（5 原有 + cia + 导入行）
  await page.click('#btnCoop');
  await page.waitForTimeout(300);
  const rowCount = await page.locator('#panel .row').count();

  // ② cia 开关：打开后地图应有 cia 图层（自定义开关，原生 input 视觉隐藏 → 事件派发）
  await page.evaluate(() => { const s = document.getElementById('swCia'); s.checked = true; s.dispatchEvent(new Event('change', { bubbles: true })); });
  await page.waitForTimeout(2500);
  const ciaOn = await page.evaluate(() => !!ciaLayer && map.hasLayer(ciaLayer));
  await page.screenshot({ path: path.join(OUT, 'v030-1-cia开.png') });
  await page.evaluate(() => { const s = document.getElementById('swCia'); s.checked = false; s.dispatchEvent(new Event('change', { bubbles: true })); });
  await page.waitForTimeout(300);
  const ciaOff = await page.evaluate(() => ciaLayer && !map.hasLayer(ciaLayer));

  // ③ 淡入淡出：缩放触发重排后 500ms 应全部收敛 alpha=1、退场清空
  await page.evaluate(() => map.setZoom(16));
  await page.waitForTimeout(600);
  const fadeSettled = await page.evaluate(() =>
    engine.labels.every(p => p.alpha >= 1) && engine.fadingOut.length === 0);

  // ④ 台账 Excel 导入（真实文件）→ 内存生效 + 状态栏（v0.3.1 起含闲置厂房统计）
  await page.setInputFiles('#impFile', LEDGER);
  await page.waitForTimeout(1200);
  const statusText = await page.evaluate(() => document.getElementById('impStatus').textContent);
  const entCount = await page.evaluate(() => engine.data.length);
  const facCount = await page.evaluate(() => factoryLayer.factoryCount);
  await page.screenshot({ path: path.join(OUT, 'v030-2-导入后.png') });

  // ⑤ 下载数据文件：文件名 data.js 且四套数据整体带走（v0.3.1 修复只导企业的缺陷）
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.click('#impDownload'),
  ]);
  const dlPath = path.join(OUT, '.tmp.download.data.js');
  await download.saveAs(dlPath);
  const dlBody = fs.readFileSync(dlPath, 'utf8');
  fs.unlinkSync(dlPath);
  const dlKeys = (() => {
    const m = dlBody.match(/const DATA = (\{.*\});\s*$/s);
    if (!m) return null;
    const d = JSON.parse(m[1]);
    return { enterprises: d.enterprises.length, roads: d.roads.length,
             parcels: d.parcels.length, factories: d.factories.length };
  })();

  const ok = rowCount === 7
    && ciaOn && ciaOff
    && fadeSettled
    && entCount === 164 && statusText.includes('164') && statusText.includes('闲置厂房 25')
    && facCount === 25
    && download.suggestedFilename() === 'data.js'
    && dlBody.startsWith('/*') && dlBody.includes('const DATA')
    && dlKeys && dlKeys.enterprises === 164 && dlKeys.roads === 13
    && dlKeys.parcels === 13 && dlKeys.factories === 25
    && errors.length === 0;

  console.log(JSON.stringify({ rowCount, ciaOn, ciaOff, fadeSettled, entCount, facCount, statusText,
    dlFile: download.suggestedFilename(), dlKeys, errors }, null, 1));
  console.log(ok ? 'P3-BROWSER-VERIFY-PASS' : 'P3-BROWSER-VERIFY-FAIL');
  await browser.close();
  process.exit(ok ? 0 : 1);
})();
