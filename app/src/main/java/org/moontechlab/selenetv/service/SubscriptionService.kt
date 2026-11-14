package org.moontechlab.selenetv.service

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.moontechlab.selenetv.model.LiveSource
import org.moontechlab.selenetv.model.SearchResource
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream

object SubscriptionService {
    private const val TAG = "SubscriptionService"
    private const val MAX_SUBSCRIPTION_BYTES = 5 * 1024 * 1024 // 5 MB 上限

    class SubscriptionException(message: String) : Exception(message)

    data class ParsedSubscription(
        val searchResources: List<SearchResource>,
        val liveSources: List<LiveSource>,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 纯解析：JSON 字符串 → ParsedSubscription。
     * 兼容原生 Selene 格式（api_site / lives 字典）及 TVBox 格式（sites / lives / channels 数组与字典）。
     * 失败抛 SubscriptionException。
     */
    fun parseSubscriptionJson(text: String): ParsedSubscription {
        val rootElement: JsonElement = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw SubscriptionException("订阅内容格式无效: ${e.message}")
        }

        val searchResources = mutableListOf<SearchResource>()
        val liveSources = mutableListOf<LiveSource>()

        fun parseSearchResource(mapKey: String, elem: JsonElement): SearchResource? {
            return try {
                if (elem !is JsonObject) return null
                val key = elem["key"]?.jsonPrimitive?.contentOrNull ?: mapKey
                val name = elem["name"]?.jsonPrimitive?.contentOrNull ?: key
                val api = elem["api"]?.jsonPrimitive?.contentOrNull
                    ?: elem["ext"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                if (api.isEmpty()) return null
                val detail = elem["detail"]?.jsonPrimitive?.contentOrNull
                val from = elem["from"]?.jsonPrimitive?.contentOrNull
                SearchResource(
                    key = key,
                    name = name,
                    api = api,
                    detail = detail,
                    from = from,
                    disabled = false,
                )
            } catch (_: Exception) {
                null
            }
        }

        fun parseLiveSource(mapKey: String, elem: JsonElement): LiveSource? {
            return try {
                if (elem !is JsonObject) return null
                val key = elem["key"]?.jsonPrimitive?.contentOrNull ?: mapKey
                val name = elem["name"]?.jsonPrimitive?.contentOrNull ?: key
                val url = elem["url"]?.jsonPrimitive?.contentOrNull ?: ""
                if (url.isEmpty()) return null
                val ua = elem["ua"]?.jsonPrimitive?.contentOrNull ?: ""
                val epg = elem["epg"]?.jsonPrimitive?.contentOrNull ?: ""
                val from = elem["from"]?.jsonPrimitive?.contentOrNull ?: ""
                LiveSource(
                    key = key,
                    name = name,
                    url = url,
                    ua = ua,
                    epg = epg,
                    from = from,
                    disabled = false,
                )
            } catch (_: Exception) {
                null
            }
        }

        if (rootElement is JsonObject) {
            // 解析搜索源：优先取 api_site，不存在则取 sites
            val sitesElem = rootElement["api_site"] ?: rootElement["sites"]
            when (sitesElem) {
                is JsonObject -> {
                    sitesElem.entries.forEach { (mapKey, value) ->
                        parseSearchResource(mapKey, value)?.let { searchResources.add(it) }
                    }
                }
                is JsonArray -> {
                    sitesElem.forEachIndexed { idx, value ->
                        parseSearchResource("site_$idx", value)?.let { searchResources.add(it) }
                    }
                }
                else -> {}
            }

            // 解析直播源：优先取 lives，不存在则取 channels
            val livesElem = rootElement["lives"] ?: rootElement["channels"]
            when (livesElem) {
                is JsonObject -> {
                    livesElem.entries.forEach { (mapKey, value) ->
                        parseLiveSource(mapKey, value)?.let { liveSources.add(it) }
                    }
                }
                is JsonArray -> {
                    livesElem.forEachIndexed { idx, value ->
                        parseLiveSource("live_$idx", value)?.let { liveSources.add(it) }
                    }
                }
                else -> {}
            }
        } else if (rootElement is JsonArray) {
            // 直接为站点数组
            rootElement.forEachIndexed { idx, value ->
                parseSearchResource("site_$idx", value)?.let { searchResources.add(it) }
            }
        }

        if (searchResources.isEmpty() && liveSources.isEmpty()) {
            throw SubscriptionException("订阅内容无可用源")
        }

        return ParsedSubscription(searchResources, liveSources)
    }

    /**
     * 同步阻塞：获取订阅内容（支持 HTTP/HTTPS 网络链接及 file:// 或 / 本地文件路径）
     * 自动识别 Base58 编码或直接 JSON 内容，解析并返回 ParsedSubscription。
     * 必须在 Dispatchers.IO 调用。
     */
    fun fetchAndParse(urlOrPath: String): ParsedSubscription {
        val body = loadContent(urlOrPath)
        val trimmed = body.trim()
        val text = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            // 原生明文 JSON
            trimmed
        } else {
            // 尝试 Base58 解码，失败则回退到原始明文
            try {
                val decoded = org.moontechlab.selenetv.util.Base58.decode(trimmed)
                String(decoded, Charsets.UTF_8)
            } catch (e: Exception) {
                trimmed
            }
        }
        val textTrimmed = text.trimStart()
        val looksLikeJson = textTrimmed.startsWith("{") || textTrimmed.startsWith("[")
        val isM3uOrTxt = !looksLikeJson && (text.startsWith("#EXTM3U") || text.contains("#genre#") || (text.contains(",") && text.contains("http")))
        if (isM3uOrTxt) {
            val name = if (urlOrPath.startsWith("http")) "网络直播源" else File(urlOrPath).nameWithoutExtension.ifEmpty { "本地直播源" }
            val liveSource = LiveSource(
                key = stableLiveKey(urlOrPath),
                name = name,
                url = urlOrPath,
                ua = "AptvPlayer/1.4.10",
                epg = "",
                from = if (urlOrPath.startsWith("http")) "online" else "local",
                disabled = false,
            )
            return ParsedSubscription(searchResources = emptyList(), liveSources = listOf(liveSource))
        }
        return parseSubscriptionJson(text)
    }

    /**
     * 读取订阅内容：网络 URL 或 本地文件
     */
    private fun loadContent(urlOrPath: String): String {
        val trimmed = urlOrPath.trim()
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> {
                httpGet(trimmed)
            }
            lower.startsWith("file://") -> {
                val path = trimmed.substring(7)
                readFileContent(path)
            }
            trimmed.startsWith("/") -> {
                readFileContent(trimmed)
            }
            else -> {
                val file = File(trimmed)
                if (file.exists() && file.isFile) {
                    readFileContent(file.absolutePath)
                } else {
                    throw SubscriptionException("订阅链接协议不支持（支持 http/https 或本地文件路径）")
                }
            }
        }
    }

    /**
     * 读取本地文件内容，兼顾 Android 常见存储路径映射
     */
    private fun readFileContent(rawPath: String): String {
        val path = when {
            rawPath.startsWith("/sdcard/") && !File(rawPath).exists() -> {
                val alt = rawPath.replace("/sdcard/", "/storage/emulated/0/")
                if (File(alt).exists()) alt else rawPath
            }
            rawPath.startsWith("/storage/emulated/0/") && !File(rawPath).exists() -> {
                val alt = rawPath.replace("/storage/emulated/0/", "/sdcard/")
                if (File(alt).exists()) alt else rawPath
            }
            else -> rawPath
        }

        val file = File(path)
        if (!file.exists()) {
            throw SubscriptionException("本地文件不存在: $path")
        }
        if (!file.isFile) {
            throw SubscriptionException("指定路径不是文件: $path")
        }
        if (!file.canRead()) {
            throw SubscriptionException("本地文件无读取权限（请检查存储权限设置）: $path")
        }
        if (file.length() > MAX_SUBSCRIPTION_BYTES) {
            throw SubscriptionException("本地文件超过 5MB 上限")
        }

        return try {
            file.readText(Charsets.UTF_8).trim()
        } catch (e: Exception) {
            throw SubscriptionException("读取本地文件失败: ${e.message}")
        }
    }

    /**
     * 后台刷新：使用已存订阅 URL 重新拉取并仅覆盖 sources（不动用户数据、不动订阅 URL）。
     * 返回 true=成功，false=失败（已记录日志）。
     */
    suspend fun refreshAndPersist(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val url = org.moontechlab.selenetv.data.local.LocalStore.getSubscriptionUrl() ?: return@withContext false
        runCatching { fetchAndParse(url) }.fold(
            onSuccess = { parsed ->
                val currentUrl = org.moontechlab.selenetv.data.local.LocalStore.getSubscriptionUrl()
                if (currentUrl != url) {
                    Log.w(TAG, "刷新期间订阅 URL 已变更，丢弃旧拉取结果")
                    return@fold false
                }
                org.moontechlab.selenetv.data.local.LocalStore.saveSubscriptionSources(
                    parsed.searchResources,
                    parsed.liveSources,
                )
                LiveService.clearAllCache()
                true
            },
            onFailure = {
                Log.w(TAG, "刷新订阅失败: ${it::class.simpleName}: ${it.message}")
                false
            },
        )
    }

    private fun stableLiveKey(urlOrPath: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(urlOrPath.toByteArray(Charsets.UTF_8))
        val hex = bytes.take(6).joinToString("") { "%02x".format(it) }
        return "live_$hex"
    }

    private fun httpGet(url: String): String {
        var currentUrl = url
        var redirects = 0
        while (redirects < 5) {
            val u = try {
                java.net.URL(currentUrl)
            } catch (e: java.net.MalformedURLException) {
                throw SubscriptionException("订阅链接格式无效: ${e.message}")
            }
            val scheme = u.protocol?.lowercase()
            if (scheme != "http" && scheme != "https") {
                throw SubscriptionException("订阅链接协议不支持（仅 http/https）")
            }
            val conn = u.openConnection() as java.net.HttpURLConnection
            if (conn is javax.net.ssl.HttpsURLConnection) {
                conn.sslSocketFactory = trustAllSslSocketFactory()
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            try {
                val code = conn.responseCode
                if (code in 301..308) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw SubscriptionException("订阅重定向缺少 Location ($code)")
                    currentUrl = java.net.URL(u, loc).toString()
                    redirects++
                    continue
                }
                if (code != 200) throw SubscriptionException("获取订阅内容失败 ($code)")
                return conn.inputStream.use { input ->
                    val capped = readBytesCapped(input, MAX_SUBSCRIPTION_BYTES)
                    String(capped, Charsets.UTF_8).trim()
                }
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        }
        throw SubscriptionException("订阅链接重定向次数过多")
    }

    private fun readBytesCapped(input: InputStream, cap: Int): ByteArray {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(tmp)
            if (n < 0) break
            total += n
            if (total > cap) throw SubscriptionException("订阅内容超过 ${cap / 1024} KB 上限")
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }

    private fun trustAllSslSocketFactory(): javax.net.ssl.SSLSocketFactory {
        val tm = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(null, tm, java.security.SecureRandom())
        return ctx.socketFactory
    }
}
