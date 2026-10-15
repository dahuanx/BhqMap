package com.bianhequ.bhqmap.update

import com.bianhequ.bhqmap.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 更新链路的 HTTP 与哈希工具。
 *
 * 为什么不用 OkHttp / Retrofit：本工程全程零第三方网络库，更新链路的需求也很窄
 * （三次 GET：清单、数据包、安装包），没有拦截器、连接池、长连接的需求。
 * 少一个依赖就少一份体积与一份供应链风险 —— 这与[com.bianhequ.bhqmap.data.EnterpriseStore]
 * 里"宁可手写 org.json 也不引 Gson"是同一条原则。
 *
 * 关于 302：Gitee 的 raw 与附件下载都会重定向到 CDN（`raw.giteeusercontent.com` /
 * `foruda.gitee.com`）。[HttpURLConnection] 默认跟随同协议跳转，2026-10-15 用 JDK
 * 同款 API 实测可以取到正文与安装包，所以这里不用手写跳转处理。
 *
 * 所有方法都不抛异常、失败返回 null / false —— 调用方按"没拿到"处理即可。
 * 更新是锦上添花的功能，任何一步失败都不该让主界面出问题。
 */
internal object UpdateHttp {

    private const val CONNECT_TIMEOUT_MS = 6000
    private const val READ_TIMEOUT_MS = 20000

    /** 带版本号的 UA：Gitee 并不校验，但出问题时可从服务端日志分辨来源 */
    private const val USER_AGENT = "BhqMap"

    /** 取文本（清单文件） */
    fun getText(url: String): String? {
        return try {
            val conn = open(url)
            try {
                if (conn.responseCode != 200) null
                else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                runCatching { conn.disconnect() }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 取字节（台账数据文件，几百 KB 量级，可直接进内存） */
    fun getBytes(url: String): ByteArray? {
        return try {
            val conn = open(url)
            try {
                if (conn.responseCode != 200) null
                else conn.inputStream.use { it.readBytes() }
            } finally {
                runCatching { conn.disconnect() }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 流式下载到文件（安装包 3~4 MB，走流式，边下边回报进度）。
     *
     * 返回 true 表示"下载完整"：HTTP 200、未被取消、且实际字节数与 Content-Length 一致。
     * 服务端未给 Content-Length 时不做长度校验（只保证正常读到流尾）。
     */
    fun download(
        url: String,
        dest: File,
        onProgress: ((Long, Long) -> Unit)?,
        isCancelled: (() -> Boolean)?
    ): Boolean {
        return try {
            val conn = open(url)
            try {
                if (conn.responseCode != 200) return false
                val total = conn.contentLengthLong
                var done = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (isCancelled?.invoke() == true) return false
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            done += n
                            onProgress?.invoke(done, total)
                        }
                        output.flush()
                    }
                }
                total <= 0 || done == total
            } finally {
                runCatching { conn.disconnect() }
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** SHA-256（小写十六进制），用于校验下载内容与清单声明一致 */
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return toHex(digest)
    }

    fun sha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            toHex(digest.digest())
        } catch (t: Throwable) {
            ""
        }
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "$USER_AGENT/${BuildConfig.VERSION_NAME}")
        conn.setRequestProperty("Accept", "*/*")
        return conn
    }
}
