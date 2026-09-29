package com.bianhequ.bhqmap.map

import android.graphics.Color

/**
 * 自绘文字的投影样式（v0.5 加重）。
 *
 * 地图上有两处自绘文字：企业名称标签（[EnterpriseOverlay]）与比例尺读数
 * （[MapScaleOverlay]）。它们压在卫星影像上，影像本身明暗对比强烈，
 * v0.4 那种 2dp / 60% 的浅投影在亮部（厂房顶、空地）等于没有，
 * 白字直接糊进底图。这里集中定义一份参数，两处共用 ——
 * 免得日后只改了一处、两块文字的观感对不上。
 *
 * 调参口径：投影要"看得见黑边但不脏字"，半径再大就会染到相邻字上。
 */
object TextShadow {

    /** 投影半径（dp）。v0.4 为 2dp */
    const val RADIUS_DP = 3.4f

    /** 投影下移量（dp）。太阳光在左上角的常规假定，v0.4 为 1dp */
    const val DY_DP = 1.8f

    /** 投影不透明度（0-255）。v0.4 为 60%（153） */
    const val ALPHA = 240

    /** 投影颜色：纯黑按 [ALPHA] 调透明度 */
    val color: Int get() = Color.argb(ALPHA, 0, 0, 0)
}
