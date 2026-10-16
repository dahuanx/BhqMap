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

    /**
     * 读取全部企业。
     *
     * **播种只在首次安装（本地没有任何文件）时发生** —— 这是 v0.9.0 的关键改动。
     *
     * 旧逻辑是「本地种子版本低于包内版本就用 assets 覆盖」，而机内导入台账写出的文件
     * 带的正是**写入当时的包内版本号**，于是每次发版提升 SEED_VERSION，
     * 用户导入的数据就会被静默整表覆盖一次，全程没有提示。
     * 手动分发时代这还只是"一次、有人知道"；接上应用内自动更新后，
     * 它会变成"全单位手机同一天批量静默覆盖"，风险被放大到不可接受。
     *
     * 内置数据的更新改由「数据热更新」负责（见 update/DataSync.kt）：
     * 它比对版本、校验哈希，并且对用户导入的数据主动让路。
     */
    fun load(): List<Enterprise> {
        if (!file.exists()) seedFromAssets()

        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?: return emptyList()

        val list = EnterpriseJson.parse(text)
        isSampleData = list.isNotEmpty() && list.all { it.id.startsWith(SAMPLE_ID_PREFIX) }
        return list
    }

    /** 本地数据版本（文件头 `seedVersion`）。无文件返回 0 */
    fun localDataVersion(): Int = readHeader().first

    /**
     * 本地数据来源。v0.8.1 及更早写出的文件没有这个字段，返回 [SOURCE_UNKNOWN] ——
     * 数据热更新会因此保守处理：不自动覆盖，改为询问一次。
     */
    fun localSource(): String = readHeader().second

    /**
     * 只读文件头，不解析 164 条正文。
     * 更新检查每次启动都要问一次，这条路要足够轻。
     */
    private fun readHeader(): Pair<Int, String> {
        if (!file.exists()) return 0 to SOURCE_UNKNOWN
        val root = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return 0 to SOURCE_UNKNOWN
        return root.optInt("seedVersion", 0) to
            root.optString("source").ifBlank { SOURCE_UNKNOWN }
    }

    /**
     * 整表替换。默认按"用户机内导入"记账（[SOURCE_IMPORT]）——
     * 这条来源标记决定它**不会被数据热更新自动覆盖**：用户自己录的数据，用户说了算。
     */
    fun replaceAll(list: List<Enterprise>, source: String = SOURCE_IMPORT): Boolean =
        writeAtomic(EnterpriseJson.serialize(list, source, SEED_VERSION))

    /**
     * 数据热更新写入（v0.9.0）：来源记 [SOURCE_REMOTE]，数据版本记清单下发的版本 ——
     * 这样下次检查时"版本没变"，就不会重复下载。
     */
    fun replaceFromRemote(list: List<Enterprise>, dataVersion: Int): Boolean =
        writeAtomic(EnterpriseJson.serialize(list, SOURCE_REMOTE, dataVersion))

    /** 原子写：先写 tmp → 备份旧的 → 删旧 → rename。中途被杀不会留下半截 JSON */
    private fun writeAtomic(text: String): Boolean {
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        return runCatching {
            if (file.exists()) file.copyTo(File(context.filesDir, "$FILE_NAME.bak"), overwrite = true)
            tmp.writeText(text, Charsets.UTF_8)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }.isSuccess
    }

    private fun seedFromAssets() {
        runCatching {
            context.assets.open(SAMPLE_ASSET).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            // 播种后补上来源标记，否则会被当成"来源不明"保护起来，
            // 第一次热更新时反而要多问用户一次 —— 明明就是内置数据。
            // assets 里的种子不带这个字段（它由脚本生成，不掺运行时状态），所以在这里补。
            markSeedSource()
        }
    }

    /** 给刚播种的文件补写 `source: seed`，正文原样保留 */
    private fun markSeedSource() {
        val root = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return
        if (root.optString("source").isNotBlank()) return
        root.put("source", SOURCE_SEED)
        runCatching { file.writeText(root.toString(2), Charsets.UTF_8) }
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
         * v9=2026-10-16 按新台账（边合区企业台账.xlsx，文件名由 +1 版改回）整表重建
         *    （`.rebuild_seed_v9.py`）：条目数 / 坐标 / 五个标记列 / 主营业务 / 闲置厂房表
         *    **均无变化**（164 / 可上图 154 / 无坐标 10 / 简介 103 / 主营 96），
         *    仅新增 td-25「绥芬河冠宇经贸有限公司」简介一条（102 → 103）。
         * 升级安装后首次启动生效。Excel 导入写入的数据也带当前版本号，不会被误重置。
         *
         * **v0.9.0 语义修正**：最后那句是 v0.8.1 时的判断，**实际是错的** ——
         * 导入写出的版本号等于写入当时的包内版本，于是之后每次发版提升 SEED_VERSION，
         * 用户导入的数据都会被静默整表覆盖。v0.9.0 已把播种改为「只在首次安装发生」，
         * 本常量随之变成纯粹的**数据版本号**（与发布清单 `update.json` 的 `data.version`
         * 同口径）。不要再靠提升它来让存量设备换数据 —— 那条路已经堵死，改用数据热更新。
         */
        const val SEED_VERSION = 9

        /**
         * 数据来源标记（v0.9.0）。
         *
         * 决定数据热更新能不能覆盖本地文件 —— 规则与理由见 `update/DataSync.kt` 的类注释。
         * 一句话：**分不清来源时，一律当作"用户的数据"保护起来。**
         */

        /** 首次安装时从 assets 播种出来的 */
        const val SOURCE_SEED = "seed"

        /** 用户在机内导入 Excel 得到的 —— **永不**被热更新自动覆盖 */
        const val SOURCE_IMPORT = "import"

        /** 上一次数据热更新写入的 */
        const val SOURCE_REMOTE = "remote"

        /** v0.8.1 及更早写出的文件没有来源标记 —— 保守当作"用户的数据"处理，只询问不覆盖 */
        const val SOURCE_UNKNOWN = "unknown"
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

    /**
     * [source] 记数据来源（seed / import / remote），[dataVersion] 记数据版本 ——
     * 两者都是 v0.9.0 为「数据热更新与用户导入共存」加的记账字段，
     * 读方见 [EnterpriseStore.localSource] / [EnterpriseStore.localDataVersion]。
     */
    fun serialize(
        list: List<Enterprise>,
        source: String = EnterpriseStore.SOURCE_SEED,
        dataVersion: Int = EnterpriseStore.SEED_VERSION
    ): String {
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
            put("seedVersion", dataVersion)
            put("source", source)
            put("count", list.size)
            put("enterprises", arr)
        }.toString(2)
    }
}
