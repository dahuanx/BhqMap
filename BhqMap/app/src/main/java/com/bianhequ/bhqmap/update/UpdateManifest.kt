package com.bianhequ.bhqmap.update

import org.json.JSONObject

/**
 * 更新清单（仓库根目录的 `update.json`）的内存表示。
 *
 * 这份文件由发布脚本 `.publish_gitee.py` 自动生成并推到仓库 master 分支，
 * App 只读、不改。字段定义与发布脚本一一对应，改字段必须两边同时改。
 *
 * 设计取舍：
 *   - 判版本一律用 [versionCode]（整数），不用 versionName 字符串比较 —— 字符串比大小
 *     在 "0.10.0" 与 "0.9.0" 这种场景下会得出错误答案。
 *   - [minSupportedCode] 为**强制更新**下界。第一期固定下发 0（即不启用强制）：
 *     这个字段一旦配错（比如误写成比当前版本还大的值），全单位手机会在启动时
 *     被不可取消的对话框锁死，且远程救不回来。宁可不用。
 *   - 数据包（[dataVersion] 等）与安装包分开描述：台账更新不必发版，
 *     这是「数据热更新」与「App 更新」共用一个清单文件的接口。
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    /** 低于此值的客户端必须更新。0 = 不强制 */
    val minSupportedCode: Int,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val notes: String,
    /** 台账数据版本，与包内种子 [com.bianhequ.bhqmap.data.EnterpriseStore.SEED_VERSION] 同一口径 */
    val dataVersion: Int,
    val dataSource: String,
    val enterprisesUrl: String,
    val enterprisesSha256: String,
    val factoriesUrl: String,
    val factoriesSha256: String
) {

    /** 清单里带有可用的安装包信息 */
    val hasApk: Boolean get() = apkUrl.isNotBlank() && apkSha256.isNotBlank()

    /** 清单里带有可用的数据包信息 */
    val hasData: Boolean
        get() = dataVersion > 0 && enterprisesUrl.isNotBlank() && enterprisesSha256.isNotBlank()

    /** 本机是否需要升级安装包 */
    fun isNewerThan(localVersionCode: Int): Boolean = versionCode > localVersionCode

    /** 是否强制更新（第一期恒为 false，因为 minSupportedCode 下发 0） */
    fun isMandatory(localVersionCode: Int): Boolean =
        minSupportedCode > 0 && localVersionCode < minSupportedCode

    companion object {
        /**
         * 解析清单。任何字段缺失或格式错误都不抛异常，返回 null 让调用方静默跳过 ——
         * 更新检查失败不该影响地图使用，这是本模块的底线。
         */
        fun parse(text: String): UpdateManifest? {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val code = root.optInt("versionCode", 0)
            if (code <= 0) return null

            val data = root.optJSONObject("data")
            return UpdateManifest(
                versionCode = code,
                versionName = root.optString("versionName"),
                minSupportedCode = root.optInt("minSupportedCode", 0),
                apkUrl = root.optString("apkUrl"),
                apkSizeBytes = root.optLong("apkSizeBytes", 0L),
                apkSha256 = root.optString("apkSha256"),
                notes = root.optString("notes"),
                dataVersion = data?.optInt("version", 0) ?: 0,
                dataSource = data?.optString("source").orEmpty(),
                enterprisesUrl = data?.optString("enterprisesUrl").orEmpty(),
                enterprisesSha256 = data?.optString("enterprisesSha256").orEmpty(),
                factoriesUrl = data?.optString("factoriesUrl").orEmpty(),
                factoriesSha256 = data?.optString("factoriesSha256").orEmpty()
            )
        }
    }
}
