package com.bianhequ.bhqmap.data

/**
 * 企业档案。
 *
 * 坐标一律 WGS-84（CGCS2000 在使用精度上一致），与天地图影像底图、手机 GPS 同系，
 * 全程不做任何坐标转换 —— 这是本项目的核心决策，不要在这一层引入 GCJ-02/BD-09 偏移。
 *
 * 字段命名与 Excel 表头解耦：Excel 里写"公司名称""主营业务"也能认，
 * 映射关系在 v0.3 的导入模块里做，本类只表达"数据是什么"。
 */
data class Enterprise(
    /** 稳定主键：优先统一社会信用代码；无则由企业名称全称派生 */
    val id: String,
    /** 企业名称（全称）；台账只填了简称时，这里放简称并置 [nameIsShort] */
    val name: String,
    /** 标签显示用的简称。为空时由 [labelText] 从全称推导 */
    val shortName: String? = null,
    /** 经度（WGS-84）。无坐标时为 NaN —— 见 [hasCoord] */
    val lon: Double,
    /** 纬度（WGS-84）。无坐标时为 NaN —— 见 [hasCoord] */
    val lat: Double,
    /** 主营业务 */
    val mainBusiness: String = "",
    /** 企业简介 */
    val intro: String = "",
    /** 是否规上企业 —— 档案页标注，决定点位的描边样式 */
    val aboveScale: Boolean = false,
    /** 是否重点企业 —— 全貌及放大到中倍率前只显示重点企业（简称） */
    val isKey: Boolean = false,
    /** 是否协作区项目 —— 任何倍率都显示；开关只管标签：关=简称，开=沙金全称 */
    val isCoop: Boolean = false,
    /**
     * 是否园区项目（v0.7.13，台账「是否园区项目」列）。
     *
     * 与 [isCoop] 同属"项目"大类，规则完全对齐（v0.7.15 起两边一个字都不差）：
     * 任何倍率都显示；面板里的独立开关只管标签样式 —— 关（默认）= 简称，
     * 开 = 亮橄榄全称，与协作区项目的沙金全称一眼可分。
     */
    val isPark: Boolean = false,
    /**
     * 是否行政/公共事业单位（v0.5）。与"企业"在图上用不同符号区分：
     * 圆点 → 黄色五角星，且标签字号比重点企业大 2sp。
     * 它不是企业档案的一种标签，而是"这一点不是经营主体"的声明，
     * 所以渲染层级最高（字号最大者先占位）。
     */
    val isGov: Boolean = false,
    /** 所属片区（非必填，仅用于展示分组） */
    val cluster: String? = null,
    /**
     * [name] 只是简称（台账「企业名称（全称）」列留空，只填了简称）。
     * v0.6 起为"台账有记录但信息不全"的条目服务：照样上图、照样能查，
     * 只在档案页提示名称待补全，不因为全称缺失就把企业整条丢掉。
     */
    val nameIsShort: Boolean = false
) {

    /**
     * 是否有可用坐标。台账里有一批企业只有名称、坐标列留空 ——
     * 不编造坐标，也不因此丢弃档案：坐标缺失的条目照常进入数据、
     * 可被搜索和查看，只是不在地图上落点（档案页坐标行显示"坐标待补充"）。
     */
    val hasCoord: Boolean
        get() = !lon.isNaN() && !lat.isNaN()

    /** 标签上显示的文字：优先简称，否则剥掉地名前缀与"有限公司"后缀 */
    val labelText: String
        get() = shortName?.takeIf { it.isNotBlank() }
            ?: name.removePrefix("绥芬河市")
                .removeSuffix("有限公司")
                .removeSuffix("有限责任公司")
                .ifBlank { name }

    /**
     * 基本合法性校验。
     *
     * 名称是唯一的硬要求 —— 没有名字的条目无法在界面上表达。
     * 坐标缺失（NaN）不再是丢弃理由（v0.6）：台账里确实有"有名字没坐标"的企业，
     * 把它们整条丢掉会让 App 的数据看起来比台账少一截。改为保留档案、不上图。
     * 但 (0,0) 与越界坐标仍要剔除 —— 那是录入错误，不是"缺坐标"。
     */
    fun isValid(): Boolean {
        if (name.isBlank()) return false
        if (!hasCoord) return true
        if (lon == 0.0 && lat == 0.0) return false
        return lat in -90.0..90.0 && lon in -180.0..180.0
    }

    /**
     * 标签避让优先级。数字越大越优先（v0.7 由散在比较器里的布尔链收敛成唯一阶梯）：
     *
     *     4 行政/事业单位  ← 不是"更重要"，而是字号最大（16sp），大块头先占位
     *     3 协作区项目 / 园区项目
     *     2 重点企业
     *     1 规上企业
     *     0 普通企业
     *
     * 这个数决定两件事，都必须走这一处：
     *   1. **占位顺序** —— 优先级高的先挑位置（上/下/右/左），逐个贪心；
     *   2. **叠压顺序** —— 允许重叠后（见 EnterpriseOverlay.OVERLAP_RATIO），
     *      优先级高的**后画**，压在上面，保证被压住的是普通企业而不是项目名。
     */
    val priority: Int
        get() = when {
            isGov -> 4
            isCoop || isPark -> 3
            isKey -> 2
            aboveScale -> 1
            else -> 0
        }

    /** 归档用的排序键，同时决定标签避让的优先级。同级按名称排，保证结果稳定可复现 */
    companion object {
        val PRIORITY_COMPARATOR: Comparator<Enterprise> =
            compareByDescending<Enterprise> { it.priority }
                .thenBy { it.name }
    }
}
