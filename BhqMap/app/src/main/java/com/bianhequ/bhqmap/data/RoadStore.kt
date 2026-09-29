package com.bianhequ.bhqmap.data

import android.content.Context
import android.util.Log
import android.util.Xml
import org.osmdroid.util.GeoPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * 园区道路数据源（v0.7.16）：解析 `assets/roads.kml`。
 *
 * 只认两个标签：`<Placemark>/<name>` 与 `<Placemark>/<coordinates>`。
 * Style / ExtendedData / altitudeMode 全部跳过 —— 本 App 不画线条，样式无意义。
 *
 * 为什么用 [XmlPullParser]（Android 内置）而不是引 jsoup / dom4j：
 *   单文件、单次、结构固定，pull 解析器零依赖、零反射、内存占用与文件同阶，
 *   正合本工程"能不引就不引"的一贯取舍（见 [EnterpriseStore] 用内置 `org.json`）。
 *
 * 与 [EnterpriseStore] 的差别：道路是**只读的包内资产**，不做 filesDir 覆盖、
 * 不做版本号播种 —— 换路网 = 替换 `assets/roads.kml` 重新打包。
 * 若日后要支持"运行时导入 KML"，在此处加一层 filesDir 优先即可，调用方不变。
 */
class RoadStore(private val context: Context) {

    companion object {
        /** 路网 KML 在 assets 下的文件名 */
        const val ASSET_NAME = "roads.kml"

        /** 与 [com.bianhequ.bhqmap.MainActivity] 同一个 Logcat 标签 */
        private const val TAG = "BhqMap"
    }

    /**
     * 读取并解析路网。任何异常（文件缺失、XML 损坏）都退化为空列表 ——
     * 路网是辅助注记，不该因为它把地图拖崩。
     */
    fun load(): List<Road> = try {
        context.assets.open(ASSET_NAME).use { parse(it) }
    } catch (t: Throwable) {
        Log.w(TAG, "道路 KML 读取失败：$t")
        emptyList()
    }

    private fun parse(input: InputStream): List<Road> {
        val parser = Xml.newPullParser()
        // KML 有无命名空间前缀都吃得下：下面统一按"最后一个冒号之后"取标签名
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, "UTF-8")

        val roads = ArrayList<Road>(24)
        // TEXT 事件可能被分片投递，逐段累积
        val buf = StringBuilder()
        var name: String? = null
        var coords = ArrayList<GeoPoint>(32)
        var inPlacemark = false
        var inName = false
        var inCoords = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfterLast(':')
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "Placemark" -> {
                        inPlacemark = true
                        name = null
                        coords = ArrayList(32)
                    }
                    "name" -> if (inPlacemark) {
                        inName = true
                        buf.setLength(0)
                    }
                    "coordinates" -> if (inPlacemark) {
                        inCoords = true
                        buf.setLength(0)
                    }
                }
                XmlPullParser.TEXT -> if (inName || inCoords) buf.append(parser.text)
                XmlPullParser.END_TAG -> when (tag) {
                    "name" -> if (inName) {
                        name = buf.toString().trim()
                        inName = false
                    }
                    "coordinates" -> if (inCoords) {
                        appendCoords(buf.toString(), coords)
                        inCoords = false
                    }
                    "Placemark" -> {
                        val n = name
                        if (inPlacemark && !n.isNullOrEmpty() && coords.size >= 2) {
                            roads.add(Road(n, coords))
                        }
                        inPlacemark = false
                    }
                }
            }
            event = parser.next()
        }

        Log.d(TAG, "道路 KML：解析到 ${roads.size} 条 · 顶点 ${roads.sumOf { it.points.size }} 个")
        return roads
    }

    /**
     * KML 的 coordinates 块是「经度,纬度,高程」三元组按空白分隔的长串：
     * `131.0305,44.3750,0 131.0344,44.3679,0 …`
     * 逐段拆开，只取前两个数；高程列与格式异常的行直接跳过，不抛异常。
     */
    private fun appendCoords(raw: String, out: ArrayList<GeoPoint>) {
        for (token in raw.split('\n', '\r', '\t', ' ')) {
            if (token.isEmpty()) continue
            val parts = token.split(',')
            if (parts.size < 2) continue
            val lon = parts[0].trim().toDoubleOrNull() ?: continue
            val lat = parts[1].trim().toDoubleOrNull() ?: continue
            out.add(GeoPoint(lat, lon))
        }
    }
}
