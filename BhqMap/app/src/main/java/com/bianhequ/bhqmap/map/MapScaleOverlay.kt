package com.bianhequ.bhqmap.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import androidx.core.content.ContextCompat
import com.bianhequ.bhqmap.R
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.max

/**
 * 左下角比例尺（v0.4）。
 *
 * 为什么不用 osmdroid 自带的 ScaleBarOverlay：
 *   1. 它的取值模型走 xdpi/ydpi 物理 DPI，与本项目用反投影实测的
 *      [GroundResolution]（分档阈值的同源模型）可能不一致；
 *   2. 文字/线条/背景颜色只能换 Paint，玻璃底、圆角、内边距都要自己拼；
 *   3. 无法自测验证其绘制坐标原点。
 *   自绘 60 行，全部走已验证的实测模型，观感与按钮系统一致。
 *
 * 刻度取 1/2/5 × 10^n 序列，保证读数是"整数好念"的值（100 m / 500 m / 2 km）。
 */
class MapScaleOverlay(private val mapView: MapView) : Overlay() {

    private val res = mapView.context.resources
    private val density = res.displayMetrics.density

    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, res.displayMetrics)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(mapView.context, R.color.scalebar_bg)
        style = Paint.Style.FILL
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(mapView.context, R.color.scalebar_fg)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(mapView.context, R.color.scalebar_fg)
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
        // 投影与地图上另外两处文字同参数（v0.5 加重），见 [TextShadow]
        setShadowLayer(dp(TextShadow.RADIUS_DP), 0f, dp(TextShadow.DY_DP), TextShadow.color)
    }

    /** 刻度候选：1/2/5 × 10^n，单位米 */
    private val stepMultipliers = intArrayOf(1, 2, 5)

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val w = mapView.width
        val h = mapView.height
        if (w <= 0 || h <= 0) return

        val mpp = GroundResolution.metersPerPixel(mapView.projection, w, h)
        if (mpp.isNaN() || mpp <= 0.0) return

        // 比例尺条最长约 110dp；取不超过该长度的最大刻度
        val maxBarPx = dp(110f)
        var bestMeters = 0.0
        var bestPx = 0f
        outer@ for (k in 0..7) {
            for (m in stepMultipliers) {
                val meters = m * Math.pow(10.0, k.toDouble())
                val px = (meters / mpp).toFloat()
                if (px > maxBarPx) break@outer
                bestMeters = meters
                bestPx = px
            }
        }
        if (bestMeters <= 0.0 || bestPx < dp(24f)) return

        val text = formatLength(bestMeters)
        val fm = textPaint.fontMetrics
        val textH = fm.descent - fm.ascent

        val padX = dp(10f)
        val padTop = dp(6f)
        val padBottom = dp(8f)
        val barAreaH = dp(8f)
        val textBarGap = dp(3f)

        val boxW = max(bestPx, textPaint.measureText(text)) + padX * 2
        val boxH = padTop + textH + textBarGap + barAreaH + padBottom
        val left = dp(14f)
        val bottom = h - dp(14f)
        val top = bottom - boxH

        // 玻璃底圆角面板
        canvas.drawRoundRect(
            RectF(left, top, left + boxW, bottom), dp(5f), dp(5f), bgPaint
        )

        // 刻度文字
        val baseline = top + padTop - fm.ascent
        canvas.drawText(text, left + boxW / 2f, baseline, textPaint)

        // 刻度条：横线 + 两端端点
        val barY = top + padTop + textH + textBarGap + barAreaH / 2f
        val x0 = left + padX
        val x1 = x0 + bestPx
        canvas.drawLine(x0, barY, x1, barY, barPaint)
        canvas.drawLine(x0, barY - barAreaH / 2f, x0, barY + barAreaH / 2f, barPaint)
        canvas.drawLine(x1, barY - barAreaH / 2f, x1, barY + barAreaH / 2f, barPaint)
    }

    private fun formatLength(meters: Double): String =
        if (meters >= 1000.0) "%.0f km".format(meters / 1000.0)
        else "%.0f m".format(meters)
}
