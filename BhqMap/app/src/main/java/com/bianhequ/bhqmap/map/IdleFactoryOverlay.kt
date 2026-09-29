package com.bianhequ.bhqmap.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import androidx.core.content.ContextCompat
import com.bianhequ.bhqmap.R
import com.bianhequ.bhqmap.data.IdleFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * 闲置厂房图层（v0.7.28）。
 *
 * 洲哥要求原文：「新建了闲置厂房工作表，在项目按钮的弹窗中加入"闲置厂房"选项，
 * 默认不显示……选一个合适的字体颜色样式，闲置厂房名称和闲置面积分两行显示」。
 *
 * 本层做两件事：
 *   1. **画标记**：每处闲置厂房在它的坐标上落一枚珊瑚橙圆角方块（12dp）；
 *   2. **两行标签**：第一行名称、第二行闲置面积，避让后贴在标记旁边。
 *
 * ------------------------------------------------------------------
 * 一、为什么要点 + 标签，不能只有标签
 * ------------------------------------------------------------------
 * 闲置地块层是"面"，框体自己就说明了位置（"这块地"）；闲置厂房层是"点"——
 * 台账里给的是经纬度，没有轮廓。没有标记的话，两行字浮在影像上没人知道指的是哪栋。
 * 所以标记是**事实**（这栋楼空着），标签是**注记**；避让挤不下时丢注记、留标记，
 * 与地块层"丢名保框"同一条取舍。
 *
 * 标记为什么是**方块**不是圆：企业点位已经占满了"圆"这个形状（四级圆点 + 行政五角星），
 * 再来一种圆点会跟企业混。方块 + 珊瑚橙，与企业圆点（军绿/卡其白/亮橄榄/沙金）
 * 在形状与颜色上都分得开 —— 而且"厂房"本来就该是方方正正一栋。
 *
 * ------------------------------------------------------------------
 * 二、颜色怎么选的（珊瑚橙 #FF8A65）
 * ------------------------------------------------------------------
 * 闲置地块层已经占走了色相环上最空的 262°（紫）。此时"离已有五色（沙金 45° /
 * 卡其白 54° / 亮橄榄 78° / 明黄 51° / 湖蓝 189°）**以及紫**都远"的位置，
 * 只剩暖橙段：珊瑚橙落在 14°，与最近的沙金差 30°。
 * 30° 听着不大，但两者一是灰调橄榄金（#D9B95C）、一是高饱和珊瑚橙（#FF8A65），
 * 明度与饱和度都不同，图例里 12dp 并排也不会认错。
 *
 * 更关键的一层保险：**本层打开时企业名称一律不画**（图层互斥，
 * 见 [com.bianhequ.bhqmap.MainActivity.onLayerToggled]），
 * 所以地图上根本不会出现"珊瑚橙的厂房名"与"沙金的协作区名"同屏的场面。
 * 会与企业圆点同屏 —— 那是形状与颜色双重区分的方块，不冲突。
 *
 * 明度上取"中偏亮"（L\* ≈ 68），与另五个功能色同一档：深色影像（林地、阴影）
 * 上跳得出来，浅色影像（水泥地、白屋顶）上不至于糊掉。
 *
 * ------------------------------------------------------------------
 * 三、标签两行的层级：名称大、面积小
 * ------------------------------------------------------------------
 * 洲哥要的是"名称 + 面积两行"。两行**不做同字号**：名称 12sp 加粗、面积 11sp 常规，
 * 靠字号 + 色深做出主次 —— 扫一眼先看到"哪家"，需要时再看"多少平"。
 * 两者都用自家珊瑚橙（文字比标记再亮一档，面积行更浅一档），不用白色：
 * 白色是底图道路名的语言，图层的名称该跟图层自己的颜色走。
 *
 * 折行规则在 [IdleFactory.nameLines] / [IdleFactory.areaLines] 里：
 * **只在台账原文自带的空格或逗号处断**，不硬折（硬折会切断词义）。
 * 于是 22 条是"名称 1 行 + 面积 1 行"，3 条 B-7-x（名称含空格）是 3 行，
 * 1 条面积含逗号的是 4 行 —— 全都不超过 4 行，也不出现半屏宽的横条。
 *
 * ------------------------------------------------------------------
 * 四、为什么名称不设缩放门槛
 * ------------------------------------------------------------------
 * 道路名有 `MIN_ZOOM = 17` 门槛（全貌档 13 条路的屏幕长度衬不下路名），
 * 闲置厂房没有：离线复现（`.sim_idle_factories.py`，真实坐标 + 1080×2400 @3.0）
 * 显示全貌档（约 2.97 m/px）就能落下 **21/25** 个标签，只有 4 个因撞位丢掉，
 * 且都是国林三期/四期那几处挨得极近的（间距 35~90 m）。
 * 而且本层打开时企业名全隐，屏幕是空出来的 —— 没有非要靠门槛才能挡掉的东西。
 *
 * 撞位丢的那 4 个，标记照画：**标记一个不少**，与"名称总闸"的设计一致。
 */
class IdleFactoryOverlay(
    private val context: Context,
    private val dataProvider: () -> List<IdleFactory>
) : Overlay() {

    companion object {
        /** 与 [com.bianhequ.bhqmap.MainActivity] 同一个 Logcat 标签 */
        private const val TAG = "BhqMap"

        /**
         * 名称行字号（sp）。12sp 与企业普通标签、地块名称同档 ——
         * 借字号表达重要度：名称比面积重要，就不是同字号；
         * 但这一层整体是"附加图层"，不压过企业标签的 14sp/16sp 层级，所以不到 13。
         */
        private const val NAME_SIZE_SP = 12f

        /** 面积行字号（sp）。比名称小一档，做出主次 */
        private const val AREA_SIZE_SP = 11f

        /** 文字描边线宽（dp）。与企业标签、地块名称的 1.6dp 同源，观感一致 */
        private const val TEXT_STROKE_DP = 1.6f

        /**
         * 标记边长（dp）。企业圆点半径 4.6~6.8dp（直径 9.2~13.6dp），
         * 方块取 12dp 与之同量级 —— 不抢眼，但在地图上找得到。
         */
        private const val MARKER_SIZE_DP = 12f

        /** 标记圆角（dp）。12dp 的方块给 2.5dp 圆角，是"厂房"不是"色块" */
        private const val MARKER_CORNER_DP = 2.5f

        /** 标记描边（dp）。比企业圆点的 1.2dp 略粗，方块比圆更容易被影像吞掉 */
        private const val MARKER_STROKE_DP = 1.4f

        /**
         * 标记边缘到标签边缘的间距（dp）。
         * 4dp 是"贴得近但不粘"的距离：再小标签会像长在方块上，
         * 再大有几个挨得近的点就没有可用候选位了。
         */
        private const val LABEL_GAP_DP = 4f

        /** 标签内边距（dp）：横向。与地块名称的 labelPadH 同值 */
        private const val LABEL_PAD_H_DP = 5f

        /** 标签内边距（dp）：纵向 */
        private const val LABEL_PAD_V_DP = 2f

        /** 两行之间的行距（dp）。与企业标签、地块名称同值 */
        private const val LINE_SPACING_DP = 1.5f

        /** 标签之间的最小间隙（dp），与地块名称同值 */
        private const val LABEL_PAD_DP = 2f

        /**
         * 标签准许的重叠比例，取与企业标签、地块名称同一个数
         * （[EnterpriseOverlay.OVERLAP_RATIO]）。同一个观感口径只留一处定义。
         */
        private const val OVERLAP_RATIO = EnterpriseOverlay.OVERLAP_RATIO

        /** 标签至少要在屏幕内露出的面积比例，低于此值就不画（同口径） */
        private const val VISIBLE_MIN_RATIO = EnterpriseOverlay.VISIBLE_MIN_RATIO

        /** 标记视野剔除余量（px） */
        private const val CULL_MARGIN_PX = 64f

        /**
         * 8 个候选位，顺序即优先级：**下 / 上 / 右 / 左 / 四斜角**。
         *
         * 「下」排第一是因为标签块是横向的两行（宽 > 高），压在点下方时
         * 遮挡自家标记的概率最低、也不容易被上方的其他点压住。
         * 四斜角放最后：斜位在密集区容易与邻居的正位撞上，先试完正位再退而求其次。
         */
        private val CAND_DX = floatArrayOf(0f, 0f, 1f, -1f, 1f, -1f, 1f, -1f)
        private val CAND_DY = floatArrayOf(1f, -1f, 0f, 0f, 1f, 1f, -1f, -1f)
    }

    // ------------------------------------------------------------------
    // 对外只读状态（供长按指北针的校准读数使用）
    // ------------------------------------------------------------------

    /** 数据里的闲置厂房总数 */
    var factoryCount: Int = 0
        private set

    /** 本次绘制落在视野内的**标记**数 */
    var visibleCount: Int = 0
        private set

    /** 本次实际写出标签的处数 */
    var labelCount: Int = 0
        private set

    /** 标记画了、标签没写的处数（撞位或出屏） */
    var hiddenCount: Int = 0
        private set

    /**
     * 图层开关（由显示控制浮层的「闲置厂房」行控制，默认关）。
     * 关掉时本层**整层不画**、四个计数一并归零。
     *
     * 与「闲置地块」互斥，但**互斥是在 MainActivity 里做的**（一个 LayerMode 三态），
     * 本层只管自己画不画 —— 图层不该知道别的图层存不存在。
     */
    var visible: Boolean = false

    // ------------------------------------------------------------------
    // 画笔
    // ------------------------------------------------------------------

    private val metrics = context.resources.displayMetrics
    private val density = metrics.density

    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, metrics)

    /** 标记填充：珊瑚橙压到 70%，与企业圆点同一个透明度档 —— 让影像透出来 */
    private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.idle_factory_fill)
    }

    /** 标记描边：不透明珊瑚橙。压到 100% 才能把方块从深色影像里剥出来 */
    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(MARKER_STROKE_DP)
        color = ContextCompat.getColor(context, R.color.idle_factory_line)
    }

    /**
     * 名称基准笔。`Paint()` 的默认 textSize 是 **12px 不是 12sp**，漏设会让
     * `measureText` 与真实落笔对不上 —— [RoadOverlay] 踩过这个坑，
     * 这里沿用它的"基准笔 + 拷贝构造"写法，避免再踩一次。
     */
    private val nameBasePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(NAME_SIZE_SP)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    /** 面积基准笔：小一档、常规字重 */
    private val areaBasePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(AREA_SIZE_SP)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    /** 文字描边（深灰，与企业标签同色）。投影只挂在这两支上 */
    private val nameStrokePaint = Paint(nameBasePaint).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(TEXT_STROKE_DP)
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.label_outline)
        setShadowLayer(dp(TextShadow.RADIUS_DP), 0f, dp(TextShadow.DY_DP), TextShadow.color)
    }

    private val areaStrokePaint = Paint(areaBasePaint).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(TEXT_STROKE_DP)
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.label_outline)
        setShadowLayer(dp(TextShadow.RADIUS_DP), 0f, dp(TextShadow.DY_DP), TextShadow.color)
    }

    /** 名称行：亮珊瑚橙 */
    private val nameFillPaint = Paint(nameBasePaint).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.idle_factory_text)
    }

    /** 面积行：更浅一档的珊瑚橙 —— 与名称行拉开主次 */
    private val areaFillPaint = Paint(areaBasePaint).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.idle_factory_text_sub)
    }

    // ------------------------------------------------------------------
    // 绘制期复用对象（每帧重建的是内容，不是容器）
    // ------------------------------------------------------------------

    private val pt = Point()
    private val rect = RectF()
    private val placedBoxes = ArrayList<RectF>(32)

    /** 各处标记的屏幕坐标与"位置是否可用"标记，下标与 [ordered] 对齐 */
    private var anchorX = FloatArray(32)
    private var anchorY = FloatArray(32)
    private var anchorOk = BooleanArray(32)

    /** 按面积降序的绘制/命名顺序（标签让位时大面积优先） */
    private val ordered = ArrayList<IdleFactory>(32)

    /** 上一次的数据源实例，用于判断要不要重排（闲置厂房是静态资产，一次即可） */
    private var lastSource: List<IdleFactory>? = null

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        // 关掉时整层不画，计数一并归零 —— 免得读数里留着上一档的旧值
        if (!visible) {
            factoryCount = 0
            visibleCount = 0
            labelCount = 0
            hiddenCount = 0
            return
        }

        val src = dataProvider()
        factoryCount = src.size
        if (src.isEmpty()) {
            visibleCount = 0
            labelCount = 0
            hiddenCount = 0
            return
        }

        // 数据源换了实例（或长度变了）才重排：闲置厂房是静态资产，正常只走一次
        if (src !== lastSource || ordered.size != src.size) {
            lastSource = src
            ordered.clear()
            ordered.addAll(src)
            // 面积大的先挑位置 —— 挤不下时丢掉的是小厂房标签，符合"大厂房更该被看见"
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

        // ---- 第一遍：画全部标记 ----
        // 与标签分两遍：否则先画的标签会被后画标记盖住一角。
        // 标记在这里**一个不漏**（只要能投影、在视野内），标签挤不下只影响第二遍。
        val half = dp(MARKER_SIZE_DP) / 2f
        val corner = dp(MARKER_CORNER_DP)
        for (i in ordered.indices) {
            val f = ordered[i]
            if (!f.hasCoord) {
                anchorOk[i] = false
                continue
            }
            projection.toPixels(f.point, pt)
            val x = pt.x.toFloat()
            val y = pt.y.toFloat()
            if (x < -CULL_MARGIN_PX || x > w + CULL_MARGIN_PX ||
                y < -CULL_MARGIN_PX || y > h + CULL_MARGIN_PX
            ) {
                anchorOk[i] = false
                continue
            }
            anchorX[i] = x
            anchorY[i] = y
            anchorOk[i] = true

            rect.set(x - half, y - half, x + half, y + half)
            canvas.drawRoundRect(rect, corner, corner, markerFillPaint)
            canvas.drawRoundRect(rect, corner, corner, markerStrokePaint)
            visibleCount++
        }

        if (visibleCount == 0) return

        // ---- 第二遍：逐个贴两行标签 ----
        placedBoxes.clear()
        val padH = dp(LABEL_PAD_H_DP)
        val padV = dp(LABEL_PAD_V_DP)
        val lineSpacing = dp(LINE_SPACING_DP)
        val pad = dp(LABEL_PAD_DP)
        val reach = half + dp(LABEL_GAP_DP)

        for (i in ordered.indices) {
            if (!anchorOk[i]) continue
            val f = ordered[i]
            val nameCount = f.nameLines.size
            val lineCount = nameCount + f.areaLines.size
            if (lineCount == 0) continue

            // 量宽量高：逐行用自己的画笔（名称 12sp / 面积 11sp，字高不等）
            var maxW = 0f
            var totalH = 0f
            for (k in 0 until lineCount) {
                val p = fillPaintAt(k, nameCount)
                val tw = p.measureText(textAt(f, k, nameCount))
                if (tw > maxW) maxW = tw
                val fm = p.fontMetrics
                totalH += fm.descent - fm.ascent
            }
            totalH += lineSpacing * (lineCount - 1)

            val boxW = maxW + padH * 2
            val boxH = totalH + padV * 2
            val x = anchorX[i]
            val y = anchorY[i]

            var placed = false
            for (c in CAND_DX.indices) {
                val cx = x + CAND_DX[c] * (reach + boxW / 2f)
                val cy = y + CAND_DY[c] * (reach + boxH / 2f)
                rect.set(cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy + boxH / 2f)
                // 判据用的是**外扩后的框**：标签之间留 2dp 视觉缝
                rect.set(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)

                if (!enoughVisible(rect, w, h) || collidesWithPlaced(rect)) continue

                placedBoxes.add(RectF(rect))
                drawLabel(canvas, f, nameCount, lineCount, cx, cy, totalH, lineSpacing)
                placed = true
                break
            }
            if (placed) labelCount++ else hiddenCount++
        }

        Log.d(
            TAG,
            "闲置厂房：${factoryCount} 处 · 视野内 ${visibleCount} · 标签 ${labelCount} · 未标 ${hiddenCount}"
        )
    }

    /** 第 k 行该用哪支填充笔：前 [nameCount] 行是名称，其余是面积 */
    private fun fillPaintAt(k: Int, nameCount: Int): Paint =
        if (k < nameCount) nameFillPaint else areaFillPaint

    /** 第 k 行的文字 */
    private fun textAt(f: IdleFactory, k: Int, nameCount: Int): String =
        if (k < nameCount) f.nameLines[k] else f.areaLines[k - nameCount]

    /** 以 (cx, cy) 为中心落两行（或 3~4 行）文字，整体垂直居中 */
    private fun drawLabel(
        canvas: Canvas,
        f: IdleFactory,
        nameCount: Int,
        lineCount: Int,
        cx: Float,
        cy: Float,
        totalH: Float,
        lineSpacing: Float
    ) {
        var top = cy - totalH / 2f
        for (k in 0 until lineCount) {
            val isName = k < nameCount
            val fill = if (isName) nameFillPaint else areaFillPaint
            val stroke = if (isName) nameStrokePaint else areaStrokePaint
            val fm = fill.fontMetrics
            val baseline = top - fm.ascent
            val text = textAt(f, k, nameCount)
            canvas.drawText(text, cx, baseline, stroke)
            canvas.drawText(text, cx, baseline, fill)
            top += (fm.descent - fm.ascent) + lineSpacing
        }
    }

    /** 标签框与屏幕的相交面积是否达标（口径见 [VISIBLE_MIN_RATIO]） */
    private fun enoughVisible(r: RectF, w: Int, h: Int): Boolean {
        val l = if (r.left > 0f) r.left else 0f
        val t = if (r.top > 0f) r.top else 0f
        val rr = if (r.right < w) r.right else w.toFloat()
        val bb = if (r.bottom < h) r.bottom else h.toFloat()
        if (rr <= l || bb <= t) return false
        val inter = (rr - l) * (bb - t)
        return inter >= r.width() * r.height() * VISIBLE_MIN_RATIO
    }

    /** 候选标签框是否与已放置的相撞（重叠面积 > 较小框 × [OVERLAP_RATIO]） */
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
            val boxArea = box.width() * box.height()
            val smaller = if (area < boxArea) area else boxArea
            if (inter > smaller * OVERLAP_RATIO) return true
        }
        return false
    }
}
