package com.bianhequ.bhqmap.update

import android.content.Context
import java.io.File

/**
 * 安装包下载 + 完整性校验。
 *
 * 两道校验必须都过：**大小**与 **SHA-256**。签名不同系统本来就会拒绝覆盖安装，
 * 所以这里防的不是"装到别人伪造的包"（那个由签名兜住），而是**传输损坏**：
 * 弱网下少几个字节的 APK 会被系统安装器报"解析包错误"，用户只会觉得"这软件坏了"。
 *
 * 关于断点续传：Gitee 附件**不支持 Range**（2026-10-15 实测，带 Range 头仍返回全量），
 * 所以不做续传，失败就整包重来。3~4 MB 的包在 4G 下重来一次的代价可以接受，
 * 换成"续传出错得到半个包"的坑不值得。
 */
object ApkDownloader {

    /**
     * 下载并校验。返回可安装的文件；任何一步失败返回 null（临时文件已清理）。
     *
     * [onProgress] 回调百分比（0~100）；服务端未给 Content-Length 时回调 -1，
     * 界面应显示为「不确定进度」而不是 0%。
     */
    fun download(
        context: Context,
        manifest: UpdateManifest,
        onProgress: ((Int) -> Unit)?,
        isCancelled: () -> Boolean
    ): File? {
        if (!manifest.hasApk) return null

        val dest = ApkInstaller.apkFile(context, manifest.versionName)
        // 先清掉上次的残留，避免"上次没下完的半个文件"被当成新包
        runCatching { if (dest.exists()) dest.delete() }
        runCatching { dest.parentFile?.mkdirs() }

        val completed = UpdateHttp.download(
            manifest.apkUrl,
            dest,
            { done, total ->
                onProgress?.invoke(if (total > 0) ((done * 100) / total).toInt() else -1)
            },
            isCancelled
        )

        if (!completed) {
            runCatching { dest.delete() }
            return null
        }
        if (!UpdateHttp.sha256(dest).equals(manifest.apkSha256, ignoreCase = true)) {
            runCatching { dest.delete() }
            return null
        }
        if (manifest.apkSizeBytes > 0 && dest.length() != manifest.apkSizeBytes) {
            runCatching { dest.delete() }
            return null
        }
        return dest
    }
}
