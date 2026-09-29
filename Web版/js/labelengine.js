/**
 * 企业点位与名称标签引擎 —— 安卓版 map/EnterpriseOverlay.kt 的 Web 移植。
 *
 * 移植范围（P1）：
 *   - LodConfig 分档（m/px 门槛，与 zoom 无关）
 *   - 候选池筛选：远档只留 重点/行政/项目（项目不受档位限制，v0.7.9 结论）
 *   - 中间档 14.5<z<15.5「筛选」语义（v0.7.24）：只出重点/行政名称，圆点保留
 *   - 全显档 z≥17.5：全体豁免避让，8 位里挑总重叠最小位（v0.7.11-A）
 *   - 8 候选位贪心避让 + 屏幕网格加速 + 有界重叠（0.35 / 全显障碍 0.08）
 *   - priority 派生：挑位顺序、叠压顺序（高者后画）、点击命中顺序
 *   - 粘性候选位（v0.7.3 lastSlot）：重排先试上次用的位
 *   - 字号按最高身份取档、开关只管简称/全称+颜色（v0.7.26），双身份再判 isKey
 *   - 名称总闸 namesHidden：全撤名称、圆点保留（v0.7.27，P2 图层用）
 *   - 无坐标条目不上图（v0.6）
 *
 * 未移植（P1 范围外，注明出处）：
 *   - 跟图平移 + 停稳重排（v0.7.5）—— Web 改为每次视野变化全量重排，
 *     154 家数据量下 JS 每帧重排 < 2ms，无性能压力；粘性位保证滑动不翻转
 *   - 淡入淡出（v0.7.3 FADE_MS）—— 观感增强项，后续版本补
 */

'use strict';

/* ---------------- Enterprise.kt 派生 ---------------- */

function priorityOf(e) {
  if (e.isGov) return 4;
  if (e.isCoop || e.isPark) return 3;
  if (e.isKey) return 2;
  if (e.aboveScale) return 1;
  return 0;
}

/** 标签显示文字：优先简称，否则剥地名前缀与公司后缀 */
function labelTextOf(e) {
  if (e.shortName && e.shortName.trim()) return e.shortName;
  let t = e.name.replace(/^绥芬河市/, '');
  t = t.replace(/有限责任公司$/, '').replace(/有限公司$/, '');
  return t.trim() || e.name;
}

function hasCoord(e) {
  return typeof e.lon === 'number' && isFinite(e.lon)
      && typeof e.lat === 'number' && isFinite(e.lat);
}

/* ---------------- 字体与测量 ---------------- */

function fontString(bold, sizePx) {
  return `${bold ? '700' : '400'} ${sizePx}px "Microsoft YaHei", "PingFang SC", "Noto Sans SC", sans-serif`;
}

class TextPair {
  constructor(ctx, bold, color, sizePx) {
    this.bold = bold;
    this.color = color;
    this.size = sizePx;
    this.ctx = ctx;
    ctx.font = fontString(bold, sizePx);
    const m = ctx.measureText('测国');
    // fontBoundingBox 在现代浏览器可用；兜底用经验系数
    const asc = m.fontBoundingBoxAscent ?? sizePx * 0.88;
    const desc = m.fontBoundingBoxDescent ?? sizePx * 0.26;
    this.ascent = asc;
    this.descent = desc;
    this.lineH = asc + desc;
  }
  measure(text) { this.ctx.font = fontString(this.bold, this.size); return this.ctx.measureText(text).width; }
}

/* ---------------- 引擎主体 ---------------- */

class LabelEngine {

  /**
   * @param {HTMLCanvasElement} canvas 覆盖在地图上的画布（CSS px 坐标系）
   * @param {Array} enterprises 企业档案（enterprises.json 的 enterprises 数组）
   * @param {(e:object)=>void} onTap 点选回调
   */
  constructor(canvas, enterprises, onTap) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.data = enterprises;
    this.onTap = onTap;

    // 对外读数（校准用）
    this.metersPerPixel = NaN;
    this.lodLevel = CFG.LOD_KEY;
    this.visibleCount = 0;
    this.labelCount = 0;
    this.hiddenByCollision = 0;

    // 三个样式开关（v0.7.15 语义：只管标签样式）
    this.showCoop = false;
    this.showPark = false;
    this.showAbove = false;
    this.namesHidden = false;

    this.dots = [];        // {e,x,y}
    this.labels = [];      // {e,rect,lines,pair,slot,gap,boxW,boxH,alpha}
    this.lastSlot = new Map();   // 粘性候选位 id→slot（v0.7.3）
    this._fontsReady = false;

    // 淡入淡出（v0.7.3：出现/消失带 FADE_MS 透明度过渡）
    this.fadingOut = [];   // 退场标签快照 {...label, alpha}（旧位旧内容，逐帧减 alpha）
    this.animating = false;
    this._prevAlpha = new Map();   // id→alpha（重排时接续上轮透明度）
  }

  /** 当前档位（LodConfig.levelFor；阈值统一按物理像素 m/px） */
  static lodFor(mppPhys) {
    if (!isFinite(mppPhys) || mppPhys > CFG.MPP_KEY) return CFG.LOD_KEY;
    return CFG.LOD_ALL;
  }

  /**
   * 全量重排（对应 relayout + layoutLabels）。
   * @param {object} view {w,h,zoom,mpp, project:(lat,lon)=>({x,y})}
   */
  layout(view) {
    const { w, h, zoom } = view;
    this.view = view;
    // 物理像素 m/px：CSS px = dp，物理 px = css/dpr；App 全部阈值按物理像素口径
    const dpr = view.dpr || 1;
    this.metersPerPixel = view.mpp / dpr;
    this.lodLevel = LabelEngine.lodFor(this.metersPerPixel);

    // 供退场 diff：必须先存引用再清空（v0.3.0 测试抓出的顺序错）
    this._prevLabels = this.labels;
    this.dots = [];
    this.labels = [];
    this.visibleCount = 0;
    this.labelCount = 0;
    this.hiddenByCollision = 0;

    // 退场列表在重排时整表重建（150ms 过渡，残留的半程退场直接放弃，与 App 语义一致）
    this.fadingOut = [];

    const mpp = this.metersPerPixel;
    const forceAll = mpp < CFG.MPP_FORCE_ALL;                                  // ← z ≥ 17.5
    const keyGovOnly = mpp > CFG.MPP_KEYGOV_MIN && mpp < CFG.MPP_KEYGOV_MAX;   // ← 14.5 < z < 15.5

    // ---- 候选池筛选（relayout 的筛选段）----
    const candidates = [];
    for (const e of this.data) {
      if (!hasCoord(e)) continue;                     // v0.6：无坐标不上图
      if (!inView(e, view, 0.05)) continue;           // 视野外扩 5%
      const include = forceAll || (this.lodLevel === CFG.LOD_ALL)
          || e.isKey || e.isGov
          || e.isCoop || e.isPark;                    // 项目不受档位限制
      if (!include) continue;
      candidates.push(e);
    }
    if (candidates.length === 0) return;

    // 排序即优先级：同级按名称（Enterprise.PRIORITY_COMPARATOR）
    candidates.sort((a, b) => (priorityOf(b) - priorityOf(a)) || a.name.localeCompare(b.name, 'zh'));
    this.visibleCount = candidates.length;

    // ---- 布局 ----
    this._layoutLabels(view, candidates, forceAll, keyGovOnly);

    // ---- 淡入淡出（v0.7.3）：新标签 alpha 从 0（或上轮半程）起，
    //      消失的标签按旧快照进退场列表逐帧减 alpha ----
    const newIds = new Set();
    for (const p of this.labels) {
      newIds.add(p.e.id);
      p.alpha = Math.min(1, this._prevAlpha.get(p.e.id) ?? 0);
    }
    for (const p of this._prevLabels || []) {
      if (!newIds.has(p.e.id)) this.fadingOut.push({ ...p, alpha: p.alpha ?? 1 });
    }
    this._prevAlpha = new Map();
    for (const p of this.labels) this._prevAlpha.set(p.e.id, p.alpha);
  }

  /** 推进淡入淡出一帧（App draw 帧 v0.7.3 移植；dt 最坏 100ms 封顶防跳帧突跳）
   *  @returns {boolean} 是否仍在过渡中（主程序据此续帧） */
  advance(dtMs) {
    let animating = false;
    for (const p of this.labels) {
      if (p.alpha < 1) {
        p.alpha = Math.min(1, p.alpha + dtMs / CFG.FADE_MS);
        if (p.alpha < 1) animating = true;
      }
    }
    for (let i = this.fadingOut.length - 1; i >= 0; i--) {
      const p = this.fadingOut[i];
      p.alpha -= dtMs / CFG.FADE_MS;
      if (p.alpha <= 0) this.fadingOut.splice(i, 1);
      else animating = true;
    }
    this.animating = animating;
    return animating;
  }

  _layoutLabels(view, list, forceAll, keyGovOnly) {
    const { w, h } = view;
    const ctx = this.ctx;
    const cell = 46;   // dp（v0.7 网格单元）
    const grid = new Map();   // key -> Occupied[]

    // 字号画笔（构造时初始化，字体可能后加载则重建）
    const P = this._pairs || (this._pairs = makePairs(ctx, w));
    const padH = CFG.LABEL_PAD_H, lineSp = CFG.LINE_SPACING;

    for (const e of list) {
      const pt = view.project(e.lat, e.lon);
      const x = pt.x, y = pt.y;

      this.dots.push({ e, x, y });

      if (this.namesHidden) continue;                     // 名称总闸（v0.7.27）
      if (keyGovOnly && !(e.isKey || e.isGov)) continue;  // 中间档筛选（v0.7.24）

      const gap = dotRadiusOf(e) + CFG.LABEL_GAP;
      if (x < -240 || x > w + 240 || y < -80 || y > h + 80) continue;

      // ---- 标签文字与样式（v0.7.26 口径：字号按最高身份，开关只管文字/颜色）----
      let pair, rawText, maxLineWidth;
      if (e.isGov) {
        pair = P.gov; rawText = e.shortName || labelTextOf(e); maxLineWidth = w * 0.55;
      } else if (e.isCoop) {
        if (this.showCoop) { pair = e.isKey ? P.coopKey : P.coop; rawText = e.name; maxLineWidth = w * 0.62; }
        else if (e.isKey)  { pair = P.key;  rawText = labelTextOf(e); maxLineWidth = w * 0.55; }
        else               { pair = P.normal; rawText = labelTextOf(e); maxLineWidth = w * 0.50; }
      } else if (e.isPark) {
        if (this.showPark) { pair = e.isKey ? P.parkKey : P.park; rawText = e.name; maxLineWidth = w * 0.62; }
        else if (e.isKey)  { pair = P.key;  rawText = labelTextOf(e); maxLineWidth = w * 0.55; }
        else               { pair = P.normal; rawText = labelTextOf(e); maxLineWidth = w * 0.50; }
      } else if (e.isKey) {
        pair = P.key; rawText = e.shortName || labelTextOf(e); maxLineWidth = w * 0.55;
      } else if (e.aboveScale && this.showAbove) {
        pair = P.above; rawText = e.name; maxLineWidth = w * 0.58;
      } else {
        pair = P.normal; rawText = labelTextOf(e); maxLineWidth = w * 0.50;
      }

      const lines = wrapText(pair, rawText, maxLineWidth, 2);
      if (lines.length === 0) continue;

      let boxW = 0;
      for (const ln of lines) boxW = Math.max(boxW, pair.measure(ln));
      boxW += padH * 2;
      const boxH = pair.lineH * lines.length + lineSp * (lines.length - 1) + padH;

      // 粘性候选位（v0.7.3）
      const prev = this.lastSlot.get(e.id);
      const order = [];
      for (let i = 0; i < CFG.SLOT_COUNT; i++) order.push(prev == null ? i : (prev + i) % CFG.SLOT_COUNT);

      const exempt = forceAll;   // v0.7.24 后豁免只剩全显档一种
      let chosen = -1;
      const rect = { l: 0, t: 0, r: 0, b: 0 };

      if (exempt) {
        // v0.7.11-A：8 位里挑总重叠最小的
        let bestSlot = -1, bestOv = Infinity;
        for (const slot of order) {
          slotRect(slot, x, y, gap, boxW, boxH, rect);
          if (!enoughVisible(rect, w, h)) continue;
          const ov = overlapSum(grid, rect, cell);
          if (ov < bestOv - 1e-3) { bestOv = ov; bestSlot = slot; }
          if (ov <= 0) break;
        }
        if (bestSlot >= 0) { chosen = bestSlot; slotRect(chosen, x, y, gap, boxW, boxH, rect); }
        else { slotRect(0, x, y, gap, boxW, boxH, rect); chosen = 0; }  // 兜底硬画 0 号位
      } else {
        for (const slot of order) {
          slotRect(slot, x, y, gap, boxW, boxH, rect);
          if (!enoughVisible(rect, w, h)) continue;
          if (!collides(grid, rect, cell)) { chosen = slot; break; }
        }
      }

      if (chosen >= 0) {
        this.lastSlot.set(e.id, chosen);
        this.labels.push({
          e, rect: { ...rect }, lines, pair, slot: chosen, gap, boxW, boxH,
        });
        insert(grid, rect, cell, exempt);
      } else {
        this.lastSlot.delete(e.id);
        this.hiddenByCollision++;
      }
    }
    this.labelCount = this.labels.length;
  }

  /* ---------------- 绘制 ---------------- */

  /** 清屏（v0.2.1 从 draw 拆出：main.js 先 clear 再按 v0.7.29 叠放顺序落笔，
   *  路名要画在企业层之下，不能让 engine.draw 的 clearRect 把它擦掉） */
  clear() {
    const ctx = this.ctx;
    const dpr = window.devicePixelRatio || 1;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, this.canvas.clientWidth, this.canvas.clientHeight);
  }

  /** 绘制点位与标签（不再清屏 —— main.js 先调 clear()） */
  draw() {
    const ctx = this.ctx;

    // 1. 圆点/五角星
    for (const d of this.dots) {
      const e = d.e;
      if (e.isGov) drawStar(ctx, d.x, d.y, CFG.DOT_R.gov, CFG.COLOR.dot_gov, CFG.COLOR.dot_stroke_dark);
      else {
        let fill, stroke;
        if (e.isCoop || e.isPark)      { fill = CFG.COLOR.dot_coop;   stroke = CFG.COLOR.dot_stroke_dark; }
        else if (e.isKey)              { fill = CFG.COLOR.dot_key;    stroke = CFG.COLOR.dot_stroke_dark; }
        else if (e.aboveScale)         { fill = CFG.COLOR.dot_above;  stroke = CFG.COLOR.dot_stroke_dark; }
        else                           { fill = CFG.COLOR.dot_normal; stroke = CFG.COLOR.dot_stroke_light; }
        drawDot(ctx, d.x, d.y, dotRadiusOf(e), fill, stroke);
      }
    }

    // 2. 标签：倒序绘制（labels 按优先级降序 → 高者后画压在上层）
    for (let i = this.labels.length - 1; i >= 0; i--) {
      const p = this.labels[i];
      drawLabel(ctx, p, CFG.COLOR.text_shadow);
    }
    // 3. 退场标签（v0.7.3）：按旧位旧内容绘制，alpha 逐帧衰减
    for (const p of this.fadingOut) {
      drawLabel(ctx, p, CFG.COLOR.text_shadow);
    }
  }

  /* ---------------- 点击命中（onSingleTapConfirmed） ---------------- */

  hitTest(x, y) {
    // 先标签（正序 = 最高优先级在最上）
    for (const p of this.labels) {
      const r = p.rect;
      if (x >= r.l && x <= r.r && y >= r.t && y <= r.b) return p.e;
    }
    // 再点位（放宽手指半径，取最近）
    let best = null, bestDist = CFG.TOUCH_RADIUS;
    for (const d of this.dots) {
      const dist = Math.hypot(x - d.x, y - d.y);
      if (dist <= bestDist) { bestDist = dist; best = d.e; }
    }
    return best;
  }
}

/* ---------------- 几何与碰撞 ---------------- */

function inView(e, view, padFrac) {
  // 粗筛：按 App relayout 口径，视野外扩 padFrac（5%）；
  // 不带固定余量 —— 更外的点由布局期 ±240px 边距检查处理
  const latPad = (view.latSpan || 0.05) * padFrac;
  const lonPad = (view.lonSpan || 0.05) * padFrac;
  return e.lat >= view.south - latPad && e.lat <= view.north + latPad
      && e.lon >= view.west - lonPad && e.lon <= view.east + lonPad;
}

function dotRadiusOf(e) {
  if (e.isGov) return CFG.DOT_R.gov;
  if (e.isCoop || e.isPark) return CFG.DOT_R.coop;
  if (e.isKey) return CFG.DOT_R.key;
  if (e.aboveScale) return CFG.DOT_R.above;
  return CFG.DOT_R.normal;
}

/** 第 slot 号候选位矩形（EnterpriseOverlay.slotRect 原样移植） */
function slotRect(slot, x, y, gap, boxW, boxH, out) {
  switch (slot) {
    case 0: out.l = x - boxW / 2; out.t = y - gap - boxH; out.r = x + boxW / 2; out.b = y - gap; break;
    case 1: out.l = x - boxW / 2; out.t = y + gap; out.r = x + boxW / 2; out.b = y + gap + boxH; break;
    case 2: out.l = x + gap; out.t = y - boxH / 2; out.r = x + gap + boxW; out.b = y + boxH / 2; break;
    case 3: out.l = x - gap - boxW; out.t = y - boxH / 2; out.r = x - gap; out.b = y + boxH / 2; break;
    case 4: out.l = x + gap; out.t = y - gap - boxH / 2; out.r = x + gap + boxW; out.b = y - gap + boxH / 2; break;
    case 5: out.l = x + gap; out.t = y + gap - boxH / 2; out.r = x + gap + boxW; out.b = y + gap + boxH / 2; break;
    case 6: out.l = x - gap - boxW; out.t = y - gap - boxH / 2; out.r = x - gap; out.b = y - gap + boxH / 2; break;
    default: out.l = x - gap - boxW; out.t = y + gap - boxH / 2; out.r = x - gap; out.b = y + gap + boxH / 2; break;
  }
}

/** 标签至少露出 60% 面积（enoughVisible） */
function enoughVisible(r, w, h) {
  const pad = CFG.SCREEN_PAD;
  const l = Math.max(r.l, pad), t = Math.max(r.t, pad);
  const rt = Math.min(r.r, w - pad), b = Math.min(r.b, h - pad);
  if (rt <= l || b <= t) return false;
  const area = (r.r - r.l) * (r.b - r.t);
  if (area <= 0) return false;
  return (rt - l) * (b - t) >= area * CFG.VISIBLE_MIN_RATIO;
}

function cellKey(gx, gy) { return gx * 73856093 ^ gy * 19349663; }

function collides(grid, r, cell) {
  const gx0 = Math.floor(r.l / cell), gx1 = Math.floor(r.r / cell);
  const gy0 = Math.floor(r.t / cell), gy1 = Math.floor(r.b / cell);
  const rArea = (r.r - r.l) * (r.b - r.t);
  for (let gx = gx0; gx <= gx1; gx++) {
    for (let gy = gy0; gy <= gy1; gy++) {
      const bucket = grid.get(cellKey(gx, gy));
      if (!bucket) continue;
      for (const o of bucket) {
        const ow = Math.min(o.r.r, r.r) - Math.max(o.r.l, r.l);
        if (ow <= 0) continue;
        const oh = Math.min(o.r.b, r.b) - Math.max(o.r.t, r.t);
        if (oh <= 0) continue;
        const smaller = Math.min(rArea, (o.r.r - o.r.l) * (o.r.b - o.r.t));
        if (smaller <= 0) continue;
        const tol = o.exempt ? CFG.EXEMPT_BLOCK_RATIO : CFG.OVERLAP_RATIO;
        if (ow * oh > smaller * tol) return true;
      }
    }
  }
  return false;
}

function overlapSum(grid, r, cell) {
  const gx0 = Math.floor(r.l / cell), gx1 = Math.floor(r.r / cell);
  const gy0 = Math.floor(r.t / cell), gy1 = Math.floor(r.b / cell);
  const seen = new Set();
  let sum = 0;
  for (let gx = gx0; gx <= gx1; gx++) {
    for (let gy = gy0; gy <= gy1; gy++) {
      const bucket = grid.get(cellKey(gx, gy));
      if (!bucket) continue;
      for (const o of bucket) {
        if (seen.has(o)) continue;
        seen.add(o);
        const ow = Math.min(o.r.r, r.r) - Math.max(o.r.l, r.l);
        if (ow <= 0) continue;
        const oh = Math.min(o.r.b, r.b) - Math.max(o.r.t, r.t);
        if (oh <= 0) continue;
        sum += ow * oh;
      }
    }
  }
  return sum;
}

/** 存副本（v0.7.11 修的别名 Bug —— JS 里 {...rect} 同理） */
function insert(grid, r, cell, exempt) {
  const gx0 = Math.floor(r.l / cell), gx1 = Math.floor(r.r / cell);
  const gy0 = Math.floor(r.t / cell), gy1 = Math.floor(r.b / cell);
  const item = { r: { ...r }, exempt };
  for (let gx = gx0; gx <= gx1; gx++) {
    for (let gy = gy0; gy <= gy1; gy++) {
      const k = cellKey(gx, gy);
      let bucket = grid.get(k);
      if (!bucket) { bucket = []; grid.set(k, bucket); }
      bucket.push(item);
    }
  }
}

/* ---------------- 文字折行（wrap/ellipsize 移植） ---------------- */

function wrapText(pair, text, maxWidth, maxLines) {
  if (!text || maxWidth <= 0) return [];
  const out = [];
  let start = 0;
  while (start < text.length) {
    const isLast = out.length === maxLines - 1;
    const rest = text.slice(start);
    if (isLast) {
      const fit = ellipsize(pair, rest, maxWidth);
      if (fit) out.push(fit);
      break;
    }
    let lo = 1, hi = rest.length;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (pair.measure(rest.slice(0, mid)) <= maxWidth) lo = mid; else hi = mid - 1;
    }
    if (lo < 1) return [];
    out.push(rest.slice(0, lo));
    start += lo;
    if (lo === rest.length) break;
  }
  return out;
}

function ellipsize(pair, text, maxWidth) {
  if (!text) return '';
  if (pair.measure(text) <= maxWidth) return text;
  const el = '…', elW = pair.measure(el);
  let lo = 0, hi = text.length;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (pair.measure(text.slice(0, mid)) + elW <= maxWidth) lo = mid; else hi = mid - 1;
  }
  return lo <= 0 ? '' : text.slice(0, lo) + el;
}

/* ---------------- 绘制原语 ---------------- */

function drawDot(ctx, x, y, radius, fill, stroke) {
  ctx.beginPath();
  ctx.arc(x, y, radius, 0, Math.PI * 2);
  ctx.fillStyle = fill;
  ctx.strokeStyle = stroke;
  ctx.lineWidth = 1.4;
  ctx.fill();
  ctx.stroke();
}

/** 正立五角星（starPath 移植：外接圆半径，-90° 起每 36° 一顶点） */
function starPoints(outerR) {
  const innerR = outerR * CFG.STAR_INNER_RATIO;
  const pts = [];
  for (let i = 0; i < 10; i++) {
    const r = i % 2 === 0 ? outerR : innerR;
    const a = (-90 + i * 36) * Math.PI / 180;
    pts.push([r * Math.cos(a), r * Math.sin(a)]);
  }
  return pts;
}

function drawStar(ctx, cx, cy, outerR, fill, stroke) {
  const pts = starPoints(outerR);
  ctx.beginPath();
  ctx.moveTo(cx + pts[0][0], cy + pts[0][1]);
  for (let i = 1; i < 10; i++) ctx.lineTo(cx + pts[i][0], cy + pts[i][1]);
  ctx.closePath();
  ctx.fillStyle = fill;
  ctx.strokeStyle = stroke;
  ctx.lineWidth = 1.6;
  ctx.lineJoin = 'round';
  ctx.fill();
  ctx.stroke();
}

/** 单个标签：灰描边（1.6px）→ 填充 + 投影（alpha 淡入淡出，v0.7.3） */
function drawLabel(ctx, p, shadowColor) {
  const pair = p.pair;
  ctx.font = fontString(pair.bold, pair.size);
  ctx.textAlign = 'center';
  ctx.textBaseline = 'alphabetic';
  ctx.globalAlpha = p.alpha ?? 1;
  const cx = (p.rect.l + p.rect.r) / 2;
  let top = p.rect.t + CFG.LABEL_PAD_H / 2;
  for (const line of p.lines) {
    const baseline = top + pair.ascent;
    ctx.strokeStyle = CFG.COLOR.label_outline;
    ctx.lineWidth = 1.6;
    ctx.lineJoin = 'round';
    ctx.strokeText(line, cx, baseline);
    ctx.fillStyle = pair.color;
    ctx.shadowColor = shadowColor;
    ctx.shadowBlur = 3.4;
    ctx.shadowOffsetY = 1.8;
    ctx.fillText(line, cx, baseline);
    ctx.shadowColor = 'transparent';
    ctx.shadowBlur = 0;
    ctx.shadowOffsetY = 0;
    top += pair.lineH + CFG.LINE_SPACING;
  }
  ctx.globalAlpha = 1;
}

/** 构造全套标签画笔（字号档见 EnterpriseOverlay makeTextPaint 调用处） */
function makePairs(ctx, w) {
  return {
    gov:     new TextPair(ctx, true,  CFG.COLOR.label_text_gov, 16),
    key:     new TextPair(ctx, true,  CFG.COLOR.label_text_key, 14),
    normal:  new TextPair(ctx, false, CFG.COLOR.label_text_normal, 12),
    coop:    new TextPair(ctx, true,  CFG.COLOR.label_text_coop, 12),
    park:    new TextPair(ctx, true,  CFG.COLOR.label_text_park, 12),
    above:   new TextPair(ctx, true,  CFG.COLOR.label_text_above, 12),
    coopKey: new TextPair(ctx, true,  CFG.COLOR.label_text_coop, 14),
    parkKey: new TextPair(ctx, true,  CFG.COLOR.label_text_park, 14),
  };
}
