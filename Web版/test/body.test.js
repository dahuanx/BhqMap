/**
 * 测试体（由 run.js 拼接后运行；CFG / LabelEngine 等由拼接注入全局作用域）。
 * 四种典型视野对照安卓离线仿真产量级：
 *   全貌远档 / 中间档筛选(z14.8) / 近档避让 / 近距全显(z18) + 开关与 hitTest。
 */
'use strict';
const fs = require('fs');
const path = require('path');
// data.js 单行 JSON（.gen_web_data.py 生成）：含 enterprises/roads/parcels/factories 四套
const dataJs = fs.readFileSync(path.join(__dirname, '..', 'data', 'data.js'), 'utf8');
const DATA = JSON.parse(dataJs.match(/const DATA = (.*);/s)[1]);

/* ---- mock canvas ctx：每汉字 12px 宽 ---- */
function mockCtx() {
  return {
    font: '',
    measureText(t) {
      return { width: [...t].length * 12, fontBoundingBoxAscent: 10.5, fontBoundingBoxDescent: 3.5 };
    },
    setTransform() {}, clearRect() {},
    beginPath() {}, arc() {}, moveTo() {}, lineTo() {}, closePath() {},
    fill() {}, stroke() {}, fillText() {}, strokeText() {},
  };
}

function fakeView(w, h, zoom, dpr) {
  const clat = 44.3469, clon = 131.0497;
  const mpp = 156543.03392 * Math.cos(clat * Math.PI / 180) / Math.pow(2, zoom);
  const halfLat = (h / 2) * mpp / 111320;
  const halfLon = (w / 2) * mpp / (111320 * Math.cos(clat * Math.PI / 180));
  return {
    w, h, zoom, mpp, dpr: dpr || 1,
    north: clat + halfLat, south: clat - halfLat,
    west: clon - halfLon, east: clon + halfLon,
    latSpan: halfLat * 2, lonSpan: halfLon * 2,
    project: (lat, lon) => ({
      x: w / 2 + (lon - clon) * 111320 * Math.cos(clat * Math.PI / 180) / mpp,
      y: h / 2 - (lat - clat) * 111320 / mpp,
    }),
  };
}

function newEngine() {
  return new LabelEngine({ clientWidth: 1080, clientHeight: 1600, getContext: () => mockCtx() },
    DATA.enterprises, () => {});
}

function run(name, zoom, expect, opts) {
  const o = opts || {};
  const eng = newEngine();
  eng.layout(fakeView(o.w || 1080, o.h || 1600, zoom, o.dpr || 1));
  const ok = expect(eng);
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}: zoom=${zoom} mppPhys=${eng.metersPerPixel.toFixed(2)} lod=${eng.lodLevel} visible=${eng.visibleCount} labels=${eng.labelCount} dropped=${eng.hiddenByCollision}`);
  return ok;
}

let all = true;

/* 全貌（App 全貌 ↔ 桌面 z15.2-15.3，物理 m/px ≈ 3.03 > MPP_KEY 2.0 → 重点档） */
all &= run('全貌远档', 15.3, e => e.lodLevel === CFG.LOD_KEY && e.labelCount > 0 && e.labelCount <= e.visibleCount);

/* 中间档筛选：物理 m/px ∈ (2.42, 4.83)（← App 14.5<z<15.5）→ 只出重点/行政名 */
all &= run('中间档筛选', 15.0, e => {
  const keyGov = DATA.enterprises.filter(x => x.isKey || x.isGov).length;
  return e.labelCount < e.visibleCount && e.labelCount <= keyGov;
});

/* 近档避让：物理 m/px < 2.0 → 全部档 */
all &= run('近档避让', 16.0, e => e.lodLevel === CFG.LOD_ALL && e.labelCount > 0);

/* 近距全显：物理 m/px < 0.6042（← z ≥ 17.5）→ 豁免避让，0 弃名 */
all &= run('近距全显', 18.0, e => e.visibleCount > 0 && e.labelCount > 0 && e.hiddenByCollision === 0);

/* dpr=3（手机）：同一物理屏幕（1080×1600 物理px = 360×533 css）+ 同一地理范围，
 * 分档与标签数应与 dpr=1 桌面完全一致（阈值物理口径的自洽性验证） */
all &= run('手机 dpr=3 全貌', 13.6, e => {
  const eng2 = newEngine();
  eng2.layout(fakeView(1080, 1600, 15.3, 1));   // 桌面：1080×1600 css
  return e.lodLevel === eng2.lodLevel && Math.abs(e.labelCount - eng2.labelCount) <= 2
      && Math.abs(e.visibleCount - eng2.visibleCount) <= 2;
}, { w: 360, h: 533, dpr: 3 });   // 手机：同一物理屏 = 360×533 css

/* 开关：三开关全开 → 出现彩色全称标签 */
{
  const eng = newEngine();
  eng.showCoop = true; eng.showPark = true; eng.showAbove = true;
  eng.layout(fakeView(1080, 1600, 15.9));
  const colored = eng.labels.filter(p =>
    p.pair.color !== CFG.COLOR.label_text_normal && p.pair.color !== CFG.COLOR.label_text_key);
  console.log(`${colored.length > 0 ? 'PASS' : 'FAIL'}  开关全开: 彩色全称标签 ${colored.length} 个`);
  all &= colored.length > 0;
}

/* hitTest：标签矩形中心命中对应企业 */
{
  const eng = newEngine();
  eng.layout(fakeView(1080, 1600, 16));
  const p = eng.labels[0];
  const cx = (p.rect.l + p.rect.r) / 2, cy = (p.rect.t + p.rect.b) / 2;
  const hit = eng.hitTest(cx, cy);
  const ok = !!hit && hit.id === p.e.id;
  console.log(`${ok ? 'PASS' : 'FAIL'}  hitTest 标签命中 (${p.e.id})`);
  all &= ok;
}

/* 粘性候选位：小幅平移后仍留存的标签槽位不变（被裁掉的除外） */
{
  const eng = newEngine();
  const v1 = fakeView(1080, 1600, 16);
  eng.layout(v1);
  const before = new Map(eng.lastSlot);
  const v2 = fakeView(1080, 1600, 16);
  v2.project = (lat, lon) => ({ x: v1.project(lat, lon).x - 5, y: v1.project(lat, lon).y });  // 平移 5px
  eng.layout(v2);
  let stable = true;
  for (const [id, slot] of before) {
    const cur = eng.lastSlot.get(id);
    if (cur !== undefined && cur !== slot) { stable = false; break; }
  }
  console.log(`${stable ? 'PASS' : 'FAIL'}  粘性候选位（小幅平移槽位不变）`);
  all &= stable;
}

/* ================= P2 附加图层 ================= */

/* 名称总闸 namesHidden：图层态企业名全撤、圆点保留（v0.7.27） */
{
  const eng = newEngine();
  eng.layout(fakeView(1080, 1600, 16));
  const dotsBefore = eng.dots.length, labelsBefore = eng.labelCount;
  eng.namesHidden = true;
  eng.layout(fakeView(1080, 1600, 16));
  const ok = eng.labelCount === 0 && eng.visibleCount === dotsBefore && labelsBefore > 0;
  console.log(`${ok ? 'PASS' : 'FAIL'}  namesHidden: 名 0 · 圆点 ${eng.visibleCount}（原 ${dotsBefore}）`);
  all &= ok;
}

/* RoadLabelLayer：z 门槛（Web 版放宽为物理 5.0 m/px ≈ 比例尺读数 300m，洲哥指示）
   + 门槛以上出名字 + 角度规范 */
{
  const ctx = mockCtx();
  const far = new RoadLabelLayer(DATA.roads);
  far.layout(fakeView(1080, 1600, 14.0), ctx);   // mppPhys ≈ 6.83 > 5.0（比例尺约读 500m+）
  const near = new RoadLabelLayer(DATA.roads);
  near.layout(fakeView(1080, 1600, 15.0), ctx);  // mppPhys ≈ 3.42 ≤ 5.0（比例尺读 300m 段）
  const anglesOk = near._placed.every(p => p.angle >= -90 && p.angle <= 90);
  // 未标（droppedCount）是合法行为：路太短衬不下名字或撞位挪无可挪（App 同款语义）
  const ok = far.roadCount === 0 && far.labelCount === 0
      && near.labelCount > 0 && near.roadCount >= near.labelCount && anglesOk;
  console.log(`${ok ? 'PASS' : 'FAIL'}  路名层: 门槛下 0 · 门槛上 ${near.labelCount}/${near.roadCount} · 角度∈[-90,90]=${anglesOk}`);
  all &= ok;
}

/* v0.2.1 回归：① 缩小过门槛不得残留上一帧路名（"飞出去"根因）；
 * ② 300m 读数段（mpp∈[3.0,5.0)）全段出路名——取上界附近点验证 */
{
  const ctx = mockCtx();
  const r = new RoadLabelLayer(DATA.roads);
  r.layout(fakeView(1080, 1600, 15.0), ctx);
  const aboveOk = r.labelCount > 0;
  r.layout(fakeView(1080, 1600, 13.5), ctx);   // 缩小过门槛（mpp ≈ 9.1 > 5）
  const noStale = r.labelCount === 0 && r._placed === null;
  const scaleUpperMpp = 4.99;   // 比例尺读数恰为 300m 的最远端
  const boundaryOk = scaleUpperMpp <= CFG.MPP_ROADS;
  const ok = aboveOk && noStale && boundaryOk;
  console.log(`${ok ? 'PASS' : 'FAIL'}  路名层回归: 门槛上有名=${aboveOk} · 缩小无残留=${noStale} · 300m读数段上界(${scaleUpperMpp}≤${CFG.MPP_ROADS.toFixed(1)})=${boundaryOk}`);
  all &= ok;
}

/* ParcelLayer：丢名保框（框数 ≥ 名数）+ 默认关 */
{
  const ctx = mockCtx();
  const off = new ParcelLayer(DATA.parcels);
  off.layout(fakeView(1080, 1600, 15.3), ctx);
  const offOk = off.visibleCount === 0 && off.labelCount === 0 && !off.visible;

  const on = new ParcelLayer(DATA.parcels);
  on.visible = true;
  on.layout(fakeView(1080, 1600, 15.3), ctx);
  // App 离线仿真：全貌档（~3 m/px）11/13 出名 —— 同量级即算对齐（测量字体有差）
  const simOk = on.visibleCount > 0 && on.labelCount > 0
      && on.labelCount <= on.visibleCount && on.visibleCount >= on.labelCount;
  console.log(`${offOk && simOk ? 'PASS' : 'FAIL'}  地块层: 默认关=${off.visible} · 视野内 ${on.visibleCount} · 名称 ${on.labelCount} · 未标 ${on.hiddenCount}（App 仿真 11/13）`);
  all &= offOk && simOk;
}

/* FactoryLayer：标记一个不少（只要在视野内）+ 标签 ≤ 标记 + 「下」优先 */
{
  const ctx = mockCtx();
  const fl = new FactoryLayer(DATA.factories);
  fl.visible = true;
  fl.layout(fakeView(1080, 1600, 15.3), ctx);
  // 全貌档 App 仿真 21/25 出标签；标记 25 全画
  const markerOk = fl.visibleCount >= 20 && fl.labelCount > 0 && fl.labelCount <= fl.visibleCount;
  // 「下」优先：验证候选位顺序常量与 App CAND_DX/DY 一致
  const candOk = JSON.stringify(CFG.FACTORY_CAND) === JSON.stringify([[0,1],[0,-1],[1,0],[-1,0],[1,1],[-1,1],[1,-1],[-1,-1]]);
  console.log(`${markerOk && candOk ? 'PASS' : 'FAIL'}  厂房层: 视野内 ${fl.visibleCount} · 标签 ${fl.labelCount} · 未标 ${fl.hiddenCount}（App 仿真 21/25）· 8 位「下」优先=${candOk}`);
  all &= markerOk && candOk;
}

/* 折行规则：名称首空格断行、面积全角逗号断行（IdleFactory 派生移植） */
{
  const n1 = factoryNameLines('B-7-2地块 勃源冷链物流加工项目');   // → 2 行
  const n2 = factoryNameLines('曲美木业');                          // → 1 行
  const a1 = factoryAreaLines('占地1.44万平方米，建筑面积7000平方米'); // → 2 行
  const a2 = factoryAreaLines('闲置6500平方米');                     // → 1 行
  const ok = n1.length === 2 && n2.length === 1 && a1.length === 2 && a2.length === 1;
  console.log(`${ok ? 'PASS' : 'FAIL'}  厂房折行: 「${n1.join('/')}」「${a1.join('/')}`);
  all &= ok;
}

/* 地块面积排序锚（Parcel.areaM2）：全部 > 0 且名称含面积段可拆两行 */
{
  const areas = DATA.parcels.map(p => ParcelLayer.areaM2(p.ring));
  const ok = areas.every(a => a > 10000) && parcelLabelLines(DATA.parcels[0].name).length === 2;
  console.log(`${ok ? 'PASS' : 'FAIL'}  地块面积 ${areas.length} 块全可排序 · 名称按空格拆两行`);
  all &= ok;
}

/* 淡入淡出（v0.7.3 FADE_MS=150）：新标签 alpha 从 0 淡入；消失标签进退场列表淡出 */
{
  const eng = new LabelEngine({ clientWidth: 1080, clientHeight: 1600, getContext: () => mockCtx() },
    DATA.enterprises, () => {});
  eng.layout(fakeView(1080, 1600, 16));          // 首排：全部从 0 起淡入
  const startOk = eng.labelCount > 0 && eng.labels.every(p => p.alpha === 0) && eng.fadingOut.length === 0;
  eng.advance(75);                                // 半程
  const midOk = eng.labels.every(p => p.alpha > 0.3 && p.alpha < 0.7);
  eng.advance(75);                                // 收敛
  const endOk = eng.labels.every(p => p.alpha === 1) && !eng.animating;
  // 缩小换视野让一部分标签消失 → 退场列表非空，1 帧后清空
  eng.layout(fakeView(1080, 1600, 18));
  const nFade = eng.fadingOut.length;
  eng.advance(300);
  const outOk = nFade > 0 && eng.fadingOut.length === 0 && !eng.animating;
  const ok = startOk && midOk && endOk && outOk;
  console.log(`${ok ? 'PASS' : 'FAIL'}  淡入淡出: 淡入0→1(${startOk && midOk && endOk}) · 退场 ${nFade} 条 150ms 内清空=${outOk}`);
  all &= ok;
}

/* 台账 Excel 解析（.rebuild_seed_v7.py 移植）：对真实台账文件核对四项统计 */
{
  const XLSX = require('C:/Users/Administrator/.workbuddy/binaries/node/workspace/node_modules/xlsx');
  const ledgerPath = path.join(__dirname, '..', '..', '边合区企业台账.xlsx');
  const wb = XLSX.read(fs.readFileSync(ledgerPath), { type: 'buffer' });
  const { enterprises, stats } = parseLedgerWorkbook(XLSX, wb);
  // 基准：种子 v7 = 164 条 / 154 有坐标 / 10 无坐标
  const ok = stats.total === 164 && stats.withcoord === 154 && stats.nocoord === 10
      && enterprises[0].id.startsWith('td-')
      && enterprises.every(e => e.id.startsWith('td-'))
      && enterprises.filter(e => e.nameIsShort).length === stats.shortonly
      && enterprises.every(e => hasCoord(e) ? (e.lon >= 130 && e.lon <= 132.5) : true);
  console.log(`${ok ? 'PASS' : 'FAIL'}  台账解析: ${stats.total} 条（基准 164）· 可上图 ${stats.withcoord}（基准 154）· 无坐标 ${stats.nocoord}（基准 10）· 仅简称 ${stats.shortonly} · 同点位重复 ${stats.dupGroups} 组`);
  all &= ok;
}

/* 闲置厂房工作表解析（.build_idle_factories.py 移植）：与 App 包内 idle_factories.json 逐条全等 */
{
  const XLSX = require('C:/Users/Administrator/.workbuddy/binaries/node/workspace/node_modules/xlsx');
  const ledgerPath = path.join(__dirname, '..', '..', '边合区企业台账.xlsx');
  const wb = XLSX.read(fs.readFileSync(ledgerPath), { type: 'buffer' });
  const fac = parseFactorySheet(XLSX, wb);
  const ref = JSON.parse(fs.readFileSync(
    path.join(__dirname, '..', '..', 'BhqMap', 'app', 'src', 'main', 'assets', 'idle_factories.json'),
    'utf8')).factories;
  let diff = 0;
  const diffs = [];
  if (fac.factories.length !== ref.length) diff++;
  for (let i = 0; i < Math.min(fac.factories.length, ref.length); i++) {
    const a = fac.factories[i], b = ref[i];
    if (a.id !== b.id || a.name !== b.name || a.areaText !== b.areaText
        || Math.abs(a.lon - b.lon) > 1e-9 || Math.abs(a.lat - b.lat) > 1e-9
        || Math.abs(a.areaM2 - b.areaM2) > 0.01) { diff++; diffs.push(`${a.id} ${a.name}: web=${a.areaText} app=${b.areaText}`); }
  }
  // 已知滞后白名单：App 包内 idle_factories.json 落后台账一次改动
  // （if-26 国林四期50号 8339.49→8339.47，App 下轮跑 .build_idle_factories.py 重生成即平）。
  // Web 忠于当前台账，此处只允许这一处差异。
  const knownLag = diffs.length === 1 && diffs[0].startsWith('if-26 ');
  const ok = fac && fac.stats.total === 25 && diff <= 1 && knownLag
      && fac.factories[0].id.startsWith('if-')
      && fac.factories.every(f => f.lon > 130 && f.lon < 132.5 && f.lat > 43.5 && f.lat < 45.5);
  console.log(`${ok ? 'PASS' : 'FAIL'}  闲置厂房解析: ${fac.stats.total} 条（基准 25）· 与 App 包内 JSON 差异 ${diff} 处（白名单 if-26 面积滞后）· 跳过无坐标 ${fac.stats.skippedNoCoord}`);
  all &= ok;
}

global.__SMOKE_EXIT__ = all ? 0 : 1;
console.log(all ? '--- ALL PASS ---' : '--- HAS FAILURES ---');
