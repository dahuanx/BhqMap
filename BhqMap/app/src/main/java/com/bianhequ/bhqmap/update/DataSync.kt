package com.bianhequ.bhqmap.update

import com.bianhequ.bhqmap.data.EnterpriseJson
import com.bianhequ.bhqmap.data.EnterpriseStore
import com.bianhequ.bhqmap.data.IdleFactoryStore

/**
 * 台账数据热更新（v0.9.0）。
 *
 * 解决的是"台账改了，但代码没改"这一类更新 —— 从 v0.7.25 到 v0.8.1 的几次发版里，
 * 纯数据变化的占了大多数。这类更新不该走"打包 3 MB → 上传 → 全单位下载安装"的链路。
 *
 * ## 与"用户自己导入过台账"的关系（本模块最要紧的规则）
 *
 * 数据来源分四种，写在 `enterprises.json` 的 `source` 字段里：
 *
 *   `seed`    首次安装时从 assets 播种出来的 → **允许**被热更新替换
 *   `remote`  上一次热更新写进去的           → **允许**被热更新替换
 *   `import`  用户在机内导入 Excel 得到的     → **永不**自动替换（他的数据他说了算）
 *   `unknown` 早期版本写的文件，没有来源标记  → **不自动替换**，改为询问一次
 *
 * 判据刻意保守：**分不清来源时，一律当作"用户的数据"保护起来**。
 * 代价是多一次询问，收益是不会有人因为一次静默升级丢掉自己录入的台账 ——
 * 这个代价值得付。
 *
 * 交互上对应三种结果，见 [evaluate]：
 *   - [Decision.SHOULD_UPDATE] 静默更新，用户无感
 *   - [Decision.NEED_CONFIRM]  弹一次确认框
 *   - [Decision.SKIP_USER_DATA] 跳过，不打扰（用户的导入数据比内置台账新是他的选择）
 */
object DataSync {

    enum class Decision { UP_TO_DATE, SKIP_USER_DATA, NEED_CONFIRM, SHOULD_UPDATE }

    data class Result(val ok: Boolean, val detail: String)

    /** 只做判断，不下载 —— 供界面决定要不要弹框 */
    fun evaluate(store: EnterpriseStore, manifest: UpdateManifest): Decision {
        if (!manifest.hasData) return Decision.UP_TO_DATE
        if (manifest.dataVersion <= store.localDataVersion()) return Decision.UP_TO_DATE
        return when (store.localSource()) {
            EnterpriseStore.SOURCE_IMPORT -> Decision.SKIP_USER_DATA
            EnterpriseStore.SOURCE_UNKNOWN -> Decision.NEED_CONFIRM
            else -> Decision.SHOULD_UPDATE
        }
    }

    /**
     * 执行热更新。企业数据是"必须成功"的一步，厂房数据是"顺手带上"——
     * 厂房下载失败不影响已完成的企业数据替换（它是辅助图层，不该拖累主数据）。
     */
    fun apply(
        store: EnterpriseStore,
        factoryStore: IdleFactoryStore,
        manifest: UpdateManifest
    ): Result {
        if (!manifest.hasData) return Result(false, "清单未提供数据包")

        val entBytes = UpdateHttp.getBytes(manifest.enterprisesUrl)
            ?: return Result(false, "企业数据下载失败")
        if (!UpdateHttp.sha256(entBytes).equals(manifest.enterprisesSha256, ignoreCase = true)) {
            return Result(false, "企业数据校验未通过")
        }
        val enterprises = EnterpriseJson.parse(String(entBytes, Charsets.UTF_8))
        if (enterprises.isEmpty()) return Result(false, "企业数据解析为空")
        if (!store.replaceFromRemote(enterprises, manifest.dataVersion)) {
            return Result(false, "企业数据写入失败")
        }

        var factoryCount = -1
        if (manifest.factoriesUrl.isNotBlank()) {
            val fBytes = UpdateHttp.getBytes(manifest.factoriesUrl)
            if (fBytes != null && UpdateHttp.sha256(fBytes).equals(manifest.factoriesSha256, ignoreCase = true)) {
                val factories = factoryStore.parseText(String(fBytes, Charsets.UTF_8))
                if (factoryStore.replaceAll(factories, "网络同步 · " + manifest.dataSource)) {
                    factoryCount = factories.size
                }
            }
        }

        val detail = if (factoryCount >= 0) {
            "企业 ${enterprises.size} 条 · 闲置厂房 $factoryCount 处"
        } else {
            "企业 ${enterprises.size} 条"
        }
        return Result(true, detail)
    }
}
