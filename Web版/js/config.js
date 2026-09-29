/**
 * 边合区产业地图 · Web 版配置
 *
 * 所有常量从安卓版 Kotlin 源码逐项移植，来源标注在每一段注释里：
 *   - LodConfig.kt          → MPP_KEY / LOD 分档
 *   - EnterpriseOverlay.kt  → 避让、字号、候选位、宽高比等
 *   - Enterprise.kt         → priority / labelText 派生规则
 *   - colors.xml            → 全部颜色（含 alpha 前缀原样保留）
 *
 * 单位约定：这里所有 px 均为 CSS 像素（对应安卓 dp/sp 在 density=1 下的值）。
 * 浏览器高分屏下由 canvas 的 dpr 缩放保证清晰度，几何计算全程 CSS px。
 */

const CFG = {
  /* 天地图浏览器端 tk（洲哥 2026-09-24 申请，权限类型=浏览器端） */
  TK: 'd6cd7c5a9e44537e6c04c497c066da7c',

  /* ---------- LodConfig.kt ---------- */
  MPP_KEY: 2.0,        // m/px 大于此值 = 重点档（远档），否则全部档
  LOD_KEY: 0,
  LOD_ALL: 1,

  /* ---------- EnterpriseOverlay.kt companion ----------
   * 口径说明（重要）：App 的 zoom 常量（FORCE_ALL_ZOOM / KEY_GOV_ZOOM_*）建立在
   * "osmdroid 1 map px = 1 物理像素"之上；Leaflet 是 "1 map px = 1 CSS px(=dp)"。
   * 为落实 LodConfig 的哲学「同一套阈值在任何设备上都表现为同样的疏密程度」，
   * Web 版把这三个 zoom 常量按全貌纬度 44.3469° 换算成**物理像素 m/px**，
   * 引擎统一用 mppPhys（= mppCss / dpr）比较 —— 桌面(dpr=1)与安卓 App 数值一致，
   * 手机上与 App 行为也对齐。换算：m/px(物理) = 156543.03392×cos(44.3469°)/2^zoom
   *   zoom 17.5 → 0.6042；zoom 15.5 → 2.4171；zoom 14.5 → 4.8336
   */
  MPP_KEY: 2.0,            // > 此值 = 重点档（远档），LodConfig.MPP_KEY 原值
  MPP_FORCE_ALL: 0.6042,   // < 此值 = 全体豁免避让（← FORCE_ALL_ZOOM 17.5）
  MPP_KEYGOV_MIN: 2.4171,  // 物理m/px ∈ (MIN, MAX) 开区间 = 只出重点/行政名称
  MPP_KEYGOV_MAX: 4.8336,  //   （← KEY_GOV_ZOOM_MIN/MAX 14.5/15.5，v0.7.24 筛选语义）
  OVERLAP_RATIO: 0.35,       // 冲突阈：重叠面积 > 较小标签面积×此值 才算冲突（v0.7.23）
  EXEMPT_BLOCK_RATIO: 0.08,  // 普通标签 vs 全显标签的收紧容忍度（v0.7.11）
  VISIBLE_MIN_RATIO: 0.60,   // 标签至少露出 60% 面积即可（v0.7）
  SLOT_COUNT: 8,             // 上/下/右/左 + 四斜角
  LABEL_PAD_H: 5,            // 标签水平内边距 dp
  LABEL_GAP: 4,              // 标签与圆点间距 dp
  LINE_SPACING: 1.5,         // 多行行距 dp
  TOUCH_RADIUS: 20,          // 点位命中放宽半径 dp
  SCREEN_PAD: 2,             // 屏幕内缩边距 dp
  FADE_MS: 150,              // 标签淡入淡出时长 ms（EnterpriseOverlay FADE_MS=150f，v0.7.3）
  STAR_INNER_RATIO: 0.382,   // 五角星内接/外接圆比 sin18°/sin54°

  /* ---------- 圆点半径 dp（dotStyle） ---------- */
  DOT_R: { gov: 8.6, coop: 6.8, key: 6.2, above: 5.4, normal: 4.6 },

  /* ---------- colors.xml（hex 含 alpha 前缀） ---------- */
  COLOR: {
    label_text_key:    '#F2F0E6',  // 卡其白
    label_text_normal: '#F2F0E6',
    label_text_coop:   '#D9B95C',  // 沙金
    label_text_gov:    '#FFD400',  // 明黄
    label_text_park:   '#9DB06A',  // 亮橄榄
    label_text_above:  '#5FD0E8',  // 湖蓝
    label_outline:     '#6E6E6E',
    dot_coop:   'rgba(217,185,92,0.70)',   // #B3D9B95C
    dot_key:    'rgba(157,176,106,0.70)',  // #B39DB06A
    dot_above:  'rgba(242,240,230,0.70)',  // #B3F2F0E6
    dot_normal: 'rgba(85,107,47,0.70)',    // #B3556B2F
    dot_gov:    'rgba(255,212,0,0.85)',    // #D9FFD400
    dot_stroke_dark:  'rgba(42,51,32,0.55)',  // #8C2A3320
    dot_stroke_light: 'rgba(242,240,230,0.70)',
    text_shadow: 'rgba(0,0,0,0.94)',       // TextShadow: 94% 不透明
  },

  /* ---------- 字号档（sp；sp → CSS px 直映） ---------- */
  FONT: { gov: 16, key: 14, normal: 12 },

  /* ---------- 默认视野（BianhequArea.kt 占位范围） ---------- */
  BOUNDS: [[44.3271, 131.0299], [44.3753, 131.0672]],
  CENTER: [44.3468525979, 131.0496647326],

  /* ---------- 瓦片 ---------- */
  TILE_MIN_ZOOM: 1,
  TILE_MAX_ZOOM: 18,   // 与安卓版 TiandituTileSource 一致
  SUBDOMAINS: [0,1,2,3,4,5,6,7],

  /* ================= P2 附加图层（v0.7.16 / v0.7.27 / v0.7.28 移植） ================= */

  /* ---------- RoadOverlay.kt ----------
   * **仅 Web 版放宽（洲哥 2026-09-24 指示）：比例尺读数 300m 时路名应可见。**
   * Leaflet 比例尺（maxWidth 100px）读 300m 对应 m/px ∈ [3.0, 5.0)，
   * 取物理 5.0 m/px（≈ App 的 z14.5）门槛：读 500m 隐藏、读 300m 及更近全显。
   * App 侧不跟随，仍为 RoadOverlay MIN_ZOOM=17（0.856 m/px）。
   * 历史：App MIN_ZOOM=17 换算曾写死 0.8541，与 mpp@z17.0(=0.85409) 余量仅
   * 0.001% 且随平移纬度漂移，v0.2.1 改为按最南纬 44.3271 计算 ×1.002。 */
  MPP_ROADS: 5.0,
  ROAD_TEXT_SIZE: 11,       // 11sp，离线仿真定档（v0.7.16）
  ROAD_STROKE_DP: 2.4,      // 白边线宽
  ROAD_AVOID_PAD_DP: 3,     // 路名间避让余量
  ROAD_MAX_RATIO: 0.86,     // 标签宽/路线长上限
  ROAD_SCREEN_MARGIN: 24,   // 标签中心距屏边最小距离 px
  ROAD_CULL_MARGIN: 220,    // 视野剔除余量 px
  ROAD_SLOTS: [0.5, 0.42, 0.58, 0.35, 0.65, 0.28, 0.72, 0.2, 0.8],  // SLOT_FRACTIONS

  /* ---------- ParcelOverlay.kt / IdleFactoryOverlay.kt ---------- */
  PARCEL_BORDER_DP: 2.0,
  PARCEL_TEXT_SIZE: 12,
  PARCEL_CULL_MARGIN: 64,
  FACTORY_NAME_SIZE: 12,
  FACTORY_AREA_SIZE: 11,
  FACTORY_MARKER_DP: 12,     // 方块边长
  FACTORY_CORNER_DP: 2.5,    // 圆角
  FACTORY_STROKE_DP: 1.4,
  FACTORY_CULL_MARGIN: 64,
  /* 8 候选位顺序：下/上/右/左/四斜角（CAND_DX/DY，屏幕 y 向下） */
  FACTORY_CAND: [[0,1],[0,-1],[1,0],[-1,0],[1,1],[-1,1],[1,-1],[-1,-1]],

  /* ---------- colors.xml P2 追加 ---------- */
  /* （追加进上面 COLOR 不便，单独一组，语义相同） */
  COLOR2: {
    parcel_line: '#B388FF',                       // 框体轮廓：紫罗兰实线
    parcel_fill: 'rgba(179,136,255,0.14)',        // #24B388FF 14% 填充
    parcel_text: '#C9A8FF',                       // 地块名称：比框体线亮一档
    factory_line: '#FF8A65',                      // 标记描边：珊瑚橙
    factory_fill: 'rgba(255,138,101,0.70)',       // #B3FF8A65 70% 填充
    factory_text: '#FFA98A',                      // 名称行
    factory_text_sub: '#FFD2C0',                  // 面积行：浅一档
  },
};

/** Web 版本号（独立于安卓版演进，从 v0.1.0 起） */
const WEB_VERSION = 'v0.3.1';

/** 版权信息页文案（dialog_about.xml 定稿原文，v0.7.30） */
const ABOUT_TEXT = {
  owner: '绥芬河市边境经济合作区管理局',
  team: '2025组团式援边绥芬河工作队',
  date: '2026 年 9 月',
  copyright: '本软件著作权归绥芬河市边境经济合作区管理局所有，供园区管理工作内部使用；未经书面许可，不得复制、传播或用于商业用途。',
  baseMap: '底图数据来源：国家地理信息公共服务平台「天地图」（审图号 GS(2025)1508号）',
  dataNote: '企业信息来源于管理局企业台账，仅供工作参考，不作为行政或权属依据。',
  footer: '© 2026　绥芬河市边境经济合作区管理局',
};
