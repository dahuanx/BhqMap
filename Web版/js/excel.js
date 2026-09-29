/**
 * 台账 Excel 解析 —— .rebuild_seed_v7.py 的浏览器端移植（P3）。
 *
 * 输入：SheetJS Workbook（工作表名「企业台账」，数据从第 2 行起）
 * 输出：{ enterprises, stats }，stats={total,full,shortonly,withcoord,nocoord,dupGroups}
 *
 * 列号（1-based，与 rebuild 脚本一致）：
 *   C2=全称  D=简称  E=lon  F=lat
 *   G=规模以上  H=协作区  I=园区  J=重点  K=行政
 *   N13=简介
 *
 * 规则（逐条对照脚本）：
 *   - id = td-<台账行号>（行号稳定键，不复用）；
 *   - 仅简称条目：name=简称、nameIsShort=true、不写 shortName；
 *   - 坐标校验 130.0≤lon≤132.5 且 43.5≤lat≤45.5，否则不写坐标（保留档案不上图）；
 *   - 「是/Y/y/true/True/1」判真；
 *   - 同点位（1e-6）重复只提示不合并（v5 教训：各自保留独立条目）。
 *
 * 浏览器与 Node 双端共用：XLSX 实例由调用方注入（浏览器=window.XLSX，测试=require('xlsx')）。
 */

'use strict';

/* 判真（rebuild 脚本 yes()） */
function ledgerYes(v) {
  if (v === null || v === undefined) return false;
  const t = String(v).trim();
  return t === '是' || t === 'Y' || t === 'y' || t === 'true' || t === 'True' || t === '1';
}

/* 单元格取值转字符串（rebuild 脚本 s()） */
function ledgerStr(v) {
  if (v === null || v === undefined) return '';
  const t = String(v).trim();
  return t;
}

/** 数字列转 Number：openpyxl 给数值，SheetJS 对文本格数字也给字符串，统一 parseFloat */
function ledgerNum(v) {
  if (v === null || v === undefined || v === '') return NaN;
  return parseFloat(v);
}

/**
 * @param {object} XLSX SheetJS 实例
 * @param {object} wb Workbook（sheetRows 建议给足够行数）
 */
function parseLedgerWorkbook(XLSX, wb) {
  const ws = wb.Sheets['企业台账'];
  if (!ws) throw new Error('台账里没有「企业台账」工作表');

  const stats = { total: 0, full: 0, shortonly: 0, withcoord: 0, nocoord: 0, dupGroups: 0 };
  const ents = [];
  const coordDup = new Map();

  /* sheet_to_json 按行给数组（header:1，0-based → 行 r 从 1 起 = 台账第 2 行起） */
  const rows = XLSX.utils.sheet_to_json(ws, { header: 1, defval: null });
  for (let i = 1; i < rows.length; i++) {
    const row = rows[i];
    const r = i + 1;                       // 1-based 行号 = 台账行号
    const get = (col) => row[col - 1];     // 1-based 列号 → 数组下标
    const name = ledgerStr(get(2));
    const short = ledgerStr(get(3));
    if (!name && !short) continue;         // 预留空行跳过

    const display = name || short;
    const entry = {
      id: `td-${r}`,
      name: display,
      mainBusiness: '',
      intro: ledgerStr(get(13)),
      aboveScale: ledgerYes(get(6)),
    };
    if (name) {
      stats.full++;
      if (short && short !== name) entry.shortName = short;
    } else {
      stats.shortonly++;
      entry.nameIsShort = true;
    }

    let lon = ledgerNum(get(4)), lat = ledgerNum(get(5));
    if (isFinite(lon) && isFinite(lat)
        && lon >= 130.0 && lon <= 132.5 && lat >= 43.5 && lat <= 45.5) {
      entry.lon = Math.round(lon * 1e10) / 1e10;
      entry.lat = Math.round(lat * 1e10) / 1e10;
      stats.withcoord++;
      const key = `${lon.toFixed(6)},${lat.toFixed(6)}`;
      if (!coordDup.has(key)) coordDup.set(key, []);
      coordDup.get(key).push(`td-${r}:${display}`);
    } else {
      lon = lat = NaN;
      stats.nocoord++;
    }

    if (ledgerYes(get(7))) entry.isCoop = true;
    if (ledgerYes(get(8))) entry.isPark = true;
    if (ledgerYes(get(9))) entry.isKey = true;
    if (ledgerYes(get(10))) entry.isGov = true;

    ents.push(entry);
    stats.total++;
  }

  let dupGroups = 0;
  for (const [k, v] of coordDup) if (v.length > 1) dupGroups++;
  stats.dupGroups = dupGroups;

  return { enterprises: ents, stats };
}

/* ================= 闲置厂房工作表（v0.3.1 追加） =================
 * .build_idle_factories.py 的浏览器端移植（App 同一份数据的另一条生成路）。
 * 列（1-based）：B2=名称  C=闲置面积  D=经度  E=纬度，数据从第 2 行起。
 * 规则（逐条对照脚本）：
 *   - id = if-<台账行号>；
 *   - 名称空 → 整行跳过；坐标缺 → 跳过（App 同款，不保留档案）；
 *   - 面积原文不改写；areaM2 取**第一个**「N [万]平方米」数字（恒是占地/闲置那个，
 *     建筑面积排后面），只用于排序（大厂房先得名称），不展示。
 */

const FACTORY_AREA_RE = /([0-9]+(?:\.[0-9]+)?)\s*(万)?\s*平方米/;

function parseFactoryAreaM2(text) {
  const m = FACTORY_AREA_RE.exec(text || '');
  if (!m) return 0;
  const v = parseFloat(m[1]);
  return m[2] ? v * 10000 : v;
}

/**
 * @param {object} XLSX SheetJS 实例
 * @param {object} wb Workbook
 * @returns {?{factories: Array, stats: object}} 台账没有「闲置厂房」表时返回 null（企业导入照常）
 */
function parseFactorySheet(XLSX, wb) {
  const ws = wb.Sheets['闲置厂房'];
  if (!ws) return null;

  const stats = { total: 0, skippedNoCoord: 0, noArea: 0 };
  const factories = [];

  const rows = XLSX.utils.sheet_to_json(ws, { header: 1, defval: null });
  for (let i = 1; i < rows.length; i++) {
    const row = rows[i];
    const r = i + 1;                       // 1-based 行号 = 台账行号
    const get = (col) => row[col - 1];
    const name = ledgerStr(get(2));
    if (!name) continue;                   // 空行跳过

    const lon = ledgerNum(get(4)), lat = ledgerNum(get(5));
    if (!isFinite(lon) || !isFinite(lat)) {
      stats.skippedNoCoord++;
      continue;
    }

    const areaText = ledgerStr(get(3));
    const areaM2 = parseFactoryAreaM2(areaText);
    if (areaM2 <= 0) stats.noArea++;

    factories.push({
      id: `if-${r}`,
      name,
      areaText,
      areaM2,
      lon: Math.round(lon * 1e10) / 1e10,
      lat: Math.round(lat * 1e10) / 1e10,
    });
    stats.total++;
  }
  return { factories, stats };
}
