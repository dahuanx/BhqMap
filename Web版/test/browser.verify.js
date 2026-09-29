/**
 * 真实浏览器渲染验证（v0.2.1 路名修复）
 * 场景复现洲哥报告：放大到路名出现（z17+）→ 缩小 → 路名不得残留/飞出。
 */
const path = require('path');
const fs = require('fs');
const { chromium } = require(path.join(
  'C:', 'Users', 'Administrator', '.workbuddy', 'binaries', 'node', 'workspace', 'node_modules', 'playwright-core'));

const OUT = __dirname;

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE: ' + m.text()); });

  await page.goto('http://127.0.0.1:8123/index.html', { waitUntil: 'load' });
  await page.waitForTimeout(4500);   // 等瓦片

  const probe = () => page.evaluate(() => ({
    zoom: map.getZoom(),
    roadLabels: roadLayer.labelCount,
    roadPlaced: roadLayer._placed ? roadLayer._placed.length : 0,
    entLabels: engine.labelCount,
  }));

  // ① 全貌（z≈13.6）：无路名（mpp ≈ 9 > 5）
  await page.evaluate(() => map.setZoom(13.6));
  await page.waitForTimeout(900);
  const far = await probe();
  await page.screenshot({ path: path.join(OUT, 'v021-1-全貌.png') });

  // ② 放大到 z15.0（比例尺读数 300m 段）：路名应出现
  await page.evaluate(() => map.setZoom(15.0));
  await page.waitForTimeout(900);
  const near = await probe();
  await page.screenshot({ path: path.join(OUT, 'v021-2-z15-路名.png') });

  // ③ 缩小回 z13.5：路名应全部消失（无残留 = 不飞出）
  await page.evaluate(() => map.setZoom(13.5));
  await page.waitForTimeout(900);
  const back = await probe();
  await page.screenshot({ path: path.join(OUT, 'v021-3-缩小后.png') });

  // ④ 动画路径缩小（触发 zoomanim transform）：z15 → z13 连续
  await page.evaluate(() => map.setZoom(15));
  await page.waitForTimeout(700);
  await page.evaluate(() => map.flyTo(map.getCenter(), 13, { duration: 1.2 }));
  await page.waitForTimeout(2000);
  const flown = await probe();
  await page.screenshot({ path: path.join(OUT, 'v021-4-飞行缩小后.png') });

  const ok = far.roadLabels === 0 && near.roadLabels > 0
    && back.roadLabels === 0 && back.roadPlaced === 0
    && flown.roadLabels === 0 && flown.roadPlaced === 0
    && errors.length === 0;

  console.log(JSON.stringify({ far, near, back, flown, errors }, null, 1));
  console.log(ok ? 'BROWSER-VERIFY-PASS' : 'BROWSER-VERIFY-FAIL');
  await browser.close();
  process.exit(ok ? 0 : 1);
})();
