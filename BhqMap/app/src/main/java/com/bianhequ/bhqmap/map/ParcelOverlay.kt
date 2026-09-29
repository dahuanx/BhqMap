package com.bianhequ.bhqmap.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import androidx.core.content.ContextCompat
import com.bianhequ.bhqmap.R
import com.bianhequ.bhqmap.data.Parcel
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs

/**
 * 闲置地块图层（v0.7.27）。
 *
 * 洲哥要求原文：「加入了闲置地块图层，在项目按钮的弹窗中加入"闲置地块"选项，
 * 默认不显示……选一个合适的框体颜色，然后在每个框体中间显示名称」。
 *
 * 本层做两件事，就两件：
 *   1. **画框**：把 13 个地块的轮廓画成半透明填充 + 实线描边的多边形；
 *   2. **在框体中间写名称**：取多边形质心，把 KML 名称（面积 + 地类）拆成两行压上去。
 *
 * ------------------------------------------------------------------
 * 一、框体颜色怎么选的（violet #B388FF）
 * ------------------------------------------------------------------
 * 本工程已有五个"功能色"，各自绑定一个企业身份：
 *   沙金 #D9B95C / 亮橄榄 #9DB06A / 明黄 #FFD400 / 卡其白 #F2F0E6 / 湖蓝 #5FD0E8。
 * 把它们的色相列出来 —— 前四个挤在 45°~80°（暖黄绿一脉），湖蓝在 189°（冷青）。
 * **色相空间里最空的一段是 280°~320°（紫）**，所以第六色取紫，不是审美偏好，
 * 是"离已有五色都最远"的解。
 *
 * 为什么不用橙 / 红：暖色段已被沙金与明黄占满，再加一个必然撞色；
 * 而且红橙在这类图层里会被读成"警示/禁区"，与"闲置待招商"的语义相反。
 * 为什么不用青绿：与湖蓝只差 40° 左右，缩小到点位尺度就会混。
 *
 * **v0.7.28 补记（色相分工的完整版）**：上面这段是"当时只有一个图层"时的取舍。
 * v0.7.28 加了第二层（闲置厂房）之后，暖橙段被正式启用给厂房（珊瑚橙 #FF8A65，14°）——
 * 因为 262°（紫）已被地块占走，能同时离"已有五色 + 紫"都远的位置只剩暖橙。
 * 当时那条"红橙会被读成警示"的顾虑也随之被更强的两道保险覆盖：
 *   ① 本工程两个图层**互斥**，任一图层打开时企业名称一律不画，
 *      所以"珊瑚橙的厂房名"从不与"沙金的协作区名"同屏；
 *   ② 珊瑚橙比正红亮、饱和度也低一档，图例里还带着「闲置厂房」四个字。
 * 结论：**紫给"面"（地块），珊瑚橙给"点"（厂房）**，两色在色相环上相距 248°，
 * 是这两个图层能同时摆进图例还一眼分得开的最优解。详见 [IdleFactoryOverlay] 的类注释。
 *
 * 紫罗兰 #B388FF 明度偏高（L\* ≈ 72），在深色影像（林地、阴影）上显眼，
 * 在浅色影像（水泥地、白屋顶）上也不至于消失 —— 与五色同一个"中偏亮"的明度档。
 *
 * ------------------------------------------------------------------
 * 二、填充为什么只有 14%
 * ------------------------------------------------------------------
 * 底图是卫星影像，地块的价值就在影像本身（有没有厂房、有没有堆料、空地形态）。
 * 填充再深一点就把影像盖住了。14% 的效果是"一眼看出这块地被圈了"，
 * 同时影像的纹理依然清楚。定这个数的是**目的**，不是好看：
 * 这层要回答的是"哪块地空着"，不是"这块地什么颜色"。
 *
 * ------------------------------------------------------------------
 * 三、名称的位置：多边形质心，而不是包围盒中心
 * ------------------------------------------------------------------
 * 地块形状不规则（0.147 平方公里仓储用地是一根南北向的长条、0.26 平方公里待定
 * 是一条折了三折的带子），包围盒中心常常落在**多边形外面**。质心（面积重心）
 * 对凸多边形一定在内；对这几块凹多边形，离线核过 13 块**全部落在环内**
 * （见 `.audit_parcels.py`）。所以直接用质心，不需要"内点搜索"那套。
 *
 * 名称之间沿用企业标签的避让口径（重叠面积 > 较小框 × [OVERLAP_RATIO] 即判冲突），
 * 冲突时**丢掉名称、保留框**并按地块面积从大到小让位 ——
 * 框是事实（这块地在这儿），名称是注记，注记挤不下时该让的是注记。
 * 离线复现（`.sim_parcels.py`）：全貌档 11/13 出名称，z ≥ 14.5 后 13/13 全出。
 *
 * ------------------------------------------------------------------
 * 四、为什么名称不设缩放门槛（与道路名不同）
 * ------------------------------------------------------------------
 * 道路名有 `MIN_ZOOM = 17` 门槛，是因为全貌档 13 条路的屏幕长度衬不下 11sp 的路名，
 * 硬画就是 13 串字糊在一起。地块名称不一样：13 个地块天然分散在 1.9 km² 上，
 * 离线量过全貌档也能放下 11 个 —— 没有需要靠门槛去挡的东西。
 * 洲哥打开开关就该看见名称，这是"框体中间显示名称"的字面要求。
 */
class ParcelOverlay(
    private val context: Context,
    private val dataProvider: () -> List<Parcel>
) : Overlay() {

    companion object {
        /** 与 [com.bianhequ.bhqmap.MainActivity] 同一个 Logcat 标签 */
        private const val TAG = "BhqMap"

        /**
         * 地块名称字号（sp）。
         *
         * 12sp 与企业普通标签同档：地块是**面**，名称压在面中央，四周是自家地盘，
         * 不像点位名称那样需要跟邻居抢空间 —— 但它也不该比企业名更抢眼
         * （企业是主内容、地块是背景态），所以不放大。
         * 用加粗 + 专属色把它与"普通企业名"区分开。
         */
        private const val TEXT_SIZE_SP = 12f

        /** 名称描边线宽（dp）。与企业标签的 1.6dp 同源，观感一致 */
        private const val TEXT_STROKE_DP = 1.6f

        /**
         * 框体线宽（dp）。
         * 比企业标签描边（1.6dp）粗，比道路白边（2.4dp）细 ——
         * 面轮廓要"看得见边界"但不是画线稿，2.0dp 在 3 m/px 到 0.3 m/px
         * 全档都立得住（线宽不随缩放变，与界碑式注记同规矩）。
         */
        private const val BORDER_WIDTH_DP = 2.0f

        /** 名称之间、名称与屏幕边的余量（dp） */
        private const val LABEL_PAD_DP = 2f

        /**
         * 名称准许的重叠比例，取与企业标签同一个数（[EnterpriseOverlay.OVERLAP_RATIO]）。
         * 地块名称数量少（13 个）、下方是自家框体，不像企业名那样密集，
         * 但没有理由另立一套阈值 —— 同一个观感口径只留一处定义。
         */
        private const val OVERLAP_RATIO = EnterpriseOverlay.OVERLAP_RATIO

        /** 名称至少要在屏幕内露出的面积比例，低于此值就不画（与企业标签同口径） */
        private const val VISIBLE_MIN_RATIO = EnterpriseOverlay.VISIBLE_MIN_RATIO

        /** 框体视野剔除余量（px）：面可能只露一角 */
        private const val CULL_MARGIN_PX = 64f

        /** 名称左右内边距（dp），与企业标签的 labelPadH 同值 */
        private const val LABEL_PAD_H_DP = 5f

        /** 两行之间的行距（dp），与企业标签同值 */
        private const val LINE_SPACING_DP = 1.5f
    }

    // ------------------------------------------------------------------
    // 对外只读状态（供长按指北针的校准读数使用）
    // ------------------------------------------------------------------

    /** 数据里的地块总数 */
    var parcelCount: Int = 0
        private set

    /** 本次绘制画进视野的框体数 */
    var visibleCount: Int = 0
        private set

    /** 本次实际写出名称的地块数 */
    var labelCount: Int = 0
        private set

    /** 框画了、名称没画的地块数（被挤出屏幕或与别的名称撞位） */
    var hiddenCount: Int = 0
        private set

    /**
     * 图层开关（由显示控制浮层的「闲置地块」行控制，默认关）。
     * 关掉时本层**整层不画**、四个计数一并归零。
     */
    var visible: Boolean = false

    // ------------------------------------------------------------------
    // 画笔
    // ------------------------------------------------------------------

    private val metrics = context.resources.displayMetrics
    private val density = metrics.density

    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, metrics)

    /** 面填充：同一支紫，压到 14% —— 底下是卫星影像，填充的职责只是"标出范围" */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.parcel_fill)
    }

    /** 面轮廓：不透明紫，2.0dp 实线 */
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(BORDER_WIDTH_DP)
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.parcel_line)
    }

    /**
     * 名称基准笔：只提供字号/对齐/字重，自己不画。
     * `Paint()` 的默认 textSize 是 12px（不是 12sp），漏设会让 `measureText`
     * 与真实落笔对不上 —— 这个坑 [RoadOverlay] 踩过，此处沿用它的"基准笔 + 拷贝构造"写法。
     */
    private val basePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(TEXT_SIZE_SP)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    /** 名称描边笔（深灰，与企业标签同色），投影只挂在这一支上 */
    private val textStrokePaint = Paint(basePaint).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(TEXT_STROKE_DP)
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.label_outline)
        setShadowLayer(
            dp(TextShadow.RADIUS_DP), 0f, dp(TextShadow.DY_DP), TextShadow.color
        )
    }

    /** 名称面：亮紫，比框体线再亮一档，保证在自家 14% 底上仍然跳得出来 */
    private val textFillPaint = Paint(basePaint).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.parcel_text)
    }

    // ------------------------------------------------------------------
    // 绘制期复用对象（每帧重建的是内容，不是容器）
    // ------------------------------------------------------------------

    private val path = Path()
    private val pt = Point()
    private val rect = RectF()
    private val placedBoxes = ArrayList<RectF>(16)

    /** 顶点投影缓冲，按最大顶点数增长一次后不再分配 */
    private var xs = FloatArray(48)
    private var ys = FloatArray(48)

    /** 各块名称锚点（屏幕坐标）与"是否可用"标记，下标与 [ordered] 对齐 */
    private var anchorX = FloatArray(24)
    private var anchorY = FloatArray(24)
    private var anchorOk = BooleanArray(24)

    /** 按面积降序的绘制/命名顺序（名称让位时大块优先） */
    private val ordered = ArrayList<Parcel>(16)

    /** 上一次的数据源实例，用于判断要不要重排（地块是静态资产，一次即可） */
    private var lastSource: List<Parcel>? = null

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        // 关掉时整层不画，计数一并归零 —— 免得读数里留着上一档的旧值
        if (!visible) {
            parcelCount = 0
            visibleCount = 0
            labelCount = 0
            hiddenCount = 0
            return
        }

        val src = dataProvider()
        parcelCount = src.size
        if (src.isEmpty()) {
            visibleCount = 0
            labelCount = 0
            hiddenCount = 0
            return
        }

        // 数据源换了实例（或长度变了）才重排：地块是静态资产，正常只走一次
        if (src !== lastSource || ordered.size != src.size) {
            lastSource = src
            ordered.clear()
            ordered.addAll(src)
            // 面积大的先挑位置 —— 挤不下时丢掉的是小块名称，符合"大块更该被看见"
            ordered.sortByDescending { it.areaM2 }
            val need = ordered.size
            if (anchorX.size < need) {
                anchorX = FloatArray(need)
                anchorY = FloatArray(need)
                anchorOk = BooleanArray(need)
            }
        }

        val w = mapView.width
        val h = mapView.height
        if (w <= 0 || h <= 0) return
        val projection = mapView.projection

        visibleCount = 0
        labelCount = 0
        hiddenCount = 0

        // ---- 第一遍：先画全部框体 ----
        // 必须与名称分开两遍：否则先画的名称会被后画地块的填充盖住。
        for (i in ordered.indices) {
            val parcel = ordered[i]
            val n = parcel.ring.size
            if (n < 3) {
                anchorOk[i] = false
                continue
            }
            if (xs.size < n) {
                xs = FloatArray(n)
                ys = FloatArray(n)
            }

            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (j in 0 until n) {
                projection.toPixels(parcel.ring[j], pt)
                val x = pt.x.toFloat()
                val y = pt.y.toFloat()
                xs[j] = x
                ys[j] = y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }

            // 整块在屏幕外
            if (maxX < -CULL_MARGIN_PX || minX > w + CULL_MARGIN_PX ||
                maxY < -CULL_MARGIN_PX || minY > h + CULL_MARGIN_PX
            ) {
                anchorOk[i] = false
                continue
            }

            path.reset()
            path.moveTo(xs[0], ys[0])
            for (j in 1 until n) path.lineTo(xs[j], ys[j])
            path.close()

            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, borderPaint)
            visibleCount++

            // 屏幕空间质心（面积重心）。地块在屏幕上的面积恒为正，
            // 分母 6A 不会为 0；退化情形（所有点共线）由下面的面积判据挡掉
            var twiceArea = 0.0
            var cx = 0.0
            var cy = 0.0
            for (j in 0 until n) {
                val k = (j + 1) % n
                val cross = xs[j].toDouble() * ys[k] - xs[k].toDouble() * ys[j]
                twiceArea += cross
                cx += (xs[j] + xs[k]) * cross
                cy += (ys[j] + ys[k]) * cross
            }
            if (abs(twiceArea) < 1.0) {
                anchorOk[i] = false
                continue
            }
            anchorX[i] = (cx / (3.0 * twiceArea)).toFloat()
            anchorY[i] = (cy / (3.0 * twiceArea)).toFloat()
            anchorOk[i] = true
        }

        if (visibleCount == 0) return

        // ---- 第二遍：逐个放名称 ----
        placedBoxes.clear()
        val fm = textFillPaint.fontMetrics
        val lineH = fm.descent - fm.ascent
        val padH = dp(LABEL_PAD_H_DP)
        val lineSpacing = dp(LINE_SPACING_DP)

        for (i in ordered.indices) {
            if (!anchorOk[i]) continue
            val lines = labelLines(ordered[i].name)
            if (lines.isEmpty()) continue

            val textW = lines.maxOf { textFillPaint.measureText(it) }
            val boxW = textW + padH * 2
            val boxH = lineH * lines.size + lineSpacing * (lines.size - 1) + padH
            val cx = anchorX[i]
            val cy = anchorY[i]

            rect.set(
                cx - boxW / 2f - dp(LABEL_PAD_DP),
                cy - boxH / 2f - dp(LABEL_PAD_DP),
                cx + boxW / 2f + dp(LABEL_PAD_DP),
                cy + boxH / 2f + dp(LABEL_PAD_DP)
            )

            // 名称被屏幕切掉太多就不画（与企业标签同一条判据）
            if (!enoughVisible(rect, w, h) || collidesWithPlaced(rect)) {
                hiddenCount++
                continue
            }

            placedBoxes.add(RectF(rect))
            labelCount++

            // 两行整体在锚点上垂直居中：先算总高，再逐行推基线
            val totalH = lineH * lines.size + lineSpacing * (lines.size - 1)
            var top = cy - totalH / 2f
            for (line in lines) {
                val baseline = top - fm.ascent
                canvas.drawText(line, cx, baseline, textStrokePaint)
                canvas.drawText(line, cx, baseline, textFillPaint)
                top += lineH + lineSpacing
            }
        }

        Log.d(
            TAG,
            "闲置地块：${parcelCount} 块 · 视野内 ${visibleCount} · 名称 ${labelCount} · 未标 ${hiddenCount}"
        )
    }

    /**
     * 名称拆行：KML 名称恒为「面积 + 空格 + 地类」（`0.086平方公里 工业用地`），
     * 按第一个空格拆两行 —— 上行是数字、下行是汉字，两行等宽好排；
     * 合成一行会有 9~10 个字，全貌档（3 m/px）必然伸到框外。
     *
     * 没有空格的名字（未来的脏数据）就单行显示，不做额外切分。
     */
    private fun labelLines(name: String): List<String> =
        name.split(' ', limit = 2).map { it.trim() }.filter { it.isNotEmpty() }

    /** 名称与屏幕的相交面积是否达标（口径见 [VISIBLE_MIN_RATIO]） */
    private fun enoughVisible(rect: RectF, w: Int, h: Int): Boolean {
        val l = if (rect.left > 0f) rect.left else 0f
        val t = if (rect.top > 0f) rect.top else 0f
        val r = if (rect.right < w) rect.right else w.toFloat()
        val b = if (rect.bottom < h) rect.bottom else h.toFloat()
        if (r <= l || b <= t) return false
        val inter = (r - l) * (b - t)
        return inter >= rect.width() * rect.height() * VISIBLE_MIN_RATIO
    }

    /** 候选名称框是否与已放置的名称相撞（重叠面积 > 较小框 × [OVERLAP_RATIO]） */
    private fun collidesWithPlaced(candidate: RectF): Boolean {
        val area = candidate.width() * candidate.height()
        for (box in placedBoxes) {
            val ox = (if (candidate.right < box.right) candidate.right else box.right) -
                    (if (candidate.left > box.left) candidate.left else box.left)
            if (ox <= 0f) continue
            val oy = (if (candidate.bottom < box.bottom) candidate.bottom else box.bottom) -
                    (if (candidate.top > box.top) candidate.top else box.top)
            if (oy <= 0f) continue
            val inter = ox * oy
            val smaller = if (area < box.width() * box.height()) area else box.width() * box.height()
            if (inter > smaller * OVERLAP_RATIO) return true
        }
        return false
    }
}
