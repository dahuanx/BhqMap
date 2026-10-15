package com.bianhequ.bhqmap.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 拉起系统安装器 + 「安装未知应用」授权引导。
 *
 * 三条硬约束：
 *
 *   1. **必须走 FileProvider**。Android 7.0 起不允许把 `file://` URI 交给别的应用，
 *      否则抛 FileUriExposedException。本项目 minSdk 26，所以这里没有第二种写法。
 *      两个可能的落地目录（外部私有 / 内部私有）都必须在 `res/xml/file_paths.xml`
 *      里登记，漏登记会在取 URI 时抛 IllegalArgumentException。
 *
 *   2. **Android 8.0 起需要单独授权**。用户得为本应用打开「安装未知应用」开关，
 *      代码只能把用户送过去，不能代劳。下载**之前**就要判断并引导，
 *      否则用户等 3 MB 下载完才被拦，体验很差。
 *
 *   3. **安装包放在应用私有目录**。外部私有目录优先（空间大、不占内部存储），
 *      不可用时回退内部私有目录。两个位置都会随卸载一起清除，
 *      不在用户手机上留垃圾 —— 这是刻意选的，不用公共 Download 目录。
 */
object ApkInstaller {

    /** 安装包落地路径 */
    fun apkFile(context: Context, versionName: String): File {
        val ext = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val dir = ext ?: File(context.filesDir, "downloads").apply { mkdirs() }
        return File(dir, "BhqMap-v$versionName.apk")
    }

    /** 是否已获得「安装未知应用」授权（Android 8.0 以下视为已授权） */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 跳到本应用的「安装未知应用」授权页 */
    fun openPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 拉起系统安装器。返回 false 表示没有可处理该意图的 Activity（极少数定制 ROM），
     * 调用方应给出"请到文件管理器手动安装"的提示而不是静默失败。
     */
    fun install(context: Context, apk: File): Boolean {
        if (!apk.exists()) return false
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
            true
        }.getOrDefault(false)
    }

    /** 安装包是否已经下载过且完整（同版本） */
    fun cachedApk(context: Context, versionName: String, sha256: String): File? {
        val f = apkFile(context, versionName)
        if (!f.exists()) return null
        return if (sha256.isNotBlank() && UpdateHttp.sha256(f).equals(sha256, ignoreCase = true)) f else null
    }
}
