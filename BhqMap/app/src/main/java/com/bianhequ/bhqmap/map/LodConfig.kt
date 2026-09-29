package com.bianhequ.bhqmap.map

/**
 * 标签分级显隐阈值（v0.3 规则，洲哥 2026-09-22 定稿）。
 *
 * 单位统一用「米/像素」而不是 zoom 级别 —— 理由见 [GroundResolution]。
 * 这样无论屏幕密度、无论是否开启瓦片 DPI 缩放，同一套阈值在任何设备上
 * 都表现为"同样的疏密程度"，不需要逐台手机标定。
 *
 * 两档规则：
 *   远档（m/px > [MPP_KEY]，含全貌 3.03 m/px）→ 只显示重点企业，标签用简称；
 *   近档（m/px ≤ [MPP_KEY]，按旧实测口径约 z14.2 以上）→ 重点企业只留圆点不出名，
 *                                                        显示其他企业的名称标签。
 * 协作区项目不受分档限制：按钮开启时任何级别都常显（全称、金色）。
 *
 * 阈值来历：全貌 3.03 m/px；约 2.0 m/px 时 62 家企业的标签
 * 在碰撞避让后大部分能放下。真实数量继续增长时按状态栏读数复核。
 *
 * **单位口径提醒（v0.7.23 修正）**：本类阈值一律用 m/px，**与 zoom 无关** ——
 * 读这段时不要按 zoom 心算。若确需换算，取决于"全貌到底是哪个 zoom"这个悬案：
 *   按 v0.3 旧实测（全貌 z13.6 ↔ 3.03 m/px）→ MPP_KEY = 2.0 落在 **z≈14.2**；
 *   按几何推算（全貌 z15.2）                 → 则落在 **z≈15.8**。
 * 两种口径都**不是**早期注释写的 z16.5（那是笔误，v0.7.23 已删）。真机以长按指北针读数为准。
 *
 * 与 zoom 常量（[EnterpriseOverlay.FORCE_ALL_ZOOM]、`KEY_GOV_ZOOM_MIN/MAX`）不同源：
 * 那几个是"读起来方便"的 zoom 门槛，本类是"到哪里都一样"的密疏门槛，两者互不换算。
 */
object LodConfig {

    /** 远档：只显示重点企业（简称） */
    const val LOD_KEY = 0

    /** 近档：显示其他企业名称（重点企业只留点） */
    const val LOD_ALL = 1

    /** 大于此分辨率：重点企业模式 */
    const val MPP_KEY = 2.0

    fun levelFor(metersPerPixel: Double): Int = when {
        // 尺寸未知时按远档处理，宁可少画也不错画
        metersPerPixel.isNaN() -> LOD_KEY
        metersPerPixel > MPP_KEY -> LOD_KEY
        else -> LOD_ALL
    }

    fun labelOf(level: Int): String = when (level) {
        LOD_KEY -> "重点"
        else -> "全部"
    }
}
