/**
 * 边合区产业地图 · Web 版主程序（P2）
 *
 * P1 职责（保留）：
 *   1. Leaflet 地图初始化（天地图 img_w 底图，tk 浏览器端鉴权）
 *   2. 画布覆盖层：企业点位 + 避让标签（LabelEngine）
 *   3. 缩放/平移跟图（v0.1.1 修复：zoomanim 同步变换 + moveend 重排）
 * P2 新增：
 *   4. 附加图层三件套（layers.js）：路名 → 企业 → 地块 → 厂房 叠放顺序（v0.7.29）
 *   5. LayerMode 三态互斥（NONE/PARCEL/FACTORY + 样式开关快照，MainActivity 移植）
 *   6. 三段式详情面板（v0.7.20：固定头 / 滚动简介 / 固定尾）
 *   7. 版权信息页（v0.7.30 版式 B；入口 = 点版本号，读数挪到长按版本号）
 */

'use strict';

/* ---------------- 地图初始化 ---------------- */

const map = L.map('map', {
  zoomControl: false,
  zoomSnap: 0.1,
  zoomDelta: 0.5,
  wheelPxPerZoomLevel: 120,
  minZoom: CFG.TILE_MIN_ZOOM,
  maxZoom: CFG.TILE_MAX_ZOOM,
  attributionControl: false,   // 版权信息在版权页（对应 App 不显示 attribution 的取舍）
}).fitBounds(CFG.BOUNDS, { padding: [0, 0] });

function tiandituLayer(layer, opts) {
  return L.tileLayer('https://t{s}.tianditu.gov.cn/{l}_w/wmts?' + [
    'SERVICE=WMTS', 'REQUEST=GetTile', 'VERSION=1.0.0',
    'LAYER={l}', 'STYLE=default', 'TILEMATRIXSET=w', 'FORMAT=tiles',
    'TileMatrix={z}', 'TileRow={y}', 'TileCol={x}', `tk=${CFG.TK}`,
  ].join('&'), Object.assign({
    subdomains: CFG.SUBDOMAINS.map(String),
    minZoom: CFG.TILE_MIN_ZOOM,
    maxZoom: CFG.TILE_MAX_ZOOM,
    l: layer,
  }, opts));
}

const imgLayer = tiandituLayer('img').addTo(map);
// 影像注记层（cia）：与安卓版一致默认关闭 —— 地名会与自绘标签打架。
// 需要时打开：tiandituLayer('cia').addTo(map);

L.control.scale({ imperial: false, position: 'bottomleft' }).addTo(map);

/* ---------------- 画布覆盖层（P1 机制 + P2 图层挂载） ---------------- */

const labelPane = map.createPane('labelCanvas');
labelPane.style.pointerEvents = 'none';

const canvas = document.getElementById('labels');
labelPane.appendChild(canvas);
if (L.Browser.any3d) L.DomUtil.addClass(canvas, 'leaflet-zoom-animated');

const engine = new LabelEngine(canvas, DATA.enterprises, showDetail);

/* 附加图层（绘制顺序 = App v0.7.29 叠放顺序：路名最底、厂房最上） */
const roadLayer = new RoadLabelLayer(DATA.roads || []);
const parcelLayer = new ParcelLayer(DATA.parcels || []);
const factoryLayer = new FactoryLayer(DATA.factories || []);

let canvasTopLeft = L.point(0, 0);

function resetCanvas() {
  const size = map.getSize();
  const dpr = window.devicePixelRatio || 1;
  canvasTopLeft = map.containerPointToLayerPoint([0, 0]).round();
  L.DomUtil.setPosition(canvas, canvasTopLeft);
  canvas.width = size.x * dpr;
  canvas.height = size.y * dpr;
  canvas.style.width = size.x + 'px';
  canvas.style.height = size.y + 'px';
  scheduleRedraw();
}

map.on('zoomanim', (e) => {
  const scale = map.getZoomScale(e.zoom, map.getZoom());
  const topLeftLatLng = map.containerPointToLatLng([0, 0]);
  const offset = map._latLngToNewLayerPoint(topLeftLatLng, e.zoom, e.center).round();
  L.DomUtil.setTransform(canvas, offset, scale);
});

function currentView() {
  const size = map.getSize();
  const center = map.getCenter();
  const b = map.getBounds();
  const zoom = map.getZoom();
  const mpp = 156543.03392 * Math.cos(center.lat * Math.PI / 180) / Math.pow(2, zoom);
  return {
    w: size.x, h: size.y, zoom, mpp,
    dpr: window.devicePixelRatio || 1,
    north: b.getNorth(), south: b.getSouth(),
    west: b.getWest(), east: b.getEast(),
    latSpan: b.getNorth() - b.getSouth(),
    lonSpan: b.getEast() - b.getWest(),
    project: (lat, lon) => {
      const p = map.latLngToLayerPoint([lat, lon]);
      return { x: p.x - canvasTopLeft.x, y: p.y - canvasTopLeft.y };
    },
  };
}

let rafPending = false;
/** 按 v0.7.29 叠放顺序清屏+落笔（v0.2.1 起清屏独立） */
function drawAll(ctx) {
  engine.clear();
  roadLayer.draw(ctx);
  engine.draw();
  parcelLayer.draw(ctx);
  factoryLayer.draw(ctx);
}

function scheduleRedraw() {
  if (map._animatingZoom) return;
  if (rafPending) return;
  rafPending = true;
  requestAnimationFrame(() => {
    rafPending = false;
    const view = currentView();
    const ctx = canvas.getContext('2d');
    engine.layout(view);
    roadLayer.layout(view, ctx);      // 路名层（门槛 Web 版 5.0 m/px）
    parcelLayer.layout(view, ctx);    // 地块层（图层开时才出内容）
    factoryLayer.layout(view, ctx);   // 厂房层
    drawAll(ctx);
    startFadeLoop();
  });
}

/* ---------------- 淡入淡出动画循环（App v0.7.3 帧 移植） ----------------
 * 布局重排后若有标签在过渡中，按真实帧间隔推进 alpha 至收敛。 */

let fadeRaf = false, lastFrameMs = 0;
function fadeTick(now) {
  const dt = lastFrameMs ? Math.min(now - lastFrameMs, 100) : 16;   // 封顶 100ms 防跳帧
  lastFrameMs = now;
  const animating = engine.advance(dt);
  drawAll(canvas.getContext('2d'));
  if (animating) requestAnimationFrame(fadeTick);
  else { fadeRaf = false; lastFrameMs = 0; }
}
function startFadeLoop() {
  if (fadeRaf || (!engine.animating && !engine.fadingOut.length)) return;
  fadeRaf = true;
  requestAnimationFrame(fadeTick);
}

map.on('moveend viewreset resize', resetCanvas);
document.fonts && document.fonts.ready.then(() => { engine._pairs = null; scheduleRedraw(); });

resetCanvas();

/* ---------------- 点选 ---------------- */

map.on('click', (e) => {
  const origin = map.layerPointToContainerPoint(canvasTopLeft);
  const ent = engine.hitTest(e.containerPoint.x - origin.x, e.containerPoint.y - origin.y);
  if (ent) showDetail(ent);
  else hideDetail();
});

/* ---------------- 显示控制浮层 + LayerMode 三态互斥（MainActivity 移植） ----------------
 * 状态机（MainActivity.onLayerToggled 原样）：
 *   ① NONE → 图层：快照三个样式开关并强制关闭；
 *   ② 图层 → NONE：按快照复原；
 *   ③ 图层 ⟷ 图层互切：快照与复原都不做（快照保持"最初那次"）。
 * 连带：namesHidden = 处于图层态（企业名全撤、圆点保留）。
 */
const btnCoop = document.getElementById('btnCoop');
const panel = document.getElementById('panel');

const LAYER = { NONE: 0, PARCEL: 1, FACTORY: 2 };
let layerMode = LAYER.NONE;
const savedSwitches = { coop: false, park: false, above: false };

const sw = {
  coop: document.getElementById('swCoop'),
  park: document.getElementById('swPark'),
  above: document.getElementById('swAbove'),
  idle: document.getElementById('swIdle'),
  factory: document.getElementById('swFactory'),
};

function panelHasCustomState() {
  return engine.showCoop || engine.showPark || engine.showAbove || layerMode !== LAYER.NONE;
}

function refreshFab() {
  btnCoop.classList.toggle('active', panelHasCustomState());
}

/** 处于图层态时三个样式行整行置灰（DISABLED_ROW_ALPHA = 0.45，MainActivity 同值） */
function refreshSwitchesEnabled() {
  const enabled = layerMode === LAYER.NONE;
  sw.coop.disabled = !enabled;
  sw.park.disabled = !enabled;
  sw.above.disabled = !enabled;
  for (const id of ['rowCoop', 'rowPark', 'rowAbove']) {
    document.getElementById(id).style.opacity = enabled ? '' : '0.45';
  }
}

/** 程序性赋值不惊动 change 监听（App setCheckedSilently 对应物：input.checked 不触发事件） */
function setCheckedSilently(input, checked) { input.checked = checked; }

function syncPopupSwitchState() {
  setCheckedSilently(sw.coop, engine.showCoop);
  setCheckedSilently(sw.park, engine.showPark);
  setCheckedSilently(sw.above, engine.showAbove);
  setCheckedSilently(sw.idle, layerMode === LAYER.PARCEL);
  setCheckedSilently(sw.factory, layerMode === LAYER.FACTORY);
}

/** onLayerToggled 移植：target ∈ {PARCEL, FACTORY} */
function onLayerToggled(target, on) {
  const wasOn = layerMode !== LAYER.NONE;
  layerMode = on ? target : LAYER.NONE;
  const nowOn = layerMode !== LAYER.NONE;

  if (!wasOn && nowOn) {
    // ① 快照 + 强制关闭（顺序不能反）
    savedSwitches.coop = engine.showCoop;
    savedSwitches.park = engine.showPark;
    savedSwitches.above = engine.showAbove;
    engine.showCoop = false; engine.showPark = false; engine.showAbove = false;
  } else if (wasOn && !nowOn) {
    // ② 按快照复原（③ 图层互切什么都不做）
    engine.showCoop = savedSwitches.coop;
    engine.showPark = savedSwitches.park;
    engine.showAbove = savedSwitches.above;
  }

  // 名称总闸：图层态企业名全撤、圆点保留；图层开关各归各位（互斥由单变量派生保证）
  engine.namesHidden = nowOn;
  parcelLayer.visible = layerMode === LAYER.PARCEL;
  factoryLayer.visible = layerMode === LAYER.FACTORY;

  syncPopupSwitchState();
  refreshSwitchesEnabled();
  refreshFab();
  scheduleRedraw();
}

sw.coop.addEventListener('change', () => { engine.showCoop = sw.coop.checked; refreshFab(); scheduleRedraw(); });
sw.park.addEventListener('change', () => { engine.showPark = sw.park.checked; refreshFab(); scheduleRedraw(); });
sw.above.addEventListener('change', () => { engine.showAbove = sw.above.checked; refreshFab(); scheduleRedraw(); });
sw.idle.addEventListener('change', () => onLayerToggled(LAYER.PARCEL, sw.idle.checked));
sw.factory.addEventListener('change', () => onLayerToggled(LAYER.FACTORY, sw.factory.checked));

/* ---------------- 影像注记开关（P3 补齐，App 无此入口） ----------------
 * cia_w 注记瓦片层：与 img 同一 WMTS 服务同 tk，惰性创建、首次打开才联网。 */

let ciaLayer = null;
document.getElementById('swCia').addEventListener('change', (ev) => {
  const on = ev.target.checked;
  if (on && !ciaLayer) ciaLayer = tiandituLayer('cia').addTo(map);
  else if (ciaLayer) { if (on) map.addLayer(ciaLayer); else map.removeLayer(ciaLayer); }
});

/* ---------------- 台账 Excel 导入（P3：.rebuild_seed_v7.py 浏览器端移植） ----------------
 * 规则见 js/excel.js（列号/id=td-行号/坐标校验/仅简称 全对齐脚本）。
 * 导入后内存立即生效；「下载数据文件」导出新 data.js 供替换持久化。 */

const impFile = document.getElementById('impFile');
const impStatus = document.getElementById('impStatus');

document.getElementById('rowImport').addEventListener('click', () => impFile.click());

impFile.addEventListener('change', () => {
  const file = impFile.files && impFile.files[0];
  impFile.value = '';   // 允许重复导入同名文件
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    try {
      const wb = XLSX.read(reader.result, { type: 'array' });
      const { enterprises, stats } = parseLedgerWorkbook(XLSX, wb);
      if (!stats.total) throw new Error('台账里没有有效条目');

      DATA.enterprises = enterprises;
      DATA.count = stats.total;
      DATA.seedImportedAt = new Date().toISOString().slice(0, 19).replace('T', ' ');
      // 引擎重建索引：数据换血，粘性位/退场列表/字号画笔全部失效
      engine.data = enterprises;
      engine.lastSlot.clear();
      engine.fadingOut = [];
      engine._prevAlpha.clear();
      engine._pairs = null;
      scheduleRedraw();

      /* 闲置厂房工作表（v0.3.1，.build_idle_factories.py 移植）：
       * 表缺失 → 照常只导企业；表在 → 厂房层换血重排 */
      let fac = null;
      try { fac = parseFactorySheet(XLSX, wb); } catch (_) { fac = null; }
      let facMsg = '';
      if (fac) {
        DATA.factories = fac.factories;
        factoryLayer.factories = fac.factories;
        factoryLayer.factoryCount = fac.factories.length;
        factoryLayer._ordered = null;   // 面积降序缓存随数据失效
        scheduleRedraw();
        facMsg = ` · 闲置厂房 ${fac.stats.total} 条`;
        if (fac.stats.skippedNoCoord) facMsg += `（跳过无坐标 ${fac.stats.skippedNoCoord}）`;
      }

      let msg = `导入 ${stats.total} 条（全称 ${stats.full} / 仅简称 ${stats.shortonly}）`
          + ` · 可上图 ${stats.withcoord} · 无坐标 ${stats.nocoord}`
          + facMsg;
      if (stats.dupGroups) msg += ` · 同点位重复 ${stats.dupGroups} 组（保留独立条目）`;
      msg += '　';
      impStatus.classList.add('show');
      impStatus.innerHTML = escapeHtml(msg)
        + '<a href="javascript:void(0)" id="impDownload">下载数据文件</a>';
      document.getElementById('impDownload').addEventListener('click', downloadDataJs);
    } catch (err) {
      impStatus.classList.add('show');
      impStatus.textContent = `导入失败：${err.message}`;
    }
  };
  reader.readAsArrayBuffer(file);
});

/** 导出 data.js（持久化：下载后替换 Web版/data/data.js）
 * v0.3.1 修：v0.3.0 只导了企业——替换后路网/地块/厂房会全丢，四套数据整体带走。 */
function downloadDataJs() {
  const payload = {
    version: DATA.version ?? 2,
    seedVersion: DATA.seedVersion,
    count: DATA.count,
    enterprises: DATA.enterprises,
    roads: DATA.roads || [],
    parcels: DATA.parcels || [],
    factories: DATA.factories || [],
  };
  const body = '/* 由台账导入生成（' + (DATA.seedImportedAt || '') + '）；替换 Web版/data/data.js 即持久 */\n'
    + 'const DATA = ' + JSON.stringify(payload) + ';\n';
  const url = URL.createObjectURL(new Blob([body], { type: 'text/javascript' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = 'data.js';
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 3000);
}

btnCoop.addEventListener('click', () => {
  panel.classList.toggle('show');
  btnCoop.classList.toggle('active', panelHasCustomState());
});

document.addEventListener('click', (ev) => {
  if (!panel.classList.contains('show')) return;
  if (panel.contains(ev.target) || btnCoop.contains(ev.target)) return;
  panel.classList.remove('show');
  btnCoop.classList.remove('active');
});

/* ---------------- 三段式详情面板（v0.7.20 移植） ----------------
 * 固定头（名称/徽章/主营业务）+ 独立滚动简介（max 360dp 由 CSS flex 收紧，
 * 简介 min-height 72px 兜底 = INTRO_MIN_HEIGHT_DP）+ 固定尾（坐标行）。
 * 首行缩进 2 字符 = LeadingMarginSpan.Standard(indent,0) 的 CSS 对应物
 * text-indent:2em（只缩首行、折行顶格）。
 */
const detail = document.getElementById('detail');

function showDetail(e) {
  document.getElementById('dName').textContent =
    e.nameIsShort ? `${e.name}（简称）` : e.name;

  const badges = [];
  if (e.isGov)   badges.push('<span class="badge b-gov">行政/事业单位</span>');
  if (e.isCoop)  badges.push('<span class="badge b-coop">协作区项目</span>');
  if (e.isPark)  badges.push('<span class="badge b-park">园区项目</span>');
  if (e.isKey)   badges.push('<span class="badge b-key">重点企业</span>');
  if (e.aboveScale) badges.push('<span class="badge b-above">规模以上</span>');
  const badgeBox = document.getElementById('dBadges');
  badgeBox.innerHTML = badges.join(' ');
  badgeBox.style.display = badges.length ? '' : 'none';

  // v0.4：无主营业务整栏隐藏，不显示"—"占位
  const biz = document.getElementById('dBiz');
  if (e.mainBusiness && e.mainBusiness.trim()) {
    biz.textContent = e.mainBusiness;
    biz.style.display = '';
  } else {
    biz.style.display = 'none';
  }

  // 简介按段落重排，每段首行缩进 2 字符；空简介给占位句
  const introEl = document.getElementById('dIntro');
  const intro = (e.intro || '').trim();
  const paragraphs = intro ? intro.split('\n').map(s => s.trim()).filter(Boolean) : [];
  if (!paragraphs.length) paragraphs.push('（暂无简介）');
  introEl.innerHTML = paragraphs.map(p => `<p>${escapeHtml(p)}</p>`).join('');
  document.getElementById('dIntroScroll').scrollTop = 0;   // 每次打开回到简介顶部

  // 坐标行：缺坐标时明说，别让空白像显示故障
  document.getElementById('dCoord').textContent = hasCoord(e)
    ? `WGS-84   ${e.lat.toFixed(6)}, ${e.lon.toFixed(6)}` + (e.cluster ? `   ·   ${e.cluster}` : '')
    : '坐标待补充 · 未在地图标出';

  detail.classList.add('show');
}

function hideDetail() { detail.classList.remove('show'); }
document.getElementById('dClose').addEventListener('click', hideDetail);

function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/* ---------------- 版权信息页（v0.7.30 版式 B 移植） ----------------
 * 入口 = 点版本号（App 同款）；校准读数挪到**长按版本号** 600ms
 * （对应 App 长按指北针）。
 */
const about = document.getElementById('about');

function showAbout() {
  document.getElementById('aboutVer').textContent = `${WEB_VERSION}`;
  document.getElementById('aboutOwner').textContent = ABOUT_TEXT.owner;
  document.getElementById('aboutTeam').textContent = ABOUT_TEXT.team;
  document.getElementById('aboutDate').textContent = ABOUT_TEXT.date;
  document.getElementById('aboutCopyright').textContent = ABOUT_TEXT.copyright;
  document.getElementById('aboutBasemap').textContent = ABOUT_TEXT.baseMap;
  document.getElementById('aboutData').textContent = ABOUT_TEXT.dataNote;
  document.getElementById('aboutFooter').textContent = ABOUT_TEXT.footer;
  about.classList.add('show');
}
function hideAbout() { about.classList.remove('show'); }

document.getElementById('aboutClose').addEventListener('click', hideAbout);
about.addEventListener('click', (ev) => { if (ev.target === about) hideAbout(); });   // 点面板外关闭

/* ---------------- 校准读数（长按版本号 600ms） ---------------- */

const readout = document.getElementById('readout');
const tvVersion = document.getElementById('tvVersion');

let lpTimer = null, lpFired = false;
tvVersion.addEventListener('pointerdown', () => {
  lpFired = false;
  lpTimer = setTimeout(() => { lpFired = true; updateReadout(); readout.classList.add('show'); }, 600);
});
for (const ev of ['pointerup', 'pointerleave', 'pointercancel']) {
  tvVersion.addEventListener(ev, () => clearTimeout(lpTimer));
}
tvVersion.addEventListener('click', () => {
  if (lpFired) { lpFired = false; return; }   // 长按松开时的 click 不算
  readout.classList.remove('show');
  showAbout();
});
document.addEventListener('click', (ev) => {
  if (!readout.classList.contains('show')) return;
  if (readout.contains(ev.target) || ev.target === tvVersion) return;
  readout.classList.remove('show');
});

function updateReadout() {
  document.getElementById('rZoom').textContent = map.getZoom().toFixed(1);
  document.getElementById('rMpp').textContent = isFinite(engine.metersPerPixel)
    ? engine.metersPerPixel.toFixed(2) + ' m/px' : '—';
  document.getElementById('rLod').textContent = engine.lodLevel === CFG.LOD_KEY ? '重点' : '全部';
  document.getElementById('rVisible').textContent = engine.visibleCount;
  document.getElementById('rLabel').textContent = engine.labelCount;
  document.getElementById('rHidden').textContent = engine.hiddenByCollision;
  // 路名行（App line3：未到门槛报门槛，到了报计数；门槛 Web 版已放宽至 5.0）
  document.getElementById('rRoad').textContent =
    (engine.metersPerPixel > CFG.MPP_ROADS)
      ? `门槛 ${CFG.MPP_ROADS.toFixed(1)} m/px 未到`
      : `${roadLayer.roadCount} 条 / 名 ${roadLayer.labelCount} / 弃 ${roadLayer.droppedCount}`;
  // 图层行（App line4：一行说清两个图层）
  document.getElementById('rLayer').textContent = layerMode === LAYER.PARCEL
    ? `地块 ${parcelLayer.visibleCount} / 名 ${parcelLayer.labelCount} / 未标 ${parcelLayer.hiddenCount}`
    : layerMode === LAYER.FACTORY
      ? `厂房 ${factoryLayer.visibleCount} / 名 ${factoryLayer.labelCount} / 未标 ${factoryLayer.hiddenCount}`
      : '无（均关）';
}

/* ---------------- 版本号 ---------------- */

tvVersion.textContent = `Web ${WEB_VERSION}`;
