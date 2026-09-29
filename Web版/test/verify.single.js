/* 单文件封装验收：file:// 协议直接打开（= 对方双击场景），探针 + 截图 + JS 错误收集 */
const path = require('path');
const fs = require('fs');
const { chromium } = require('C:/Users/Administrator/.workbuddy/binaries/node/workspace/node_modules/playwright-core');

(async () => {
  const file = path.resolve(__dirname, '..', '..', '边合区产业地图-Webv0.3.1.html');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });

  await page.goto('file:///' + file.replace(/\\/g, '/'));
  await page.waitForTimeout(4500);   // 等底图瓦片

  const probe1 = await page.evaluate(() => ({
    version: typeof WEB_VERSION !== 'undefined' ? WEB_VERSION : null,
    hasMap: typeof map !== 'undefined' && !!map,
    ents: typeof engine !== 'undefined' ? engine.visibleCount : -1,
    labels: typeof engine !== 'undefined' ? engine.labelCount : -1,
    roads: typeof DATA !== 'undefined' && Array.isArray(DATA.roads) ? DATA.roads.length : -1,
    parcels: typeof parcelLayer !== 'undefined' ? DATA.parcels.length : -1,
    factories: typeof factoryLayer !== 'undefined' ? factoryLayer.factoryCount : -1,
    tilesLoaded: document.querySelectorAll('.leaflet-tile-loaded').length,
  }));
  await page.screenshot({ path: path.join(__dirname, 'single-1-全貌.png') });

  // 缩放到近档 + 厂房图层开（全链路：引擎/图层/浮层在单文件里都活着）
  await page.evaluate(() => map.setZoom(16.5));
  await page.waitForTimeout(800);
  await page.evaluate(() => {
    const s = document.getElementById('swFactory');
    s.checked = true; s.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await page.waitForTimeout(1200);
  const probe2 = await page.evaluate(() => ({
    // App 语义：图层开 → 企业名全撤（labels=0 正确），厂房标记在
    labelsHidden: engine.labelCount === 0,
    facMarkers: factoryLayer._markers ? factoryLayer._markers.length : 0,
  }));
  await page.screenshot({ path: path.join(__dirname, 'single-2-近档厂房.png') });

  const ok = probe1.version === 'v0.3.1' && probe1.hasMap
    && probe1.ents > 0 && probe1.labels > 0 && probe1.tilesLoaded > 0
    && probe1.roads === 13 && probe1.parcels === 13 && probe1.factories === 25
    && probe2.labelsHidden && probe2.facMarkers > 0
    && errors.length === 0;
  console.log(JSON.stringify({ probe1, probe2, errors }, null, 1));
  console.log(ok ? 'SINGLE-FILE-VERIFY-PASS' : 'SINGLE-FILE-VERIFY-FAIL');
  await browser.close();
  process.exit(ok ? 0 : 1);
})();
