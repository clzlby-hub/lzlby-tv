package org.moontechlab.selenetv.service

import android.util.Log
import org.moontechlab.selenetv.model.LiveChannel
import org.moontechlab.selenetv.model.LiveChannelGroup
import org.moontechlab.selenetv.model.LiveSource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 直播服务
 * 管理直播源、频道的获取和缓存
 */
object LiveService {
    private const val TAG = "LiveService"
    private const val TIMEOUT = 30000
    private const val CACHE_DURATION_MS = 2 * 60 * 60 * 1000L // 2小时
    private const val MAX_M3U_BYTES = 10 * 1024 * 1024 // 10MB 上限，防止超大响应 OOM
    private const val MAX_REDIRECTS = 5

    // 预编译正则
    private val TVG_URL_REGEX = Regex("""(?:x-tvg-url|url-tvg)="([^"]*)"""")
    private val TVG_ID_REGEX = Regex("""tvg-id="([^"]*)"""")
    private val TVG_NAME_REGEX = Regex("""tvg-name="([^"]*)"""")
    private val TVG_LOGO_REGEX = Regex("""tvg-logo="([^"]*)"""")
    private val GROUP_TITLE_REGEX = Regex("""group-title="([^"]*)"""")

    // 缓存（线程安全：可能被多个协程并发读写不同源）
    @Volatile private var sourcesCache: CacheItem<List<LiveSource>>? = null
    private val channelsCache = ConcurrentHashMap<String, CacheItem<List<LiveChannel>>>()

    private data class CacheItem<T>(
        val data: T,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - timestamp > CACHE_DURATION_MS
    }

    /**
     * 获取直播源列表
     * @param forceRefresh 是否强制刷新缓存
     */
    fun getLiveSources(forceRefresh: Boolean = false): List<LiveSource> {
        if (!forceRefresh) {
            sourcesCache?.let { cache ->
                if (!cache.isExpired()) return cache.data
            }
        }

        return try {
            val list = mutableListOf<LiveSource>()
            var customUrl = AppDataService.getCustomLiveUrlSync().trim()
            if (customUrl.isEmpty()) {
                val defaultLiveFile = java.io.File(org.moontechlab.selenetv.SeleneApplication.instance.filesDir, "live/custom_live.m3u")
                if (defaultLiveFile.exists() && defaultLiveFile.length() > 0) {
                    customUrl = defaultLiveFile.absolutePath
                }
            }

            if (customUrl.isNotEmpty()) {
                val name = if (customUrl.startsWith("/") || customUrl.startsWith("file://")) {
                    val fn = java.io.File(customUrl.removePrefix("file://")).name
                    "本地电视 ($fn)"
                } else {
                    "电视直播"
                }
                list.add(LiveSource(key = "custom_live", name = name, url = customUrl, ua = ""))
            }

            val repoSources = org.moontechlab.selenetv.data.Repositories.sources().getLiveSources()
            for (s in repoSources) {
                if (list.none { it.url == s.url }) {
                    list.add(s)
                }
            }

            sourcesCache = CacheItem(list)
            list
        } catch (e: Exception) {
            Log.e(TAG, "获取直播源失败: ${e.message}")
            sourcesCache?.data ?: throw e
        }
    }

    /**
     * 获取直播频道列表
     * 从直播源 URL 获取 M3U 内容并解析
     */
    fun getLiveChannels(source: LiveSource, forceRefresh: Boolean = false): List<LiveChannel> {
        if (!forceRefresh) {
            channelsCache[source.key]?.let { cache ->
                if (!cache.isExpired()) return cache.data
            }
        }

        return try {
            val m3uContent = fetchM3uContent(source)
            val channels = parseM3U(source.key, m3uContent)
            channelsCache[source.key] = CacheItem(channels)
            channels
        } catch (e: Exception) {
            Log.e(TAG, "获取直播频道失败: ${e.message}")
            channelsCache[source.key]?.data ?: throw e
        }
    }

    /**
     * 将频道列表按分组归类
     */
    fun groupChannels(channels: List<LiveChannel>): List<LiveChannelGroup> {
        val grouped = linkedMapOf<String, MutableList<LiveChannel>>()
        for (channel in channels) {
            val groupName = channel.group.ifEmpty { "未分组" }
            grouped.getOrPut(groupName) { mutableListOf() }.add(channel)
        }
        return grouped.map { (name, chs) -> LiveChannelGroup(name, chs) }
    }

    /**
     * 获取 HTTP 请求 M3U 内容
     */
    private fun fetchM3uContent(source: LiveSource): String {
        var url = source.url.trim()
        if (url.startsWith("/") || url.startsWith("file://")) {
            val path = if (url.startsWith("file://")) url.substring(7) else url
            val file = java.io.File(path)
            if (file.exists() && file.isFile) {
                return decodeResponse(file.readBytes())
            } else {
                throw Exception("本地直播源文件不存在: $path")
            }
        }

        var redirects = 0
        while (true) {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            connection.setRequestProperty(
                "User-Agent",
                source.ua.ifEmpty { "AptvPlayer/1.4.10" }
            )

            try {
                val statusCode = connection.responseCode
                // 手动处理跨协议重定向：HttpURLConnection 默认不跟随 http<->https 跳转，
                // 而很多 M3U 源会从 http 301 到 https，否则直接失败
                if (statusCode == 301 || statusCode == 302 || statusCode == 303 ||
                    statusCode == 307 || statusCode == 308
                ) {
                    val location = connection.getHeaderField("Location")
                    if (location != null && redirects < MAX_REDIRECTS) {
                        url = URL(URL(url), location).toString()
                        redirects++
                        continue
                    }
                }
                if (statusCode != 200) {
                    throw Exception("请求失败: $statusCode")
                }

                val bytes = connection.inputStream.readBytesCapped(MAX_M3U_BYTES)
                return decodeResponse(bytes)
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * 读取输入流，超过上限即抛错，避免超大/恶意响应导致 OOM
     */
    private fun InputStream.readBytesCapped(max: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            total += n
            if (total > max) {
                throw Exception("M3U 内容过大（超过 ${max / (1024 * 1024)}MB）")
            }
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }

    /**
     * 解码响应字节（支持 UTF-8 和 Latin-1）
     */
    private fun decodeResponse(bytes: ByteArray): String {
        return try {
            val text = String(bytes, Charsets.UTF_8)
            if (!text.contains('\uFFFD')) text
            else String(bytes, Charsets.ISO_8859_1)
        } catch (_: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    /**
     * 解析 M3U 或 TXT 内容为频道列表
     */
    private fun parseM3U(sourceKey: String, content: String): List<LiveChannel> {
        if (!content.contains("#EXTINF:") && (content.contains("#genre#") || content.contains(","))) {
            return parseTxtPlaylist(sourceKey, content)
        }

        val channels = mutableListOf<LiveChannel>()
        val lines = content.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        var channelIndex = 0

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            if (line.startsWith("#EXTM3U")) {
                i++
                continue
            }

            if (line.startsWith("#EXTINF:")) {
                val tvgId = TVG_ID_REGEX.find(line)?.groupValues?.get(1) ?: ""
                val tvgName = TVG_NAME_REGEX.find(line)?.groupValues?.get(1) ?: ""
                val rawLogo = TVG_LOGO_REGEX.find(line)?.groupValues?.get(1) ?: ""
                val group = GROUP_TITLE_REGEX.find(line)?.groupValues?.get(1) ?: "未分组"

                val commaIndex = line.lastIndexOf(',')
                val title = if (commaIndex != -1 && commaIndex < line.length - 1) {
                    line.substring(commaIndex + 1).trim()
                } else ""

                val name = title.ifEmpty { tvgName }
                val logo = normalizeLogoUrl(rawLogo, name, tvgId)

                if (i + 1 < lines.size && !lines[i + 1].startsWith("#")) {
                    val url = lines[i + 1]
                    if (name.isNotEmpty() && url.isNotEmpty()) {
                        try {
                            URL(url) // 验证 URL 格式
                            channels.add(
                                LiveChannel(
                                    id = "$sourceKey-$channelIndex",
                                    tvgId = tvgId,
                                    name = name,
                                    logo = logo,
                                    group = group,
                                    url = url
                                )
                            )
                            channelIndex++
                        } catch (_: Exception) {
                            // 无效 URL，跳过
                        }
                    }
                    i += 2
                    continue
                }
            }
            i++
        }

        return channels
    }

    /**
     * 规范化并补全台标 URL，修复失效域名并对常见央视/卫视自动补全台标
     */
    fun normalizeLogoUrl(rawLogo: String, channelName: String, tvgId: String): String {
        val trimmed = rawLogo.trim()
        if (trimmed.isNotEmpty()) {
            if (trimmed.contains("live.fanmingming.com/tv/")) {
                return trimmed.replace("https://live.fanmingming.com/tv/", "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/")
                    .replace("http://live.fanmingming.com/tv/", "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/")
            }
            if (trimmed.contains("raw.githubusercontent.com/fanmingming/live/main/tv/")) {
                return trimmed.replace("https://raw.githubusercontent.com/fanmingming/live/main/tv/", "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/")
            }
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return trimmed
            }
        }

        // 若原台标为空或不可用，依据频道名和 tvg-id 自动推断
        val candidate = (channelName.ifEmpty { tvgId })
            .replace("［.*?］".toRegex(), "")
            .replace("\\[.*?\\]".toRegex(), "")
            .replace("（.*?）".toRegex(), "")
            .replace("\\(.*?\\)".toRegex(), "")
            .replace("4K", "")
            .replace("8K", "")
            .replace("FHD", "")
            .replace("UHD", "")
            .replace("HD", "")
            .replace("SD", "")
            .replace("超清", "")
            .replace("高清", "")
            .replace("标清", "")
            .replace("综合", "")
            .replace("频道", "")
            .replace(" ", "")
            .replace("-", "")
            .trim()

        val upper = candidate.uppercase(java.util.Locale.getDefault())

        // 央视 CCTV
        if (upper.startsWith("CCTV")) {
            val numPart = upper.removePrefix("CCTV")
            if (numPart.contains("5+") || numPart.contains("5PLUS")) {
                return "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/CCTV5plus.png"
            }
            if (numPart.contains("4K")) return "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/CCTV4K.png"
            if (numPart.contains("8K")) return "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/CCTV8K.png"
            if (numPart.contains("新闻")) return "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/CCTV13.png"
            if (numPart.contains("少儿")) return "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/CCTV14.png"
            if (numPart.contains("音乐")) return "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/CCTV15.png"
            if (numPart.contains("农业") || numPart.contains("农村")) return "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/CCTV17.png"
            val digits = numPart.filter { it.isDigit() }
            if (digits.isNotEmpty()) {
                return "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/CCTV$digits.png"
            }
        }

        // CGTN
        if (upper.startsWith("CGTN")) {
            if (upper.contains("法语") || upper.contains("FRENCH")) return "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/CGTNFrench.png"
            if (upper.contains("俄语") || upper.contains("RUSSIAN")) return "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/CGTNRussian.png"
            if (upper.contains("阿语") || upper.contains("ARABIC")) return "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/CGTNArabic.png"
            if (upper.contains("西语") || upper.contains("SPANISH")) return "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/CGTNSpanish.png"
            if (upper.contains("纪录") || upper.contains("DOC")) return "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/CGTNDocumentary.png"
            return "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/CGTN.png"
        }

        // 地方卫视（湖南卫视、浙江卫视、东方卫视等）
        if (candidate.endsWith("卫视")) {
            val encoded = java.net.URLEncoder.encode(candidate, "UTF-8")
            return "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/$encoded.png"
        }

        return ""
    }

    /**
     * 解析 TXT 格式直播源（格式：央视频道,#genre# \n CCTV-1,http://...）
     */
    private fun parseTxtPlaylist(sourceKey: String, content: String): List<LiveChannel> {
        val channels = mutableListOf<LiveChannel>()
        val lines = content.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        var currentGroup = "常用频道"
        var channelIndex = 0

        for (line in lines) {
            if (line.contains("#genre#")) {
                currentGroup = line.substringBefore(",").substringBefore("#").trim().ifEmpty { "其他" }
                continue
            }
            if (line.contains(",")) {
                val name = line.substringBefore(",").trim()
                val url = line.substringAfter(",").trim()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    val logo = normalizeLogoUrl("", name, name)
                    channels.add(
                        LiveChannel(
                            id = "$sourceKey-$channelIndex",
                            tvgId = name,
                            name = name,
                            logo = logo,
                            group = currentGroup,
                            url = url,
                        )
                    )
                    channelIndex++
                }
            }
        }
        return channels
    }

    /**
     * 清除所有缓存
     */
    fun clearAllCache() {
        sourcesCache = null
        channelsCache.clear()
    }

    /**
     * 清除频道缓存
     */
    fun clearChannelsCache() {
        channelsCache.clear()
    }
}
