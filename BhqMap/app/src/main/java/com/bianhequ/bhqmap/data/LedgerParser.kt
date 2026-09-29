package com.bianhequ.bhqmap.data

/**
 * 台账语义解析 —— `Web版/js/excel.js` 的逐条移植（v0.8.0）。
 *
 * 输入：[LedgerXlsx.parse] 的结果（工作表名 → 行）。
 * 输出：企业列表 / 闲置厂房列表 + 统计（给导入确认框展示）。
 *
 * 列位（1-based，与 excel.js 代码一致；excel.js 头注释「C2=全称」是笔误）：
 *   企业台账表：B=全称  C=简称  D=经度  E=纬度
 *               F=规上  G=协作  H=园区  I=重点  J=行政  M=简介
 *   闲置厂房表：B=名称  C=闲置面积  D=经度  E=纬度
 *
 * 规则（逐条对照 excel.js / .rebuild_seed_v7.py）：
 *   · id = td-<台账行号> / if-<台账行号>（行号稳定键）；
 *   · 企业：全称简称都空 → 整行跳过；仅简称条目 name=简称、nameIsShort=true；
 *   · 坐标校验 130.0≤lon≤132.5 且 43.5≤lat≤45.5，否则保留档案不落点（厂房则直接跳过）；
 *   · 判真集合「是/Y/y/true/True/1」；
 *   · 同点位（1e-6）重复只提示不合并（v5 教训：各自保留独立条目）。
 */
object LedgerParser {

    /** 台账里没有「企业台账」工作表 —— 由调用方转成用户可读的错误提示 */
    class MissingLedgerSheetException(message: String) : Exception(message)

    data class EnterpriseStats(
        val total: Int,
        val full: Int,
        val shortonly: Int,
        val withcoord: Int,
        val nocoord: Int,
        val dupGroups: Int
    )

    data class EnterpriseResult(val enterprises: List<Enterprise>, val stats: EnterpriseStats)

    data class FactoryStats(val total: Int, val skippedNoCoord: Int, val noArea: Int)

    data class FactoryResult(val factories: List<IdleFactory>, val stats: FactoryStats)

    /** 台账工作表名（两版一致，勿改） */
    const val SHEET_ENTERPRISE = "企业台账"
    const val SHEET_FACTORY = "闲置厂房"

    /** 坐标有效域（与 Web 版一致：绥芬河周边，超出即录入错误） */
    private const val LON_MIN = 130.0
    private const val LON_MAX = 132.5
    private const val LAT_MIN = 43.5
    private const val LAT_MAX = 45.5

    fun parseEnterprises(sheets: Map<String, LedgerXlsx.Sheet>): EnterpriseResult {
        val sheet = sheets[SHEET_ENTERPRISE]
            ?: throw MissingLedgerSheetException("台账里没有「$SHEET_ENTERPRISE」工作表")

        var full = 0
        var shortonly = 0
        var withcoord = 0
        var nocoord = 0
        val coordDup = HashMap<String, MutableList<String>>()
        val ents = ArrayList<Enterprise>(sheet.rows.size)

        for (row in sheet.rows) {
            val r = row.number
            val name = str(cell(row, 2))
            val short = str(cell(row, 3))
            if (name.isEmpty() && short.isEmpty()) continue   // 预留空行跳过

            // excel.js：仅全称条目才写 shortName；全称空 → name=简称、nameIsShort=true
            val shortName: String? =
                if (name.isNotEmpty() && short.isNotEmpty() && short != name) short else null
            val nameIsShort = name.isEmpty()

            var lon = num(cell(row, 4))
            var lat = num(cell(row, 5))
            var dupKey: String? = null
            if (!lon.isNaN() && !lat.isNaN() &&
                lon >= LON_MIN && lon <= LON_MAX && lat >= LAT_MIN && lat <= LAT_MAX
            ) {
                // 与 excel.js 的 Math.round(v*1e10)/1e10 对齐：收敛浮点尾差
                lon = Math.round(lon * 1e10) / 1e10
                lat = Math.round(lat * 1e10) / 1e10
                dupKey = "%.6f,%.6f".format(lon, lat)
            } else {
                lon = Double.NaN
                lat = Double.NaN
            }

            val e = Enterprise(
                id = "td-$r",
                name = name.ifEmpty { short },
                shortName = shortName,
                lon = lon,
                lat = lat,
                mainBusiness = "",
                intro = str(cell(row, 13)),
                aboveScale = yes(cell(row, 6)),
                isCoop = yes(cell(row, 7)),
                isPark = yes(cell(row, 8)),
                isKey = yes(cell(row, 9)),
                isGov = yes(cell(row, 10)),
                nameIsShort = nameIsShort
            )
            if (!e.isValid()) continue

            if (name.isNotEmpty()) full++ else shortonly++
            if (dupKey != null) {
                withcoord++
                coordDup.getOrPut(dupKey) { mutableListOf() }.add("td-$r:${e.name}")
            } else {
                nocoord++
            }
            ents.add(e)
        }

        return EnterpriseResult(
            enterprises = ents,
            stats = EnterpriseStats(
                total = ents.size,
                full = full,
                shortonly = shortonly,
                withcoord = withcoord,
                nocoord = nocoord,
                dupGroups = coordDup.values.count { it.size > 1 }
            )
        )
    }

    /** 台账没有「闲置厂房」表时返回 null（企业导入照常 —— 与 Web 版 v0.3.1 同语义） */
    fun parseFactories(sheets: Map<String, LedgerXlsx.Sheet>): FactoryResult? {
        val sheet = sheets[SHEET_FACTORY] ?: return null

        var skippedNoCoord = 0
        var noArea = 0
        val out = ArrayList<IdleFactory>(sheet.rows.size)

        for (row in sheet.rows) {
            val r = row.number
            val name = str(cell(row, 2))
            if (name.isEmpty()) continue   // 空行跳过

            val lon = num(cell(row, 4))
            val lat = num(cell(row, 5))
            if (lon.isNaN() || lat.isNaN()) {
                skippedNoCoord++
                continue   // 厂房层没有"档案"概念：没坐标就不上图（App/Web 同款）
            }

            val areaText = str(cell(row, 3))
            val areaM2 = parseFactoryAreaM2(areaText)
            if (areaM2 <= 0.0) noArea++

            out.add(
                IdleFactory(
                    id = "if-$r",
                    name = name,
                    areaText = areaText,
                    areaM2 = areaM2,
                    lon = lon,
                    lat = lat
                )
            )
        }
        return FactoryResult(out, FactoryStats(out.size, skippedNoCoord, noArea))
    }

    // ------------------------------------------------------------------
    // excel.js 的三个取值函数
    // ------------------------------------------------------------------

    private fun cell(row: LedgerXlsx.Row, col: Int): String? = row.cells[col]

    /** ledgerStr：去空白 */
    private fun str(v: String?): String = v?.trim() ?: ""

    /** ledgerNum：转数字，失败/空 → NaN（与 parseFloat(isFinite) 口径一致） */
    private fun num(v: String?): Double {
        val t = str(v)
        if (t.isEmpty()) return Double.NaN
        return t.toDoubleOrNull() ?: Double.NaN
    }

    /** ledgerYes（rebuild 脚本 yes()）：「是/Y/y/true/True/1」 */
    private fun yes(v: String?): Boolean = str(v) in YES_SET

    private val YES_SET = setOf("是", "Y", "y", "true", "True", "1")

    /** 面积原文 → m²（取第一个「N [万]平方米」；恒是占地/闲置那个，建筑面积排后面） */
    private fun parseFactoryAreaM2(text: String): Double {
        val m = FACTORY_AREA_RE.find(text) ?: return 0.0
        val v = m.groupValues[1].toDoubleOrNull() ?: return 0.0
        return if (m.groupValues[2].isNotEmpty()) v * 10000.0 else v
    }

    private val FACTORY_AREA_RE = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(万)?\s*平方米""")
}
