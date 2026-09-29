/**
 * P2 附加图层三件套 —— 安卓版三个 Overlay 的 Web 移植。
 *
 *   RoadLabelLayer    ← map/RoadOverlay.kt        （v0.7.16 建 / v0.7.17 加门槛）
 *   ParcelLayer       ← map/ParcelOverlay.kt      （v0.7.27）
 *   FactoryLayer      ← map/IdleFactoryOverlay.kt （v0.7.28）
 *
 * 共同约定：
 *   - 三层共用主画布，绘制顺序 = 叠放顺序（MainActivity v0.7.29 定稿）：
 *       路名 → （比例尺）→ 企业层 → 地块层 → 厂房层
 *     由 main.js 按此顺序调用 draw(ctx)。
 *   - 坐标系与企业层相同：CSS px 画布本地坐标（view.project 给出）。
 *   - 阈值统一用物理 m/px（mppPhys = mppCss/dpr），与企业层同口径。
 *   - 颜色取 colors.xml 原值（CFG.COLOR2），透明度已含在 rgba 里。
 *
 * 逐项对照（源码行为 → 本文件）见各类的移植注释。
 */

'use strict';

/* ---------------- 公共小件 ---------------- */

/** 描边+填充+投影 的一次落笔（对应 App「先描边后填充」双 Paint） */
function paintOutlined(ctx, text, x, baseline, fill, outline, shadowColor, strokeW) {
  ctx.strokeStyle = outline;
  ctx.lineWidth = strokeW;
  ctx.lineJoin = 'round';
  ctx.lineCap = 'round';
  ctx.strokeText(text, x, baseline);
  ctx.fillStyle = fill;
  ctx.shadowColor = shadowColor;
  ctx.shadowBlur = 3.4;
  ctx.shadowOffsetY = 1.8;
  ctx.fillText(text, x, baseline);
  ctx.shadowColor = 'transparent';
  ctx.shadowBlur = 0;
  ctx.shadowOffsetY = 0;
}

/** 标签框与屏幕的相交面积是否 ≥ 自身面积×VISIBLE_MIN_RATIO（两 Overlay 同款判据） */
function layerEnoughVisible(r, w, h) {
  const l = Math.max(r.l, 0), t = Math.max(r.t, 0);
  const rr = Math.min(r.r, w), b = Math.min(r.b, h);
  if (rr <= l || b <= t) return false;
  const area = (r.r - r.l) * (r.b - r.t);
  if (area <= 0) return false;
  return (rr - l) * (b - t) >= area * CFG.VISIBLE_MIN_RATIO;
}

/** 重叠面积 > 较小框×OVERLAP_RATIO 判冲突（两 Overlay 同款判据） */
function layerCollides(placed, r) {
  const area = (r.r - r.l) * (r.b - r.t);
  for (const b of placed) {
    const ow = Math.min(r.r, b.r) - Math.max(r.l, b.l);
    if (ow <= 0) continue;
    const oh = Math.min(r.b, b.b) - Math.max(r.t, b.t);
    if (oh <= 0) continue;
    const bArea = (b.r - b.l) * (b.b - b.t);
    const smaller = Math.min(area, bArea);
    if (ow * oh > smaller * CFG.OVERLAP_RATIO) return true;
  }
  return false;
}

/** 矩形文本测量辅助：给定 ctx.font 后量宽 */
function measureWith(ctx, font, text) {
  ctx.font = font;
  return ctx.measureText(text).width;
}

/* ================================================================
 * RoadLabelLayer —— RoadOverlay.kt 移植
 * ================================================================
 * 只画名、不画线；文字与路线平行；z 门槛以下整层不画。
 * 放置：长度中点 → 切线角规范 [-90°,90°] → 9 个比例位 → 旋转 AABB 避让。
 * 排序：路线屏幕长度降序（主干道名字先挑位）。纯函数，每次 draw 全量重算，
 * 不需要粘性位/淡入淡出（App 注释第三节同款结论）。
 */
class RoadLabelLayer {

  constructor(roads) {
    this.roads = roads;           // [{name, coords:[[lon,lat],...]}]
    this.roadCount = 0;           // 视野内参与竞争的路线数
    this.labelCount = 0;          // 实际写出名称数
    this.droppedCount = 0;        // 长度不够或避让失败数
  }

  /**
   * @param {object} view 与企业层同构
   * @param {CanvasRenderingContext2D} ctx 共用画布（layout 阶段只做测量）
   */
  layout(view, ctx) {
    const { w, h } = view;
    this.roadCount = 0; this.labelCount = 0; this.droppedCount = 0;
    // v0.2.1 修（关键）：先清上一帧放置结果。门槛下早退若残留 _placed，
    // 缩小地图后会按旧视野坐标把路名画在错位上（洲哥报告的"路名飞出去"根因）。
    this._placed = null;
    this._baselineFix = 0;
    this._font = '';

    // v0.7.17：z 门槛以下整层不画（物理 m/px 口径，≤ MPP_ROADS 才出）
    if (view.mpp / (view.dpr || 1) > CFG.MPP_ROADS) return;
    if (!this.roads.length || w <= 0 || h <= 0) return;

    const font = fontString(true, CFG.ROAD_TEXT_SIZE);
    ctx.font = font;
    const m = ctx.measureText('测国');
    const asc = m.fontBoundingBoxAscent ?? CFG.ROAD_TEXT_SIZE * 0.88;
    const desc = m.fontBoundingBoxDescent ?? CFG.ROAD_TEXT_SIZE * 0.26;
    const halfTextH = (asc + desc) / 2;
    const baselineFix = -(asc + desc) / 2;
    const strokeWidth = CFG.ROAD_STROKE_DP;
    const pad = CFG.ROAD_AVOID_PAD_DP;

    // ---- 1) 投影 + 量长度 + 视野剔除（draw 第 1 段）----
    const paths = [];
    for (const road of this.roads) {
      const n = road.coords.length;
      if (n < 2) continue;
      const xs = new Float64Array(n), ys = new Float64Array(n), cum = new Float64Array(n);
      let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
      for (let i = 0; i < n; i++) {
        const p = view.project(road.coords[i][1], road.coords[i][0]);
        xs[i] = p.x; ys[i] = p.y;
        if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x;
        if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y;
      }
      for (let i = 1; i < n; i++) cum[i] = cum[i-1] + Math.hypot(xs[i]-xs[i-1], ys[i]-ys[i-1]);
      const len = cum[n-1];
      if (len <= 1) continue;   // 退化成点，画不出平行文字
      if (maxX < -CFG.ROAD_CULL_MARGIN || minX > w + CFG.ROAD_CULL_MARGIN ||
          maxY < -CFG.ROAD_CULL_MARGIN || minY > h + CFG.ROAD_CULL_MARGIN) continue;
      paths.push({ name: road.name, n, xs, ys, cum, len });
    }
    this.roadCount = paths.length;
    if (!paths.length) return;

    // ---- 2) 长路优先挑位（同级保持原序，稳定）----
    paths.sort((a, b) => b.len - a.len);

    // ---- 3) 逐条挑位（9 个比例位 + 旋转 AABB 避让）----
    const placed = [];   // {text,x,y,angle,box}
    const textWCache = new Map();
    for (const p of paths) {
      if (!textWCache.has(p.name)) textWCache.set(p.name, measureWith(ctx, font, p.name));
      const textW = textWCache.get(p.name);
      if (textW + strokeWidth > p.len * CFG.ROAD_MAX_RATIO) { this.droppedCount++; continue; }

      let picked = null;
      for (const f of CFG.ROAD_SLOTS) {
        const at = roadPointAt(p, f);            // {x,y,angle}
        if (at.x < CFG.ROAD_SCREEN_MARGIN || at.x > w - CFG.ROAD_SCREEN_MARGIN ||
            at.y < CFG.ROAD_SCREEN_MARGIN || at.y > h - CFG.ROAD_SCREEN_MARGIN) continue;
        const box = roadAabb(at.x, at.y, at.angle, textW, halfTextH, strokeWidth, pad);
        if (!placed.some(pl => rectsOverlap(pl.box, box))) {
          picked = { text: p.name, x: at.x, y: at.y, angle: at.angle, box };
          break;
        }
      }
      if (picked) placed.push(picked); else this.droppedCount++;
    }
    this.labelCount = placed.length;
    this._placed = placed;
    this._baselineFix = baselineFix;
    this._font = font;
  }

  draw(ctx) {
    if (!this._placed || !this._placed.length) return;
    ctx.font = this._font;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'alphabetic';
    for (const pl of this._placed) {
      ctx.save();
      ctx.translate(pl.x, pl.y);
      ctx.rotate(pl.angle * Math.PI / 180);
      // 旋转后局部坐标画：白边（带投影）→ 黑字，与 App stroke/fill 双笔一致
      paintOutlined(ctx, pl.text, 0, this._baselineFix, '#000000', '#FFFFFF',
        CFG.COLOR.text_shadow, CFG.ROAD_STROKE_DP);
      ctx.restore();
    }
  }
}

/** 路线上比例 f 处的屏幕位置与切线角（pointAt 移植；角度规范 [-90,90]） */
function roadPointAt(p, f) {
  const target = f * p.len;
  let i = 1;
  while (i < p.n - 1 && p.cum[i] < target) i++;
  const segLen = p.cum[i] - p.cum[i-1];
  const u = segLen <= 0 ? 0 : (target - p.cum[i-1]) / segLen;
  const x0 = p.xs[i-1], y0 = p.ys[i-1];
  const dx = p.xs[i] - x0, dy = p.ys[i] - y0;
  let ang = Math.atan2(dy, dx) * 180 / Math.PI;
  if (ang > 90) ang -= 180; else if (ang < -90) ang += 180;
  return { x: x0 + dx * u, y: y0 + dy * u, angle: ang };
}

/** 旋转文字的外接矩形 AABB，外扩 pad（roadAabb 移植） */
function roadAabb(cx, cy, angleDeg, textW, halfTextH, strokeW, pad) {
  const rad = angleDeg * Math.PI / 180;
  const c = Math.abs(Math.cos(rad)), s = Math.abs(Math.sin(rad));
  const hw = textW / 2 + strokeW, hh = halfTextH + strokeW;
  const ex = hw * c + hh * s + pad;
  const ey = hw * s + hh * c + pad;
  return { l: cx - ex, t: cy - ey, r: cx + ex, b: cy + ey };
}

function rectsOverlap(a, b) {
  return Math.min(a.r, b.r) > Math.max(a.l, b.l) && Math.min(a.b, b.b) > Math.max(a.t, b.t);
}

/* ================================================================
 * ParcelLayer —— ParcelOverlay.kt 移植
 * ================================================================
 * 两遍绘制：先全部框体（14% 填充 + 2dp 紫边），再逐个放名称。
 * 名称锚点 = 屏幕空间多边形质心；按面积降序让位；冲突丢名保框；
 * 名称不设缩放门槛（App 注释第四节的结论）。
 */
class ParcelLayer {

  constructor(parcels) {
    this.parcels = parcels;       // [{name, ring:[[lon,lat],...]}]
    this.visible = false;         // 浮层「闲置地块」行控制，默认关（洲哥要求）
    this.parcelCount = parcels.length;
    this.visibleCount = 0;
    this.labelCount = 0;
    this.hiddenCount = 0;
    this._ordered = null;         // 面积降序缓存（数据静态，算一次）
  }

  /** 面积（m²，等距圆柱近似，Parcel.kt areaM2 移植）—— 只用于排序 */
  static areaM2(ring) {
    const n = ring.length;
    if (n < 3) return 0;
    let midLat = 0;
    for (const c of ring) midLat += c[1];
    midLat /= n;
    const kx = 111320 * Math.cos(midLat * Math.PI / 180), ky = 111132;
    const x0 = ring[0][0], y0 = ring[0][1];
    let s = 0;
    for (let i = 0; i < n; i++) {
      const a = ring[i], b = ring[(i + 1) % n];
      s += (a[0] - x0) * kx * (b[1] - y0) * ky - (b[0] - x0) * kx * (a[1] - y0) * ky;
    }
    return Math.abs(s) / 2;
  }

  layout(view, ctx) {
    const { w, h } = view;
    this.visibleCount = 0; this.labelCount = 0; this.hiddenCount = 0;
    this._shapes = null; this._labels = null;
    if (!this.visible || !this.parcels.length || w <= 0 || h <= 0) return;

    if (!this._ordered) {
      this._ordered = this.parcels
        .map(p => ({ name: p.name, ring: p.ring, areaM2: ParcelLayer.areaM2(p.ring) }))
        .sort((a, b) => b.areaM2 - a.areaM2);   // 大块先得名
    }

    const font = fontString(true, CFG.PARCEL_TEXT_SIZE);
    ctx.font = font;
    const m = ctx.measureText('测国');
    const asc = m.fontBoundingBoxAscent ?? CFG.PARCEL_TEXT_SIZE * 0.88;
    const desc = m.fontBoundingBoxDescent ?? CFG.PARCEL_TEXT_SIZE * 0.26;
    const lineH = asc + desc;
    const padH = CFG.LABEL_PAD_H, padGap = 2, lineSpacing = CFG.LINE_SPACING;

    // ---- 第一遍：画全部框体 + 算质心 ----
    const shapes = [];   // {ring(px), ok, cx, cy}
    for (const p of this._ordered) {
      const n = p.ring.length;
      if (n < 3) { shapes.push({ ok: false }); continue; }
      const px = new Array(n), py = new Array(n);
      let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
      for (let i = 0; i < n; i++) {
        const pt = view.project(p.ring[i][1], p.ring[i][0]);
        px[i] = pt.x; py[i] = pt.y;
        if (pt.x < minX) minX = pt.x; if (pt.x > maxX) maxX = pt.x;
        if (pt.y < minY) minY = pt.y; if (pt.y > maxY) maxY = pt.y;
      }
      if (maxX < -CFG.PARCEL_CULL_MARGIN || minX > w + CFG.PARCEL_CULL_MARGIN ||
          maxY < -CFG.PARCEL_CULL_MARGIN || minY > h + CFG.PARCEL_CULL_MARGIN) {
        shapes.push({ ok: false }); continue;
      }
      // 屏幕空间质心（鞋带公式面积重心）
      let twiceArea = 0, cx = 0, cy = 0;
      for (let i = 0; i < n; i++) {
        const k = (i + 1) % n;
        const cross = px[i] * py[k] - px[k] * py[i];
        twiceArea += cross;
        cx += (px[i] + px[k]) * cross;
        cy += (py[i] + py[k]) * cross;
      }
      const ok = Math.abs(twiceArea) >= 1.0;
      if (ok) { cx /= 3 * twiceArea; cy /= 3 * twiceArea; }
      shapes.push({ ok, px, py, cx, cy });
      this.visibleCount++;
    }
    if (!this.visibleCount) return;
    this._shapes = shapes;

    // ---- 第二遍：逐个放名称（丢名保框）----
    const placed = [];
    const labels = [];
    for (let i = 0; i < shapes.length; i++) {
      const sh = shapes[i];
      if (!sh.ok) continue;
      const lines = parcelLabelLines(this._ordered[i].name);
      if (!lines.length) continue;
      let textW = 0;
      for (const ln of lines) textW = Math.max(textW, measureWith(ctx, font, ln));
      const boxW = textW + padH * 2;
      const boxH = lineH * lines.length + lineSpacing * (lines.length - 1) + padH;
      const r = {
        l: sh.cx - boxW / 2 - padGap, t: sh.cy - boxH / 2 - padGap,
        r: sh.cx + boxW / 2 + padGap, b: sh.cy + boxH / 2 + padGap,
      };
      if (!layerEnoughVisible(r, w, h) || layerCollides(placed, r)) { this.hiddenCount++; continue; }
      placed.push(r);
      this.labelCount++;
      labels.push({ lines, cx: sh.cx, cy: sh.cy, lineH, lineSpacing, boxW });
    }
    this._labels = labels;
    this._font = font;
    this._asc = asc;
  }

  draw(ctx) {
    if (!this._shapes) return;
    // 框体
    ctx.lineJoin = 'round';
    for (const sh of this._shapes) {
      if (!sh.ok) continue;
      ctx.beginPath();
      ctx.moveTo(sh.px[0], sh.py[0]);
      for (let i = 1; i < sh.px.length; i++) ctx.lineTo(sh.px[i], sh.py[i]);
      ctx.closePath();
      ctx.fillStyle = CFG.COLOR2.parcel_fill;
      ctx.fill();
      ctx.strokeStyle = CFG.COLOR2.parcel_line;
      ctx.lineWidth = CFG.PARCEL_BORDER_DP;
      ctx.stroke();
    }
    // 名称
    if (!this._labels) return;
    ctx.font = this._font;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'alphabetic';
    for (const lb of this._labels) {
      let top = lb.cy - (lb.lineH * lb.lines.length + lb.lineSpacing * (lb.lines.length - 1)) / 2;
      for (const ln of lb.lines) {
        paintOutlined(ctx, ln, lb.cx, top + this._asc, CFG.COLOR2.parcel_text,
          CFG.COLOR.label_outline, CFG.COLOR.text_shadow, 1.6);
        top += lb.lineH + lb.lineSpacing;
      }
    }
  }
}

/** 地块名称拆行：按第一个空格拆两行（labelLines 移植），无空格则单行 */
function parcelLabelLines(name) {
  return name.split(' ', 2).map(s => s.trim()).filter(Boolean);
}

/* ================================================================
 * FactoryLayer —— IdleFactoryOverlay.kt 移植
 * ================================================================
 * 两遍绘制：先全部方块标记（一个不漏），再逐个贴两行标签。
 * 标签 8 候选位顺序「下/上/右/左/四斜角」，判据用外扩 2dp 后的框；
 * 名称 12sp 加粗 / 面积 11sp 常规，珊瑚橙两档；折行只在原文空格/逗号处。
 */
class FactoryLayer {

  constructor(factories) {
    this.factories = factories;   // [{id,name,areaText,areaM2,lon,lat}]
    this.visible = false;         // 浮层「闲置厂房」行控制，默认关
    this.factoryCount = factories.length;
    this.visibleCount = 0;
    this.labelCount = 0;
    this.hiddenCount = 0;
    this._ordered = null;
  }

  layout(view, ctx) {
    const { w, h } = view;
    this.visibleCount = 0; this.labelCount = 0; this.hiddenCount = 0;
    this._markers = null; this._labels = null;
    if (!this.visible || !this.factories.length || w <= 0 || h <= 0) return;

    if (!this._ordered) {
      this._ordered = this.factories
        .map(f => Object.assign({}, f, {
          nameLines: factoryNameLines(f.name),
          areaLines: factoryAreaLines(f.areaText),
        }))
        .sort((a, b) => b.areaM2 - a.areaM2);   // 大面积先挑位
    }

    const nameFont = fontString(true, CFG.FACTORY_NAME_SIZE);
    const areaFont = fontString(false, CFG.FACTORY_AREA_SIZE);

    // ---- 第一遍：画全部标记（数据在 draw 落笔，这里只算锚点）----
    const half = CFG.FACTORY_MARKER_DP / 2;
    const markers = [];
    for (const f of this._ordered) {
      if (typeof f.lon !== 'number' || !isFinite(f.lon)) continue;
      const pt = view.project(f.lat, f.lon);
      if (pt.x < -CFG.FACTORY_CULL_MARGIN || pt.x > w + CFG.FACTORY_CULL_MARGIN ||
          pt.y < -CFG.FACTORY_CULL_MARGIN || pt.y > h + CFG.FACTORY_CULL_MARGIN) continue;
      markers.push({ f, x: pt.x, y: pt.y });
      this.visibleCount++;
    }
    if (!markers.length) return;
    this._markers = markers;

    // ---- 第二遍：逐个贴标签（8 候选位，「下」优先）----
    const padH = CFG.LABEL_PAD_H, padV = 2, lineSpacing = CFG.LINE_SPACING, padGap = 2;
    const reach = half + CFG.LABEL_GAP;
    const placed = [];
    const labels = [];

    for (const mk of markers) {
      const f = mk.f;
      const nameCount = f.nameLines.length;
      const lineCount = nameCount + f.areaLines.length;
      if (!lineCount) continue;

      // 逐行用自己的画笔量宽量高（名称/面积字号不等）
      let maxW = 0, totalH = 0;
      const lineFonts = [];
      for (let k = 0; k < lineCount; k++) {
        const font = k < nameCount ? nameFont : areaFont;
        lineFonts.push(font);
        maxW = Math.max(maxW, measureWith(ctx, font, k < nameCount ? f.nameLines[k] : f.areaLines[k - nameCount]));
        ctx.font = font;
        const m = ctx.measureText('测国');
        totalH += (m.fontBoundingBoxAscent ?? 12 * 0.88) + (m.fontBoundingBoxDescent ?? 12 * 0.26);
      }
      totalH += lineSpacing * (lineCount - 1);
      const boxW = maxW + padH * 2, boxH = totalH + padV * 2;

      let chosen = null;
      for (const [dx, dy] of CFG.FACTORY_CAND) {
        const cx = mk.x + dx * (reach + boxW / 2);
        const cy = mk.y + dy * (reach + boxH / 2);
        const r = { l: cx - boxW / 2 - padGap, t: cy - boxH / 2 - padGap,
                    r: cx + boxW / 2 + padGap, b: cy + boxH / 2 + padGap };
        if (!layerEnoughVisible(r, w, h) || layerCollides(placed, r)) continue;
        placed.push(r);
        chosen = { f, cx, cy, boxW, totalH, lineCount, nameCount, lineFonts, lineSpacing };
        break;
      }
      if (chosen) { this.labelCount++; labels.push(chosen); }
      else this.hiddenCount++;
    }
    this._labels = labels;
    this._nameFont = nameFont;
    this._areaFont = areaFont;
  }

  draw(ctx) {
    if (this._markers) {
      const half = CFG.FACTORY_MARKER_DP / 2;
      const corner = CFG.FACTORY_CORNER_DP;
      ctx.lineJoin = 'round';
      for (const mk of this._markers) {
        roundedRect(ctx, mk.x - half, mk.y - half, CFG.FACTORY_MARKER_DP, CFG.FACTORY_MARKER_DP, corner);
        ctx.fillStyle = CFG.COLOR2.factory_fill;
        ctx.fill();
        ctx.strokeStyle = CFG.COLOR2.factory_line;
        ctx.lineWidth = CFG.FACTORY_STROKE_DP;
        ctx.stroke();
      }
    }
    if (!this._labels) return;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'alphabetic';
    for (const lb of this._labels) {
      let top = lb.cy - lb.totalH / 2;
      for (let k = 0; k < lb.lineCount; k++) {
        const isName = k < lb.nameCount;
        ctx.font = lb.lineFonts[k];
        const m = ctx.measureText('测国');
        const asc = m.fontBoundingBoxAscent ?? 12 * 0.88;
        const text = isName ? lb.f.nameLines[k] : lb.f.areaLines[k - lb.nameCount];
        paintOutlined(ctx, text, lb.cx, top + asc,
          isName ? CFG.COLOR2.factory_text : CFG.COLOR2.factory_text_sub,
          CFG.COLOR.label_outline, CFG.COLOR.text_shadow, 1.6);
        const desc = m.fontBoundingBoxDescent ?? 12 * 0.26;
        top += asc + desc + lb.lineSpacing;
      }
    }
  }
}

function roundedRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

/** 名称折行：第一个空格处断（IdleFactory.nameLines 移植），无空格一行 */
function factoryNameLines(name) {
  const i = name.indexOf(' ');
  if (i <= 0) return [name];
  const a = name.slice(0, i).trim(), b = name.slice(i + 1).trim();
  return (a && b) ? [a, b] : [name];
}

/** 面积折行：只在原文全角逗号处断（IdleFactory.areaLines 移植） */
function factoryAreaLines(areaText) {
  if (!areaText.includes('，')) return [areaText];
  const parts = areaText.split('，').map(s => s.trim()).filter(Boolean);
  return parts.length ? parts : [areaText];
}
