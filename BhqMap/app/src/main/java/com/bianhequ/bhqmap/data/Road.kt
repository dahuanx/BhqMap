package com.bianhequ.bhqmap.data

import org.osmdroid.util.GeoPoint

/**
 * 园区道路（v0.7.16）。
 *
 * 来源：`assets/roads.kml`（洲哥 2026-09-23 提供的路网），一条 Placemark
 * = 一条道路 = 一条 LineString。只取 `<name>` 与 `<coordinates>`，
 * 线宽 / 颜色 / 线型那些样式一律忽略 —— **本 App 不画道路线条**，
 * 只把路名沿线标注出来（见 [com.bianhequ.bhqmap.map.RoadOverlay]）。
 *
 * 坐标：KML 里是 `经度,纬度,高程` 的 WGS-84，与全工程坐标系一致，**零转换**。
 * 高程列（恒为 0）直接丢弃。
 *
 * 为什么不用 osmdroid 的 KmlDocument / GroundOverlay：
 *   1. osmdroid 的 KML 支持只覆盖 KmlDocument，解析后仍要自己写 Overlay 才画得出
 *      沿线的旋转文字，等于多引一套解析器只为了拿两个字段；
 *   2. 内置解析会把 LineString 变成 Polyline，而第一条需求就是"不显示线条"；
 *   3. 自己用 XmlPullParser 解析这 20 行，零依赖、字段可控，与
 *      [EnterpriseStore] 用内置 `org.json` 而不引 Gson 是同一个取舍。
 */
class Road(
    /** 道路名称，直接来自 KML 的 `<name>`（如"规划十路"） */
    val name: String,

    /**
     * 路线折线顶点，至少 2 个。
     *
     * 用 [GeoPoint] 而不是两个 DoubleArray：osmdroid 的
     * `Projection.toPixels(IGeoPoint, Point)` 正好吃它，省一层转换。
     * 顶点数最多 22（规划十路），全库 50 个点 —— 每帧重新投影的开销可忽略，
     * 所以不做屏幕坐标缓存。
     */
    val points: List<GeoPoint>
)
