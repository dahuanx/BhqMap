package com.bianhequ.bhqmap.data

import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * xlsx 台账解析器 —— `Web版/js/excel.js` 的机内对应物（v0.8.0，洲哥要求"开发 app 版的导入功能"）。
 *
 * **零依赖**：xlsx 本质是 zip + 一组 XML（OOXML），用 `ZipInputStream` + `XmlPullParser`
 * 手写解析即可覆盖台账这种受控输入，不必引入 Apache POI（poi-ooxml 连同传递依赖
 * 要给 APK 膨胀 6~8 MB，而台账只用到了它的千分之一）。项目里 [RoadStore] /
 * [ParcelStore] 用 XmlPullParser、[EnterpriseJson] 用 org.json，同一条路线。
 *
 * 只解析 xlsx（OOXML）。老式二进制 .xls（BIFF）不在这条路上 —— 真机导入时
 * 会给出"请另存为 .xlsx"的明确提示，不做静默失败。
 *
 * 覆盖的单元格类型（台账实际出现的只有前两种，其余按标准兜底）：
 *   · `t="s"`         共享字符串（索引到 sharedStrings 池）
 *   · 无 t（数字）     `<v>131.052…</v>` 直取
 *   · `t="str"`       公式缓存字符串
 *   · `t="inlineStr"` `<is><t>…</t></is>` 内联文本
 *   · `t="b"`         布尔（"1"→"true"、"0"→"false"，与 SheetJS 的 String(true) 对齐）
 *
 * 列位与工作表名以 `Web版/js/excel.js` 的**代码**为准（其头注释「C2=全称」是笔误，
 * 实际台账 B=全称 C=简称 D=经度 E=纬度 F~J=五个是否列 M=简介 —— 已用 openpyxl
 * 探针核对过真实台账，并以 `.sim_import_mirror.py` 镜像验证出期望统计：
 * 164 条 / 154 可上图 / 10 无坐标 / 厂房 25 处）。
 */
object LedgerXlsx {

    private const val TAG = "BhqMap"

    /** 台账里的一行：[number] 是 1-based 工作表行号（id=td-<行号> 的依据），[cells] 列号(1-based)→文本 */
    class Row(val number: Int, val cells: Map<Int, String>)

    /** 一张工作表：按行号升序的行列表 */
    class Sheet(val rows: List<Row>)

    /** xlsx 结构性错误（不是 zip、缺必要部件等），message 直接可给用户看 */
    class LedgerFormatException(message: String) : Exception(message)

    /**
     * 解析 xlsx 流，返回 工作表名 → 表。
     * 流由调用方关闭；本方法只读一次，内存里留存的是解析结果。
     */
    fun parse(input: InputStream): Map<String, Sheet> {
        val zip = ZipInputStream(input)

        var workbookXml: String? = null
        var relsXml: String? = null
        var sharedStrings: List<String> = emptyList()
        val worksheetXmls = HashMap<String, String>()   // zip 内路径 → xml 文本

        // 逐 entry 分拣。xlsx 要求 [Content_Types].xml 第一个，但顺序不保证，一律遍历全包。
        while (true) {
            val entry = zip.nextEntry ?: break
            val path = entry.name
            val text = zip.readBytes().toString(Charsets.UTF_8)
            when (path) {
                "xl/workbook.xml" -> workbookXml = text
                "xl/_rels/workbook.xml.rels" -> relsXml = text
                "xl/sharedStrings.xml" -> sharedStrings = parseSharedStrings(text)
                else -> if (path.startsWith("xl/worksheets/") && path.endsWith(".xml")) {
                    worksheetXmls[path] = text
                }
            }
        }

        if (workbookXml == null) {
            throw LedgerFormatException("这不是有效的 .xlsx 文件（缺少工作簿描述）")
        }
        if (relsXml == null) {
            throw LedgerFormatException("这不是有效的 .xlsx 文件（缺少工作簿关系表）")
        }

        // rId → 目标路径。Target 可能是相对（worksheets/sheet1.xml）或绝对（/xl/worksheets/…）
        val relTargets = HashMap<String, String>()
        forEachTag(relsXml, "Relationship") { attrs ->
            val id = attrs.firstOrNull { it.first == "Id" || it.first.endsWith(":Id") }?.second
            val target = attrs.firstOrNull { it.first == "Target" || it.first.endsWith(":Target") }?.second
            if (id != null && target != null) {
                relTargets[id] = when {
                    target.startsWith("/") -> target.removePrefix("/")
                    target.startsWith("xl/") -> target
                    else -> "xl/$target"
                }
            }
        }

        // sheet 名 → 目标路径。r:id 在不处理命名空间的模式下就是原始前缀名（"r:id"）
        val sheetOrder = LinkedHashMap<String, String>()
        forEachTag(workbookXml, "sheet") { attrs ->
            val name = attrs.firstOrNull { it.first == "name" || it.first.endsWith(":name") }?.second
            val rid = attrs.firstOrNull {
                it.first == "id" || it.first.endsWith(":id")
            }?.second
            if (name != null && rid != null && relTargets.containsKey(rid)) {
                sheetOrder[name] = relTargets[rid]!!
            }
        }
        if (sheetOrder.isEmpty()) {
            throw LedgerFormatException("台账里没有可用的工作表")
        }

        val out = LinkedHashMap<String, Sheet>(sheetOrder.size)
        for ((name, path) in sheetOrder) {
            val xml = worksheetXmls[path]
            if (xml == null) {
                Log.w(TAG, "台账 xlsx：工作表「$name」的 $path 不在包内，跳过")
                continue
            }
            out[name] = parseWorksheet(xml, sharedStrings)
        }
        return out
    }

    // ------------------------------------------------------------------
    // 工作表主体：<row r="2"><c r="C2" t="s"><v>5</v></c>…
    // ------------------------------------------------------------------

    private fun parseWorksheet(xml: String, sharedStrings: List<String>): Sheet {
        val p = newParser(xml)
        val rows = ArrayList<Row>(64)
        var rowNo = 0
        var cells = sortedMapOf<Int, String>()
        var curCol = -1
        var curType = ""
        var valueText: StringBuilder? = null
        var inlineCollecting = false
        var inlineText: StringBuilder? = null

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        rowNo = attr(p, "r")?.toIntOrNull() ?: (rowNo + 1)
                        cells = sortedMapOf()
                    }
                    "c" -> {
                        curCol = colFromRef(attr(p, "r"))
                        curType = attr(p, "t") ?: ""
                        valueText = null
                        inlineCollecting = false
                        inlineText = null
                    }
                    "v" -> valueText = StringBuilder()
                    "is" -> {
                        inlineCollecting = true
                        inlineText = StringBuilder()
                    }
                    "t" -> if (inlineCollecting) { /* 文本由 TEXT 事件收集 */ }
                }
                XmlPullParser.TEXT -> {
                    if (valueText != null) valueText.append(p.text)
                    if (inlineCollecting && inlineText != null) inlineText.append(p.text)
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> {
                        val raw = valueText?.toString() ?: ""
                        if (curCol > 0 && raw.isNotEmpty()) {
                            cells[curCol] = resolveCell(raw, curType, sharedStrings)
                        }
                        valueText = null
                    }
                    "is" -> {
                        val raw = inlineText?.toString() ?: ""
                        if (curCol > 0 && raw.isNotEmpty()) {
                            cells[curCol] = raw.trim()
                        }
                        inlineCollecting = false
                    }
                    "c" -> {
                        // 公式格可能只有 <f> 没有 <v>：t="str" 的 v 已在 "v" 分支处理；
                        // 空格不产生任何单元格。
                        curCol = -1
                        curType = ""
                    }
                    "row" -> {
                        if (cells.isNotEmpty()) rows.add(Row(rowNo, cells))
                        cells = sortedMapOf()
                    }
                }
            }
            ev = p.next()
        }
        return Sheet(rows)
    }

    /** 单元格取值落位：按类型把 `<v>` 原文翻译成台账文本 */
    private fun resolveCell(raw: String, type: String, sharedStrings: List<String>): String = when (type) {
        "s" -> sharedStrings.getOrNull(raw.toIntOrNull() ?: -1) ?: ""
        "str", "inlineStr" -> raw.trim()
        "b" -> if (raw.trim() == "1") "true" else "false"
        else -> raw.trim()   // 数字（无 t / t="n"）：原文就是十进制定点，直接给 parseFloat
    }

    // ------------------------------------------------------------------
    // 共享字符串池：<si><t>…</t></si> 与富文本 <si><r><t>…</t><r><t>…</t></si>
    // ------------------------------------------------------------------

    private fun parseSharedStrings(xml: String): List<String> {
        val p = newParser(xml)
        val pool = ArrayList<String>(128)
        val sb = StringBuilder()
        var inSi = false
        var depth = 0

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (p.name) {
                        "si" -> { inSi = true; sb.setLength(0) }
                        "t" -> if (inSi) { /* TEXT 事件收集 */ }
                    }
                }
                XmlPullParser.TEXT -> if (inSi) sb.append(p.text)
                XmlPullParser.END_TAG -> {
                    if (p.name == "si") {
                        pool.add(sb.toString())
                        inSi = false
                    }
                    depth--
                }
            }
            ev = p.next()
        }
        return pool
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private inline fun forEachTag(xml: String, tagName: String, block: (List<Pair<String, String>>) -> Unit) {
        val p = newParser(xml)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == tagName) {
                val attrs = ArrayList<Pair<String, String>>(p.attributeCount)
                for (i in 0 until p.attributeCount) {
                    attrs.add(p.getAttributeName(i) to p.getAttributeValue(i))
                }
                block(attrs)
            }
            ev = p.next()
        }
    }

    private fun newParser(xml: String): XmlPullParser =
        Xml.newPullParser().apply {
            setInput(xml.reader())
        }

    /** 不处理命名空间时属性名保留原始前缀：既匹配 "r"，也匹配带前缀的形态 */
    private fun attr(p: XmlPullParser, localName: String): String? {
        for (i in 0 until p.attributeCount) {
            val n = p.getAttributeName(i)
            if (n == localName || n.endsWith(":$localName")) return p.getAttributeValue(i)
        }
        return null
    }

    /** "C2" → 3；"AB12" → 28；无字母前缀返回 -1 */
    private fun colFromRef(ref: String?): Int {
        if (ref.isNullOrEmpty()) return -1
        var col = 0
        for (ch in ref) {
            when (ch) {
                in 'A'..'Z' -> col = col * 26 + (ch - 'A' + 1)
                in 'a'..'z' -> col = col * 26 + (ch - 'a' + 1)
                else -> break
            }
        }
        return if (col == 0) -1 else col
    }
}
