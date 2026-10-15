package com.bianhequ.bhqmap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.bianhequ.bhqmap.data.Enterprise
import com.bianhequ.bhqmap.data.EnterpriseStore
import com.bianhequ.bhqmap.data.IdleFactory
import com.bianhequ.bhqmap.data.IdleFactoryStore
import com.bianhequ.bhqmap.data.LedgerParser
import com.bianhequ.bhqmap.data.LedgerXlsx
import com.bianhequ.bhqmap.data.Parcel
import com.bianhequ.bhqmap.data.ParcelStore
import com.bianhequ.bhqmap.data.Road
import com.bianhequ.bhqmap.data.RoadStore
import com.bianhequ.bhqmap.databinding.ActivityMainBinding
import com.bianhequ.bhqmap.map.EnterpriseOverlay
import com.bianhequ.bhqmap.map.IdleFactoryOverlay
import com.bianhequ.bhqmap.map.LodConfig
import com.bianhequ.bhqmap.map.MapScaleOverlay
import com.bianhequ.bhqmap.map.ParcelOverlay
import com.bianhequ.bhqmap.map.RoadOverlay
import com.bianhequ.bhqmap.map.TiandituTileSource
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.util.zip.ZipException

/**
 * 边合区产业地图 主界面。
 *
 * v0.1：天地图影像底图 + 启动即全貌 + WGS-84 坐标核验（已通过）。
 * v0.2：企业点位与名称标签、按地面分辨率分级显隐、标签碰撞避让、点击查看企业档案。
 * v0.3：接入真实台账 62 家（内置种子 v2）；显隐规则反转（远档重点/近档其他）；
 *       协作区项目常显开关；标签灰描边+阴影；右侧悬浮按钮系统；去掉三个图层开关。
 * v0.4：去掉顶部状态栏（读数改为长按指北针查看 + Logcat）；
 *       详情面板无主营业务则隐藏该栏、简介过长时可滑动查看；
 *       重点企业字号加大；协作区全称两行折行不截断；
 *       四级点位色（沙金/亮橄榄/卡其白/军绿，全部半透明）；
 *       右上角指北针；左下角比例尺；定位图标改小箭头（红描边黑半透明）；
 *       启动图标缩至圆形蒙版安全圆内（视觉规范 v1.6）。
 * v0.5：台账新增「是否行政或公共事业单位」列 → 该点位画黄色五角星、标签 16sp
 *       （比重点企业大 2），详情面板加对应徽章；种子版本升 3（54 家台账，42 家入包）；
 *       指北针放大、N 字缩小；文字投影加重；标签避让放宽（细长条重叠不再算冲突）；
 *       四级圆点填充透明度统一到 70%。
 * v0.6：修复 isGov 在 JSON 解析层被漏读（五角星从未生效）、在远档筛选里被漏掉；
 *       种子升 4 —— 台账 74 条全部入包：只填简称的 20 家以简称入图并标注"（简称）"，
 *       坐标留空的 12 家保留档案、不进地图；
 *       详情面板徽章补上"行政/事业单位"；台账总数与可上图数分开计数。
 * v0.6.1：避让规则改版 —— 优先级阶梯（项目 > 重点 > 规上 > 普通）收敛到 Enterprise.priority
 *       唯一入口，同时决定挑位顺序 / 叠压顺序 / 点击命中顺序；允许最多 40% 的标签重叠
 *       （EnterpriseOverlay.OVERLAP_RATIO），标签绘制顺序反转为高优先级压在最上层；
 *       校准读数末尾新增"重叠容差"百分比，供现场调这一个常量。
 * v0.7：解决"名称过少"—— 离线复现算法后发现三处真实丢标签的判据（候选位仅 4 个、
 *       要求整条入屏、字号规则双轨），逐条修掉。
 * v0.7.1：重叠容差 40% → 55%（EnterpriseOverlay.OVERLAP_RATIO）。
 * v0.7.2：撤除两个悬浮按钮 —— 「显示全部企业」（连同 EnterpriseOverlay.showAll 字段）
 *       与「未上图企业」入口，相关图标资源一并删除。右侧按钮组回到 6 个：
 *       协作区 / 全貌 / 放大 / 缩小 / 定位 / 搜索。
 *       读数里的"全量:开/关"同步去掉；无坐标条目仍可在搜索里查到档案。
 * v0.7.13：台账新增「是否园区项目」列 → 「项目」按钮改为**显示面板**入口，
 *       面板内「协作区项目」「园区项目」两个开关独立控制显隐（默认开 / 关）；
 *       种子升 6（75 条 / 可上图 63）。
 * v0.7.14：面板内再加「规上企业」开关 —— 管标签样式而非显隐：关（默认）= 简称，
 *       与普通企业同档；开 = 湖蓝全称，便于一眼挑出。
 * v0.7.15：三个开关语义统一（洲哥要求"这三种的显示模式都一样"）—— 一律只控标签样式：
 *       关（默认）= 简称、与普通企业一字不差；开 = 全称 + 专属色（沙金/亮橄榄/湖蓝）。
 *       v0.7.13 的"关掉即整条不上图"作废，协作区与园区项目恢复"任何倍率都显示"
 *       （即 v0.7.9 回滚时确认的行为）；面板同时去掉全部副标题说明文字。
 * v0.7.16：接入园区路网（`assets/roads.kml`，13 条）—— 新增 [RoadOverlay]，
 *       **不画线条**，只把路名沿线标注：黑字、白边、带投影，文字方向与路线平行
 *       （角度规范到 [-90°, 90°]，"由东往西"画的那几条不会倒着写）。
 *       校准读数新增第三行「道路 / 路名 / 未标」。
 * v0.7.17：路名加显示门槛 —— **只在 z ≥ 17 时出现**（洲哥要求）。
 *       全貌（约 z13.6、视野 3.3 km）是"全园浏览"档，路名不占版面；
 *       z≥17 视野收窄到约 300 m，屏上只剩一两条路，路名这时才有用。
 *       未到门槛时读数第三行改报门槛本身，不报那三个 0。
 * v0.7.19：显示控制面板视觉改造 —— 由居中白底 AlertDialog 改为**锚定主按钮的深色玻璃浮层**
 *       （PopupWindow，在 btnCoop 左侧 8dp、垂直居中），每行加 12dp 示例色块（= 开启后
 *       标签全称的专属色），开关轨道/拇指改为自定义着色（开用户军绿，覆盖系统 Material 紫）。
 *       再点一次按钮可收起；点浮层外部关闭。详见《显示控制浮层规范 v1.0》。
 * v0.7.20：详情面板改为「固定头 + 独立滚动的简介 + 固定尾」——企业名 / 类型徽章 /
 *       主营业务固定在顶部、坐标行固定在底部，只有简介区滚动，长简介下翻时头部始终可见；
 *       简介每段首行缩进 2 字符（LeadingMarginSpan，折行顶格）。
 *       面板块的可用高度由实测头部高度动态收紧（fitPanelHeight），不再整页滚动。
 * v0.7.23：两处避让参数调整（[EnterpriseOverlay.OVERLAP_RATIO] 0.55 → 0.35、
 *       重点/行政全显档 15~16 → 14.5~15.5）：标签准许的"最坏重叠深度"由五成半收到
 *       三成半，豁免档整体下移半级盖住"园区全貌 → 看清片区"的过渡段。
 * v0.7.24：中间档语义由"豁免"改为"筛选"（[EnterpriseOverlay.KEY_GOV_ZOOM_MIN] /
 *       [EnterpriseOverlay.KEY_GOV_ZOOM_MAX]）—— 14.5 < z < 15.5 时**只有重点/行政出名称**，
 *       其余企业保留圆点、撤下名称；校准读数第一行新增「· 仅重点+行政」标记。
 * v0.7.25：种子升 v7 —— 台账大扩充后整表重建（164 条 / 可上图 154 家，见
 *       [EnterpriseStore.SEED_VERSION] 注释）。代码逻辑零改动。
 * v0.7.26：修正"重点企业却没加大"—— 「项目 + 重点」双身份条目（实木家居产业园
 *       一~四期、鼎中冷链）此前被项目分支截走、按 12sp 画。现口径为
 *       **字号按最高身份取档、开关只管文字与颜色**：关=简称 14sp 卡其白加粗、
 *       开=全称 14sp 加粗 + 项目色；纯项目仍 12sp 双态。
 * v0.7.27：新增**闲置地块图层**（`assets/parcels.kml`，13 块，合计 1.94 km²）——
 *       浮层加第四行「闲置地块」开关（默认关）：打开后画紫罗兰框体 + **框体中央的地块名称**
 *       （`map/ParcelOverlay`，紫 #B388FF = 离已有五色最远的色相段）；
 *       同时把「协作区项目 / 园区项目 / 规上企业」三个样式开关**强制关闭并置灰**、
 *       并置 [EnterpriseOverlay.namesHidden] = true —— 全部企业名撤下、**圆点保留**；
 *       关闭本项即**复原**三开关打开本项前的状态。校准读数新增第四行。
 * v0.7.28：新增**闲置厂房图层**（台账新建「闲置厂房」工作表 25 处，合计 0.1747 km²，
 *       经 `.build_idle_factories.py` 生成 `assets/idle_factories.json`）——
 *       浮层加第五行「闲置厂房」开关（默认关）：打开后画珊瑚橙方块标记
 *       （`map/IdleFactoryOverlay`，珊瑚橙 #FF8A65 = 闲置地块取走紫色后色相环上
 *       离"已有五色 + 紫"都最远的暖橙段），每处标**两行**：名称 12sp 加粗 / 闲置面积
 *       11sp 常规，只在台账原文自带的空格、逗号处折行。
 *       两个图层（闲置地块 / 闲置厂房）**双向互斥** —— 浮层第五行打开时第四行自动关闭，
 *       反之亦然；两行都是图层开关，前三行样式开关在任何图层打开时一并强制关闭并置灰。
 *       实现上把 v0.7.27 的布尔量 `idleVisible` 重构为三态 [LayerMode]
 *       （NONE / PARCEL / FACTORY）：**图层间互切不再重新快照**，
 *       所以"地块开 → 直接切厂房 → 关厂房"能一路复原到最初的三开关状态。
 *       校准读数的图层行改为**一行说清两个图层**（原「闲置地块 关」的写法作废）。
 * v0.7.29：修掉**附加图层被企业圆点压住**的现场问题（洲哥手机端验证发现）——
 *       闲置地块层与闲置厂房层原先都用 `add(0, …)` 插在叠加层列表最底，
 *       而 osmdroid 按下标从小到大绘制，企业层在末尾 ⇒ 圆点永远压在上面，
 *       厂房的两行名称与 12dp 方块被成片挡住。现改为 `add(…)` 追加，
 *       两个附加图层整体位于企业层之上；叠放顺序与代价权衡写成
 *       [addEnterpriseOverlay] 上方的"叠放顺序"注释块。**纯叠放位置改动，
 *       绘制逻辑、避让参数、颜色一律未变。**
 * v0.7.30：新增**版权信息页**（洲哥要求：点右下角版本号打开）——版式 B「分段卡片式」
 *       （三版比选见 `设计交付/关于页样式/`），内容为权益方 / 开发团队 / 开发时间三行
 *       + 版权声明 + 底图来源（补上**审图号 GS(2025)1508号**，此前一直未落地）
 *       + 数据说明 + 页脚版权行；标题栏图标直接用**真实 launcher 图标**
 *       （`@mipmap/ic_launcher` + `clipToOutline` 裁 12dp 圆角）。
 *       实现见 [showAboutDialog]：AlertDialog + 透明 window 底 + 显式 300dp 宽，
 *       三条关闭路径（按钮 / 点外部 / 返回键）。版本号随 BuildConfig 自动更新。
 * v0.8.0：新增**台账 Excel 机内导入**（洲哥要求"开发 app 版的导入功能"，与 Web 版 v0.3.0/v0.3.1 同构）——
 *       显示控制浮层第六行「导入台账 Excel」**动作行**（分隔线之下，不计入开关行上限；
 *       虚线沙金色块 = 动作，对齐 Web 的 .sw-imp）。SAF 选文件（零存储权限）→
 *       后台线程解析（[LedgerXlsx] 零依赖 zip+XML 解析器 + [LedgerParser] excel.js 逐条移植）
 *       → 统计确认框（条目/可上图/无坐标/重复组/厂房）→ 确认后整表替换：
 *       企业走 [EnterpriseStore.replaceAll]，闲置厂房走 [IdleFactoryStore.replaceAll]
 *       （后者 v0.8.0 起从只读 assets 改造为「filesDir 优先、assets 兜底」）。
 *       口径与 Web 完全一致：id=td-/if-<行号>、坐标 130~132.5/43.5~45.5、
 *       判真「是/Y/y/true/True/1」、同点位 1e-6 只提示不合并；
 *       期望统计已用 `.sim_import_mirror.py` 镜像锚定（164/154/10/厂房 25）。
 * v0.8.1：按新台账（边合区企业台账+1.xlsx，2026-10-15）更新数据 —— 种子 v8。
 *       条目数/坐标/五个标记列/闲置厂房表均无变化（164 / 可上图 154 / 无坐标 10），
 *       变的是内容：**主营业务首次有数据（96 条）** → 详情页「主营业务」栏首次亮出
 *       （v0.4 起本就有"空则整栏隐藏"的逻辑，无需改 UI）；**简介 5 条 → 102 条**；
 *       5 处企业名称订正（鸿基→洪基包装 等，行号不变）。
 *       配套修一处口径缺口：[LedgerParser] 此前把 mainBusiness 写死空串（生成种子时
 *       该列全空），导入路径同样丢这一栏 —— 改为读 L 列，与种子脚本、Web 版 excel.js
 *       三方同口径。闲置厂房 JSON 按新台账重跑（数据未变，仅换数据源自证同源）。
 *       另：详情面板标题去掉「（简称）」后缀（洲哥要求）—— v0.6 加它是为提醒补全称，
 *       但它紧挨企业名、易被读成名称的一部分，一律只显示名称本身（Web 版同步）。
 * 后续：搜索筛选导出、恢复内置数据入口。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: EnterpriseStore
    private lateinit var enterpriseOverlay: EnterpriseOverlay
    private lateinit var roadOverlay: RoadOverlay
    private lateinit var parcelOverlay: ParcelOverlay
    private lateinit var idleFactoryOverlay: IdleFactoryOverlay

    private var enterprises: List<Enterprise> = emptyList()

    /** 园区路网（v0.7.16，来自 assets/roads.kml）。只用于标注名称，不画线条 */
    private var roads: List<Road> = emptyList()

    /** 闲置地块（v0.7.27，来自 assets/parcels.kml）。13 块 / 合计 1.94 km²，只读包内资产 */
    private var parcels: List<Parcel> = emptyList()

    /**
     * 闲置厂房（v0.7.28 来自台账「闲置厂房」工作表；v0.8.0 起支持机内导入覆盖）。
     * 启动时 filesDir/idle_factories.json 优先、assets 兜底（见 [IdleFactoryStore]）。
     */
    private var idleFactories: List<IdleFactory> = emptyList()

    /**
     * 三个"全称模式"开关（v0.7.15 起语义完全一致，同一个弹窗内各占一行）。
     *
     *   关（默认）= 简称，与普通企业一字不差；
     *   开 = 全称 + 专属色（协作区沙金 / 园区亮橄榄 / 规上湖蓝）。
     *
     * 三者都**只管标签怎么标，不管上不上图**：
     *   协作区项目 / 园区项目 任何倍率都显示（v0.7.9 回滚时确认的结论），
     *   规上企业 按 LodConfig 分档显示（与普通企业同规则）。
     * 默认全关 —— 首次安装的观感就是"全图只出简称"。
     */
    private var coopVisible = false
    private var parkVisible = false
    private var aboveVisible = false

    /**
     * 附加图层的三态枚举（v0.7.28 重构）。
     *
     * v0.7.27 时只有一个图层，用布尔量 `idleVisible` 就够了；v0.7.28 加了第二个图层，
     * 而且两个图层**双向互斥**（洲哥要求：「闲置地块开关打开后闲置厂房开关也关闭」），
     * 布尔量就没法表达"现在开的是哪一个"了。
     *
     * 换成三态之后，状态机只有一个变量、一条推理链：
     *   · 进入 NONE  → 复原三个样式开关（按快照）
     *   · 离开 NONE  → 快照三个样式开关并全部关闭
     *   · **图层之间互切（PARCEL ⟷ FACTORY）→ 两者都不做**，快照保持"最初那次"，
     *     所以"地块开 → 直接切厂房 → 关厂房"能一路复原到最初的三开关状态，
     *     而不是复原到"地块开着的时候"那个中间态。
     *
     * 两个图层打开时的连带动作完全相同（洲哥两次要求一字不差）：
     *   1. 「协作区项目 / 园区项目 / 规上企业」三个样式开关**强制关闭并置灰**
     *      —— 图层互斥，附加图层是主角时不该再叠企业全称；
     *   2. [EnterpriseOverlay.namesHidden] = true —— **所有企业名一律不画**，
     *      圆点保留（图层旁落着哪些企业，是判断这块地/这栋厂房能不能招来配套的现场信息）。
     */
    private enum class LayerMode { NONE, PARCEL, FACTORY }

    /** 当前打开的附加图层（默认 [LayerMode.NONE] = 两个都不开，洲哥要求"默认不显示"） */
    private var layerMode = LayerMode.NONE

    /**
     * 闲置地块图层是否打开（浮层第四行）。派生量，**不是独立状态** ——
     * 独立成两个布尔量就会出现"两个都开"这种非法态，三态枚举从根上避免了它。
     */
    private val idleVisible: Boolean
        get() = layerMode == LayerMode.PARCEL

    /**
     * 闲置厂房图层是否打开（浮层第五行）。同 [idleVisible]，是派生量。
     * 图层互斥由这个 getter 天然保证：两者不可能同时为 true。
     */
    private val factoryVisible: Boolean
        get() = layerMode == LayerMode.FACTORY

    /**
     * 打开**任一**附加图层之前三个样式开关的状态快照（v0.7.27 立、v0.7.28 沿用）。
     * 关闭图层时按它复原 —— 洲哥原话："关闭开关后复原"。
     * 只在"从 NONE 进图层"那一次快照，图层间互切、反复开关都不会覆盖它。
     */
    private var savedCoop = false
    private var savedPark = false
    private var savedAbove = false

    /**
     * 台账文件选择器（v0.8.0）：SAF `OpenDocument`，**不需要任何存储权限**。
     *
     * mime 给四类：xlsx 官方、老 .xls（选中后明确提示"另存为 .xlsx"）、
     * `application/octet-stream`（QQ/微信下载与部分文件管理器不给正经 mime）、
     * WPS 的 xlsx mime（手机上常见台账来源）。
     *
     * 属性初始化时注册（STARTED 之前），这是 `registerForActivityResult` 的要求；
     * 选中回调里 uri 可能为 null（用户在系统选择器里点了返回），直接忽略。
     */
    private val importLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onLedgerPicked(uri)
        }

    /**
     * 浮层内**五个**开关共用的监听器（v0.7.27 收敛为单一字段，v0.7.28 加第五个仍不动结构）。
     *
     * 为什么要收敛：附加图层打开时要**程序性地**把别的开关置为未选中/未选中之外的状态，
     * 而 `setChecked()` 会照样触发监听器 —— 那就把"程序性改写"当成了"用户操作"，
     * 状态机立刻乱掉。收敛成一个字段后，程序性赋值只需
     * `setOnCheckedChangeListener(null)` → 赋值 → 挂回 `switchListener`，
     * 不必为每个开关各存一份原始 lambda（那样极易漏挂、漏挂就是"开关点了没反应"）。
     */
    private val switchListener = CompoundButton.OnCheckedChangeListener { v, checked ->
        when (v.id) {
            R.id.swCoop -> {
                coopVisible = checked
                enterpriseOverlay.showCoop = checked
                onDisplayToggle()
            }
            R.id.swPark -> {
                parkVisible = checked
                enterpriseOverlay.showPark = checked
                onDisplayToggle()
            }
            R.id.swAbove -> {
                aboveVisible = checked
                enterpriseOverlay.showAbove = checked
                onDisplayToggle()
            }
            R.id.swIdle -> onLayerToggled(LayerMode.PARCEL, checked)
            R.id.swFactory -> onLayerToggled(LayerMode.FACTORY, checked)
        }
    }

    /**
     * 显示控制浮层（v0.7.19 起）。
     * 由 AlertDialog 改为锚定 btnCoop 的 PopupWindow —— 浮层出现在按钮左侧、垂直居中于按钮，
     * 「谁开的这层」关系由位置自己表达。持有引用是为了再点一次按钮能收起（toggle）。
     */
    private var displayPopup: PopupWindow? = null

    /** 定位层，按钮首次点击时创建 */
    private var locationOverlay: MyLocationNewOverlay? = null

    /** 校准读数刷新节流时间戳 */
    private var lastTelemetryAt = 0L

    /** 最近一次校准读数（长按指北针弹出） */
    private var lastTelemetry: String = ""

    private val tk: String get() = BuildConfig.TIANDITU_TK

    companion object {
        /** 默认视野四周留白（像素），让边界点不至于贴着屏幕边 */
        private const val OVERVIEW_PADDING_PX = 48

        /** osmdroid 配置用的 SharedPreferences 文件名 */
        private const val PREFS_NAME = "osmdroid_prefs"

        /** 读数刷新节流间隔（毫秒） */
        private const val TELEMETRY_THROTTLE_MS = 300L

        /** Logcat 过滤标签：adb logcat -s BhqMap */
        private const val TAG = "BhqMap"

        /** 自身位置箭头图标的边长（dp）。v0.4 起改小 */
        private const val LOCATOR_SIZE_DP = 20

        /** 详情面板最大高度（dp），与布局里 constraintHeight_max 保持一致 */
        private const val PANEL_MAX_HEIGHT_DP = 360

        /**
         * 简介滚动区的兜底最小高度（dp，约 3 行）。
         * 头部（名称可能两行 + 徽章 + 主营业务）在极端情况下会吃掉大半面板，
         * 若可用空间被算到 0 以下，至少留这么高，宁可面板略微超过 360dp
         * 也不能让简介整个看不见。
         */
        private const val INTRO_MIN_HEIGHT_DP = 72

        /**
         * 显示控制浮层的兜底宽度（dp）。
         * 正常一律用「实测宽度」，只有测量返回 0 这类异常才拿它顶上；
         * 176 ≈ 12 左右内边距 ×2 + 12 色块 + 8 间距 + 80 文字列 + 开关 ≈52。
         */
        private const val POPUP_FALLBACK_WIDTH_DP = 176

        /**
         * 被图层互斥禁用时，浮层里那一行的整行不透明度（v0.7.27）。
         *
         * 单靠开关自身变灰不够显眼（深色玻璃底上 12% 白轨道很小一块），
         * 整行一起压到 45% 才一眼看得出"这行此刻不可操作"。
         * 不压到更低：文字仍需能读出来 —— 用户要能看清"哪三项被暂时关掉了"，
         * 才知道关掉闲置地块后会复原什么。
         */
        private const val DISABLED_ROW_ALPHA = 0.45f

        /**
         * 版权信息页的面板宽度（dp，v0.7.30）。
         *
         * 为什么是 300：这一页最长的一行是「绥芬河市边境经济合作区管理局」（15 字）——
         * 12.5sp 下约 188dp，加上左侧"权益方"标签（35dp）与 12dp 间距，
         * 内容区至少要 235dp；两侧各留 14dp 内边距 ⇒ 面板 ≥ 263dp。
         * 300dp 是这个下限往上取整、并留出换更长的单位名余量的值。
         *
         * 为什么不写成 match_parent：这页是"一页纸"，不是全景面板。
         * 满屏宽会让 10~11sp 的小字拉成很长的行，读起来费劲，也与浮层的窄面板语言不一致。
         * 窄屏（<332dp）上由 [showAboutDialog] 钳到「屏宽 − 32dp」。
         */
        private const val ABOUT_DIALOG_WIDTH_DP = 300

        private const val REQ_LOCATION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid 全局配置必须在创建 MapView 之前完成
        initOsmdroid()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (tk.isBlank()) {
            Toast.makeText(
                this,
                "未读到天地图 tk，请检查 local.properties 里的 TIANDITU_TK",
                Toast.LENGTH_LONG
            ).show()
        }

        // 企业数据：种子版本低于内置版本时自动重新播种（升级安装即换新数据）
        store = EnterpriseStore(this)
        enterprises = store.load()

        // 路网（v0.7.16）：只读的包内资产，不走播种机制 —— 换路网就是换 assets/roads.kml
        roads = RoadStore(this).load()

        // 闲置地块（v0.7.27）：同样是只读资产，与路网一个待遇
        parcels = ParcelStore(this).load()

        // 闲置厂房（v0.7.28）：v0.8.0 起 filesDir 优先（机内导入覆盖）、assets 兜底
        idleFactories = IdleFactoryStore(this).load()

        setupMap()
        addEnterpriseOverlay()
        addScaleBar()
        addRoadOverlay()
        addParcelOverlay()
        addIdleFactoryOverlay()
        setupUi()
        logTelemetry()
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    /** osmdroid 全局配置：缓存路径放在应用私有目录，不需要存储权限 */
    private fun initOsmdroid() {
        val cfg = Configuration.getInstance()
        cfg.load(this, getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
        cfg.userAgentValue = packageName

        cfg.osmdroidBasePath = File(filesDir, "osmdroid").apply { if (!exists()) mkdirs() }
        cfg.osmdroidTileCache = File(filesDir, "osmdroid/tiles").apply { if (!exists()) mkdirs() }

        // 瓦片缓存上限 200 MB。个人开发者配额约 10 万次/天，且一次瓦片请求算一次调用，
        // 缓存是省配额的关键，必须开。
        cfg.tileFileSystemCacheMaxBytes = 200L * 1024 * 1024
        cfg.tileFileSystemCacheTrimBytes = 180L * 1024 * 1024
    }

    private fun setupMap() = with(binding.map) {
        // 主底图：天地图影像（唯一的瓦片数据源，绝不使用 OSM 等境外源）
        setTileSource(TiandituTileSource.image(tk))
        setUseDataConnection(true)
        setMultiTouchControls(true)

        // 高清影像为默认（v0.3 起固定）：瓦片 1:1 像素映射，不放大插值
        isTilesScaledToDpi = false

        minZoomLevel = TiandituTileSource.MIN_ZOOM.toDouble()
        maxZoomLevel = TiandituTileSource.MAX_ZOOM.toDouble()

        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

        // 启动即定位边合区全貌（等 MapView 完成布局拿到真实宽高后再算）
        fitToBhqArea()

        addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                scheduleTelemetry()
                return true
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                scheduleTelemetry()
                return true
            }
        })
    }

    private fun setupUi() {
        // 右下角版本号（v0.7.8）：白字无底带阴影，运行时按构建配置填充，不手工维护
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        // 右侧悬浮按钮组
        // 「项目」按钮（v0.7.13）：不再直接切换协作区，改为弹出**项目面板** ——
        // 面板内两个开关分别控制协作区项目与园区项目的显隐，各自独立、即时生效。
        // v0.7.14：面板内再加「规上企业」开关（控标签样式，不控显隐），按钮略为通用入口。
        binding.btnCoop.isSelected = panelHasCustomState()
        binding.btnCoop.setOnClickListener { showDisplayPanel() }
        binding.btnOverview.setOnClickListener {
            fitToBhqArea()
            hideEnterprisePanel()
        }
        binding.btnZoomIn.setOnClickListener { binding.map.controller.zoomIn() }
        binding.btnZoomOut.setOnClickListener { binding.map.controller.zoomOut() }
        binding.btnLocate.setOnClickListener { onLocateClicked() }
        binding.btnSearch.setOnClickListener { showSearchDialog() }

        // 右上角指北针：地图不旋转，恒指正北；长按弹出校准读数（替代原顶部状态栏）
        binding.ivCompass.setOnLongClickListener {
            Toast.makeText(this, lastTelemetry.ifBlank { "读数生成中…" }, Toast.LENGTH_LONG).show()
            true
        }

        binding.btnPanelClose.setOnClickListener { hideEnterprisePanel() }

        // 右下角版本号（v0.7.30）：点击弹出**版权信息页**。
        // 放在这个位置的用意：版本号是所有 App 里"关于/版权"的既成入口，
        // 用户不必被教；而这一页也不是高频功能，不需要占一个悬浮按钮。
        binding.tvVersion.setOnClickListener { showAboutDialog() }
    }

    /**
     * 显示控制浮层（v0.7.13 建为"项目面板"，v0.7.14 加入规上企业，v0.7.15 三者语义统一，
     * v0.7.19 由居中 AlertDialog 改为锚定主按钮的深色玻璃 PopupWindow）。
     *
     * 三个开关，**一套语义** —— 都只管标签样式，不管显隐：
     *   关（默认）= 简称，与普通企业一字不差；
     *   开 = 全称 + 专属色（协作区沙金 / 园区亮橄榄 / 规上湖蓝）。
     * 面板里只有"类别名 + 示例色块 + 开关"，不写副标题（v0.7.15 洲哥要求：不要说明文字）。
     *
     * v0.7.27 再加第四行「闲置地块」、v0.7.28 再加第五行「闲置厂房」——
     * 这两行与前三个**不是同一类**：那是**图层**开关（打开后地图多一层：
     * 地块是框体 + 框体中央的名称，厂房是方块标记 + 名称/面积两行），
     * 语义差异由示例色块的形状表达：**前三行实心 = 标签颜色，后两行空心 = 图层颜色**。
     * 两个图层彼此**双向互斥**、且打开任一个都会强制关闭并置灰前三行，
     * 关闭后按快照复原 —— 详见 [onLayerToggled]。
     *
     * 弹出几何（详见《显示控制浮层规范》第 5 节）：
     *   xoff = -(浮层实测宽 + 8dp)      → 贴在主按钮左侧 8dp
     *   yoff = -(浮层高 + 锚点高) / 2   → 垂直居中于按钮
     * 注意 yoff **不是** -(浮层高 - 锚高)/2 —— showAsDropDown 默认把浮层顶放在锚点底部，
     * 写错会让浮层偏下半个锚高。
     *
     * v0.7.22（洲哥：框体太宽、已顶到右侧屏幕边界）：病根在测量不在数值 ——
     * 宽度一度交给 WRAP_CONTENT，而布局里行内用「layout_width=0dp + layout_weight=1」，
     * 自量时传的是 AT_MOST 规格，weight 子项会被撑到可用宽度上限 ⇒ 自量结果 = 满屏，
     * xoff 算成巨大负值 ⇒ 浮层被推出屏幕左侧，又被 PopupWindow 默认的"裁剪回屏幕"
     * 拽回来 ⇒ 满屏宽、左右贴边。现布局改 wrap_content + 固定 80dp 文字列（三行等宽、
     * 开关纵向对齐），自量准确；同时把实测宽度显式交给 PopupWindow，双保险。
     */
    private fun showDisplayPanel() {
        // 再点一次按钮 = 收起（toggle），与"点外部关闭"互补
        if (displayPopup?.isShowing == true) {
            displayPopup?.dismiss()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_project_filter_v2, null)
        val swCoop = view.findViewById<SwitchCompat>(R.id.swCoop)
        val swPark = view.findViewById<SwitchCompat>(R.id.swPark)
        val swAbove = view.findViewById<SwitchCompat>(R.id.swAbove)
        val swIdle = view.findViewById<SwitchCompat>(R.id.swIdle)
        val swFactory = view.findViewById<SwitchCompat>(R.id.swFactory)

        // 先赋初值、再挂监听：初始赋值不应触发一次多余的重排
        swCoop.isChecked = coopVisible
        swPark.isChecked = parkVisible
        swAbove.isChecked = aboveVisible
        swIdle.isChecked = idleVisible
        swFactory.isChecked = factoryVisible
        // v0.7.27 起五个开关共用一个监听器 —— 收敛成单一字段，程序性赋值才好"摘挂"（见 switchListener）
        swCoop.setOnCheckedChangeListener(switchListener)
        swPark.setOnCheckedChangeListener(switchListener)
        swAbove.setOnCheckedChangeListener(switchListener)
        swIdle.setOnCheckedChangeListener(switchListener)
        swFactory.setOnCheckedChangeListener(switchListener)
        // 任一附加图层打开中：三个样式开关连同整行一起置灰 —— 图层互斥，它们此刻不生效。
        // 置灰而不是隐藏，是为了让"关闭图层后会复原这三个开关"这件事看得见。
        // 两个图层行**始终可操作**（那是图层之间互切的唯一入口），不参与置灰。
        // 与 onLayerToggled 走同一个方法，灰化规则只此一处。
        refreshSwitchesEnabled(view)

        // v0.8.0：第六行动作行「导入台账 Excel」（与 Web 版 v0.3.0 同构）。
        // 先收起浮层再进系统文件选择器 —— SAF 选择器是全屏/底部面板，
        // 浮层开着会悬在它上面，视觉上两层叠着，还可能挡住选择器的"取消"。
        view.findViewById<View>(R.id.rowImport).setOnClickListener {
            displayPopup?.dismiss()
            importLauncher.launch(
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel",
                    "application/octet-stream",
                    "application/wps-office.xlsx"
                )
            )
        }

        val density = resources.displayMetrics.density
        val gapPx = (8 * density).toInt()

        // v0.7.22：先自量，再拿**实测宽度**显式建 PopupWindow。
        // 不能把宽度留给 WRAP_CONTENT —— 那样显示时还要按屏幕再量一次，
        // 一旦结果与自量不一致，锚点偏移就算错（v0.7.19 的"满屏贴边"正是这么来的）。
        val screenWidth = resources.displayMetrics.widthPixels
        view.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = if (view.measuredWidth > 0) {
            view.measuredWidth
        } else {
            dpToPx(POPUP_FALLBACK_WIDTH_DP)
        }

        val popup = PopupWindow(view, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 8f * density
        popup.animationStyle = R.style.PopupAnimBhqMap
        popup.isOutsideTouchable = true
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        // 缺这句，isOutsideTouchable 不生效 —— 点浮层外面关不掉
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.setOnDismissListener { displayPopup = null }
        displayPopup = popup

        val anchorHeight =
            if (binding.btnCoop.height > 0) binding.btnCoop.height else (56 * density).toInt()
        var xoff = -(popupWidth + gapPx)
        // 兜底：窄屏上若浮层左边缘会跑到屏幕外，钳到左边留 8dp
        // （宁可压住按钮，也不能像 v0.7.19 那样被系统裁回屏内、变成满屏贴边）
        val anchorLoc = IntArray(2).also { binding.btnCoop.getLocationInWindow(it) }
        val minLeftPx = (8 * density).toInt()
        if (anchorLoc[0] + xoff < minLeftPx) {
            xoff = -(anchorLoc[0] - minLeftPx)
        }
        var yoff = -((view.measuredHeight + anchorHeight) / 2)

        // v0.7.28：浮层长到五行（约 240dp），垂直居中于按钮时上下都可能探出屏幕。
        // PopupWindow 默认会把探出的部分裁回屏内，裁的结果是浮层贴着屏幕上/下边，
        // 而它的动画与圆角都按"居中于按钮"设计，贴边会显得突兀 —— 这里先行钳住，
        // 上边探出就往下推、下边探出就往上提，最多推到"离屏幕边 8dp"。
        // （按钮本身在屏幕垂直中部，正常情况根本不会触发，这是防御性代码。）
        val marginY = (8 * density).toInt()
        val screenH = resources.displayMetrics.heightPixels
        var top = anchorLoc[1] + anchorHeight + yoff
        if (top < marginY) {
            yoff += marginY - top
            top = marginY
        }
        val bottom = top + view.measuredHeight
        if (bottom > screenH - marginY) {
            yoff -= bottom - (screenH - marginY)
        }

        popup.showAsDropDown(binding.btnCoop, xoff, yoff)
    }

    /**
     * 主按钮的激活态：面板里是否有开关被打开。
     * v0.7.15 起三个样式开关默认全关，所以主按钮常态未激活 —— 只有开了全称标注才点亮。
     * v0.7.27 起闲置地块图层也算"面板有自定义状态"，v0.7.28 起闲置厂房同样算。
     */
    private fun panelHasCustomState(): Boolean =
        coopVisible || parkVisible || aboveVisible || layerMode != LayerMode.NONE

    /** 面板开关切换后的统一收尾：按钮激活态、重绘、读数刷新 */
    private fun onDisplayToggle() {
        binding.btnCoop.isSelected = panelHasCustomState()
        binding.map.invalidate()
        scheduleTelemetry()
    }

    /**
     * 附加图层开关的联动（v0.7.27 建为 [onIdleToggled]，**v0.7.28 重构为三态**）。
     *
     * 洲哥两次要求的措辞几乎一样，只是多了一个互斥对象：
     *   v0.7.27：「开关打开后，协作区项目、园区项目、规上企业均关闭（如在打开状态），
     *             所有的企业名也都不显示……关闭开关后复原」
     *   v0.7.28：「……协作区项目、园区项目、规上企业、**闲置地块**均关闭（如在打开状态）……
     *             3、上一版加入的闲置地块开关打开后闲置厂房开关也关闭」
     *
     * 所以两个图层的行为完全对称，本方法对两者一视同仁，只有 [target] 不同。
     *
     * ------------------------------------------------------------------
     * 三种迁移，各做各的事（[LayerMode] 三态的意义就在这里）
     * ------------------------------------------------------------------
     *   ① NONE → 某图层：**先快照**三个样式开关，再全部关闭 —— 顺序不能反，
     *      反了就存成"关闭后的状态"、关闭图层时复原不回去；
     *   ② 某图层 → NONE：按快照把三个样式开关复原；
     *   ③ 图层 → 另一图层：**快照与复原都不做**。快照留的是"最初那次"，
     *      所以「地块开 → 直接切厂房 → 关厂房」一路复原到最初的三开关状态，
     *      而不是复原到"地块开着的时候"那个中间态。
     *
     * 三件连带动作（每次都要做，与迁移种类无关）：
     *   1. [EnterpriseOverlay.namesHidden] = 是否处于图层态 —— 企业名全撤、**圆点保留**；
     *   2. 两个图层 overlay 的 visible 按 [layerMode] 各归各位（互斥由此保证：
     *      它们由同一个变量派生，物理上不可能同时为 true）；
     *   3. 浮层五个开关**同步回写 + 重新计算置灰**：被另一个图层顶掉的那一行要
     *      当场弹回未选中，用户才看得到"互斥真的发生了"。
     *
     * 注意：浮层开关的改写是**程序性赋值**，必须摘掉监听再改（见 [setCheckedSilently]），
     * 否则会被 switchListener 当成用户操作，把快照冲掉。
     */
    private fun onLayerToggled(target: LayerMode, on: Boolean) {
        val wasOn = layerMode != LayerMode.NONE
        layerMode = if (on) target else LayerMode.NONE
        val nowOn = layerMode != LayerMode.NONE

        if (!wasOn && nowOn) {
            // ① 从"无图层"进入图层态：快照 + 强制关闭三个样式开关
            savedCoop = coopVisible
            savedPark = parkVisible
            savedAbove = aboveVisible
            coopVisible = false
            parkVisible = false
            aboveVisible = false
        } else if (wasOn && !nowOn) {
            // ② 退出图层态：按快照复原三个样式开关
            coopVisible = savedCoop
            parkVisible = savedPark
            aboveVisible = savedAbove
        }
        // ③ 图层互换：什么都不做（快照保持"最初那次"）

        // 三个样式开关的字段值要真正推给企业层 —— 图层态下 namesHidden 已让名称全隐，
        // 这一步此刻看不出差别，但退出图层态时 overlay 必须与字段一致，否则会"复原了个寂寞"。
        enterpriseOverlay.showCoop = coopVisible
        enterpriseOverlay.showPark = parkVisible
        enterpriseOverlay.showAbove = aboveVisible

        // 企业名总闸：只要处在图层态就全隐（圆点在 layoutLabels 里已先落、不受影响）
        enterpriseOverlay.namesHidden = nowOn
        // 两个图层由同一个 layerMode 派生 —— 互斥不需要额外判断，也不可能出现"两个都开"
        parcelOverlay.visible = layerMode == LayerMode.PARCEL
        idleFactoryOverlay.visible = layerMode == LayerMode.FACTORY

        // 浮层开着时同步视图（关着就什么都不做 —— 下次打开浮层时按字段赋初值）
        syncPopupSwitchState()
        refreshSwitchesEnabled()

        onDisplayToggle()
    }

    /** 把字段状态回写到浮层里的五个开关视图（浮层未打开则跳过） */
    private fun syncPopupSwitchState() {
        val content = displayPopup?.contentView ?: return
        setCheckedSilently(content.findViewById<SwitchCompat>(R.id.swCoop), coopVisible)
        setCheckedSilently(content.findViewById<SwitchCompat>(R.id.swPark), parkVisible)
        setCheckedSilently(content.findViewById<SwitchCompat>(R.id.swAbove), aboveVisible)
        setCheckedSilently(content.findViewById<SwitchCompat>(R.id.swIdle), idleVisible)
        setCheckedSilently(content.findViewById<SwitchCompat>(R.id.swFactory), factoryVisible)
    }

    /**
     * 处在图层态时：三个样式开关**连同整行**一起置灰（图层互斥，它们此刻不生效）。
     *
     * 两个图层行**不置灰** —— 它们是图层之间互切的唯一入口。
     * 洲哥要求"闲置地块打开后闲置厂房也关闭"，但没说要把那一行锁死；
     * 锁死了就没法从地块直接切到厂房，得先关一个再开一个，多一步。
     */
    private fun refreshSwitchesEnabled(content: View? = displayPopup?.contentView) {
        if (content == null) return
        val on = layerMode == LayerMode.NONE
        val rowAlpha = if (on) 1f else DISABLED_ROW_ALPHA
        content.findViewById<SwitchCompat>(R.id.swCoop).isEnabled = on
        content.findViewById<SwitchCompat>(R.id.swPark).isEnabled = on
        content.findViewById<SwitchCompat>(R.id.swAbove).isEnabled = on
        content.findViewById<View>(R.id.rowCoop).alpha = rowAlpha
        content.findViewById<View>(R.id.rowPark).alpha = rowAlpha
        content.findViewById<View>(R.id.rowAbove).alpha = rowAlpha
    }

    /**
     * 程序性改写开关状态而不惊动 [switchListener]：**摘掉 → 赋值 → 挂回**。
     * 挂回那一步是必须的 —— 漏了就是"这个开关以后点了没反应"。
     */
    private fun setCheckedSilently(sw: SwitchCompat, checked: Boolean) {
        if (sw.isChecked == checked) return
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener(switchListener)
    }

    // ------------------------------------------------------------------
    // 地图视野
    // ------------------------------------------------------------------

    /**
     * 定位到边合区全貌：把 [BianhequArea.boundingBox] 完整装进屏幕。
     * post{} 是为了等布局完成，否则 width/height 为 0 算不出来。
     */
    private fun fitToBhqArea() {
        binding.map.post {
            binding.map.zoomToBoundingBox(
                BianhequArea.boundingBox,
                /* animated = */ false,
                /* borderSizeInPixels = */ OVERVIEW_PADDING_PX
            )
            logTelemetry()
        }
    }

    // ------------------------------------------------------------------
    // 叠加层
    // ------------------------------------------------------------------

    /*
     * ══════════════════════════════════════════════════════════════════
     *  叠放顺序（v0.7.29 修正，改下面几个方法前必读）
     * ══════════════════════════════════════════════════════════════════
     *
     * osmdroid 按 `overlays` 列表下标从小到大绘制：下标小的在底、下标大的盖在上面。
     * 所以"谁压谁"完全由添加顺序决定 —— `add(x)` 追加到末尾 = 最上层，
     * `add(0, x)` 插到最前 = 最底层。**下面是本工程实际落定的叠放顺序（下标 0 → 4）**：
     *
     *     0  roadOverlay          路名                ← 骨架，最底
     *     1  MapScaleOverlay      左下角比例尺        ← 固定 UI（现状，本轮未动）
     *     2  enterpriseOverlay    企业圆点 + 名称     ← 内容
     *     3  parcelOverlay        闲置地块框体 + 名称
     *     4  idleFactoryOverlay   闲置厂房标记 + 两行标签   ← 最上层
     *
     * 两条硬约束（onCreate 里靠调用顺序 + `add(0, …)` 共同保证）：
     *   ① 路名垫底：`addRoadOverlay()` 用 `add(0, …)`；
     *   ② **两个附加图层在企业层之上**：它们用 `add(…)` 追加（见下条注释）。
     *
     * **约束 ② 是 v0.7.29 修正的现场问题**：地块层与厂房层原先都写成 `add(0, …)`
     * 压在最底，结果打开"闲置厂房"后，厂房名称与 12dp 方块被企业圆点成片压住
     * （圆点比标签小，但它画得晚）。判据不是"谁的面积大"，而是
     * **谁是被打开的主角** —— 用户刚把这一层打开，这一层的名称就必须可读。
     *
     * 付出的代价值得写清楚，免得后人又"修"回去：
     *   ① 企业圆点会被地块 14% 淡紫填充盖一层（14% 而已，圆点本身是 70% 半透明，
     *      肉眼几乎无差别）；
     *   ② 厂房方块与企业圆点重合时方块在上（方块正是用户打开这层要看的东西）；
     *   ③ 地块 2dp 边框可能从某个圆点上划过（仅限恰好落在框线上的那几个点）。
     * 三者都远小于"名称被压住"的代价。
     */

    /** 企业点位与名称标签层 */
    private fun addEnterpriseOverlay() {
        enterpriseOverlay = EnterpriseOverlay(
            context = this,
            dataProvider = { enterprises },
            onEnterpriseTap = { showEnterprisePanel(it) }
        ).apply {
            onLayoutChanged = { scheduleTelemetry() }
            // 面板三个开关的初值：v0.7.15 起一律默认关（= 全图只出简称）
            showCoop = coopVisible
            showPark = parkVisible
            showAbove = aboveVisible
        }
        binding.map.overlays.add(enterpriseOverlay)
    }

    /** 左下角比例尺 */
    private fun addScaleBar() {
        binding.map.overlays.add(0, MapScaleOverlay(binding.map))
    }

    /**
     * 道路名称层（v0.7.16）。
     *
     * 用 `add(0, …)` 插到叠加层列表最前 —— osmdroid 按列表顺序绘制，
     * 索引 0 最先画、压在最底。道路是骨架、企业是内容，内容该盖在骨架之上：
     * 企业点与名称（后加入）会压住路名，反之不会。
     *
     * 本层不参与企业标签的避让计算，也不受三个"全称模式"开关影响 ——
     * 路名是底图注记，与业务图层各管各的。
     *
     * 路网为空（KML 缺失/解析失败）时照样挂上：draw 里首行即返回，开销为零，
     * 而 [logTelemetry] 可以无条件读它的计数，不必到处判空。
     */
    private fun addRoadOverlay() {
        roadOverlay = RoadOverlay(
            context = this,
            dataProvider = { roads }
        )
        binding.map.overlays.add(0, roadOverlay)
    }

    /**
     * 闲置地块图层（v0.7.27；叠放位置 v0.7.29 修正）。
     *
     * 用 `add(…)` 追加到列表末尾 = 压在企业层**之上**。
     * v0.7.27 原本写的是 `add(0, …)`（逻辑是"地块是地、企业是地上的内容，地该在下面"），
     * 但那套地理隐喻只对**框体填充**成立，对**框体中央的地块名称**是反的 ——
     * 名称是注记，注记被企业圆点压住就没法读了。同一个图层只能有一个叠放位置，
     * 既然这一层打开时企业名本来就全撤（[EnterpriseOverlay.namesHidden]）、
     * 且地块名称要可读，就整体放到企业层之上。权衡详见 [addEnterpriseOverlay] 上方的
     * "叠放顺序"注释块。
     *
     * 默认 [ParcelOverlay.visible] = false（洲哥要求"默认不显示"），由浮层第四行控制；
     * 图层为空（KML 缺失/解析失败）时照样挂上：draw 首行即返回，开销为零，
     * [logTelemetry] 可以无条件读它的计数，不必到处判空。
     */
    private fun addParcelOverlay() {
        parcelOverlay = ParcelOverlay(
            context = this,
            dataProvider = { parcels }
        )
        binding.map.overlays.add(parcelOverlay)
    }

    /**
     * 闲置厂房图层（v0.7.28；叠放位置 v0.7.29 修正）。
     *
     * 用 `add(…)` 追加到列表末尾 = **整张图上最上层**（地块层之后添加）。
     * v0.7.28 原本是 `add(0, …)`，当时的注释写着"标记只有 12dp 一小块，
     * 企业圆点压不住它的信息"—— **那句判断是错的**：标记是小，但每处还跟着
     * 两行名称/面积文字（最宽约 280px），企业圆点成片压上去，标签直接不可读。
     * 现场验证即由此发现问题，v0.7.29 改为追加。
     *
     * 与地块层一样：两者**互斥**，同时只有一个 [visible] = true（由 [layerMode] 派生），
     * 所以不必操心两层叠在一起的观感。默认关（洲哥要求"默认不显示"）。
     */
    private fun addIdleFactoryOverlay() {
        idleFactoryOverlay = IdleFactoryOverlay(
            context = this,
            dataProvider = { idleFactories }
        )
        binding.map.overlays.add(idleFactoryOverlay)
    }

    // ------------------------------------------------------------------
    // 定位
    // ------------------------------------------------------------------

    private fun onLocateClicked() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) {
            toggleLocation()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_LOCATION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                toggleLocation()
            } else {
                Toast.makeText(this, "未授权定位权限，无法定位", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 开/关定位层。首次开启拿到定位后自动平移过去。
     * v0.4：图标改为 20dp 导航箭头（红描边 + 黑半透明），以箭头几何中心为锚点；
     *       精度圈默认关闭，避免大蓝圈喧宾夺主。
     */
    private fun toggleLocation() {
        val overlay = locationOverlay ?: createLocationOverlay().also {
            locationOverlay = it
            binding.map.overlays.add(0, it)
        }

        if (overlay.isMyLocationEnabled) {
            overlay.disableMyLocation()
            Toast.makeText(this, "已停止定位", Toast.LENGTH_SHORT).show()
        } else {
            overlay.enableMyLocation()
            Toast.makeText(this, "定位中…（请在室外开阔处使用）", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createLocationOverlay(): MyLocationNewOverlay {
        val arrow = rasterizeVector(R.drawable.ic_my_location, dpToPx(LOCATOR_SIZE_DP))
        return MyLocationNewOverlay(GpsMyLocationProvider(this), binding.map).apply {
            setDrawAccuracyEnabled(false)
            // 有方位角时画方向箭头、无方位角时画人员图标；
            // 两种情况都用同一枚箭头，观感一致（setDirectionIcon 在 6.1.20 已存在，已用 javap 核对）
            setPersonIcon(arrow)
            setPersonAnchor(0.5f, 0.5f)
            setDirectionIcon(arrow)
            setDirectionAnchor(0.5f, 0.5f)
            runOnFirstFix {
                runOnUiThread {
                    myLocation?.let { binding.map.controller.animateTo(it) }
                }
            }
        }
    }

    /** 把矢量资源栅格化成指定边长的位图（osmdroid 定位层只吃 Bitmap） */
    private fun rasterizeVector(resId: Int, sizePx: Int): Bitmap {
        val drawable = ContextCompat.getDrawable(this, resId)
            ?: throw IllegalStateException("drawable $resId not found")
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bmp
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // 台账 Excel 导入（v0.8.0）
    // ------------------------------------------------------------------

    /**
     * 用户在系统文件选择器里选中台账后的入口（[importLauncher] 的回调）。
     *
     * 三段式：**解析（后台线程）→ 确认（统计对话框）→ 替换（后台线程 + 重载）**。
     * 解析和替换都必须离开主线程 —— 295 行的台账虽小，但 zip 解压 + XML 解析
     * 在低端机上有可感知的停顿，而"让地图卡一下"是本工程的红线。
     */
    private fun onLedgerPicked(uri: Uri) {
        Toast.makeText(this, "正在解析台账…", Toast.LENGTH_SHORT).show()
        val fileName = queryDisplayName(uri)
        Thread {
            val parsed = runCatching {
                val sheets = contentResolver.openInputStream(uri)?.use { LedgerXlsx.parse(it) }
                    ?: throw LedgerXlsx.LedgerFormatException("打不开选中的文件")
                val ent = LedgerParser.parseEnterprises(sheets)
                val fac = LedgerParser.parseFactories(sheets)
                ent to fac
            }
            runOnUiThread {
                parsed.fold(
                    onSuccess = { (ent, fac) -> showImportConfirm(fileName, ent, fac) },
                    onFailure = { t -> showImportError(t) }
                )
            }
        }.start()
    }

    /** 导入确认框：把解析统计摆给用户看，确认后才真正替换数据 */
    private fun showImportConfirm(
        fileName: String,
        ent: LedgerParser.EnterpriseResult,
        fac: LedgerParser.FactoryResult?
    ) {
        val st = ent.stats
        val msg = buildString {
            append("已解析「").append(fileName).append("」：\n\n")
            append("· 企业条目 ").append(st.total)
                .append("（全称 ").append(st.full).append(" / 仅简称 ").append(st.shortonly).append("）\n")
            append("· 可上图 ").append(st.withcoord)
                .append(" · 无坐标档案 ").append(st.nocoord).append("\n")
            append("· 同点位重复组 ").append(st.dupGroups).append("（仅提示，不合并）\n")
            if (fac == null) {
                append("· 闲置厂房：台账无此表（保留现有数据）\n")
            } else {
                append("· 闲置厂房 ").append(fac.stats.total).append(" 处")
                    .append("（缺坐标跳过 ").append(fac.stats.skippedNoCoord).append("）\n")
            }
            append("\n将替换地图上的企业与闲置厂房数据，替换前自动备份上一份。")
        }
        AlertDialog.Builder(this)
            .setTitle("导入台账")
            .setMessage(msg)
            .setPositiveButton("替换") { _, _ -> applyImport(fileName, ent, fac) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 真正落盘 + 重载数据 + 刷新地图。替换也放后台线程（写文件 + 重新解析 JSON） */
    private fun applyImport(
        sourceName: String,
        ent: LedgerParser.EnterpriseResult,
        fac: LedgerParser.FactoryResult?
    ) {
        val factoryStore = IdleFactoryStore(this)
        Thread {
            val okE = runCatching { store.replaceAll(ent.enterprises) }.getOrDefault(false)
            // 台账没有「闲置厂房」表时不动厂房数据（与 Web 版 v0.3.1 同语义）
            val okF = fac == null ||
                runCatching { factoryStore.replaceAll(fac.factories, sourceName) }
                    .getOrDefault(false)

            runOnUiThread {
                if (!okE || !okF) {
                    Toast.makeText(this, "替换失败：数据未变更（旧数据已保留）", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                // dataProvider 每帧取值（见 EnterpriseOverlay.layoutLabels），
                // 重新赋字段 + invalidate 即可让全部图层换数据，无需重建 overlay。
                enterprises = store.load()
                idleFactories = factoryStore.load()
                binding.map.invalidate()
                logTelemetry()
                val facCount = fac?.stats?.total ?: -1
                Toast.makeText(
                    this,
                    "导入完成：企业 ${ent.stats.total} 条 · 可上图 ${ent.stats.withcoord}" +
                        if (facCount >= 0) " · 厂房 $facCount 处" else "",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    /** 解析失败的分类提示 —— 每种失败都告诉用户"下一步做什么"，不甩异常文本 */
    private fun showImportError(t: Throwable) {
        val msg = when (t) {
            is LedgerXlsx.LedgerFormatException -> t.message ?: "文件无法识别"
            is LedgerParser.MissingLedgerSheetException -> t.message ?: "缺少工作表"
            is ZipException -> "不是有效的 .xlsx 文件。老版 .xls 请先用电脑另存为 .xlsx"
            else -> "解析失败：${t.message ?: t.javaClass.simpleName}"
        }
        AlertDialog.Builder(this)
            .setTitle("导入失败")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
    }

    /** SAF 文件的显示名（确认框标题用）；查不到就用 "台账.xlsx" 兜底 */
    private fun queryDisplayName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull() ?: "台账.xlsx"
    }

    // ------------------------------------------------------------------
    // 版权信息页（v0.7.30）
    // ------------------------------------------------------------------

    /**
     * 版权信息页（入口：右下角版本号）。
     *
     * 版式 B「分段卡片式」（洲哥 2026-09-24 从三版里选定）：标题栏（图标 + 应用名 + 版本号）
     * → 「开发信息」卡（权益方 / 开发团队 / 开发时间）→ 「版权与数据来源」卡
     * （版权声明 + 底图来源 + 数据说明）→ 页脚版权行 → 关闭按钮。
     * 选它不是因为好看 —— 三版里它不是最好看的那一版 ——
     * 而是**它是唯一还能继续加行的结构**：将来要补"数据更新日期 / 意见反馈 / 技术支持"，
     * 往卡片里塞一行即可，不必重排。
     *
     * 三个实现要点（改这个对话框前必读）：
     *   1. **window 背景必须设透明**。主题是 `DayNight`，系统在浅色模式下会给 AlertDialog
     *      一个白底圆角背景，把 dialog_about 自己的深色底裹在里面、四角露出白边；
     *      设透明后这一页的外观完全由布局决定，系统深浅色都不影响
     *      （布局里每个文字颜色都已显式指定，不依赖主题 attr）。
     *   2. **宽度显式设 300dp**（[ABOUT_DIALOG_WIDTH_DP]），不交给 AlertDialog 默认测量 ——
     *      AppCompat 会给自定义视图套一层 `windowMinWidth`（随主题 / 字号设置而变），
     *      宽度不可控。`setLayout` **必须在 `show()` 之后**调：show 之前 window 的 decor
     *      还没挂上，设了会被之后的测量覆盖。
     *   3. **三条关闭路径**要齐：关闭按钮 / 点面板外部 / 返回键。
     *      后两条来自 AlertDialog 的 `cancelable = true`（默认值），不需要写代码 ——
     *      但要知道它们从哪来，别在后续改动里顺手把 cancelable 关掉。
     *
     * 版本号取 [BuildConfig.VERSION_NAME] / [BuildConfig.VERSION_CODE] 运行时填充，
     * 与右下角那一行**同源** —— 升版本时两处自动跟着走，不靠人记得改。
     */
    private fun showAboutDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        view.findViewById<TextView>(R.id.tvAboutVer).text =
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        // 要点 1：不设透明，浅色系统主题下会露出系统白底圆角
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()

        // 要点 2：show 之后才有 decor，此时设宽度才生效；窄屏钳到「屏宽 − 32dp」
        val screenWidth = resources.displayMetrics.widthPixels
        val width = dpToPx(ABOUT_DIALOG_WIDTH_DP).coerceAtMost(screenWidth - dpToPx(32))
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        view.findViewById<View>(R.id.btnAboutClose).setOnClickListener { dialog.dismiss() }
    }

    // ------------------------------------------------------------------
    // 搜索
    // ------------------------------------------------------------------

    private fun showSearchDialog() {
        if (enterprises.isEmpty()) {
            Toast.makeText(this, "暂无企业数据", Toast.LENGTH_SHORT).show()
            return
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply { hint = "名称 / 简称 / 主营业务关键词" }
        val wrap = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                input,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        AlertDialog.Builder(this)
            .setTitle("搜索企业")
            .setView(wrap)
            .setPositiveButton("搜索") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) doSearch(q)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doSearch(query: String) {
        val hits = enterprises.filter {
            it.name.contains(query, true) ||
                it.shortName?.contains(query, true) == true ||
                it.mainBusiness.contains(query, true) ||
                // v0.7.11：简介也参与匹配 —— 坐标重复条目去重后，被合并掉的
                // 项目全称写在简介里（"协作区项目：…"），这样仍搜得到
                it.intro.contains(query, true)
        }
        when {
            hits.isEmpty() -> Toast.makeText(this, "未找到「$query」", Toast.LENGTH_SHORT).show()
            hits.size == 1 -> focusEnterprise(hits[0])
            else -> AlertDialog.Builder(this)
                .setTitle("找到 ${hits.size} 家")
                .setItems(hits.map { it.labelText }.toTypedArray()) { _, which ->
                    focusEnterprise(hits[which])
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /**
     * 搜索结果跳转：有点位就平移过去，缺坐标就只弹档案并说明原因。
     *
     * v0.7.15：删去"搜到项目就自动打开对应开关"的逻辑 —— 三个开关已不控制显隐，
     * 项目无论如何都在图上，不会再出现"跳过去什么都没有"。也不该因为一次搜索
     * 就把 18 家协作区名字全改成全称（那是明显的越权副作用）。
     */
    private fun focusEnterprise(e: Enterprise) {
        hideEnterprisePanel()
        if (e.hasCoord) {
            binding.map.controller.animateTo(GeoPoint(e.lat, e.lon))
        } else {
            Toast.makeText(this, "「${e.name}」暂无坐标，未在地图标出", Toast.LENGTH_SHORT).show()
        }
        showEnterprisePanel(e)
    }

    // ------------------------------------------------------------------
    // 企业详情面板
    // ------------------------------------------------------------------

    private fun showEnterprisePanel(e: Enterprise) {
        // v0.8.1：去掉标题后的「（简称）」后缀（洲哥要求）。
        // 该后缀自 v0.6 起用于提醒"台账全称列留空、待补"，但面板里它紧挨企业名，
        // 看着像名称的一部分，且与"名称待补全"的提示意图重复 → 一律只显示名称本身。
        // nameIsShort 字段本身保留（渲染/搜索口径都依赖它）。
        binding.tvEntName.text = e.name

        val badges = mutableListOf<String>()
        if (e.isGov) badges.add("行政/事业单位")
        if (e.isCoop) badges.add("协作区项目")
        if (e.isPark) badges.add("园区项目")
        if (e.isKey) badges.add("重点企业")
        if (e.aboveScale) badges.add("规模以上")
        if (badges.isEmpty()) {
            binding.tvEntScale.visibility = View.GONE
        } else {
            binding.tvEntScale.text = badges.joinToString(" · ")
            binding.tvEntScale.visibility = View.VISIBLE
        }

        // v0.4：无主营业务整栏隐藏，不显示"—"占位
        if (e.mainBusiness.isBlank()) {
            binding.tvEntBusiness.visibility = View.GONE
        } else {
            binding.tvEntBusiness.text = e.mainBusiness
            binding.tvEntBusiness.visibility = View.VISIBLE
        }

        // v0.7.20：简介每段首行缩进 2 字符（见 introWithIndent）
        binding.tvEntIntro.text = introWithIndent(e.intro.ifBlank { "（暂无简介）" })

        // 坐标行：缺坐标时明说，别让读数区的空白看起来像显示故障
        binding.tvEntCoord.text = if (!e.hasCoord) {
            "坐标待补充 · 未在地图标出"
        } else {
            val coord = "WGS-84   %.6f, %.6f".format(e.lat, e.lon)
            e.cluster?.let { "$coord   ·   $it" } ?: coord
        }

        // v0.7.20：面板根是固定布局，只有 scrollIntro 会滚 —— 每次打开都回到简介顶部
        binding.panelEnt.visibility = View.VISIBLE
        binding.scrollIntro.scrollTo(0, 0)
        binding.btnPanelClose.visibility = View.VISIBLE
        fitPanelHeight()
    }

    private fun hideEnterprisePanel() {
        binding.panelEnt.visibility = View.GONE
        binding.btnPanelClose.visibility = View.GONE
    }

    /**
     * 把面板剩余高度让给简介区（v0.7.20）。
     *
     * 面板上限仍是 [PANEL_MAX_HEIGHT_DP]（360dp），但头部（企业名可能两行、徽章有无、
     * 主营业务有无）与尾部坐标行的高度**逐条企业不同**，所以简介区的可用高度不能写成
     * 一个固定值，只能实测：
     *
     *     others = 面板总高 − 简介区高      （头部 + 尾部 + 各处间距）
     *     avail  = 360dp − others           （简介区最多能占多高）
     *
     * 把 avail 写进简介区的 `matchConstraintMaxHeight`，配合布局里的
     * `layout_height="wrap_content" + constrainedHeight`，得到的就是
     * 「min(简介自然高, avail)」：简介短 → 面板贴着内容、不滚动；简介长 → 面板正好顶到
     * 360dp、简介区内部滚动。
     *
     * 用 [doOnLayout] 而不是 post：[View.post] 的回调可能早于本次 requestLayout 的布局
     * 完成，读到的 height 还是 0 或上一条企业的旧值；doOnLayout 保证回调时面板已按
     * 新内容量好。
     */
    private fun fitPanelHeight() {
        val panel = binding.panelEnt
        val intro = binding.scrollIntro

        panel.doOnLayout {
            // 上一次若走过"把简介区高度定死"的兜底路径，先放回 wrap_content 再量 ——
            // 否则 others 会把上次定死的高度算进"固定部分"，可用空间越算越小。
            if (intro.layoutParams.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                intro.layoutParams = intro.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                intro.doOnLayout { applyIntroMaxHeight() }
            } else {
                applyIntroMaxHeight()
            }
        }
    }

    /** [fitPanelHeight] 的后半段：按当前布局算出的可用空间，收紧简介区高度上限 */
    private fun applyIntroMaxHeight() {
        val panel = binding.panelEnt
        val intro = binding.scrollIntro
        val maxPx = dpToPx(PANEL_MAX_HEIGHT_DP)
        val others = panel.height - intro.height
        val avail = (maxPx - others).coerceAtLeast(dpToPx(INTRO_MIN_HEIGHT_DP))

        val lp = intro.layoutParams as ConstraintLayout.LayoutParams
        if (lp.matchConstraintMaxHeight != avail) {
            lp.matchConstraintMaxHeight = avail
            intro.layoutParams = lp
        }
    }

    /**
     * 简介每段首行缩进 2 字符（v0.7.20）。
     *
     * 用 [LeadingMarginSpan] 而不是在每段前补两个全角空格：
     *   · 补空格的方案只对"恰好断在段首"的第一行有效，一旦折行，第二行也会被顶出
     *     一段空白（除非逐行判断），长段落一折行就露馅；
     *   · LeadingMarginSpan.Standard(first, rest) 是段落级 span：first 给段落首行、
     *     rest 给其余行，所以 Standard(indent, 0) 恰好是「首行缩进、折行顶格」，
     *     与出版物排版一致，而且对 `\n` 分出的每一段自动生效。
     *
     * 缩进宽度按"两个汉字"算 —— 汉字字宽≈字号，故取 2 × textSize（px），
     * 字号改了自动跟随，不写死 dp。
     */
    private fun introWithIndent(text: String): CharSequence {
        val indent = (binding.tvEntIntro.textSize * 2f).toInt()
        val span = SpannableString(text)
        span.setSpan(
            LeadingMarginSpan.Standard(indent, 0),
            0,
            span.length,
            Spanned.SPAN_INCLUSIVE_EXCLUSIVE
        )
        return span
    }

    // ------------------------------------------------------------------
    // 校准读数（原顶部状态栏，v0.4 移入 Logcat + 长按指北针）
    // ------------------------------------------------------------------

    private fun scheduleTelemetry() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTelemetryAt < TELEMETRY_THROTTLE_MS) return
        lastTelemetryAt = now
        binding.map.post { logTelemetry() }
    }

    /** z / m/px / 分档 / 企业计数。现场标定分档阈值就看这份读数 */
    private fun logTelemetry() {
        val map = binding.map

        val mpp = enterpriseOverlay.metersPerPixel
        val mppText = if (mpp.isNaN() || mpp <= 0.0) "--" else "%.2f".format(mpp)

        // v0.7.15：三个开关含义统一为"是否用全称标注"，读数改成"全称"前缀（开/关都报）
        val styleMark = " · 全称 协作区:" + (if (coopVisible) "开" else "关") +
                " 园区:" + (if (parkVisible) "开" else "关") +
                " 规上:" + (if (aboveVisible) "开" else "关")
        // v0.7.24：中间档（只给重点/行政出名称）单独报出来 —— 这一档标签数会明显变少，
        // 读数里点明"是档位在起作用"，免得被误读成"标签掉了"。
        val keyGovMark =
            if (map.zoomLevelDouble > EnterpriseOverlay.KEY_GOV_ZOOM_MIN &&
                map.zoomLevelDouble < EnterpriseOverlay.KEY_GOV_ZOOM_MAX
            ) " · 仅重点+行政" else ""
        val line1 = "z=%.2f   %s m/px   分档:%s%s%s".format(
            map.zoomLevelDouble, mppText, LodConfig.labelOf(enterpriseOverlay.lodLevel),
            keyGovMark, styleMark
        )
        // 台账总数与可上图数分开报：两者不等时差额就是"有名称没坐标"的条目，
        // 需要去台账补坐标，不是地图漏画。
        val mapped = enterprises.count { it.hasCoord }
        // 末尾的"容差"就是 EnterpriseOverlay.OVERLAP_RATIO —— 标签允许的最大重叠比例。
        // 现场"标签掉太多"或"糊成一片"时，看这个数决定往哪调。
        val line2 = "台账 %d 条（可上图 %d）· 视野内 %d · 标签 %d · 避让 %d · 重叠容差 %d%%".format(
            enterprises.size,
            mapped,
            enterpriseOverlay.visibleCount,
            enterpriseOverlay.labelCount,
            enterpriseOverlay.hiddenByCollision,
            (EnterpriseOverlay.OVERLAP_RATIO * 100).toInt()
        )
        // v0.7.16：路网读数单独一行。路名"未标"只有两种原因 ——
        // 该路在屏幕上太短、衬不下这个路名；或与其他路名撞位后挪无可挪。
        // v0.7.17：路名有显示门槛（z ≥ 17）。未到门槛时直接报门槛，不报那三个计数 ——
        // 全是 0 会被误读成"路名功能没生效"，报门槛才说得清"是没到该看的档位"。
        val line3 = if (map.zoomLevelDouble < RoadOverlay.MIN_ZOOM) {
            "路名 z≥%.0f 才显示（当前 z=%.2f）".format(
                RoadOverlay.MIN_ZOOM, map.zoomLevelDouble
            )
        } else {
            "道路 %d 条（视野内 %d）· 路名 %d · 未标 %d".format(
                roads.size,
                roadOverlay.roadCount,
                roadOverlay.labelCount,
                roadOverlay.droppedCount
            )
        }
        // v0.7.27：附加图层单独一行。v0.7.28 两个图层互斥，**一行说清两个图层**：
        // 图层只有"地块 / 厂房 / 无"三种状态，报成一件事比占两行更清楚
        // （原「闲置地块 关」的写法随之作废）。
        // 名称"未标"的原因是它被屏幕切掉太多、或与别的名称撞位（见两层各自的 Overlay）。
        // 处在图层态时三个样式开关必然全关、企业名全隐，读数里一并说明，
        // 免得现场看到"协作区:关"以为开关失灵。
        val line4 = when (layerMode) {
            LayerMode.NONE -> "图层 无（闲置地块 / 闲置厂房 均关）"
            LayerMode.PARCEL -> "图层 闲置地块 · 地块 %d（视野内 %d）· 名称 %d · 未标 %d · 样式开关关 · 企业名全隐".format(
                parcels.size,
                parcelOverlay.visibleCount,
                parcelOverlay.labelCount,
                parcelOverlay.hiddenCount
            )
            LayerMode.FACTORY -> "图层 闲置厂房 · 厂房 %d（视野内 %d）· 名称 %d · 未标 %d · 样式开关关 · 企业名全隐".format(
                idleFactories.size,
                idleFactoryOverlay.visibleCount,
                idleFactoryOverlay.labelCount,
                idleFactoryOverlay.hiddenCount
            )
        }
        lastTelemetry = "$line1\n$line2\n$line3\n$line4"
        Log.d(TAG, lastTelemetry.replace("\n", "   |   "))
    }

    // ------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        locationOverlay?.onResume()
    }

    override fun onPause() {
        locationOverlay?.onPause()
        binding.map.onPause()
        super.onPause()
    }
}
