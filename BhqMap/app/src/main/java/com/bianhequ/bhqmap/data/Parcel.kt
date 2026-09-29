package com.bianhequ.bhqmap.data

import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.cos

/**
 * 闲置地块（v0.7.27）。
 *
 * 来源：`assets/parcels.kml`（洲哥 2026-09-24 提供的闲置地块面数据），
 * 一个 Placemark = 一个地块 = 一个 Polygon 外环。只取 `<name>` 与
 * `<outerBoundaryIs><LinearRing><coordinates>`，Style / ExtendedData /
 * altitudeMode 一律忽略 —— 本 App 的框体样式是固定的（见
 * [com.bianhequ.bhqmap.map.ParcelOverlay]），不解析 KML 里的样式。
 *
 * 坐标：KML 里是 `经度,纬度,高程` 的 WGS-84，与全工程坐标系一致，**零转换**。
 * 高程列（恒为 0）直接丢弃。
 *
 * ------------------------------------------------------------------
 * 为什么不用 osmdroid 的 Polygon / GroundOverlay
 * ------------------------------------------------------------------
 * 与企业标签、道路名同一条取舍（见 [Road] 的注释）：osmdroid 的 Polygon 能画面，
 * 但画不出"把名称压在**多边形质心**上、并在名称之间做碰撞避让"这件事 ——
 * 那正是本图层的一半需求。既然名称要自绘，面也就一起自绘了：一个 [android.graphics.Path]
 * 加两支 Paint，比再引一层封装更可控，也与 [com.bianhequ.bhqmap.map.EnterpriseOverlay]
 * 的绘制顺序/避让口径完全同源。
 *
 * ------------------------------------------------------------------
 * 命名与面积
 * ------------------------------------------------------------------
 * KML 的 `<name>` 一律形如 `"0.086平方公里 工业用地"` —— **面积 + 地类**两段，
 * 中间一个空格。App 直接按这个空格拆成两行显示（见
 * [com.bianhequ.bhqmap.map.ParcelOverlay.labelLines]），不去改写洲哥的数据：
 * 面积是他填的事实，地类是分类，两者都需要看到。
 */
class Parcel(
    /** 地块名称，直接来自 KML 的 `<name>`（如 `0.086平方公里 工业用地`） */
    val name: String,

    /**
     * 多边形外环顶点（**已闭合处理**：首尾不重复，见 [ParcelStore.parse]）。
     * 用 [GeoPoint] 而非 [DoubleArray] 是为了直接喂给
     * `Projection.toPixels(IGeoPoint, Point)`，与 [Road.points] 同款处理。
     */
    val ring: List<GeoPoint>
) {

    /**
     * 地块面积（平方米，等距圆柱近似）。
     *
     * **只用于排序** —— 名称数量不够时"大块先得名"靠它决定先后（见
     * [com.bianhequ.bhqmap.map.ParcelOverlay]），不对外展示：
     * 面积本身已经写在 KML 的名称里（`0.086平方公里 …`）。
     *
     * 园区尺度只有几公里，用 `lon × cos(中心纬度) × 111320` 与 `lat × 111132`
     * 折算成米后跑鞋带公式，与球面算法的差别在小数点后第三位，对排序无影响。
     */
    val areaM2: Double = run {
        if (ring.size < 3) {
            0.0
        } else {
            val midLat = ring.sumOf { it.latitude } / ring.size
            val kx = 111320.0 * cos(Math.toRadians(midLat))
            val ky = 111132.0
            val x0 = ring[0].longitude
            val y0 = ring[0].latitude
            var s = 0.0
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[(i + 1) % ring.size]
                val xa = (a.longitude - x0) * kx
                val ya = (a.latitude - y0) * ky
                val xb = (b.longitude - x0) * kx
                val yb = (b.latitude - y0) * ky
                s += xa * yb - xb * ya
            }
            abs(s) / 2.0
        }
    }
}
