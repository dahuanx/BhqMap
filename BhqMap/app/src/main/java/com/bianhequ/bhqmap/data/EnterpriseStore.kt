package com.bianhequ.bhqmap.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 企业数据的存取。
 *
 * 为什么不用 Room：
 *   园区企业量级在数百家，且导入方式是"整表替换"而不是逐条增删改查。
 *   用 Room 要引入 KSP 注解处理器、编译变慢、迁移脚本要跟着版本走；
 *   用 JSON 文件 + 内存列表，备份/回滚就是一次文件复制，更简单也更可靠。
 *   触发迁移的信号：企业数超过 2000 家，或需要复杂的多条件查询与分页。
 *
 * 存储位置：
 *   filesDir/enterprises.json  —— 应用私有目录，无需任何存储权限。
 *   assets/sample_enterprises.json 只在首次启动时作为种子数据复制过去，
 *   之后永久保留，相当于"恢复出厂数据"的备份。
 */
class EnterpriseStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    /** 当前数据是否是内置示例（用于在界面上明确提示，避免把示例当真数据） */
    var isSampleData: Boolean = false
        private set

    /** 读取全部企业。本地数据缺文件或种子版本旧时，用 assets 里的数据重新播种。 */
    fun load(): List<Enterprise> {
        val localSeedVersion = if (file.exists()) {
            runCatching {
                JSONObject(file.readText(Charsets.UTF_8)).optInt("seedVersion", 1)
            }.getOrDefault(1)
        } else 0
        if (localSeedVersion < SEED_VERSION) seedFromAssets()

        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?: return emptyList()

        val list = EnterpriseJson.parse(text)
        isSampleData = list.isNotEmpty() && list.all { it.id.startsWith(SAMPLE_ID_PREFIX) }
        return list
    }

    /** 整表替换（v0.3 的 Excel 导入会走这里）。写前先备份，失败可回滚。 */
    fun replaceAll(list: List<Enterprise>): Boolean {
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        return runCatching {
            if (file.exists()) file.copyTo(File(context.filesDir, "$FILE_NAME.bak"), overwrite = true)
            tmp.writeText(EnterpriseJson.serialize(list), Charsets.UTF_8)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }.isSuccess
    }

    private fun seedFromAssets() {
        runCatching {
            context.assets.open(SAMPLE_ASSET).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    companion object {
        const val FILE_NAME = "enterprises.json"
        const val SAMPLE_ASSET = "sample_enterprises.json"
        const val SAMPLE_ID_PREFIX = "sample-"

        /**
         * 内置种子数据的版本。本地文件里的 seedVersion 低于此值时自动重新播种 ——
         * v1=虚构示例 36 家，v2=2026-09-22 真实台账 62 家，
         * v3=2026-09-22 台账清理版 54 家 + 新增「是否行政或公共事业单位」列，
         * v4=2026-09-22 台账第三次更新 74 条：20 家只有简称的条目入图，
         *    12 家无坐标的条目保留档案但不落点。
         * v5=2026-09-23 坐标重复条目去重 74 → 72 条（可上图 60 家）：
         *    两组"同一个点位录了项目与公司两条"的重复（汇丰饲料、鼎中冷链），
         *    保留公司主记录、继承项目标记（isCoop/isKey），项目全称写入简介。
         * v6=2026-09-23 台账新增「是否园区项目」列并补录 3 条园区项目
         *    （海岸印记海参加工项目 / 嘉业浸油项目 / 建东村冷库项目）：
         *    入包 72 → 75 条（可上图 60 → 63），并给所有条目补 isPark 字段。
         * v7=2026-09-24 台账大扩充后**整表重建**（`.rebuild_seed_v7.py`）：
         *    台账 295 序号（130 行预留空行）→ 有效 164 条（全称 67 + 仅简称 97），
         *    可上图 60 → **154 家**，无坐标 10 条保留档案不落点；
         *    id 规则改为 td-<台账行号>（台账为唯一数据源，重建后旧 id 不延续）；
         *    北寒村台账已标「行政」→ isGov；重名 3 对（木加木业/永财经贸/美博木业
         *    各 2 处、坐标不同）各自保留独立条目。
         * v8=2026-10-15 按新台账（边合区企业台账+1.xlsx）整表重建（`.rebuild_seed_v8.py`）：
         *    条目数、坐标、五个标记列、闲置厂房表**均无变化**（164 / 可上图 154 / 无坐标 10）；
         *    变的是内容：① **主营业务（L 列）首次有数据**（96 条）—— v7 时该列全空，
         *       脚本把 mainBusiness 写死空串，本版照读落库，详情页「主营业务」栏首次亮出；
         *    ② **简介（M 列）5 条 → 102 条**（3 条含换行分段，最长 691 字）；
         *    ③ 5 处企业名称订正（鸿基→洪基包装、海象皇宫冰淇淋→绥芬河市祥光经贸、
         *       永财经贸→永材经贸、名赢→名盈木业、瀚宇→瀚萱古典家具），行号不变。
         *    订正后「永财经贸」变为「永材经贸」，与行 118 同名 ⇒ 重名 3 组改为
         *    木加木业 / 永材经贸 / 美博木业，仍各自保留独立条目（待洲哥确认是否录重）。
         * 升级安装后首次启动生效。Excel 导入写入的数据也带当前版本号，不会被误重置。
         */
        const val SEED_VERSION = 8
    }
}

/**
 * 用 Android 内置的 org.json 手写解析，不引第三方库。
 *
 * 不用 Gson 的反射反序列化：Gson 在 Kotlin data class 上走 Unsafe 分配实例，
 * JSON 里缺字段时会把非空 String 悄悄塞成 null，读取时炸在很远的地方。
 * optString/optDouble 带默认值，缺字段、类型不对都能兜住。
 */
object EnterpriseJson {

    fun parse(text: String): List<Enterprise> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("enterprises") ?: return emptyList()

        val out = ArrayList<Enterprise>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val e = Enterprise(
                id = o.optString("id").ifBlank { "auto-$i" },
                name = o.optString("name"),
                shortName = o.optString("shortName").takeIf { it.isNotBlank() },
                lon = o.optDouble("lon", Double.NaN),
                lat = o.optDouble("lat", Double.NaN),
                mainBusiness = o.optString("mainBusiness"),
                intro = o.optString("intro"),
                aboveScale = o.optBoolean("aboveScale", false),
                isKey = o.optBoolean("isKey", false),
                isCoop = o.optBoolean("isCoop", false),
                // v0.7.13：台账新增「是否园区项目」列，与协作区项目各自独立开关控制显隐
                isPark = o.optBoolean("isPark", false),
                // v0.6 修复：此前漏读 isGov，种子里写了 true 运行时也一律变 false，
                // 导致行政/事业单位的五角星从来没画出来过。
                isGov = o.optBoolean("isGov", false),
                cluster = o.optString("cluster").takeIf { it.isNotBlank() },
                nameIsShort = o.optBoolean("nameIsShort", false)
            )
            if (e.isValid()) out.add(e)
        }
        return out
    }

    fun serialize(list: List<Enterprise>): String {
        val arr = org.json.JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("name", e.name)
                if (!e.shortName.isNullOrBlank()) put("shortName", e.shortName)
                // 坐标缺失就不写这两个字段：org.json 的 put(String, double) 遇到
                // NaN 会直接抛 JSONException，缺字段反倒能被 optDouble 的默认值兜住。
                if (e.hasCoord) {
                    put("lon", e.lon)
                    put("lat", e.lat)
                }
                put("mainBusiness", e.mainBusiness)
                put("intro", e.intro)
                put("aboveScale", e.aboveScale)
                if (e.isKey) put("isKey", true)
                if (e.isCoop) put("isCoop", true)
                if (e.isPark) put("isPark", true)
                if (e.isGov) put("isGov", true)
                if (e.nameIsShort) put("nameIsShort", true)
                if (!e.cluster.isNullOrBlank()) put("cluster", e.cluster)
            })
        }
        return JSONObject().apply {
            put("version", 2)
            put("seedVersion", EnterpriseStore.SEED_VERSION)
            put("count", list.size)
            put("enterprises", arr)
        }.toString(2)
    }
}
