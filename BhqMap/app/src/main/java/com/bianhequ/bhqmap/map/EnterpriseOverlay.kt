package com.bianhequ.bhqmap.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import com.bianhequ.bhqmap.R
import com.bianhequ.bhqmap.data.Enterprise
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 企业点位与名称标签层。
 *
 * 为什么自绘而不是用 osmdroid 的 Marker：
 *   1. Marker 每个实例都是一个真实的 Android View，会被加进 MapView 的 child 列表。
 *      几百家企业在低倍率下会直接卡死甚至 OOM。
 *   2. Marker 之间没有任何避让机制，名称会糊成一团。
 *   3. 分级显隐需要按"米/像素"判断，Marker 体系里没有这个位置。
 *   自绘 Overlay 只画屏幕内的元素，且完全掌控绘制顺序与文本布局。
 *
 * v0.4 变更（洲哥 2026-09-22 反馈）：
 *   1. 重点企业字号加大（14sp，其余 12sp）；
 *   2. 协作区项目全称不再截断省略——标签允许折成两行；
 *   3. 圆点全部改半透明，四级分类色：协作区沙金 / 重点亮橄榄 / 规上卡其白 / 普通军绿。
 *
 * v0.5 变更（洲哥 2026-09-22 反馈）：
 *   1. 行政/公共事业单位的点位改画黄色五角星（[dotStyle] 的 star 分支），
 *      标签字号 16sp —— 比重点企业大 2；
 *   2. 文字投影加重（[SHADOW_ALPHA] / [SHADOW_DY_DP] / [SHADOW_RADIUS_DP]）；
 *   3. 避让放宽：细长条状的轻微重叠不再算冲突（见 [collides]）；
 *   4. 四级圆点填充透明度统一到 70%（此前三档 80% 一档 70%）。
 *
 * v0.6 变更：
 *   1. 修复远档筛选漏掉 [Enterprise.isGov]（此前全貌下行政单位根本进不了候选集）；
 *   2. 无坐标条目（[Enterprise.hasCoord] = false）跳过，不再因坐标为 NaN 而误入布局。
 *
 * v0.6.1 变更（洲哥 2026-09-22 反馈：改避让规则）：
 *   1. 优先级阶梯提到唯一入口 [Enterprise.priority]：项目 > 重点 > 规上 > 普通
 *      （行政单位仍在其上）。占位顺序与叠压顺序都由它派生；
 *   2. 允许重叠：冲突判据由"绝对侵入量"改为"**重叠面积 ≤ 较小标签面积的 [OVERLAP_RATIO]**"；
 *   3. 标签绘制顺序反转为"低优先级先画、高优先级压在上面"。
 *
 * v0.7.2 变更（洲哥 2026-09-22 反馈：撤除两个按钮）：
 *   去掉「显示全部企业」开关与「未上图企业」入口 —— 连同 [showAll] 字段、
 *   按钮、弹窗、读数里的"全量"标记一并删除，不留死代码。
 *   于是本类回到单一显隐规则：**只按 [LodConfig] 分档**，没有任何运行时开关能绕过它。
 *   无坐标条目仍保留在数据层（可被搜索、档案页可查看），只是不在地图上落点。
 *
 * v0.7.11 变更（洲哥 2026-09-23：全显档"名称尽量不重叠" + 三处修复）：
 *   1. **修复网格别名 Bug（重要）**：`insert()` 此前把循环里**复用的同一个 RectF**
 *      塞进碰撞网格，网格里所有条目都指向它 —— 每条候选位写入后，之前所有已放置
 *      标签的记录都被改写成"当前候选位"。于是碰撞判定退化成"格子占用"（候选标签
 *      的格子与任何已占格子重合即否决），而不是设计中的"重叠面积 ≤ 较小框 55%"。
 *      离线复现：这一 Bug 让中倍率视野的标签数少了一半左右
 *      （z=15.5 园区中心 7 条 vs 应有 14 条）。现改为存**副本**；
 *   2. **全显标签内部择优（A）**：豁免标签不再无脑取 0 号位，而是在 8 个候选位里
 *      选"与已放标签总重叠面积最小"的那个 —— 消除"两个全显名完全盖住"（离线实测
 *      严重压字 100%/63% → 0）；
 *   3. **全显标签当硬障碍（B）**：普通标签与"全显标签"的重叠容忍从 [OVERLAP_RATIO]
 *      （55%）收紧到 [EXEMPT_BLOCK_RATIO]（8%）—— 不许普通名压住全显名。
 *      A+B 实测：重叠对 11→5 / 7→1 / 8→2，重叠面积 7.4%→1.6%。
 *
 * v0.7.27 变更（洲哥 2026-09-24 要求：加闲置地块图层）：新增 [namesHidden] **名称总闸** ——
 *   闲置地块图层打开时置 true，全部企业名称撤下、**圆点照旧**（地块图层要回答
 *   "哪块地空着"，周边落着哪些企业是判断配套的现场信息，圆点不能撤）。
 *   与三个样式开关（[showCoop] / [showPark] / [showAbove]）不是一回事：
 *   那三个管"怎么标"，这个闸管"标不标"。落点在 [layoutLabels] 的 `dots.add()` 之后，
 *   与 v0.7.24 的中间档筛选同一个位置，保证"圆点一个不少、只撤文字"。
 *
 * v0.7.26 变更（洲哥 2026-09-24 反馈："重点企业里有字体没加大，比如实木家居产业园"）：
 *   **双身份条目改为按重点档取字号** —— 根因：台账里 5 条同时标了「重点企业」与
 *   「协作区项目」（实木家居产业园一~四期、鼎中冷链），它们命中 [Enterprise.isCoop]
 *   分支、被当普通项目画成 12sp，看上去"重点企业却没加大"。
 *   规则修正：**字号只表达重要度（与开关无关），开关只决定文字与颜色** ——
 *   项目若同时是重点企业：关 = 简称 14sp 卡其白加粗（[textKey]，与重点企业一致）、
 *   开 = 全称 14sp 加粗 + 项目色（[textCoopKey] / [textParkKey]）。
 *   纯项目仍是 12sp 双态，v0.7.15 的"关＝与普通一字不差"语义**不动**。
 *   注：描点样式与 [Enterprise.priority] 均未改（项目圆点、3 级优先级照旧）。
 *
 * v0.7.24 变更（洲哥 2026-09-24 要求）：**中间档语义由"豁免"改为"筛选"** ——
 *   洲哥装 v0.7.23 后反馈"好像没啥变化"，核对后确认上一轮把需求理解错了：
 *   他要的是"14.5 < z < 15.5 时**只显示**重点和行政"，而 v0.7.10 ~ v0.7.23
 *   实现的是"全部企业照常显示、只给重点/行政豁免避让"。现改为：
 *   本档候选**照旧全部进池**（保证圆点一个不少），但**只有重点/行政出名称**，
 *   其余企业保留圆点、撤下名称；重点/行政之间走**正常避让**（不再豁免）。
 *   常量改名 FORCE_KEY_ZOOM_MIN/MAX → [KEY_GOV_ZOOM_MIN] / [KEY_GOV_ZOOM_MAX]。
 *
 * v0.7.23 变更（洲哥 2026-09-24 要求）：**两处纯参数调整，算法结构与筛选口径都不动** ——
 *   1. [OVERLAP_RATIO] 0.55 → **0.35**：把"准许的最坏重叠深度"从五成半收到三成半。
 *      离线复现代价极小（全貌 30→28、最密档 53→51，其余各档一家不减）——
 *      砍的是"压得多狠"，不是"能放多少"；
 *   2. [KEY_GOV_ZOOM_MIN] / [KEY_GOV_ZOOM_MAX] 15.0/16.0 → **14.5/15.5**：
 *      "重点+行政全显"档整体下移半级，覆盖"园区全貌 → 看清片区"的过渡段。
 *      本档用 **zoom** 判断，而分档门槛 [LodConfig.MPP_KEY] 用 **m/px**（换算见常量注释）。
 *
 * v0.7.10 变更（洲哥 2026-09-23 要求）：**中间档重点/行政全显** ——
 *   14.5 < zoom < 15.5（开区间，[KEY_GOV_ZOOM_MIN] / [KEY_GOV_ZOOM_MAX]；
 *   原为 15/16，v0.7.23 下移半级）时，
 *   重点企业与行政/事业单位的名称**忽略避让规则全部显示**；规上/普通/项目照常避让。
 *   与 v0.7.7 的 17.5 全显是同一套机制的两个档位，只是豁免范围不同
 *   （一个豁免"重点+行政"，一个豁免"全部"）。
 *
 * v0.7.9 变更（洲哥 2026-09-23 要求回滚）：撤销 v0.7.8 的"协作区项目默认整体隐藏"，
 *   恢复 v0.7.7 行为 —— 项目点位恒显示（圆点按 isCoop 样式），项目开关只决定
 *   标签是否用金色全称样式；远档筛选仍按 `include` 规则，开关开启时项目不受档位限制。
 *   右下角版本号文字保留（v0.7.8 的另一项需求，未在回滚范围内）。
 *
 * v0.7.7 变更（洲哥 2026-09-23 要求）：**近距全显** ——
 *   zoom ≥ [FORCE_ALL_ZOOM]（17.5）时忽略避让规则，视野内企业名称全部显示：
 *   1. 分档筛选短路：所有有坐标的候选全部入列（此时 mpp 极小，分档本已到"全部"档，
 *      这里是双保险）；
 *   2. 避让判定跳过：候选位只看是否在屏内，不做碰撞检测；8 个位都放不下时
 *      兜底用 0 号位（正上方）硬画，标签允许互相重叠 —— 近距离点位稀疏，
 *      实际重叠概率很低，代价可控。
 *
 * v0.7.15 变更（洲哥 2026-09-23 要求）：**三个开关语义统一为"标签样式"** ——
 *   协作区项目 / 园区项目 / 规上企业改为完全一致的显示模式：
 *   关（默认）= 简称、常规 12sp 卡其白，与普通企业一字不差；
 *   开 = 全称、12sp 加粗 + 各自专属色（协作区沙金 / 园区亮橄榄 / 规上湖蓝）。
 *   1. [showCoop] / [showPark] **不再是"显隐"开关** —— v0.7.13 的"关闭即整体
 *      不上图"作废（洲哥原话："这三种的显示模式都一样"）。三个开关现在只决定
 *      "怎么标"，不决定"上不上图"，与 v0.7.14 的 [showAbove] 完全同构；
 *   2. 两类项目**任何倍率都显示**的特例保留 —— 这是 v0.7.9 回滚时洲哥确认过的
 *      结论（项目是重点对象，不该因为缩远就消失），与 v0.7.13 的唯一差别是
 *      开关关掉时"显示简称"而不是"整条消失"；
 *   3. 三个开关默认全关 → 首次安装的观感 = 全图只出简称；
 *   4. 描点样式与开关解耦（项目沙金圆点 / 规上卡其白圆点 / 行政五角星都不变），
 *      [Enterprise.priority] 也不动（项目仍 3 级、规上仍 1 级）；
 *   5. **例外：项目若同时是重点企业**（v0.7.26 补），关 = 简称 14sp 加粗卡其白，
 *      不是 12sp 常规 —— 字号归身份、文字与颜色归开关。
 *
 * v0.7.14 变更（洲哥 2026-09-23 要求）：**规上企业全称模式开关** ——
 *   面板里再加一个「规上企业」开关（[showAbove]），但**语义与两类项目不同**：
 *   项目开关管的是"显隐"，规上开关管的是"标签样式"。理由很硬 ——
 *   规上企业本来就在中/近档正常显示（简称 + 亮橄榄圆点），它不是一个"要不要看"
 *   的图层，只是一个"要不要点名"的强调层。
 *   1. 关（默认）= 与普通企业完全同档：简称 12sp 常规（[textNormal]），显隐仍按
 *      [LodConfig] 分档 —— 也就是说比 v0.7.13 的观感**一个字都没变**；
 *   2. 开 = 全称 12sp 加粗 + 湖蓝（[textAbove]，`label_text_above #5FD0E8`），
 *      与协作区沙金、园区亮橄榄、重点卡其白、行政明黄共处而不混。
 *   3. 开关只改标签文字与颜色，**不改显隐、不改 [Enterprise.priority]**：
 *      规上依旧是 1 级（低于重点 2、项目 3），标注优先级不变；全貌档不出规上
 *      仍与普通企业一致（那是分档的事，与开关无关）。
 *
 * v0.7.13 变更（洲哥 2026-09-23 要求）：**项目类别双开关显隐** ——
 *   台账新增「是否园区项目」列（[Enterprise.isPark]），与协作区项目同属"项目"大类，
 *   规则完全对齐，但**各自独立控制显隐**：
 *   1. 悬浮按钮由"协作区开关"变为"**项目面板**"入口，点击弹窗，内含
 *      「协作区项目」「园区项目」两个开关；
 *   2. 开关语义统一为**显隐**：关闭 = 该类圆点与名称都不上（不占位、不参与避让、
 *      不可点击命中，[FORCE_ALL_ZOOM] 全显档也不越过 —— 显隐是用户的显式选择）；
 *      开启 = 圆点 + 全称标签（协作区沙金 / 园区亮橄榄）；
 *   3. 默认值：协作区项目 **开**（保持 v0.7.9 回滚后的"常显"结论）、
 *      园区项目 **关**（是"需要时调出来"的一层）。
 *   注：本条改变了 [showCoop] 的语义（此前"关"只是回落到简称标签、点恒显示），
 *   现在"关"= 整体隐藏。默认开，故默认观感与 v0.7.12 一致（仅标签由简称变全称）。
 *
 * v0.7.12 变更（洲哥 2026-09-23 定参）：**大幅移动阈值回调** ——
 *   [SMALL_MOVE_SCREEN_FRACTION] 1/2 屏 → **约 1/3 屏**（0.35）、
 *   [SMALL_MOVE_MAX_ZOOM_JUMP] 1 级 → **0.5 级**（即 v0.7.5 原值）。
 *   效果：拖动超过 1/3 屏或缩放跳档超过 0.5 级时立即全量重排，
 *   跟图平移的适用范围收窄，减少"长滑后半屏真空、缩放后标签失配"的情况。
 *   [SETTLE_MS] 600ms 保持不变。
 *
 * v0.7.6 变更（洲哥 2026-09-23 定参）：纯参数调整，算法结构不动 ——
 *   1. [SETTLE_MS] 200ms → **600ms**：停稳 0.6s 才全量重排，运动中跟图时间更长、观感更稳；
 *   2. "小幅移动"阈值放宽：中心位移 1/3 屏 → **1/2 屏**（[SMALL_MOVE_SCREEN_FRACTION]），
 *      zoom 跳档 0.5 → **1 级**（[SMALL_MOVE_MAX_ZOOM_JUMP]）——
 *      跟图平移的适用范围更大，全量重排只在真正的长滑/跨档缩放时触发。
 *
 * v0.7.5 变更（洲哥 2026-09-23 反馈：v0.7.4"跟上一版没啥大区别，滑动和放大缩小时还是会动"）：
 *   根因：v0.7.4 的冻结只认"手指按住"（onTouchEvent DOWN/UP），而滑动观感的大部分是
 *   **惯性滑动（fling）与双击/按钮缩放动画** —— 这些时段没有触摸事件，gestureActive
 *   恒为 false，走的仍是每帧全量重排。改为**不依赖触摸的统一策略**：
 *   视野指纹变化（=地图在动，拖/甩/捏合/缩放动画通吃）→ 只跟图平移；
 *   **停稳 [SETTLE_MS] 后才全量重排**。手势跟踪与 gestureActive 一并移除。
 *
 * v0.7.4 变更（洲哥 2026-09-23 反馈：v0.7.3 有效，但要加上"手势期冻结"）：
 *   手势（触摸按住）期间不跑避让重排，改为**廉价跟图** [followMap]：
 *   圆点逐帧重投影，标签矩形按各自圆点的位移整体平移 —— 标签随图刚体移动，
 *   与 v0.7.3 的粘性位叠加后，滑动全程标签钉死在圆点旁。
 *   两个保护性出口，防止"冻结"变成"脱节"：
 *   1. 手势中大幅移动（中心位移 > 约 1/3 屏）或缩放跳档（zoom 变化 > 0.5）→ 立即全量重排；
 *   2. 抬手（ACTION_UP/CANCEL）→ 强制全量重排；纯点击（未移动）不触发重排。
 *
 * v0.7.3 变更（洲哥 2026-09-23 反馈："滑动/缩放时企业名跳来跳去"）：
 *   1. **粘性候选位**：记住每家企业上次用的候选位编号（[lastSlot]），重排时
 *      **先试上次的位**，放得下就原位不动 —— 滑动中标签与圆点的相对位置锁定，
 *      不再每帧从零抢位导致上下翻转。放不下才按原顺序重找；
 *   2. **淡入淡出**：标签出现/消失带 [FADE_MS] 透明度过渡（[fadingOut] 退场列表），
 *      退场标签每帧按圆点当前位置重投影，跟着地图走完再消失，掩盖残余的闪烁感。
 *
 * v0.7.1 变更（洲哥 2026-09-22 反馈："提到 0.55"）：
 *   [OVERLAP_RATIO] 0.40 → **0.55**。这是同一条判据的取值调整，不改算法结构 ——
 *   默认档（全貌只出重点+行政）本已 100% 成功，不受影响；放宽的余量留在长标签密集处。
 *
 * v0.7 变更（洲哥 2026-09-22 反馈："避让后显示的名称过少"）：
 *   离线用真实坐标复现本类算法后定位到三处真实丢标签的原因，逐条修掉：
 *   1. **候选位置只有 4 个**（上下左右），密集处一撞就死 → 扩到 8 个（再补四个斜角位）；
 *   2. **要求整条标签入屏**，贴边企业因半条出屏被整条丢弃 →
 *      改为"至少 [VISIBLE_MIN_RATIO] 面积可见即可"，视野边缘的企业不再无故消失；
 *   3. **字号规则双轨**（远档重点 14sp / 近档重点降为 12sp），且远档非重点也吃 14sp →
 *      统一成"行政 16 / 重点 14 / 协作区 12 金 / 其余 12"，两档一致。
 *
 * 显隐规则（[LodConfig]）：
 *   远档 → 只画重点企业与行政/事业单位（点 + 简称标签）；
 *   近档 → 画全部点位与标签；
 *   协作区项目 / 园区项目 **任何档位常显**（不受分档限制 —— v0.7.9 回滚时确认）；
 *   三个样式开关（[showCoop] / [showPark] / [showAbove]）**均不改变显隐**，
 *   只决定标签用简称还是"全称 + 专属色"。
 *
 * 标签样式：文字直接压在影像上，灰色描边 + 投影，不用底色胶囊。
 *
 * 避让算法：贪心 + 网格加速 + 有界重叠
 *   按 [Enterprise.priority] 从高到低依次尝试 8 个候选位置（上/下/右/左/四斜角），
 *   任一位置满足"可见面积达标 且 与已放置标签重叠不超过阈值"就采用；
 *   都放不下则放弃标签、只保留点位符号。
 *   优先级高者先挑位置、后绘制 —— 先挑位置让它拿到空位，后绘制让它压在别人上面。
 */
class EnterpriseOverlay(
    private val context: Context,
    private val dataProvider: () -> List<Enterprise>,
    private val onEnterpriseTap: (Enterprise) -> Unit
) : Overlay() {

    companion object {
        /** 文字投影：半径 3.4dp（v0.4 为 2dp） */
        private const val SHADOW_RADIUS_DP = 3.4f

        /** 文字投影下移量 1.8dp（v0.4 为 1dp） */
        private const val SHADOW_DY_DP = 1.8f

        /** 文字投影不透明度 0-255，94%（v0.4 为 60%） */
        private const val SHADOW_ALPHA = 240

        /** 正五角星内接圆 / 外接圆 = sin(18°)/sin(54°)，固定几何常数 */
        private const val INNER_OUTER_RATIO = 0.382f

        /**
         * 标签允许的最大重叠比例（本类唯一旋钮）。
         *
         * 判据：两个标签的重叠面积 ≤ **较小那个**标签面积的 [OVERLAP_RATIO] 时不算冲突，照画。
         * 与 v0.5/v0.6 的"绝对侵入量"（横向 5dp 且纵向 3.5dp）相比是明显放宽 ——
         * 旧规则对 100×35dp 的标签只容忍约 0.5% 的面积重叠，几乎等于不许碰。
         *
         * 取"较小那个"作分母而不是取面积和：小标签被压掉这么多就该换位置了，
         * 用大标签作分母会让小标签被整条吞掉还判为"没冲突"。
         *
         * 演进：v0.6.1 = 0.40（初版放宽）→ v0.7.1 = 0.55 → **v0.7.23 = 0.35**（洲哥定）。
         *
         * 0.55 评价：标签**数量**上确实"接近上限"（离线复现全貌可出 61/62 家），
         * 但那是拿"准许压掉五成半字面"换来的 —— 密集小簇里两个标签叠着互相啃掉
         * 三到五成，数量达标而观感变差。0.35 把"准许的最坏重叠深度"收到三成半。
         *
         * 0.35 代价（离线复现，见根目录 `.sim_param0735.py`）：
         *   全貌远档 z=13.6 → 30 家掉到 28；最密的 z=14.5 档 53 → 51；
         *   其余各档（14.0 / 15.0 / 15.5 / 16.0 / 17.0）**一家不减**。
         * 即这一刀砍的是"压得多狠"，不是"能放多少" —— 绝大多数候选位的重叠
         * 远小于三成半，只有在真挤的地方才起作用，所以损失是"每家档位最多 −2"。
         *
         * 现场校准：字被压得读不清就往小调（0.25 是文字仍可辨识的经验下限），
         * 标签掉得太多就往大调（0.70 起成片糊）。只改这一个常量。
         */
        const val OVERLAP_RATIO = 0.35f

        /**
         * 标签至少要在屏幕内露出的面积比例（v0.7）。
         *
         * v0.6 之前要求标签**整条**落在屏内（原来的 `screenRect.contains(rect)`），
         * 贴边企业的标签只要有一条边越界就被整条丢弃 —— 全貌时视野东西向只剩约 48px 余量，
         * 两侧企业的名字会成批消失，这不是避让造成的，是这条判据造成的。
         * 改为"露出 [VISIBLE_MIN_RATIO] 即可"，视野边缘的企业不再无故不见。
         * 调到 1.0 就退回 v0.6 的严格行为。
         */
        const val VISIBLE_MIN_RATIO = 0.60f

        /** 候选位置数：上/下/右/左 + 四斜角（v0.7 由 4 扩到 8） */
        private const val SLOT_COUNT = 8

        /**
         * 标签出现/消失的透明度过渡时长（v0.7.3）。
         * 150ms 短到不拖沓、长到能盖住"一帧消失一帧又出现"的闪烁。
         */
        private const val FADE_MS = 150f

        /**
         * 地图停稳多久后做全量重排（v0.7.5 引入，v0.7.6 调参）。
         * 视野一有变化就刷新计时；连续运动（拖/甩/捏合/缩放动画）期间一直不到期，
         * 停稳后一次重排收尾。600ms（洲哥定）：停稳半秒多才收尾，运动中标签全程
         * 刚体跟图，观感更稳；代价是新入视野的企业要迟约 0.6s 才有标签。
         */
        private const val SETTLE_MS = 600L

        /**
         * "小幅移动"判定阈值（v0.7.12 回调回 v0.7.5 原值，洲哥定）：
         * 距上次全量重排的中心位移超过短边 × [SMALL_MOVE_SCREEN_FRACTION]（约 1/3 屏），
         * 或 zoom 变化超过 [SMALL_MOVE_MAX_ZOOM_JUMP]（0.5 级），即视为大幅 ——
         * 立即全量重排而非继续跟图平移（防长滑后跟到真空区、缩放后标签失配）。
         */
        private const val SMALL_MOVE_SCREEN_FRACTION = 0.35
        private const val SMALL_MOVE_MAX_ZOOM_JUMP = 0.5

        /**
         * 近距全显门槛（v0.7.7，洲哥定）：zoom ≥ 17.5 时忽略避让规则，
         * 视野内企业名称全部显示。近距离点位在屏幕上已经拉得很开，
         * 避让此时只会无谓地藏名字，直接全显。
         */
        const val FORCE_ALL_ZOOM = 17.5

        /**
         * 「只出重点+行政名称」区间（v0.7.10 立为"豁免"，**v0.7.24 改为"筛选"**，洲哥定）：
         * `14.5 < zoom < 15.5`（开区间）时，**只有重点企业与行政/事业单位出名称**；
         * 其余企业（项目 / 规上 / 普通）**保留圆点、撤下名称**。
         *
         * **语义变更（v0.7.24，重要）**：v0.7.10 ~ v0.7.23 期间这一档是"豁免" ——
         * 全部企业照常显示名称，只是重点/行政跳过避让、保证有位。洲哥在真机上确认
         * "看不出变化"，因为他要的是**筛选**（这一档地图上只剩这两类名称）。
         * 常量随之改名（原 FORCE_KEY_* → KEY_GOV_*），消除 "FORCE = 豁免" 的误导。
         *
         * 与 [LodConfig.MPP_KEY] 的口径关系（**两者不同源，务必留意**）：
         * 分档门槛用地面分辨率（2.0 m/px），不用 zoom；按"全貌 z13.6 ↔ 3.03 m/px"
         * 口径换算，门槛落在 **z ≈ 14.2**，故本区间落在"近档"内 ——
         * 近档本会把全部企业放进候选池，本档在此之上**再筛一次**，只放重点+行政出名称。
         * 若真机读数证明全貌实为 z15.2（见 [GroundResolution] 的悬案），换算点后移到
         * **z ≈ 15.8**，则本区间（14.5~15.5）整段位于远档，而远档本就"只显示重点+行政"
         * —— 两种口径下结论一致，只是远档口径下本档成为冗余保险。
         *
         * 产量（离线复现，`.sim_keygov_only.py`）：本档只出重点+行政后，标签数
         *   z=14.8 时 36 → **20**、z=15.0 时 23 → **10**、z=15.2 时 20 → **8**，
         * 变化肉眼可辨。筛后的重点/行政之间**走正常避让**（不再豁免），
         * 实测几乎不掉（重点企业本身有间距），不会糊。
         *
         * 端点注意：开区间 —— zoom 恰为 14.5 或 15.5 时**不生效**。
         */
        const val KEY_GOV_ZOOM_MIN = 14.5
        const val KEY_GOV_ZOOM_MAX = 15.5

        /**
         * 全显标签的"硬障碍"容忍度（v0.7.11）：普通标签与全显标签的重叠面积，
         * 只要超过较小框的这个比例就否决该候选位 —— 远严于 [OVERLAP_RATIO] 的 0.35，
         * 目的是"普通名不许压住全显名"。取 0.08 是留一点抗锯齿/取整余量，
         * 相当于"几乎不许压"。
         */
        private const val EXEMPT_BLOCK_RATIO = 0.08f
    }

    // ------------------------------------------------------------------
    // 对外只读状态，供现场校准读数用（长按指北针查看）
    // ------------------------------------------------------------------

    /** 当前视野中心的米/像素 */
    var metersPerPixel: Double = Double.NaN
        private set

    /** 当前分级（[LodConfig]） */
    var lodLevel: Int = LodConfig.LOD_KEY
        private set

    /** 当前分级下视野内的企业数 */
    var visibleCount: Int = 0
        private set

    /** 实际成功画出名称标签的数量 */
    var labelCount: Int = 0
        private set

    /** 因避让失败而只画点、没画名称的数量 */
    var hiddenByCollision: Int = 0
        private set

    /**
     * 协作区项目开关（由"项目面板"弹窗控制，v0.7.13 起语义为**显隐**）。
     * 关闭 = 协作区项目整体不上图（圆点与名称都不画）；开启 = 圆点 + 沙金全称标签。
     * 变化时自动触发重算。
     */
    var showCoop: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                lastViewHash = 0
                lastSlot.clear()   // 项目标签全称宽度不同，粘性位作废重来
                forceRelayout = true
            }
        }

    /**
     * 园区项目开关（v0.7.13，与 [showCoop] 同一个弹窗、各自独立）。
     * 默认关闭 —— 台账「是否园区项目」标记的条目默认完全不上图。
     */
    var showPark: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                lastViewHash = 0
                lastSlot.clear()
                forceRelayout = true
            }
        }

    /**
     * 规上企业全称模式（v0.7.14 引入；v0.7.15 起三个开关语义完全一致）。
     *
     *   关闭（默认）：与普通企业同档，简称 12sp 常规；
     *   开启：全称 12sp 加粗湖蓝（一眼从普通企业里挑出来）。
     * 只改标签文字与颜色，不改显隐 —— 规上进出候选池仍由 [LodConfig] 分档决定
     * （这一点与两类项目不同：项目有分档豁免，规上没有）。
     * 切换时同样清空粘性候选位 —— 简称换全称，标签宽度变了，旧位不可信。
     */
    var showAbove: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                lastViewHash = 0
                lastSlot.clear()
                forceRelayout = true
            }
        }

    /**
     * **名称总闸**（v0.7.27，洲哥要求）：**任一附加图层**打开时置 true ——
     * 全部企业**名称一律不画，圆点照旧**。
     *
     * v0.7.28 起有两个图层（闲置地块 / 闲置厂房），但它是**一个总闸**而不是两个开关：
     * 两个图层本就互斥（见 MainActivity 的 LayerMode 三态），任何时刻最多一个开着，
     * 所以"图层态 ⇒ 企业名全隐"就是完整语义，不需要图层各自一个布尔量。
     *
     * 与 [showCoop] / [showPark] / [showAbove] 不是一回事：那三个只管"怎么标"
     * （简称还是全称、什么颜色），从不管"标不标"；这个闸是**图层互斥**用的 ——
     * 打开附加图层时，地图的主角换成地块框体 / 厂房标记，企业名全撤掉才看得清。
     *
     * 为什么圆点保留（洲哥只说了"企业名不显示"）：地块图层要回答"哪块地空着"，
     * 周边有哪些企业落在这块地旁边，正是判断"这块地能不能招来配套"的现场信息。
     * 撤掉名称是为了不糊，撤掉圆点就是丢信息了。
     *
     * 实现位置在 [layoutLabels] 里 `dots.add()` **之后** —— 与 v0.7.24 的
     * 中间档筛选同一个落点，保证"圆点一个不少、只撤文字"。
     */
    var namesHidden: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                lastViewHash = 0
                lastSlot.clear()
                forceRelayout = true
            }
        }

    /**
     * 每次布局重算完成后的回调，用于输出校准读数。
     * 在 draw() 内被触发，调用方务必 post 到下一帧再动 UI，否则会递归重绘。
     */
    var onLayoutChanged: (() -> Unit)? = null

    /** 数据变了但视野没变时，强制下一次 draw 重算布局 */
    fun invalidateLayout() {
        lastViewHash = 0
        lastSlot.clear()   // 数据（坐标/名称）可能已变，粘性位不可信
        forceRelayout = true
    }

    // ------------------------------------------------------------------
    // 渲染中间态
    // ------------------------------------------------------------------

    private class Dot(val e: Enterprise, var x: Float, var y: Float)

    /**
     * 已放置的标签。v0.7.3 起额外记录布局参数（slot/gap/boxW/boxH），
     * 退场淡出时按圆点当前位置重投影 rect，让标签跟着地图走完再消失。
     */
    private class PlacedLabel(
        val e: Enterprise,
        val rect: RectF,
        val lines: List<String>,
        val paint: TextPair,
        val slot: Int,
        val gapPx: Float,
        val boxW: Float,
        val boxH: Float,
        /** 当前不透明度 0..1，淡入淡出由 draw() 按帧推进 */
        var alpha: Float
    )

    private val dots = ArrayList<Dot>(256)
    private val labels = ArrayList<PlacedLabel>(256)

    /** 上一次布局的视野指纹，相同就跳过重算 */
    private var lastViewHash: Int = 0

    /**
     * 粘性候选位（v0.7.3）：企业 id → 上次采用的候选位编号。
     * 重排时先试上次的位，能放下就不动 —— 滑动/微缩放中标签不再上下翻转。
     * 放不下才按原顺序找新位；全部失败则移除记录。
     */
    private val lastSlot = HashMap<String, Int>(128)

    /** 退场淡出中的标签（本次布局里消失、正往 0 降透明度的） */
    private val fadingOut = ArrayList<PlacedLabel>(16)

    /** 上一帧各标签的透明度，relayout 前快照、布局时续用，保证淡入不闪断 */
    private val prevAlpha = HashMap<String, Float>(128)

    /** 上一帧时间戳，用于淡入淡出的时间推进 */
    private var lastFrameMs = 0L

    // ------------------ 运动期跟图 + 停稳重排（v0.7.5） ------------------

    /** 地图停稳多久后才做全量重排 */
    private var relayoutPending = false

    /** 最近一次视野变化的时间戳，用于判断"停稳" */
    private var lastMoveMs = 0L

    /** 数据/开关变了（invalidateLayout、showCoop）→ 下一次 draw 立即重排，不等停稳 */
    private var forceRelayout = false

    /** 上次全量重排时的视野中心与 zoom，用于判断移动是否算"大幅" */
    private var lastCenterGeo: GeoPoint? = null
    private var lastCenterZoom: Double = Double.NaN

    // ------------------------------------------------------------------
    // 画笔与尺寸
    // ------------------------------------------------------------------

    private val metrics = context.resources.displayMetrics
    private val density = metrics.density

    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, metrics)

    private val labelPadH = dp(5f)
    private val labelGap = dp(4f)
    private val lineSpacing = dp(1.5f)
    private val cornerRadius = dp(4f)

    /** 触摸命中半径：手指比像素粗，点选必须放宽 */
    private val touchRadius = dp(20f)

    /** 一组文字画笔 = 描边笔 + 填充笔。绘制顺序：先描边后填充 */
    private class TextPair(val stroke: Paint, val fill: Paint)

    private fun makeTextPaint(bold: Boolean, tint: Int, sizeSp: Float): TextPair {
        val face = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        else Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.label_outline)
            textSize = sp(sizeSp)
            textAlign = Paint.Align.CENTER
            typeface = face
            style = Paint.Style.STROKE
            strokeWidth = dp(1.6f)
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            textSize = sp(sizeSp)
            textAlign = Paint.Align.CENTER
            typeface = face
            // 文字投影（v0.5 加重，参数集中在 [TextShadow]）：半径 2→3.4dp、
            // 下移 1→1.8dp、不透明度 60%→94%。底图是高对比的卫星影像，
            // 浅投影等于没有，加重后才把白字从亮部里拎出来。
            // 硬件加速只支持文字的 shadowLayer，正好够用。
            setShadowLayer(
                dp(TextShadow.RADIUS_DP), 0f, dp(TextShadow.DY_DP), TextShadow.color
            )
        }
        return TextPair(stroke, fill)
    }

    private val colorKey = ContextCompat.getColor(context, R.color.label_text_key)
    private val colorNormal = ContextCompat.getColor(context, R.color.label_text_normal)
    private val colorCoop = ContextCompat.getColor(context, R.color.label_text_coop)
    private val colorGov = ContextCompat.getColor(context, R.color.label_text_gov)
    private val colorPark = ContextCompat.getColor(context, R.color.label_text_park)
    private val colorAbove = ContextCompat.getColor(context, R.color.label_text_above)

    /**
     * 字号分三档（v0.7.26 起口径 = 按**该条目最高身份**取档，与开关无关）：
     *   行政/事业单位 16sp（比重点企业大 2，v0.5 要求）
     *   重点企业     14sp（v0.4 加大）—— 含"重点 + 项目"双身份条目
     *   其余（纯协作区 / 园区项目 / 规上全称模式）12sp
     */
    private val textGov = makeTextPaint(true, colorGov, 16f)
    private val textKey = makeTextPaint(true, colorKey, 14f)
    private val textNormal = makeTextPaint(false, colorNormal, 12f)
    private val textCoop = makeTextPaint(true, colorCoop, 12f)

    /** 园区项目标签（v0.7.13）：亮橄榄加粗 12sp，与协作区的沙金一眼可分 */
    private val textPark = makeTextPaint(true, colorPark, 12f)

    /**
     * 「项目 + 重点」双身份条目的加粗 14sp 版本（v0.7.26）。
     *
     * 台账里有 5 条既标了重点企业、又标了协作区项目（实木家居产业园一~四期、
     * 鼎中冷链），它们落在 [Enterprise.isCoop] 分支上、被当成普通项目画成 12sp，
     * 看上去"重点企业却没加大"。
     * 规则修正：**字号表达重要度、与开关无关；开关只改文字与颜色** ——
     * 既然是重点企业就先拿 14sp，开关开启时只把文字换成全称、颜色换成项目色。
     */
    private val textCoopKey = makeTextPaint(true, colorCoop, 14f)
    private val textParkKey = makeTextPaint(true, colorPark, 14f)

    /** 规上企业全称标签（v0.7.14）：湖蓝加粗 12sp，[showAbove] 开启时才用 */
    private val textAbove = makeTextPaint(true, colorAbove, 12f)

    /** 点位符号样式。star = true 时画五角星而不是圆点 */
    private class DotStyle(
        val fill: Paint,
        val stroke: Paint,
        val radius: Float,
        val star: Boolean = false
    )

    private val dotStrokeDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dot_stroke_dark)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }
    private val dotStrokeLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dot_stroke_light)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }

    /** 五角星的描边要粗一点：星的周长短，1.4dp 在影像上看不见轮廓 */
    private val starStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dot_stroke_dark)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        strokeJoin = Paint.Join.ROUND
    }

    private fun dotFill(resId: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, resId)
        style = Paint.Style.FILL
    }

    private val dotFillCoop = dotFill(R.color.dot_coop)
    private val dotFillKey = dotFill(R.color.dot_key)
    private val dotFillAbove = dotFill(R.color.dot_above)
    private val dotFillNormal = dotFill(R.color.dot_normal)
    private val dotFillGov = dotFill(R.color.dot_gov)

    /**
     * 半径也分级，配合颜色一起区分（协作区最大，普通最小）。
     * 行政单位画五角星：五角星的视觉占空比只有外接圆的 ~40%，
     * 要给出比圆点更大的外接圆才显得"同样大"。8.6dp 外接圆 ≈ 16dp 星高。
     */
    private fun dotStyle(e: Enterprise): DotStyle = when {
        e.isGov -> DotStyle(dotFillGov, starStroke, dp(8.6f), star = true)
        // 协作区与园区同属"项目"，圆点共用沙金（同类才能看出是项目）；
        // 谁是协作区、谁是园区项目由标签颜色区分（沙金 / 亮橄榄）。
        e.isCoop || e.isPark -> DotStyle(dotFillCoop, dotStrokeDark, dp(6.8f))
        e.isKey -> DotStyle(dotFillKey, dotStrokeDark, dp(6.2f))
        e.aboveScale -> DotStyle(dotFillAbove, dotStrokeDark, dp(5.4f))
        else -> DotStyle(dotFillNormal, dotStrokeLight, dp(4.6f))
    }

    // ------------------------------------------------------------------
    // 五角星路径（按外接圆半径缓存，视野内点位再多也只建几条）
    // ------------------------------------------------------------------

    private val starPathCache = HashMap<Float, Path>(4)

    /** 正立五角星，中心在 (0,0)；绘制时用 canvas.translate 搬到点位 */
    private fun starPath(outerR: Float): Path = starPathCache.getOrPut(outerR) {
        val innerR = outerR * INNER_OUTER_RATIO
        val p = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outerR else innerR
            // 从正上方（-90°）起，每 36° 一个顶点
            val a = Math.toRadians((-90.0 + i * 36.0))
            val px = (r * cos(a)).toFloat()
            val py = (r * sin(a)).toFloat()
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
        p
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val w = mapView.width
        val h = mapView.height
        if (w <= 0 || h <= 0) return

        val center = mapView.mapCenter ?: return

        // 视野指纹：zoom / 中心点 / 尺寸任一变化才算地图动了
        val hash = ((mapView.zoomLevelDouble.hashCode() * 31 +
                center.latitude.hashCode()) * 31 +
                center.longitude.hashCode()) * 31 + w * 31 + h

        val now = SystemClock.uptimeMillis()

        // v0.7.5 布局时机：地图在动就只跟图平移，停稳 SETTLE_MS 后才全量重排。
        // 不再区分触摸/惯性/缩放动画 —— 凡是视野变了都一视同仁。
        if (hash != lastViewHash) {
            lastViewHash = hash
            lastMoveMs = now
            if (!forceRelayout && isSmallMove(mapView, w, h)) {
                followMap(mapView.projection)
                relayoutPending = true
            } else {
                relayoutPending = false
                relayout(mapView.projection, w, h, mapView.zoomLevelDouble)
            }
            forceRelayout = false
        } else if (relayoutPending && now - lastMoveMs >= SETTLE_MS) {
            relayoutPending = false
            relayout(mapView.projection, w, h, mapView.zoomLevelDouble)
        }

        // v0.7.3 淡入淡出推进（按真实帧间隔，最坏 100ms 封顶防跳帧突跳）
        val dt = if (lastFrameMs == 0L) 16f else min(now - lastFrameMs, 100L).toFloat()
        lastFrameMs = now
        var animating = false
        for (p in labels) {
            if (p.alpha < 1f) {
                p.alpha = min(1f, p.alpha + dt / FADE_MS)
                if (p.alpha < 1f) animating = true
            }
        }
        if (fadingOut.isNotEmpty()) {
            val it = fadingOut.iterator()
            while (it.hasNext()) {
                val p = it.next()
                p.alpha -= dt / FADE_MS
                if (p.alpha <= 0f) it.remove()
            }
            animating = true
        }

        drawDots(canvas)
        drawLabels(canvas, mapView.projection)

        // 还有标签在过渡中，请求下一帧继续推进动画
        if (animating) mapView.postInvalidateOnAnimation()
    }

    private fun drawDots(canvas: Canvas) {
        for (d in dots) {
            val s = dotStyle(d.e)
            if (s.star) {
                // 五角星路径以原点为中心，translate 过去画完再还原，
                // 不给每个点复制一份 Path
                canvas.save()
                canvas.translate(d.x, d.y)
                val path = starPath(s.radius)
                canvas.drawPath(path, s.stroke)
                canvas.drawPath(path, s.fill)
                canvas.restore()
            } else {
                canvas.drawCircle(d.x, d.y, s.radius, s.stroke)
                canvas.drawCircle(d.x, d.y, s.radius, s.fill)
            }
        }
    }

    /**
     * 画标签（v0.7 起倒序绘制；v0.7.3 加退场层与逐标签透明度）。
     *
     * [labels] 按优先级从高到低排列，倒着画 = 优先级最高的最后落笔、压在最上层。
     * 允许 55% 重叠之后，谁在上面就决定谁看得清，方向不能反：
     *   先画的（普通企业）被压住 —— 可以接受；
     *   后画的（项目 / 重点 / 行政单位）被压住 —— 不可接受。
     * 退场标签（[fadingOut]）最先画，压在所有现役标签下面淡出。
     */
    private fun drawLabels(canvas: Canvas, projection: Projection) {
        for (p in fadingOut) {
            refreshFadingRect(p, projection)
            drawOneLabel(canvas, p)
        }
        for (i in labels.indices.reversed()) {
            drawOneLabel(canvas, labels[i])
        }
    }

    /** 单个标签：不透明时直接画；过渡中用 saveLayerAlpha 合成整体透明度 */
    private fun drawOneLabel(canvas: Canvas, p: PlacedLabel) {
        if (p.alpha >= 1f) {
            drawLabelLines(canvas, p)
            return
        }
        val a = (p.alpha * 255f).toInt()
        if (a <= 0) return
        val pad = dp(2f)
        val saved = canvas.saveLayerAlpha(
            p.rect.left - pad, p.rect.top - pad,
            p.rect.right + pad, p.rect.bottom + pad, a
        )
        drawLabelLines(canvas, p)
        canvas.restoreToCount(saved)
    }

    private fun drawLabelLines(canvas: Canvas, p: PlacedLabel) {
        val fm = p.paint.fill.fontMetrics
        val lineH = fm.descent - fm.ascent
        var top = p.rect.top + labelPadH / 2f
        for (line in p.lines) {
            val baseline = top - fm.ascent
            canvas.drawText(line, p.rect.centerX(), baseline, p.paint.stroke)
            canvas.drawText(line, p.rect.centerX(), baseline, p.paint.fill)
            top += lineH + lineSpacing
        }
    }

    // ------------------------------------------------------------------
    // 布局（视野变化时执行）
    // ------------------------------------------------------------------

    private fun relayout(projection: Projection, w: Int, h: Int, zoom: Double) {
        // v0.7.3：先快照上一帧标签（透明度 + 退场名单），布局完再做淡入淡出衔接
        val gone = ArrayList(labels)
        prevAlpha.clear()
        for (p in labels) prevAlpha[p.e.id] = p.alpha

        dots.clear()
        labels.clear()
        visibleCount = 0
        labelCount = 0
        hiddenByCollision = 0

        try {
            val all = dataProvider()
            if (all.isEmpty()) return

            metersPerPixel = GroundResolution.metersPerPixel(projection, w, h)
            val lod = LodConfig.levelFor(metersPerPixel)
            lodLevel = lod

            val box = projection.boundingBox ?: return
            // v0.7.4：记录重排视野，供手势期 isSmallMove 判断"大幅移动"用
            lastCenterGeo = GeoPoint((box.latNorth + box.latSouth) / 2, (box.lonWest + box.lonEast) / 2)
            lastCenterZoom = zoom
            // 视野外扩 5%，避免边缘标签被生硬截断
            val padLat = (box.latNorth - box.latSouth) * 0.05
            val padLon = (box.lonEast - box.lonWest) * 0.05
            val south = box.latSouth - padLat
            val north = box.latNorth + padLat
            val west = box.lonWest - padLon
            val east = box.lonEast + padLon

            // 按分档筛选：远档只留重点企业与行政/事业单位；项目类（协作区/园区）**不受档位限制**。
            // v0.7.15：三个开关一律**不参与筛选** —— 它们只管标签怎么标（简称/全称+色），
            //          不管是哪一类、开关什么状态，该显示的都照旧显示。
            // v0.7.14：规上同理，进出候选池完全由分档规则决定，与普通企业同等待遇。
            // v0.7.7：zoom ≥ FORCE_ALL_ZOOM 时全显，有坐标的候选全部入列（双保险，
            // 此时 mpp 极小、分档本已到"全部"档）。
            // v0.7.10 立、**v0.7.24 改语义**（豁免 → 筛选）：14.5 < zoom < 15.5 时
            // **只给重点/行政出名称**。候选池照旧全收（圆点一个不少），
            // 筛选发生在 [layoutLabels] 里 —— 那里把非重点/行政的**名称**跳过。
            val forceAll = zoom >= FORCE_ALL_ZOOM
            val keyGovOnly = zoom > KEY_GOV_ZOOM_MIN && zoom < KEY_GOV_ZOOM_MAX
            val candidates = ArrayList<Enterprise>(128)
            for (e in all) {
                // 无坐标的条目不上图（台账里坐标列留空的那些，只在搜索里能查到档案）
                if (!e.hasCoord) continue
                if (e.lat < south || e.lat > north || e.lon < west || e.lon > east) continue
                val include = forceAll || when (lod) {
                    // v0.6 修复：远档此前只放 isKey，把行政/事业单位也一并筛掉了。
                    LodConfig.LOD_KEY -> e.isKey || e.isGov
                    else -> true
                }
                // 项目不受分档限制：远档缩到"只显示重点企业"时项目仍要看得见
                // （v0.7.9 回滚时确认的结论，v0.7.15 保留）。
                if (!include && !e.isCoop && !e.isPark) continue
                candidates.add(e)
            }
            if (candidates.isEmpty()) return

            // 排序即优先级（[Enterprise.priority]）：行政单位 > 项目 > 重点 > 规上 > 普通。
            // 这一序决定了三件事：谁先挑位置、谁后画（压在上面）、谁先被点击命中。
            candidates.sortWith(Enterprise.PRIORITY_COMPARATOR)
            visibleCount = candidates.size
            layoutLabels(projection, candidates, w, h, forceAll, keyGovOnly)
        } finally {
            // 淡入淡出衔接：本次消失的转入退场列表跟着地图淡出；
            // 回归的标签从退场列表里捞回来，透明度从上次的位置继续。
            val aliveIds = HashSet<String>(labels.size * 2)
            for (p in labels) aliveIds.add(p.e.id)
            for (p in gone) if (p.e.id !in aliveIds) fadingOut.add(p)
            fadingOut.removeAll { it.e.id in aliveIds }
        }
    }

    /**
     * 点位 + 名称标签，做碰撞避让。
     *
     * @param forceAll     v0.7.7：全部标签跳过避让（zoom ≥ 17.5）
     * @param keyGovOnly  v0.7.10 立、**v0.7.24 改语义**：本档**只给重点/行政出名称**
     *                     （14.5 < zoom < 15.5）；其余企业只画圆点、不出名称
     */
    private fun layoutLabels(
        projection: Projection,
        list: List<Enterprise>,
        w: Int,
        h: Int,
        forceAll: Boolean = false,
        keyGovOnly: Boolean = false
    ) {
        // 单元格取"标签可能占的高度"量级，保证一次比对能覆盖相邻标签
        val cell = dp(46f)
        val grid = HashMap<Int, ArrayList<Occupied>>(256)

        val rect = RectF()
        val bestRect = RectF()

        for (e in list) {
            val pt = projection.toPixels(GeoPoint(e.lat, e.lon), Point())
            val x = pt.x.toFloat()
            val y = pt.y.toFloat()

            dots.add(Dot(e, x, y))

            // v0.7.27：名称总闸 —— 闲置地块图层打开时，全部企业名撤下、圆点保留。
            // continue 同样放在 dots.add 之后，与下面那条筛选同理。
            if (namesHidden) continue

            // v0.7.24：本档只给重点/行政出名称 —— 其余企业圆点照画、名称撤下。
            // continue 放在 dots.add 之后，保证圆点一个不少。
            if (keyGovOnly && !(e.isKey || e.isGov)) continue

            val style = dotStyle(e)
            val gap = style.radius + labelGap

            // 点本身就在屏幕外就不必算标签了（留出最大标签宽度的余量）
            if (x < -dp(240f) || x > w + dp(240f) || y < -dp(80f) || y > h + dp(80f)) {
                continue
            }

            // 标签文字与画笔（v0.7.15 统一规则，不再随档位在 14sp/12sp 之间来回切）：
            //   行政单位 16sp 明黄（不分档位，永居最前，且不受任何开关影响）
            //   协作区项目 / 园区项目 / 规上企业 —— **三者同一套模式**：
            //       开关关（默认）→ 简称 12sp 常规卡其白，与普通企业一字不差；
            //       开关开       → 全称 12sp 加粗 + 专属色（沙金 / 亮橄榄 / 湖蓝）
            //   重点企业 14sp 卡其白加粗（远档近档一致 —— 此前近档会降到 12sp）
            //   其余 12sp 常规
            // v0.7.26 补充：**字号只看重要度、开关只管文字与颜色** —— 项目若同时是重点
            // 企业（台账有 5 条，如实木家居产业园各期），关=简称 14sp 卡其白加粗、
            // 开=全称 14sp 加粗 + 项目色；纯项目仍是 12sp 双态（v0.7.15 语义不动）。
            // 分支顺序 = 优先级顺序（[Enterprise.priority]）：既是"重点又是规上"的企业
            // 先在 isKey 命中，拿重点样式 —— 与避让优先级同向，不出现"标得低调、抢位靠前"。
            val pair: TextPair
            val rawText: String
            val maxLineWidth: Float
            when {
                e.isGov -> {
                    pair = textGov
                    rawText = e.shortName ?: e.labelText
                    maxLineWidth = w * 0.55f
                }
                e.isCoop -> {
                    // v0.7.26：项目与重点双身份时按**重点档** 14sp ——
                    // 字号只表达重要度（与开关无关），开关只决定"简称/全称 + 颜色"。
                    if (showCoop) {
                        pair = if (e.isKey) textCoopKey else textCoop
                        rawText = e.name
                        maxLineWidth = w * 0.62f
                    } else if (e.isKey) {
                        pair = textKey
                        rawText = e.labelText
                        maxLineWidth = w * 0.55f
                    } else {
                        pair = textNormal
                        rawText = e.labelText
                        maxLineWidth = w * 0.50f
                    }
                }
                e.isPark -> {
                    if (showPark) {
                        pair = if (e.isKey) textParkKey else textPark
                        rawText = e.name
                        maxLineWidth = w * 0.62f
                    } else if (e.isKey) {
                        pair = textKey
                        rawText = e.labelText
                        maxLineWidth = w * 0.55f
                    } else {
                        pair = textNormal
                        rawText = e.labelText
                        maxLineWidth = w * 0.50f
                    }
                }
                e.isKey -> {
                    pair = textKey
                    rawText = e.shortName ?: e.labelText
                    maxLineWidth = w * 0.55f
                }
                e.aboveScale && showAbove -> {
                    pair = textAbove
                    rawText = e.name
                    maxLineWidth = w * 0.58f
                }
                else -> {
                    pair = textNormal
                    rawText = e.labelText
                    maxLineWidth = w * 0.50f
                }
            }

            // 最多两行；全称类（项目 / 规上全称模式）允许折行，保证名称完整不被省略
            val lines = wrap(pair.fill, rawText, maxLineWidth, maxLines = 2)
            if (lines.isEmpty()) continue

            val fm = pair.fill.fontMetrics
            val lineH = fm.descent - fm.ascent
            val boxW = lines.maxOf { pair.fill.measureText(it) } + labelPadH * 2
            val boxH = lineH * lines.size + lineSpacing * (lines.size - 1) + labelPadH

            // v0.7.3 粘性候选位：先试上次用的位，放不下再按原顺序试其余 7 个。
            // 滑动/微缩放时圆点只移几个像素，旧位多半还能放 —— 标签因此"钉"在
            // 圆点旁不翻转；只有真撞上才换位。
            val prev = lastSlot[e.id]
            val order = IntArray(SLOT_COUNT) { i ->
                if (prev == null) i else (prev + i) % SLOT_COUNT
            }
            // v0.7.24：豁免只剩"全显档"一种 —— 中间档已改为**筛选**
            //（在候选循环里就把非重点/行政挡掉了，走不到这里），
            // 所以本档内重点/行政之间**照常避让**，不会互相压字。
            val exempt = forceAll
            var chosen = -1
            if (exempt) {
                // v0.7.11（A）：全显标签必显示，但**在 8 个候选位里挑压得最少的那个** ——
                // 两个相邻的全显标签会因此一个在上、一个在下，而不是都抢正上方完全盖住。
                var bestSlot = -1
                var bestOv = Float.MAX_VALUE
                for (slot in order) {
                    slotRect(slot, x, y, gap, boxW, boxH, rect)
                    if (!enoughVisible(rect, w, h)) continue
                    val ov = overlapSum(grid, rect, cell)
                    if (ov < bestOv - 1e-3f) {
                        bestOv = ov
                        bestSlot = slot
                        bestRect.set(rect)
                    }
                    if (ov <= 0f) break          // 已经完全空位，不必再找
                }
                if (bestSlot >= 0) {
                    rect.set(bestRect)
                    chosen = bestSlot
                } else {
                    // 8 个位都不够可见（比如紧贴屏幕边缘）也硬画在 0 号位，
                    // 宁可出屏一半也不藏名字 —— "全显"的承诺要兑现。
                    slotRect(0, x, y, gap, boxW, boxH, rect)
                    chosen = 0
                }
            } else {
                for (slot in order) {
                    slotRect(slot, x, y, gap, boxW, boxH, rect)
                    if (!enoughVisible(rect, w, h)) continue
                    if (!collides(grid, rect, cell)) {
                        chosen = slot
                        break
                    }
                }
            }

            if (chosen >= 0) {
                lastSlot[e.id] = chosen
                labels.add(
                    PlacedLabel(
                        e, RectF(rect), lines, pair, chosen, gap, boxW, boxH,
                        prevAlpha[e.id] ?: 0f   // 新面孔从 0 淡入，老面孔延续上次透明度
                    )
                )
                insert(grid, rect, cell, exempt)
            } else {
                lastSlot.remove(e.id)
                hiddenByCollision++
            }
        }

        labelCount = labels.size
    }

    /**
     * 第 [slot] 号候选位的矩形（v0.7.3 从布局循环里抽出，粘性位与退场重投影共用）。
     * 前 4 位是紧贴点位的上下左右；后 4 位是四个斜角，
     * 横向让开一格、纵向让开半格 —— 密集处正交四向全被占时，
     * 斜角位常常还能错开（实测全貌档靠这四位把成功率从 90% 补到 100%）。
     */
    private fun slotRect(slot: Int, x: Float, y: Float, gap: Float, boxW: Float, boxH: Float, out: RectF) {
        when (slot) {
            0 -> out.set(x - boxW / 2, y - gap - boxH, x + boxW / 2, y - gap)
            1 -> out.set(x - boxW / 2, y + gap, x + boxW / 2, y + gap + boxH)
            2 -> out.set(x + gap, y - boxH / 2, x + gap + boxW, y + boxH / 2)
            3 -> out.set(x - gap - boxW, y - boxH / 2, x - gap, y + boxH / 2)
            4 -> out.set(x + gap, y - gap - boxH / 2, x + gap + boxW, y - gap + boxH / 2)
            5 -> out.set(x + gap, y + gap - boxH / 2, x + gap + boxW, y + gap + boxH / 2)
            6 -> out.set(x - gap - boxW, y - gap - boxH / 2, x - gap, y - gap + boxH / 2)
            else -> out.set(x - gap - boxW, y + gap - boxH / 2, x - gap, y + gap + boxH / 2)
        }
    }

    /**
     * 退场标签的矩形按圆点当前位置重投影（v0.7.3）。
     * 不重投影的话标签会钉死在旧屏幕坐标上，滑动时与地图脱节 ——
     * 让它跟着圆点走完 150ms 再消失，观感是"名字随图滑出"而不是"闪没"。
     */
    private fun refreshFadingRect(p: PlacedLabel, projection: Projection) {
        val pt = projection.toPixels(GeoPoint(p.e.lat, p.e.lon), Point())
        slotRect(p.slot, pt.x.toFloat(), pt.y.toFloat(), p.gapPx, p.boxW, p.boxH, p.rect)
    }

    // ------------------------------------------------------------------
    // 运动期跟图 + 停稳重排（v0.7.5）
    // ------------------------------------------------------------------

    /**
     * 移动是否算"小幅"（可安全跟图平移，把重排推迟到停稳）。
     * 距上次重排的中心位移 ≤ 约 1/2 屏且 zoom 变化 ≤ 1 级 → 小幅（v0.7.6 由 1/3 屏 / 0.5 级放宽）；
     * 超过的（长滑、快速捏合、搜索跳转）立即全量重排，避免跟图跟到
     * 右半屏真空、或缩放后标签大小与点位密度失配。
     */
    private fun isSmallMove(mapView: MapView, w: Int, h: Int): Boolean {
        val center = lastCenterGeo ?: return false
        val box = mapView.projection.boundingBox ?: return false
        val cur = GeoPoint((box.latNorth + box.latSouth) / 2, (box.lonWest + box.lonEast) / 2)
        if (abs(mapView.zoomLevelDouble - lastCenterZoom) > SMALL_MOVE_MAX_ZOOM_JUMP) return false
        val limitM = min(w, h) * metersPerPixel * SMALL_MOVE_SCREEN_FRACTION
        return distanceMeters(
            center.latitude, center.longitude, cur.latitude, cur.longitude
        ) <= limitM
    }

    /** 两经纬度点间大圆距离（米）。osmdroid 6.1.20 的 GeoPoint 没有 distanceTo，自己算 */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 6371000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * 廉价跟图：圆点逐帧重投影，标签矩形按各自圆点的位移**整体平移**。
     * 不跑避让、不换候选位、不新增/丢弃标签 —— 运动中标签与圆点刚体连接，
     * 视觉上就是"名字画在底图上随图滑动"。新入视野的企业等停稳重排后再上标签。
     */
    private fun followMap(projection: Projection) {
        if (dots.isEmpty()) return
        val byId = HashMap<String, PlacedLabel>(labels.size * 2)
        for (p in labels) byId[p.e.id] = p
        val pt = Point()
        for (d in dots) {
            projection.toPixels(GeoPoint(d.e.lat, d.e.lon), pt)
            val dx = pt.x - d.x
            val dy = pt.y - d.y
            if (dx == 0f && dy == 0f) continue
            d.x = pt.x.toFloat()
            d.y = pt.y.toFloat()
            byId[d.e.id]?.rect?.offset(dx, dy)
        }
    }

    // ------------------------------------------------------------------
    // 屏幕空间网格索引
    // ------------------------------------------------------------------

    /** 屏幕内缩边距：避免标签死死贴着物理边缘（系统手势区） */
    private val screenPad = dp(2f)

    /**
     * 标签是否"够看得见"（v0.7）。
     *
     * v0.6 之前用的是 `screenRect.contains(rect)` —— 整条标签必须在屏内，
     * 结果视野东西两向只剩约 48px 余量时，两侧企业的名字成批消失。
     * 现在只要求与屏幕的相交面积 ≥ 标签自身面积的 [VISIBLE_MIN_RATIO]（60%）：
     * 贴边企业的名字能露出来，用户平移一点就能看全。
     */
    private fun enoughVisible(r: RectF, w: Int, h: Int): Boolean {
        val l = max(r.left, screenPad)
        val t = max(r.top, screenPad)
        val rt = min(r.right, w - screenPad)
        val b = min(r.bottom, h - screenPad)
        if (rt <= l || b <= t) return false
        val area = r.width() * r.height()
        if (area <= 0f) return false
        return (rt - l) * (b - t) >= area * VISIBLE_MIN_RATIO
    }

    private fun cellKey(gx: Int, gy: Int): Int = gx * 73856093 xor gy * 19349663

    /**
     * 冲突判定（v0.7：允许有界重叠）。
     *
     * 只算"重叠面积 / 较小标签面积"，超过 [OVERLAP_RATIO] 才判冲突。举例
     * （两个同尺寸标签，按 0.35 计）：
     *
     *   擦边而过（重叠 0%）        → 不冲突，照画
     *   横向压掉 35% 宽度           → 不冲突，照画（此时较小者面积比正好 35%）
     *   纵向压掉 35% 高度           → 不冲突，照画
     *   压掉四成面积               → 冲突，换下一个候选位置（8 位都试完就只画点）
     *
     * 旧实现是"横向侵入 > 5dp 且纵向侵入 > 3.5dp"，对 100×35dp 的标签等于
     * 只容忍 0.5% 的面积重叠 —— 太严，标签被大量丢弃。改判据后同样的标签
     * 能容忍到约 40%，标签数明显上升，代价是允许压住约四成。
     *
     * 谁被压住由绘制顺序决定，见 [drawLabels]：低优先级先画、高优先级压在上面。
     */
    /**
     * 网格里的一条占位记录。
     *
     * v0.7.11：这里必须**存副本**（[RectF] 是可变对象）。此前的实现把布局循环里
     * 复用的同一个 RectF 实例塞进网格，之后每次写候选位都会把网格里所有旧记录
     * 一并改写 —— 碰撞判定因此退化成"格子占用"判据，标签被大量误杀。
     *
     * [exempt] 标记该标签属于"全显"（忽略避让、必须显示）的一类，
     * 普通标签遇到它要走更严的容忍度（[EXEMPT_BLOCK_RATIO]）。
     */
    private class Occupied(val r: RectF, val exempt: Boolean)

    /**
     * 候选位是否与已放标签冲突。
     * 判据：重叠面积 > 较小框面积 × 容忍度；与"全显标签"比对时容忍度收紧。
     */
    private fun collides(grid: HashMap<Int, ArrayList<Occupied>>, r: RectF, cell: Float): Boolean {
        val gx0 = floor((r.left / cell).toDouble()).toInt()
        val gx1 = floor((r.right / cell).toDouble()).toInt()
        val gy0 = floor((r.top / cell).toDouble()).toInt()
        val gy1 = floor((r.bottom / cell).toDouble()).toInt()
        val rArea = r.width() * r.height()
        for (gx in gx0..gx1) {
            for (gy in gy0..gy1) {
                val bucket = grid[cellKey(gx, gy)] ?: continue
                for (o in bucket) {
                    val ow = min(o.r.right, r.right) - max(o.r.left, r.left)
                    if (ow <= 0f) continue
                    val oh = min(o.r.bottom, r.bottom) - max(o.r.top, r.top)
                    if (oh <= 0f) continue
                    val smaller = min(rArea, o.r.width() * o.r.height())
                    if (smaller <= 0f) continue
                    val tol = if (o.exempt) EXEMPT_BLOCK_RATIO else OVERLAP_RATIO
                    if (ow * oh > smaller * tol) return true
                }
            }
        }
        return false
    }

    /**
     * 候选位与已放标签的总重叠面积（v0.7.11，供"全显标签择优"用）。
     * 同一条记录可能落在多个格子里，用集合去重，避免重复累加。
     */
    private fun overlapSum(grid: HashMap<Int, ArrayList<Occupied>>, r: RectF, cell: Float): Float {
        val gx0 = floor((r.left / cell).toDouble()).toInt()
        val gx1 = floor((r.right / cell).toDouble()).toInt()
        val gy0 = floor((r.top / cell).toDouble()).toInt()
        val gy1 = floor((r.bottom / cell).toDouble()).toInt()
        val seen = HashSet<Occupied>()
        var sum = 0f
        for (gx in gx0..gx1) {
            for (gy in gy0..gy1) {
                val bucket = grid[cellKey(gx, gy)] ?: continue
                for (o in bucket) {
                    if (!seen.add(o)) continue
                    val ow = min(o.r.right, r.right) - max(o.r.left, r.left)
                    if (ow <= 0f) continue
                    val oh = min(o.r.bottom, r.bottom) - max(o.r.top, r.top)
                    if (oh <= 0f) continue
                    sum += ow * oh
                }
            }
        }
        return sum
    }

    private fun insert(grid: HashMap<Int, ArrayList<Occupied>>, r: RectF, cell: Float, exempt: Boolean) {
        val gx0 = floor((r.left / cell).toDouble()).toInt()
        val gx1 = floor((r.right / cell).toDouble()).toInt()
        val gy0 = floor((r.top / cell).toDouble()).toInt()
        val gy1 = floor((r.bottom / cell).toDouble()).toInt()
        // v0.7.11：RectF(r) 存副本 —— 循环里的 rect 是复用的临时对象，直接存会让
        // 网格里所有记录随下一次写入一起变（详见 [Occupied] 的说明）
        val item = Occupied(RectF(r), exempt)
        for (gx in gx0..gx1) {
            for (gy in gy0..gy1) {
                grid.getOrPut(cellKey(gx, gy)) { ArrayList(4) }.add(item)
            }
        }
    }

    /**
     * 折行：优先整行放下，最后一行放不下才截断加省略号。
     * 协作区项目全称（最长 25 字）在两行内放得下，不会出现"……"。
     */
    private fun wrap(paint: Paint, text: String, maxWidth: Float, maxLines: Int): List<String> {
        if (text.isEmpty() || maxWidth <= 0f) return emptyList()
        val out = ArrayList<String>(maxLines)
        var start = 0
        while (start < text.length) {
            val isLastLine = out.size == maxLines - 1
            val rest = text.substring(start)
            if (isLastLine) {
                val fit = ellipsize(paint, rest, maxWidth)
                if (fit.isNotEmpty()) out.add(fit)
                break
            }
            // 二分找本行能放下的最多字符
            var lo = 1
            var hi = rest.length
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (paint.measureText(rest, 0, mid) <= maxWidth) lo = mid else hi = mid - 1
            }
            if (lo < 1) return emptyList()
            out.add(rest.substring(0, lo))
            start += lo
            if (lo == rest.length) break
        }
        return out
    }

    /** 按可用宽度二分截断，超出部分用省略号 */
    private fun ellipsize(paint: Paint, text: String, maxWidth: Float): String {
        if (text.isEmpty() || maxWidth <= 0f) return ""
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisWidth = paint.measureText(ellipsis)
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (paint.measureText(text, 0, mid) + ellipsisWidth <= maxWidth) lo = mid else hi = mid - 1
        }
        return if (lo <= 0) "" else text.substring(0, lo) + ellipsis
    }

    // ------------------------------------------------------------------
    // 点击命中
    // ------------------------------------------------------------------

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val x = e.x
        val y = e.y

        // 先命中标签矩形。正序遍历 = 从最上层（优先级最高）往下试，
        // 与 [drawLabels] 的倒序绘制正好对应：点到的就是眼睛看到的那个。
        for (i in labels.indices) {
            val p = labels[i]
            if (p.rect.contains(x, y)) {
                onEnterpriseTap(p.e)
                return true
            }
        }

        // 再命中点位（放宽到手指尺寸），取最近的一个
        var best: Enterprise? = null
        var bestDist = touchRadius
        for (d in dots) {
            val dist = hypot(x - d.x, y - d.y)
            if (dist <= bestDist) {
                bestDist = dist
                best = d.e
            }
        }
        best?.let {
            onEnterpriseTap(it)
            return true
        }
        return false
    }
}
