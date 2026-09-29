package com.bianhequ.bhqmap.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 闲置厂房数据源（v0.7.28 建，v0.8.0 改造为「filesDir 优先、assets 兜底」）。
 *
 * v0.7.28 时是纯只读的包内资产（换数据 = 重跑脚本重新打包）；v0.8.0 随机内
 * Excel 导入一起改造：台账「闲置厂房」工作表导入后写 [FILE_NAME]，
 * 之后启动一律读 filesDir —— 与 [EnterpriseStore] 同一套「导入覆盖」语义，
 * 但**不做 seedVersion 比较**：filesDir 有文件就用它，删掉文件即回到包内种子
 * （这个"恢复出厂"动作留给以后按需做入口）。
 *
 * 序列化结构与 assets/idle_factories.json 完全一致
 * （{version, source, count, factories}），解析代码两边共用一份 [parse]。
 *
 * 解析用 Android 内置的 org.json，不引第三方库 —— 理由同 [EnterpriseJson]：
 * optString/optDouble 带默认值，缺字段、类型不对都能兜住，不会抛到调用方。
 */
class IdleFactoryStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    /**
     * 读取并解析闲置厂房。filesDir 有导入数据（v0.8.0 起）则优先；
     * 否则回退包内 assets。任何异常（JSON 损坏等）都退化为空列表 ——
     * 这是辅助图层，不该因为它把地图拖崩。
     */
    fun load(): List<IdleFactory> {
        val local = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        if (local != null) return parse(local)

        return try {
            val text = context.assets.open(ASSET_NAME)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            parse(text)
        } catch (t: Throwable) {
            Log.w(TAG, "闲置厂房 JSON 读取失败：$t")
            emptyList()
        }
    }

    /**
     * 整表替换（v0.8.0 机内导入走这里）。写前先把旧文件备份成 .bak，
     * 走「tmp 写入 → 删旧 → rename」三步，中途被杀也不会留下半截 JSON。
     * 返回是否成功；失败时旧数据原样保留。
     */
    fun replaceAll(factories: List<IdleFactory>, source: String): Boolean {
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        return runCatching {
            if (file.exists()) file.copyTo(File(context.filesDir, "$FILE_NAME.bak"), overwrite = true)
            tmp.writeText(serialize(factories, source), Charsets.UTF_8)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }.isSuccess
    }

    /** 当前是否在使用机内导入的数据（浮层/读数提示用） */
    val isImported: Boolean get() = file.exists()

    private fun serialize(factories: List<IdleFactory>, source: String): String {
        val arr = JSONArray()
        for (f in factories) {
            arr.put(JSONObject().apply {
                put("id", f.id)
                put("name", f.name)
                put("areaText", f.areaText)
                put("areaM2", f.areaM2)
                if (f.hasCoord) {
                    put("lon", f.lon)
                    put("lat", f.lat)
                }
            })
        }
        return JSONObject().apply {
            put("version", 1)
            put("source", source)
            put("count", factories.size)
            put("factories", arr)
        }.toString(2)
    }

    private fun parse(text: String): List<IdleFactory> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("factories") ?: return emptyList()

        val out = ArrayList<IdleFactory>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            // 名称是这层的唯一意义，空名称的条目直接丢
            if (name.isEmpty()) continue
            out.add(
                IdleFactory(
                    id = o.optString("id").ifBlank { "if-auto-$i" },
                    name = name,
                    areaText = o.optString("areaText").trim(),
                    areaM2 = o.optDouble("areaM2", 0.0),
                    lon = o.optDouble("lon", Double.NaN),
                    lat = o.optDouble("lat", Double.NaN)
                )
            )
        }

        val withCoord = out.count { it.hasCoord }
        val totalArea = "%.0f".format(out.sumOf { it.areaM2 })
        Log.d(
            TAG,
            "闲置厂房：解析到 ${out.size} 处 · 有坐标 $withCoord 处 · 闲置面积合计 $totalArea 平方米"
        )
        return out
    }

    companion object {
        /** filesDir 下的导入文件名（v0.8.0 起）；assets 内的种子同名 */
        const val FILE_NAME = "idle_factories.json"

        /** 闲置厂房 JSON 在 assets 下的文件名 */
        const val ASSET_NAME = "idle_factories.json"

        /** 与 [com.bianhequ.bhqmap.MainActivity] 同一个 Logcat 标签 */
        private const val TAG = "BhqMap"
    }
}
