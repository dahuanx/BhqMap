package com.bianhequ.bhqmap.data

import android.content.Context
import android.util.Log
import android.util.Xml
import org.osmdroid.util.GeoPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * 闲置地块数据源（v0.7.27）：解析 `assets/parcels.kml`。
 *
 * 与 [RoadStore] 是同一套骨架（XmlPullParser、零依赖、异常退化为空表），
 * 差别只在几何：道路取 `<coordinates>` 当折线，地块取它当**多边形外环**。
 *
 * 三处针对面数据的额外处理：
 *   1. **只取第一个坐标块**。一个 Polygon 可以有 innerBoundaryIs（挖洞），
 *      本图层不支持挖洞 —— 若把内环也当外环读进来，会多画出一个假地块。
 *      洲哥给的这份 KML 没有内环，此规则是防未来的脏数据；
 *   2. **相邻重复顶点去重**。KML 里 0.1008 平方公里那块有 3 个坐标完全相同
 *      （GIS 导出常见），留着会让鞋带公式多算零面积三角形、也让 Path 白画；
 *   3. **闭合**。KML 的 LinearRing 首尾点相同，自绘路径时首尾要由 `Path.close()`
 *      收口，所以把重复的末点去掉 —— 否则轮廓线在末段画两遍，描边发虚。
 *
 * 与 [EnterpriseStore] 的差别同 [RoadStore]：地块是**只读的包内资产**，
 * 不做 filesDir 覆盖、不做版本号播种 —— 换地块 = 替换 `assets/parcels.kml` 重新打包。
 */
class ParcelStore(private val context: Context) {

    companion object {
        /** 地块 KML 在 assets 下的文件名 */
        const val ASSET_NAME = "parcels.kml"

        /** 与 [com.bianhequ.bhqmap.MainActivity] 同一个 Logcat 标签 */
        private const val TAG = "BhqMap"

        /** 面至少 3 个顶点才成立 */
        private const val MIN_RING = 3
    }

    /**
     * 读取并解析地块。任何异常（文件缺失、XML 损坏）都退化为空列表 ——
     * 地块是辅助图层，不该因为它把地图拖崩。
     */
    fun load(): List<Parcel> = try {
        context.assets.open(ASSET_NAME).use { parse(it) }
    } catch (t: Throwable) {
        Log.w(TAG, "闲置地块 KML 读取失败：$t")
        emptyList()
    }

    private fun parse(input: InputStream): List<Parcel> {
        val parser = Xml.newPullParser()
        // KML 有无命名空间前缀都吃得下：下面统一按"最后一个冒号之后"取标签名
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, "UTF-8")

        val parcels = ArrayList<Parcel>(16)
        // TEXT 事件可能被分片投递，逐段累积
        val buf = StringBuilder()
        var name: String? = null
        var ring = ArrayList<GeoPoint>(16)
        var inPlacemark = false
        var inName = false
        var inCoords = false
        // 本次 Placemark 是否已经拿到过坐标块 —— 拿过就不再收（忽略内环/第二个面）
        var gotRing = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfterLast(':')
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "Placemark" -> {
                        inPlacemark = true
                        name = null
                        ring = ArrayList(16)
                        gotRing = false
                    }
                    "name" -> if (inPlacemark) {
                        inName = true
                        buf.setLength(0)
                    }
                    "coordinates" -> if (inPlacemark && !gotRing) {
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
                        appendCoords(buf.toString(), ring)
                        gotRing = true
                        inCoords = false
                    }
                    "Placemark" -> {
                        val n = name
                        // 去重相邻重复点 + 去掉与首点重合的末点
                        val clean = cleanRing(ring)
                        if (inPlacemark && !n.isNullOrEmpty() && clean.size >= MIN_RING) {
                            parcels.add(Parcel(n, clean))
                        }
                        inPlacemark = false
                    }
                }
            }
            event = parser.next()
        }

        Log.d(
            TAG,
            "闲置地块 KML：解析到 ${parcels.size} 块 · 顶点 ${parcels.sumOf { it.ring.size }} 个"
        )
        return parcels
    }

    /**
     * KML 的 coordinates 块是「经度,纬度,高程」三元组按空白分隔的长串：
     * `131.0251,44.3746,0 131.0304,44.3754,0 …`
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

    /** 相邻重复点去重；若末点与首点重合则去掉末点（闭合交给 `Path.close()`） */
    private fun cleanRing(src: List<GeoPoint>): List<GeoPoint> {
        if (src.isEmpty()) return emptyList()
        val out = ArrayList<GeoPoint>(src.size)
        for (p in src) {
            val last = out.lastOrNull()
            if (last == null || last.latitude != p.latitude || last.longitude != p.longitude) {
                out.add(p)
            }
        }
        if (out.size > 1) {
            val f = out.first()
            val l = out.last()
            if (f.latitude == l.latitude && f.longitude == l.longitude) out.removeAt(out.size - 1)
        }
        return out
    }
}
