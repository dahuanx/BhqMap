package com.bianhequ.bhqmap.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import com.bianhequ.bhqmap.data.Road
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 道路名称层（v0.7.16 建，v0.7.17 加显示门槛）。
 *
 * 洲哥要求原文：「不显示线条，只显示道路名称，黑色字、白边、带阴影，
 * 道路名显示在路线上，文字方向与路线平行」。
 *
 * v0.7.17 追加一条：**路名在 z ≥ 17 才显示**（常量 [MIN_ZOOM]）。
 * 全貌约 z13.6、视野 3.3 km 宽，那一档是"全园浏览"，路名属细读信息、不该占版面；
 * z≥17 时视野收窄到约 300 m，屏上只剩一两条路，路名这时才真正有用。
 * 低于门槛时**整层不画**（一条都不出），不是在原逻辑上做减法。
 *
 * ------------------------------------------------------------------
 * 一、为什么只画名、不画线
 * ------------------------------------------------------------------
 * 底图是卫星影像，园区路网在影像上本来就看得见（水泥路面的走向清晰）。
 * 再叠一层彩色线条只会把影像盖住，反而丢信息。真正缺的是"这条路叫什么"——
 * 规划路网尚未立牌子，路名目前只存在于 KML 里。所以本层的全部职责就是：
 * 把 13 个路名，各自摆到自己那条路的中间，顺着路的走向写出来。
 *
 * ------------------------------------------------------------------
 * 二、"文字与路线平行"怎么实现
 * ------------------------------------------------------------------
 * 1. 把该条 LineString 的所有顶点投影到屏幕，逐段累加得到**屏幕折线长度**；
 * 2. 取长度中点（不是顶点中点 —— 规划十路 22 个顶点疏密不均，顶点中点会偏向密集侧）；
 * 3. 用中点所在那一段的方向算偏航角 `atan2(dy, dx)`；
 * 4. **把角度规范到 [-90°, 90°]**：超出就加/减 180°。
 *    这一步不能省 —— 否则双向道路中"从东往西"画的那条，文字会整个倒过来，
 *    手机得翻过来才读得出。规范之后，任何走向的文字都是"从左往右读"。
 *    注意是**转 180°**而不是水平镜像：镜像出来的字是反的（像照镜子），转过去才是正的。
 * 5. `canvas.rotate(angle, x, y)` 后按普通水平文字画，基线做垂直居中修正。
 *
 * ------------------------------------------------------------------
 * 三、为什么不用企业标签那套"停稳重排 + 淡入淡出"
 * ------------------------------------------------------------------
 * [EnterpriseOverlay] 那套机制是为"标签会随视野涌进涌出、互相抢位"设计的。
 * 道路名不一样：13 条路是**静态地物**，位置只由地图变换决定，
 * 且放置算法是纯函数（同样输入必得同样输出）—— 拖动地图时路名像印在影像上一样跟走，
 * 既不会跳也不会闪，所以不需要延迟、不需要过渡动画。
 * 每次 draw 重算一遍：13 条 × 平均 4 个顶点 = 50 次投影，
 * 相对 osmdroid 每帧自己画的瓦片，开销可以忽略。
 *
 * ------------------------------------------------------------------
 * 四、避让
 * ------------------------------------------------------------------
 * 路名之间两两不许压 —— 园区路网是网格，交叉口附近的路名容易叠在一起，
 * 叠上就是两串字糊成一团，谁也读不出。
 * 判据用**旋转后文字的外接矩形**（AABB）+ 3dp 余量，比有向矩形（OBB）宽松，
 * 但这里够用：路名短（3~5 字），旋转后 AABB 只比真实占位大一点点。
 * 放不下就沿路线前后挪（[SLOT_FRACTIONS]），都放不下才放弃该路名 ——
 * 宁可不标，也不标个糊的。
 *
 * 与企业标签的层间关系：本层加在 overlays 的**最底部**（先画），
 * 企业点与名称压在其上。道路是骨架、企业是内容，内容该在上层 ——
 * 也因此本层**不参与**企业的避让计算，两者不做双向避让。
 */
class RoadOverlay(
    private val context: Context,
    private val dataProvider: () -> List<Road>
) : Overlay() {

    companion object {
        /**
         * 路名字号（sp）。
         *
         * 取 11sp 是**离线仿真算出来的**，不是拍的：园区里"街"多为 2~4 字，
         * 全貌（3.03 m/px）下每字占 33px 实地约 100 m，5 字 = 165px ≈ 500 m。
         * 而园区南北向支路（规划十一~十八街）屏幕长度只有 118~290px ——
         * 12sp 时有 6 条因"标签比路还长"被略过，11sp 时降到 4 条（多出
         * 规划十四街、规划十七街两条）。见 `设计交付/v0.7.16-路名布局仿真.svg`。
         *
         * 仍比企业普通名称（12sp）小一档：路名是背景注记，不该比企业名更抢眼；
         * 但黑字 + 白边 + 投影的组合对比度远高于卡其白企业名，11sp 读起来不吃力。
         */
        private const val TEXT_SIZE_SP = 11f

        /**
         * 白边线宽（dp）。Paint 的 STROKE 是**笔画中心线**，实际向外扩 strokeWidth/2，
         * 所以视觉白边 ≈ 1.2dp。1.2dp 是"深色影像上看得见、又不吃掉黑字笔画"的下限：
         * 再细（0.8dp，与企业标签的灰边同档）在树影和水泥地上就断成虚线。
         */
        private const val STROKE_WIDTH_DP = 2.4f

        /**
         * 路名之间的避让余量（dp）。取 3dp 是让两个路名看起来"分得开"而不是"刚好不碰"，
         * 与 [EnterpriseOverlay] 的 55% 重叠容差是两种思路：那里允许压、这里不许碰。
         */
        private const val AVOID_PAD_DP = 3f

        /**
         * 标签宽度 / 路线屏幕长度的上限。超过就认为**这条路的屏幕长度衬不下这个路名**，
         * 整条略过。0.86 意味着标签最多占满路的 86%，两侧各留一点，观感才像"写在路上"
         * 而不是"顶满整条路"。
         *
         * 这条判据同时也是"缩远了自动减负"的开关：缩到 9 m/px 时，
         * 430 m 的支路只剩 47px，而"规划十八街"5 个字约 66px，判为放不下 ——
         * 于是远景只留主干道的名字，正合地图惯例。
         */
        private const val MAX_LABEL_ROUTE_RATIO = 0.86f

        /**
         * 路名开始显示的最小 zoom 级别（v0.7.17，洲哥 2026-09-23 定）。
         *
         * `z < MIN_ZOOM` 时本层**整层不画**，一个路名都不出。
         * 全貌约 z13.6（3.03 m/px）、视野 3.3 km 宽，13 条路里有 8 条能出名字 ——
         * 那是"全园浏览"的档位，路名属于细读用的细节信息，不该在这一档占版面。
         * z ≥ 17 时视野收窄到约 300 m，屏上只剩一两条路，路名这时才真正有用。
         *
         * 为什么单位用 zoom 而不是 m/px（本工程分级阈值历来用 m/px）：
         * 洲哥是照 App 里长按指北针读到的 z 值下的指令，直接用同一个量判断，
         * 中间不做换算、不引入换算是非。同一工程里 [EnterpriseOverlay.FORCE_ALL_ZOOM]
         * （17.5）、`KEY_GOV_ZOOM_MIN/MAX`（14.5/15.5）也都是 zoom 常量，口径一致。
         */
        const val MIN_ZOOM = 17.0

        /** 视野剔除的额外余量（px）：文字可能从屏幕外"伸"进可视区 */
        private const val CULL_MARGIN_PX = 220f

        /**
         * 标签中心距屏幕边缘的最小距离（px）。
         *
         * 离线仿真抓到的边界情况：**放大后，路的中点常常跑到屏幕外**——
         * 路本身与视野相交（所以没被上面那条剔除掉），但中点所在的屏幕坐标
         * 已经是负数（放大 2 倍时规划八街的中点在 -188,-32）。
         * 照画就是在屏外白画一遍，用户看不到名字还以为路没标。
         * 所以候选位的要求是"中心落在屏内"，不满足就换下一个位置。
         * 24px 只是不让字贴边，不要求整条标签入屏（那是 v0.6 教训，见
         * [EnterpriseOverlay.VISIBLE_MIN_RATIO] 的注释）。
         */
        private const val SCREEN_MARGIN_PX = 24f

        /**
         * 沿路线搜索放置位置的候选比例（起点 → 终点）。
         * 首选正中（0.5，最"写在路上"），冲突时向两侧交替挪，最远挪到 20%/80%。
         */
        private val SLOT_FRACTIONS = floatArrayOf(
            0.5f, 0.42f, 0.58f, 0.35f, 0.65f, 0.28f, 0.72f, 0.2f, 0.8f
        )
    }

    // ------------------------------------------------------------------
    // 对外只读状态（供长按指北针的校准读数使用）
    // ------------------------------------------------------------------

    /** 本次绘制参与竞争的路线数（已过视野剔除） */
    var roadCount: Int = 0
        private set

    /** 本次实际写出名称的路线数 */
    var labelCount: Int = 0
        private set

    /** 因长度不够或避让失败而没写出名称的路线数 */
    var droppedCount: Int = 0
        private set

    // ------------------------------------------------------------------
    // 画笔
    // ------------------------------------------------------------------

    private val metrics = context.resources.displayMetrics
    private val density = metrics.density

    private fun dp(v: Float) = v * density

    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, metrics)

    /**
     * 基准笔：只给下面两支笔提供**字号 / 对齐 / 字重**，自己不参与绘制。
     *
     * 三支笔必须共享同一套字体度量 —— 否则 `measureText` 量出来的宽度
     * 与真正落笔的那支笔对不上，避让框会整体算错。
     * 这个坑很隐蔽：`Paint()` 的默认 textSize 是 **12px**（不是 12sp，也不随屏幕变），
     * 忘了拷贝属性时字号"看起来还行"，量出来的宽度却只有实际的 1/3。
     */
    private val basePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(TEXT_SIZE_SP)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    /** 白边线宽，字号变了这里跟着走 */
    private val strokeWidth = dp(STROKE_WIDTH_DP)

    /**
     * 白边笔：**带投影**。绘制顺序「先描边后填充」，所以阴影落在白边外圈，
     * 形成 底图 ← 投影 ← 白边 ← 黑字 的四层，浅色影像（厂房顶、水泥地）上
     * 靠这圈投影把字剥出来，深色影像上靠白边 —— 两种底图都立得住。
     *
     * 投影参数复用 [TextShadow]（与企业标签、比例尺同源）：三处自绘文字参数一致，
     * 日后调整也只有一处。注意阴影**只挂在描边笔上**，填充笔不带 ——
     * 黑字自己的黑投影叠在黑字下面等于白画。
     */
    private val strokePaint: Paint = Paint(basePaint).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = this@RoadOverlay.strokeWidth
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(
            dp(TextShadow.RADIUS_DP), 0f, dp(TextShadow.DY_DP), TextShadow.color
        )
    }

    /** 黑字面。字号/对齐/字重同上（拷贝基准笔），只换颜色与样式 */
    private val fillPaint: Paint = Paint(basePaint).apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.FILL
    }

    /** 投影用的复用 Point，避免每个顶点都 new 一个 */
    private val pt = Point()

    /** 放置复用的矩形，减少每帧分配 */
    private val rect = RectF()

    /** [pointAt] 的输出缓冲：[0]=x [1]=y [2]=规范后的角度 */
    private val slotOut = FloatArray(3)

    // ------------------------------------------------------------------
    // 每帧的临时容器（draw 内重建，均为定长小对象，停图时不产生分配）
    // ------------------------------------------------------------------

    /** 一条路线投影到屏幕后的几何，一次 draw 生命周期内有效 */
    private class ScreenPath(val name: String, val n: Int) {
        val xs = FloatArray(n)
        val ys = FloatArray(n)

        /** 起点到第 i 个顶点的累计屏幕长度 */
        val cum = FloatArray(n)

        /** 折线总长（px） */
        var len = 0f

        var minX = 0f
        var minY = 0f
        var maxX = 0f
        var maxY = 0f
    }

    private val paths = ArrayList<ScreenPath>(24)

    /** 已放置的路名：位置 + 角度 + 文本 + 占位矩形（按放置顺序绘制） */
    private class PlacedLabel(
        val text: String,
        val x: Float,
        val y: Float,
        val angle: Float,
        val box: RectF
    )

    private val placed = ArrayList<PlacedLabel>(24)

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        // v0.7.17：近距离才出 —— z 不到门槛，整层不画（三个计数一并归零，
        // 免得读数里还留着上一档的旧值，现场会误判成"路名没生效"）
        if (mapView.zoomLevelDouble < MIN_ZOOM) {
            roadCount = 0
            labelCount = 0
            droppedCount = 0
            return
        }

        val roads = dataProvider()
        if (roads.isEmpty()) {
            roadCount = 0
            labelCount = 0
            droppedCount = 0
            return
        }

        val w = mapView.width
        val h = mapView.height
        if (w <= 0 || h <= 0) return
        val projection = mapView.projection

        // ---- 1) 投影 + 量长度 + 视野剔除 ----
        paths.clear()
        for (road in roads) {
            val n = road.points.size
            if (n < 2) continue

            val p = ScreenPath(road.name, n)
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE

            for (i in 0 until n) {
                projection.toPixels(road.points[i], pt)
                val x = pt.x.toFloat()
                val y = pt.y.toFloat()
                p.xs[i] = x
                p.ys[i] = y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }

            p.cum[0] = 0f
            for (i in 1 until n) {
                p.cum[i] = p.cum[i - 1] + hypot(p.xs[i] - p.xs[i - 1], p.ys[i] - p.ys[i - 1])
            }
            p.len = p.cum[n - 1]
            // 退化成一个点（或几乎如此）的路线没有"走向"，画不出平行文字
            if (p.len <= 1f) continue

            p.minX = minX
            p.minY = minY
            p.maxX = maxX
            p.maxY = maxY

            // 整条路都在视野外（留一点余量，避免贴边时一闪一闪）
            if (maxX < -CULL_MARGIN_PX || minX > w + CULL_MARGIN_PX ||
                maxY < -CULL_MARGIN_PX || minY > h + CULL_MARGIN_PX
            ) continue

            paths.add(p)
        }

        roadCount = paths.size
        if (paths.isEmpty()) {
            labelCount = 0
            droppedCount = 0
            return
        }

        // ---- 2) 长路优先挑位（主干道名字更该留下；同等长度保持 KML 原序，结果稳定）----
        paths.sortByDescending { it.len }

        // ---- 3) 逐条挑位 ----
        // 清空复用对象：RectF 只在这里新建 N 个（N = 实际放下的路名数，≤13）
        placed.clear()
        val fm = fillPaint.fontMetrics
        // 文字视觉半高（用于旋转后的 AABB 估算与基线居中修正）
        val halfTextH = (fm.descent - fm.ascent) / 2f
        val baselineFix = -(fm.ascent + fm.descent) / 2f

        for (p in paths) {
            val textW = fillPaint.measureText(p.name)
            // 路太短，衬不下这个路名
            if (textW + strokeWidth > p.len * MAX_LABEL_ROUTE_RATIO) {
                droppedCount++
                continue
            }

            var picked: PlacedLabel? = null
            for (f in SLOT_FRACTIONS) {
                pointAt(p, f)
                val x = slotOut[0]
                val y = slotOut[1]
                // 标签中心必须落在屏内（留 [SCREEN_MARGIN_PX] 余量）。
                // 放大后路的中点常跑到屏外，照画就是白画一遍。
                if (x < SCREEN_MARGIN_PX || x > w - SCREEN_MARGIN_PX ||
                    y < SCREEN_MARGIN_PX || y > h - SCREEN_MARGIN_PX
                ) continue
                val angle = slotOut[2]
                aabb(x, y, angle, textW, halfTextH)
                if (!collidesWithPlaced(rect)) {
                    picked = PlacedLabel(p.name, x, y, angle, RectF(rect))
                    break
                }
            }

            if (picked == null) {
                droppedCount++
            } else {
                placed.add(picked)
            }
        }

        labelCount = placed.size

        // ---- 4) 落笔 ----
        // 按放置顺序画：先挑到位的先画，后画的压在上层。因为互不重叠，层次无所谓。
        for (pl in placed) {
            val saved = canvas.save()
            canvas.rotate(pl.angle, pl.x, pl.y)
            val baseline = pl.y + baselineFix
            canvas.drawText(pl.text, pl.x, baseline, strokePaint)
            canvas.drawText(pl.text, pl.x, baseline, fillPaint)
            canvas.restoreToCount(saved)
        }
    }

    /**
     * 求路线上比例 [f]（0=起点，1=终点）处的屏幕位置与该处切线方向。
     *
     * 结果写入 [slotOut]：`[0]=x  [1]=y  [2]=角度(度，已规范到 [-90,90])`。
     * 不返回对象是为了避免每帧几十次小分配。
     */
    private fun pointAt(p: ScreenPath, f: Float) {
        val target = f * p.len

        // 线性扫过累计长度表。顶点数最多 22，二分不值得
        var i = 1
        while (i < p.n - 1 && p.cum[i] < target) i++

        val segLen = p.cum[i] - p.cum[i - 1]
        val u = if (segLen <= 0f) 0f else (target - p.cum[i - 1]) / segLen

        val x0 = p.xs[i - 1]
        val y0 = p.ys[i - 1]
        val dx = p.xs[i] - x0
        val dy = p.ys[i] - y0

        slotOut[0] = x0 + dx * u
        slotOut[1] = y0 + dy * u

        // 屏幕坐标 y 向下；转成"从左往右读"的角度
        var ang = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (ang > 90f) ang -= 180f
        else if (ang < -90f) ang += 180f
        slotOut[2] = ang
    }

    /**
     * 旋转后文字的外接矩形（AABB），外扩 [AVOID_PAD_DP] 余量，写回 [rect]。
     *
     * 旋转矩形的 AABB 半边 = (半宽, 半高) 经 |cos|/|sin| 混合后的值 ——
     * 角度越大越保守，但路名只有 3~5 字，多算出来的那点面积不影响判断。
     */
    private fun aabb(cx: Float, cy: Float, angleDeg: Float, textW: Float, halfTextH: Float) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val c = abs(cos(rad)).toFloat()
        val s = abs(sin(rad)).toFloat()
        val hw = textW / 2f + strokeWidth
        val hh = halfTextH + strokeWidth
        val ex = hw * c + hh * s + dp(AVOID_PAD_DP)
        val ey = hw * s + hh * c + dp(AVOID_PAD_DP)
        rect.set(cx - ex, cy - ey, cx + ex, cy + ey)
    }

    /** 候选位是否与已放置的路名相撞 */
    private fun collidesWithPlaced(candidate: RectF): Boolean {
        for (pl in placed) {
            if (RectF.intersects(pl.box, candidate)) return true
        }
        return false
    }
}
