package org.moontechlab.selenetv.service

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 电视直播电子节目单 (EPG) 服务
 * 支持 XMLTV (如 epg.112114.xyz) 本地日缓存与流式轻量解析，及 DIYP 接口按需查询。
 */
object EpgService {
    private const val TAG = "EpgService"
    private const val DEFAULT_EPG_URL = "https://fastly.jsdelivr.net/gh/fanmingming/live@main/e.xml"

    data class EpgProgram(
        val title: String,
        val start: String,     // "19:00"
        val stop: String,      // "19:30"
        val startMs: Long,
        val stopMs: Long,
    )

    data class ChannelEpg(
        val channelName: String,
        val currentProgram: EpgProgram?,
        val nextProgram: EpgProgram?,
        val allPrograms: List<EpgProgram> = emptyList(),
    )

    private val epgMap = ConcurrentHashMap<String, MutableList<EpgProgram>>()
    private val channelAliasMap = ConcurrentHashMap<String, String>() // tvgId/channelId -> displayName
    @Volatile private var isInitialized = false
    @Volatile private var isLoading = false
    private var lastLoadedDate: String = ""

    suspend fun init(context: Context, epgUrl: String) = withContext(Dispatchers.IO) {
        if (isLoading) return@withContext
        val todayStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        if (isInitialized && lastLoadedDate == todayStr && epgMap.isNotEmpty()) {
            return@withContext
        }

        // 优先使用 IPv4，防止 Android 电视在 IPv6 路由不通时超长挂起
        System.setProperty("java.net.preferIPv4Stack", "true")

        isLoading = true
        try {
            var url = epgUrl.trim().ifEmpty { DEFAULT_EPG_URL }
            if (url.contains("112114.xyz", ignoreCase = true)) {
                url = DEFAULT_EPG_URL
            }
            if (url.contains("diyp", ignoreCase = true) || url.contains("?ch=", ignoreCase = true)) {
                // DIYP 模式，按需查询，无需全量下载
                isInitialized = true
                lastLoadedDate = todayStr
                Log.i(TAG, "DIYP EPG configured: $url")
            } else {
                try {
                    loadXmltv(context, url, todayStr)
                } catch (e: Exception) {
                    Log.w(TAG, "Primary EPG failed: ${e.message}, falling back to default")
                    if (url != DEFAULT_EPG_URL) {
                        loadXmltv(context, DEFAULT_EPG_URL, todayStr)
                    } else throw e
                }
                isInitialized = true
                lastLoadedDate = todayStr
                Log.i(TAG, "XMLTV EPG loaded successfully, channels: ${epgMap.size}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load EPG: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    /**
     * 规范化频道名称以供模糊匹配：移除标清/超清/4K/HD/空格/连字符等
     */
    fun normalizeChannelName(name: String): String {
        if (name.isBlank()) return ""
        return name.uppercase(Locale.getDefault())
            .replace("HEVC", "")
            .replace("1080P", "")
            .replace("1080I", "")
            .replace("720P", "")
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
            .replace("-", "")
            .replace("_", "")
            .replace(" ", "")
            .replace("［.*?］".toRegex(), "")
            .replace("\\[.*?\\]".toRegex(), "")
            .replace("（.*?）".toRegex(), "")
            .replace("\\(.*?\\)".toRegex(), "")
            .trim()
    }

    /**
     * 查询指定频道的当前节目和下一档节目
     */
    fun getChannelEpg(channelName: String, tvgId: String = ""): ChannelEpg? {
        val now = System.currentTimeMillis()
        val normName = normalizeChannelName(channelName)
        val normId = normalizeChannelName(tvgId)

        // 尝试从内存缓存中查找
        val programs = (if (normId.isNotEmpty()) epgMap[normId] else null)
            ?: (if (normName.isNotEmpty()) epgMap[normName] else null)
            ?: channelAliasMap[normId]?.let { epgMap[it] }
            ?: channelAliasMap[normName]?.let { epgMap[it] }
            ?: epgMap.entries.firstOrNull { (k, _) ->
                (normName.isNotEmpty() && (k.contains(normName) || normName.contains(k))) ||
                (normId.isNotEmpty() && (k.contains(normId) || normId.contains(k)))
            }?.value

        if (programs.isNullOrEmpty()) {
            return null
        }

        val sortedPrograms = programs.sortedBy { it.startMs }
        var current: EpgProgram? = null
        var next: EpgProgram? = null

        for (i in sortedPrograms.indices) {
            val p = sortedPrograms[i]
            if (p.startMs <= now && now < p.stopMs) {
                current = p
                if (i + 1 < sortedPrograms.size) {
                    next = sortedPrograms[i + 1]
                }
                break
            } else if (p.startMs > now) {
                if (next == null) {
                    next = p
                }
                break
            }
        }

        return ChannelEpg(
            channelName = channelName,
            currentProgram = current,
            nextProgram = next,
            allPrograms = sortedPrograms,
        )
    }

    /**
     * 异步拉取 DIYP 单个频道 EPG（如果使用 DIYP 接口）
     */
    suspend fun fetchDiypChannel(diypBaseUrl: String, channelName: String): ChannelEpg? = withContext(Dispatchers.IO) {
        val normName = normalizeChannelName(channelName)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val reqUrl = if (diypBaseUrl.contains("?")) {
            "${diypBaseUrl}&ch=${normName}&date=${today}"
        } else {
            "${diypBaseUrl.trimEnd('/')}/?ch=${normName}&date=${today}"
        }

        try {
            val u = URL(reqUrl)
            val conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "AptvPlayer/1.4.10")
            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val root = JSONObject(jsonText)
            val list = root.optJSONArray("epg_data") ?: return@withContext null
            val programs = mutableListOf<EpgProgram>()
            val datePrefix = SimpleDateFormat("yyyy-MM-dd ", Locale.getDefault()).format(Date())
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val title = item.optString("title", "")
                val start = item.optString("start", "")
                val end = item.optString("end", "")
                val startMs = try { timeFormat.parse(datePrefix + start)?.time ?: 0L } catch (_: Exception) { 0L }
                val endMs = try { timeFormat.parse(datePrefix + end)?.time ?: 0L } catch (_: Exception) { 0L }
                if (title.isNotEmpty()) {
                    programs.add(EpgProgram(title, start, end, startMs, endMs))
                }
            }

            if (programs.isNotEmpty()) {
                epgMap[normName] = programs
                return@withContext getChannelEpg(channelName)
            }
        } catch (e: Exception) {
            Log.d(TAG, "fetchDiypChannel error: ${e.message}")
        }
        return@withContext null
    }

    private fun loadXmltv(context: Context, epgUrl: String, todayStr: String) {
        val cacheFile = File(context.cacheDir, "epg_${todayStr}.xml")
        if (!cacheFile.exists() || cacheFile.length() < 1024) {
            // 清理旧日期的缓存
            context.cacheDir.listFiles { _, name -> name.startsWith("epg_") && name.endsWith(".xml") }?.forEach {
                it.delete()
            }
            Log.i(TAG, "Downloading EPG from $epgUrl ...")
            downloadToFile(epgUrl, cacheFile)
        }

        if (cacheFile.exists() && cacheFile.length() > 0) {
            Log.i(TAG, "Parsing EPG file: ${cacheFile.length()} bytes")
            epgMap.clear()
            channelAliasMap.clear()
            FileInputStream(cacheFile).use { input ->
                parseXmltvStream(input)
            }
        }
    }

    private fun downloadToFile(urlStr: String, targetFile: File) {
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, tm, java.security.SecureRandom())

        val client = okhttp3.OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, tm[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder()
            .url(urlStr)
            .header("User-Agent", "AptvPlayer/1.4.10")
            .build()

        val tmpFile = File(targetFile.parentFile, targetFile.name + ".tmp")
        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                val body = resp.body ?: throw Exception("Empty body")
                tmpFile.outputStream().use { out ->
                    body.byteStream().copyTo(out)
                }
            }
            if (tmpFile.length() > 0) {
                tmpFile.renameTo(targetFile)
            }
        } finally {
            try { tmpFile.delete() } catch (_: Exception) {}
        }
    }

    private fun parseXmltvStream(input: InputStream) {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")

        var eventType = parser.eventType
        var currentChannelId = ""
        var currentStartStr = ""
        var currentStopStr = ""
        var currentTitle = ""
        val timeFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (name) {
                        "channel" -> {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            currentChannelId = normalizeChannelName(id)
                        }
                        "display-name" -> {
                            val displayName = parser.nextText().trim()
                            if (currentChannelId.isNotEmpty() && displayName.isNotEmpty()) {
                                val normDisplay = normalizeChannelName(displayName)
                                channelAliasMap[currentChannelId] = normDisplay
                                channelAliasMap[normDisplay] = currentChannelId
                            }
                        }
                        "programme" -> {
                            val ch = parser.getAttributeValue(null, "channel") ?: ""
                            currentChannelId = normalizeChannelName(ch)
                            currentStartStr = parser.getAttributeValue(null, "start") ?: ""
                            currentStopStr = parser.getAttributeValue(null, "stop") ?: ""
                            currentTitle = ""
                        }
                        "title" -> {
                            currentTitle = parser.nextText().trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name == "programme" && currentChannelId.isNotEmpty() && currentTitle.isNotEmpty()) {
                        val startMs = parseTimestamp(currentStartStr, timeFormat)
                        val stopMs = parseTimestamp(currentStopStr, timeFormat)
                        val startDisplay = formatTime(startMs)
                        val stopDisplay = formatTime(stopMs)

                        val prog = EpgProgram(
                            title = currentTitle,
                            start = startDisplay,
                            stop = stopDisplay,
                            startMs = startMs,
                            stopMs = stopMs,
                        )
                        epgMap.getOrPut(currentChannelId) { mutableListOf() }.add(prog)
                    }
                }
            }
            eventType = parser.next()
        }
    }

    private fun parseTimestamp(raw: String, format: SimpleDateFormat): Long {
        if (raw.length < 14) return 0L
        return try {
            val core = raw.substring(0, 14)
            format.parse(core)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatTime(epochMs: Long): String {
        if (epochMs <= 0L) return "--:--"
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
}
