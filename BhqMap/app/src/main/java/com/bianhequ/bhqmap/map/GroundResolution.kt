package com.bianhequ.bhqmap.map

import org.osmdroid.views.Projection
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 地面分辨率（米/像素）实测。
 *
 * 为什么不能拿 zoom 级别当分级阈值：
 *   osmdroid 开启 isTilesScaledToDpi 时，瓦片尺寸被乘上屏幕密度 ——
 *   TileSystem.setTileSize(256 × density)。世界像素宽度随之变成
 *   tileSize × 2^zoom，于是同一个 zoom 值在不同手机上对应的地面分辨率不同。
 *   拿 zoom 写死阈值，换一台手机显隐逻辑就全乱。
 *
 * 为什么不用公式反算：
 *   Projection 没有 getMetersPerPixel()（已核对 osmdroid 源码，确实不存在）。
 *   TileSystem.GroundResolution(lat, zoom) 又要求先知道缩放后的 tileSize，
 *   而 tileSize 依赖运行时的屏幕密度。与其猜参数，不如直接量。
 *
 * 做法：把屏幕左右两端各反投影成一个经纬度点，量两点间的大圆距离再除以屏幕宽度。
 *   投影在单个屏幕范围内线性度极高，误差可忽略；且天然包含了 DPI 缩放、
 *   地图旋转、滚动偏移等所有因素，跨设备、跨模式都成立。
 */
object GroundResolution {

    private const val EARTH_RADIUS_M = 6371008.8

    /**
     * 估算当前视野中心的每像素地面米数。
     * 屏幕尺寸未知（布局未完成）时返回 NaN，调用方按"最宽松"处理。
     */
    fun metersPerPixel(projection: Projection, widthPx: Int, heightPx: Int): Double {
        if (widthPx <= 0 || heightPx <= 0) return Double.NaN
        val midY = heightPx / 2
        val left = projection.fromPixels(0, midY)
        val right = projection.fromPixels(widthPx, midY)
        val meters = haversineMeters(left.latitude, left.longitude, right.latitude, right.longitude)
        if (meters.isNaN() || meters <= 0.0) return Double.NaN
        return meters / widthPx
    }

    /** 两点间大圆距离（米） */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val s1 = sin(dLat / 2)
        val s2 = sin(dLon / 2)
        val h = s1 * s1 + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * s2 * s2
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }
}
