package com.bianhequ.bhqmap.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * 天地图 WMTS 瓦片源。
 *
 * 合规说明：
 *   本类只请求 tianditu.gov.cn（国家地理信息公共服务平台）的瓦片服务，
 *   不加载、不兜底、不回退任何境外地图数据源。
 *
 * 坐标系说明：
 *   球面墨卡托矩阵集 TILEMATRIXSET=w，坐标系为 CGCS2000，
 *   与 WGS-84 在使用精度上一致，无需任何坐标转换。
 *
 * 实测记录（2026-09-21）：z3-z19 全部 HTTP 200；
 *   影像层返回 JPEG（image/jpg），注记层返回 PNG —— 故文件扩展名取 .jpg，
 *   osmdroid 按内容解码，两者都能正常显示。
 *
 * 实现注记：osmdroid 的 OnlineTileSourceBase 第 6 个参数声明为 String[]（普通数组，
 *   不是 Java 可变参数），所以传数组时不能用 * 展开运算符，直接传数组即可。
 */
class TiandituTileSource(
    private val layer: String,
    private val tk: String,
    minZoom: Int = MIN_ZOOM,
    maxZoom: Int = MAX_ZOOM,
    subdomainCount: Int = SUBDOMAIN_COUNT
) : OnlineTileSourceBase(
    "Tianditu_$layer",
    minZoom,
    maxZoom,
    TILE_SIZE_PX,
    ".jpg",
    Array(subdomainCount) { "https://t$it.tianditu.gov.cn/${layer}_w/wmts" }
) {

    override fun getTileURLString(pMapTileIndex: Long): String {
        val z = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)

        return buildString {
            // 框架在 baseUrl 数组里随机取一个，天然分摊 t0-t7 子域名的并发
            append(getBaseUrl())
            append("?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0")
            append("&LAYER=").append(layer)
            append("&STYLE=default")
            append("&TILEMATRIXSET=w")
            append("&FORMAT=tiles")
            append("&TileMatrix=").append(z)
            append("&TileRow=").append(y)
            append("&TileCol=").append(x)
            append("&tk=").append(tk)
        }
    }

    companion object {
        const val MIN_ZOOM = 1
        const val MAX_ZOOM = 18
        const val TILE_SIZE_PX = 256
        const val SUBDOMAIN_COUNT = 8

        const val LAYER_IMAGE = "img"
        const val LAYER_IMAGE_ANNOTATION = "cia"
        const val LAYER_VECTOR = "vec"
        const val LAYER_VECTOR_ANNOTATION = "cva"
        const val LAYER_BOUNDARY = "ibo"

        /** 影像底图 —— 主底图 */
        fun image(tk: String) = TiandituTileSource(LAYER_IMAGE, tk)

        /** 影像注记（地名）—— 默认关闭，避免与自绘企业标签打架 */
        fun imageAnnotation(tk: String) = TiandituTileSource(LAYER_IMAGE_ANNOTATION, tk)

        /** 全球境界 —— 边境场景可单独叠加，界线为官方标准 */
        fun boundary(tk: String) = TiandituTileSource(LAYER_BOUNDARY, tk)

        /** 矢量底图 —— 备用 */
        fun vector(tk: String) = TiandituTileSource(LAYER_VECTOR, tk)
    }
}
