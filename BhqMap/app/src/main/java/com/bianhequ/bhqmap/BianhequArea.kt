package com.bianhequ.bhqmap

import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint

/**
 * 边合区范围配置 —— 全项目唯一一处范围定义。
 *
 * 坐标系：WGS-84（与天地图 CGCS2000 同系直配，禁止任何偏移转换）。
 *
 * 当前状态（2026-09-21）：4 个控制点，[boundingBox] 是它们的【外接矩形，占位】。
 * 真实红线多边形到手后，只需替换 [boundaryPoints]，其余代码零改动。
 */
object BianhequArea {

    /** 园区中心点（2026-09-21 提供） */
    val CENTER: GeoPoint = GeoPoint(44.3468525979, 131.0496647326)

    /** 最北侧标点：北端顶在绥满高速（G10）上 */
    val NORTH: GeoPoint = GeoPoint(44.3752857924, 131.0297251599)

    /** 最南侧标点 */
    val SOUTH: GeoPoint = GeoPoint(44.3271033367, 131.0577789413)

    /**
     * 东侧扩展点（2026-09-21 第二次修正）：
     * 以最南端坐标为基准向东扩展，经度调整至 131.0671950135。
     * 用途：把沿 G331 一线的东南仓储厂房群纳入外接矩形。
     */
    val SOUTH_EAST_EXT: GeoPoint = GeoPoint(44.3271033367, 131.0671950135)

    /**
     * 边界点集（当前为 4 个控制点，仅用于推出外接矩形）。
     *
     * TODO：替换为真实红线多边形顶点（按顺时针或逆时针顺序）。
     *       来源建议：园区红线 KML/GeoJSON，或规划图纸坐标表批量录入。
     */
    val boundaryPoints: List<GeoPoint> = listOf(CENTER, NORTH, SOUTH, SOUTH_EAST_EXT)

    /** 外接矩形：App 启动默认视野 = 完整容纳这个矩形 */
    val boundingBox: BoundingBox by lazy { BoundingBox.fromGeoPoints(boundaryPoints) }

    /**
     * Excel 导入时的坐标合法性校验范围（v0.3 用）。
     * 在外接矩形基础上外扩 2 km，防止手滑把点位导到俄罗斯或哈尔滨。
     */
    val importBBox: BoundingBox by lazy {
        val padLat = 2.0 / 111.32          // 2 km 对应的纬度跨度
        val padLon = 2.0 / (111.32 * kotlin.math.cos(Math.toRadians(centerLat)))
        BoundingBox(
            boundingBox.latSouth - padLat,
            boundingBox.lonWest - padLon,
            boundingBox.latNorth + padLat,
            boundingBox.lonEast + padLon
        )
    }

    val centerLat: Double get() = boundingBox.centerLatitude
    val centerLon: Double get() = boundingBox.centerLongitude
}
