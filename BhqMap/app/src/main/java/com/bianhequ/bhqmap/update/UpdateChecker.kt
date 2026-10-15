package com.bianhequ.bhqmap.update

import android.util.Base64
import com.bianhequ.bhqmap.BuildConfig
import org.json.JSONObject

/**
 * 更新清单的获取。两条通道都指向同一个 Gitee 仓库，**不跨站**：
 *
 *   主通道：仓库 raw 静态文件 —— 不消耗 API 配额，实测连打 30 次无任何限流
 *           （2026-10-15 实测；响应头带 Varnish CDN，`max-age=60`）。
 *   备通道：Gitee contents API —— 返回 base64 正文，匿名可读（实测 200）。
 *
 * 为什么备用通道不是 GitHub：`raw.githubusercontent.com` 在国内直连 DNS 解析失败
 * （2026-10-15 实测，此前"可用"是被本机代理救出来的假象）。一个在目标网络里
 * 根本连不上的备用通道等于没有备用通道，所以备用走同域的另一条路径 ——
 * 它能防住"raw 路径被限流/临时故障"，防不住"整个 gitee.com 不可达"。
 * 后者只能在上线前用手机实地验证（见施工单的验证清单）。
 */
object UpdateChecker {

    /**
     * 拉取并解析清单。两条通道都失败返回 null —— 调用方静默跳过，
     * 绝不为此弹出错误提示（更新检查失败不影响任何既有功能）。
     */
    fun fetch(): UpdateManifest? {
        UpdateHttp.getText(BuildConfig.UPDATE_MANIFEST_URL)?.let { text ->
            UpdateManifest.parse(text)?.let { return it }
        }
        return fetchViaContentsApi()
    }

    /** 备通道：Gitee contents API，正文是 base64（可能带换行） */
    private fun fetchViaContentsApi(): UpdateManifest? {
        val body = UpdateHttp.getText(BuildConfig.UPDATE_MANIFEST_API_URL) ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val content = root.optString("content")
        if (content.isBlank()) return null
        val text = runCatching {
            String(Base64.decode(content.replace("\n", "").replace("\r", ""), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return UpdateManifest.parse(text)
    }
}
