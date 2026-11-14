package org.moontechlab.selenetv.server

import android.util.Log
import org.json.JSONObject
import org.moontechlab.selenetv.SeleneApplication
import org.moontechlab.selenetv.service.AppDataService
import org.moontechlab.selenetv.service.LiveService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 遥控器与手机传输 Web 服务器 + 本地 M3U8 反向代理 (原生 ServerSocket 高性能稳定实现)
 * 提供 Orion 风格极简精致 Web 助手、文本/订阅直传、本地源文件上传与 M3U8 直播代理。
 */
object RemoteControlServer {
    private const val TAG = "RemoteControlServer"
    private const val START_PORT = 28256
    private const val MAX_PORT_ATTEMPTS = 100
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val MAX_M3U8_BYTES = 10 * 1024 * 1024

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val isRunning = AtomicBoolean(false)
    private var currentPort: Int = START_PORT

    private var keyEventCallback: ((Int) -> Unit)? = null
    private var textInputCallback: ((String) -> Unit)? = null
    private var subscriptionCallback: ((String) -> Unit)? = null
    private var liveSourceUpdatedCallback: ((String) -> Unit)? = null

    fun getPort(): Int = currentPort

    fun setKeyEventCallback(callback: (Int) -> Unit) {
        keyEventCallback = callback
    }

    fun setTextInputCallback(callback: (String) -> Unit) {
        textInputCallback = callback
    }

    fun setSubscriptionCallback(callback: (String) -> Unit) {
        subscriptionCallback = callback
    }

    fun setLiveSourceUpdatedCallback(callback: (String) -> Unit) {
        liveSourceUpdatedCallback = callback
    }

    fun clearCallbacks() {
        keyEventCallback = null
        textInputCallback = null
        subscriptionCallback = null
        liveSourceUpdatedCallback = null
    }

    @Synchronized
    fun start(): Boolean {
        if (isRunning.get() && serverSocket != null && !serverSocket!!.isClosed) {
            Log.d(TAG, "Server already running on port $currentPort")
            return true
        }

        for (i in 0 until MAX_PORT_ATTEMPTS) {
            val port = START_PORT + i
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket = ss
                currentPort = port
                isRunning.set(true)

                val pool = ThreadPoolExecutor(
                    4,
                    16,
                    60L,
                    TimeUnit.SECONDS,
                    LinkedBlockingQueue(64),
                )
                executor = pool

                Thread({
                    Log.i(TAG, "RemoteControlServer listening on 0.0.0.0:$port")
                    while (isRunning.get() && !ss.isClosed) {
                        try {
                            val client = ss.accept()
                            try {
                                pool.execute { handleClient(client) }
                            } catch (_: RejectedExecutionException) {
                                try { client.close() } catch (_: Exception) {}
                            }
                        } catch (e: Exception) {
                            if (!isRunning.get()) break
                            Log.w(TAG, "Error accepting client: ${e.message}")
                        }
                    }
                }, "RemoteControlServer-accept").apply {
                    isDaemon = true
                    start()
                }
                return true
            } catch (e: Exception) {
                Log.d(TAG, "Port $port unavailable: ${e.message}")
            }
        }
        Log.e(TAG, "Failed to bind to any port in range $START_PORT..${START_PORT + MAX_PORT_ATTEMPTS}")
        return false
    }

    @Synchronized
    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        try {
            executor?.shutdownNow()
        } catch (_: Exception) {}
        executor = null
        Log.i(TAG, "RemoteControlServer stopped")
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // 1. 读取 HTTP 头，精准匹配 \r\n\r\n
            val headerBytes = ByteArrayOutputStream()
            var state = 0
            var b: Int
            while (input.read().also { b = it } != -1) {
                headerBytes.write(b)
                if (headerBytes.size() > MAX_HEADER_BYTES) {
                    sendResponse(output, 431, "Request Header Fields Too Large", "text/plain", body = "Headers too large".toByteArray())
                    return
                }
                if (b == 13 && (state == 0 || state == 2)) {
                    state++
                } else if (b == 10 && (state == 1 || state == 3)) {
                    state++
                    if (state == 4) break
                } else {
                    state = if (b == 13) 1 else 0
                }
            }
            if (state != 4) return

            val headerStr = headerBytes.toString("UTF-8")
            val lines = headerStr.split("\r\n")
            if (lines.isEmpty()) return

            val requestLine = lines[0]
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val path = rawPath.substringBefore("?")
            Log.d(TAG, "Request: $method $path from ${socket.inetAddress}")

            if (method == "OPTIONS") {
                sendResponse(output, 204, "No Content", null)
                return
            }

            // 处理本地 M3U8 反向代理路由 (GET/HEAD /proxy... ?url=...)
            if ((method == "GET" || method == "HEAD") && path.startsWith("/proxy")) {
                val queryString = rawPath.substringAfter("?", "")
                val queryUrl = queryString.split("&")
                    .firstOrNull { it.startsWith("url=") }
                    ?.substringAfter("url=")
                    ?: ""
                if (queryUrl.isNotEmpty()) {
                    val targetUrl = try {
                        URLDecoder.decode(queryUrl, "UTF-8")
                    } catch (_: Exception) {
                        queryUrl
                    }
                    handleProxyRequest(method, targetUrl, output)
                    return
                } else {
                    sendResponse(output, 400, "Bad Request", "text/plain", body = "Missing url parameter".toByteArray())
                    return
                }
            }

            var contentLength = 0
            for (i in 1 until lines.size) {
                val line = lines[i]
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            // 2. 精确按字节读取 Body
            val bodyBytes = if (contentLength in 1..(15 * 1024 * 1024)) {
                val buf = ByteArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val read = input.read(buf, readTotal, contentLength - readTotal)
                    if (read < 0) break
                    readTotal += read
                }
                buf.copyOf(readTotal)
            } else {
                ByteArray(0)
            }
            val body = String(bodyBytes, StandardCharsets.UTF_8)

            when {
                method == "GET" && (path == "/" || path.isEmpty()) -> {
                    sendResponse(output, 302, "Found", null, "Location: /remote_control\r\n")
                }
                method == "GET" && path == "/remote_control" -> {
                    val bytes = remoteControlHtml.toByteArray(StandardCharsets.UTF_8)
                    sendResponse(output, 200, "OK", "text/html; charset=utf-8", body = bytes)
                }
                method == "GET" && path == "/api/ping" -> {
                    val bytes = """{"pong":true}""".toByteArray(StandardCharsets.UTF_8)
                    sendResponse(output, 200, "OK", "application/json; charset=utf-8", body = bytes)
                }
                method == "POST" && path == "/handshake" -> {
                    val bytes = """{"status":"ok"}""".toByteArray(StandardCharsets.UTF_8)
                    sendResponse(output, 200, "OK", "application/json; charset=utf-8", body = bytes)
                }
                method == "POST" && (path == "/message" || path == "/api/text") -> {
                    val parsedText = try {
                        val obj = JSONObject(body)
                        obj.optString("message", obj.optString("text", body.trim()))
                    } catch (_: Exception) {
                        body.trim()
                    }
                    if (parsedText.isNotEmpty()) {
                        Log.d(TAG, "Received message/text: $parsedText")
                        textInputCallback?.invoke(parsedText)
                        val bytes = """{"status":"ok","success":true}""".toByteArray(StandardCharsets.UTF_8)
                        sendResponse(output, 200, "OK", "application/json; charset=utf-8", body = bytes)
                    } else {
                        sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8", body = """{"error":"Empty message"}""".toByteArray(StandardCharsets.UTF_8))
                    }
                }
                method == "POST" && path == "/upload" -> {
                    try {
                        val obj = JSONObject(body)
                        val filename = obj.optString("filename", "custom_live.m3u")
                        val content = obj.optString("content", "")

                        if (content.isEmpty()) {
                            sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8", body = """{"error":"Empty content"}""".toByteArray(StandardCharsets.UTF_8))
                            return
                        }

                        val safeName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        val targetDir = File(SeleneApplication.instance.filesDir, "live")
                        targetDir.mkdirs()
                        val targetFile = File(targetDir, safeName)
                        targetFile.writeText(content, Charsets.UTF_8)
                        Log.i(TAG, "Saved uploaded live playlist to: ${targetFile.absolutePath}")

                        // 独立设置自定义直播源，完全不污染主媒体订阅和搜索源
                        AppDataService.saveCustomLiveUrlSync(targetFile.absolutePath)
                        LiveService.clearAllCache()
                        liveSourceUpdatedCallback?.invoke(targetFile.absolutePath)

                        val respJson = JSONObject().apply {
                            put("status", "ok")
                            put("path", targetFile.absolutePath)
                        }
                        val bytes = respJson.toString().toByteArray(StandardCharsets.UTF_8)
                        sendResponse(output, 200, "OK", "application/json; charset=utf-8", body = bytes)
                    } catch (e: Exception) {
                        Log.e(TAG, "Upload failed: ${e.message}")
                        val errJson = JSONObject().apply {
                            put("error", e.message ?: "Upload failed")
                        }
                        sendResponse(output, 500, "Internal Server Error", "application/json; charset=utf-8", body = errJson.toString().toByteArray(StandardCharsets.UTF_8))
                    }
                }
                method == "POST" && path == "/api/key" -> {
                    val keyCode = body.trim().toIntOrNull()
                    if (keyCode != null) {
                        Log.d(TAG, "Received key: $keyCode")
                        keyEventCallback?.invoke(keyCode)
                        val bytes = """{"success":true}""".toByteArray(StandardCharsets.UTF_8)
                        sendResponse(output, 200, "OK", "application/json; charset=utf-8", body = bytes)
                    } else {
                        sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8", body = """{"error":"Invalid key"}""".toByteArray(StandardCharsets.UTF_8))
                    }
                }
                method == "POST" && path == "/api/subscription" -> {
                    val sub = body.trim()
                    if (sub.isNotEmpty()) {
                        Log.d(TAG, "Received subscription payload, length: ${sub.length}")
                        subscriptionCallback?.invoke(sub)
                        val bytes = """{"success":true,"message":"Subscription received"}""".toByteArray(StandardCharsets.UTF_8)
                        sendResponse(output, 200, "OK", "application/json; charset=utf-8", body = bytes)
                    } else {
                        sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8", body = """{"error":"Empty subscription"}""".toByteArray(StandardCharsets.UTF_8))
                    }
                }
                else -> {
                    sendResponse(output, 404, "Not Found", "text/plain", body = "Not Found".toByteArray(StandardCharsets.UTF_8))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Client handler exception: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * 本地 M3U8 反向代理核心处理
     * - 添加标准 AptvPlayer/1.4.10 UA 与跨域头
     * - 信任所有 SSL 证书，处理 HTTP 30x 跳转
     * - M3U8 列表内分片/Key 路径重写为本地代理，视频流直接流式转发
     */
    private fun handleProxyRequest(method: String, targetUrlStr: String, output: OutputStream) {
        var conn: HttpURLConnection? = null
        try {
            var targetUrl = targetUrlStr
            if (targetUrl.startsWith("http://epg.112114.xyz")) {
                targetUrl = targetUrl.replace("http://", "https://")
            }
            var redirects = 0
            while (redirects < 5) {
                val u = URL(targetUrl)
                conn = u.openConnection() as HttpURLConnection
                if (conn is HttpsURLConnection) {
                    conn.sslSocketFactory = trustAllSslSocketFactory()
                    conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
                conn.requestMethod = if (method == "HEAD") "HEAD" else "GET"
                conn.connectTimeout = 12000
                conn.readTimeout = 12000
                conn.setRequestProperty("User-Agent", "AptvPlayer/1.4.10")
                conn.setRequestProperty("Accept", "*/*")
                conn.setRequestProperty("Connection", "close")
                conn.instanceFollowRedirects = false

                val code = conn.responseCode
                if (code in 301..308) {
                    val loc = conn.getHeaderField("Location")
                    if (loc != null) {
                        targetUrl = URL(u, loc).toString()
                        conn.disconnect()
                        conn = null
                        redirects++
                        continue
                    }
                }
                break
            }

            if (conn == null) {
                sendResponse(output, 502, "Bad Gateway", "text/plain", body = "Proxy upstream connection failed".toByteArray())
                return
            }

            val responseCode = conn.responseCode
            val contentType = conn.contentType ?: "application/octet-stream"
            val isM3u8 = contentType.contains("mpegurl", ignoreCase = true) ||
                    contentType.contains("application/x-mpegurl", ignoreCase = true) ||
                    targetUrl.contains(".m3u8", ignoreCase = true)

            if (responseCode !in 200..299) {
                sendResponse(output, responseCode, conn.responseMessage ?: "Error", contentType, body = "Upstream error $responseCode".toByteArray())
                return
            }

            if (method == "HEAD") {
                sendResponse(output, responseCode, "OK", contentType)
                return
            }

            if (isM3u8) {
                val bytes = readBytesCapped(conn.inputStream, MAX_M3U8_BYTES)
                val text = String(bytes, StandardCharsets.UTF_8)
                val upstreamUrl = URL(targetUrl)
                val rewritten = rewriteM3u8(text, upstreamUrl)
                val rewrittenBytes = rewritten.toByteArray(StandardCharsets.UTF_8)
                sendResponse(output, 200, "OK", "application/vnd.apple.mpegurl; charset=utf-8", body = rewrittenBytes)
            } else {
                // TS 分片或媒体二进制流，以 32KB 缓冲流式转发
                val sb = StringBuilder()
                sb.append("HTTP/1.1 ").append(responseCode).append(" OK\r\n")
                sb.append("Connection: close\r\n")
                sb.append("Access-Control-Allow-Origin: *\r\n")
                sb.append("Content-Type: ").append(contentType).append("\r\n")
                val len = conn.contentLength
                if (len >= 0) {
                    sb.append("Content-Length: ").append(len).append("\r\n")
                }
                sb.append("\r\n")
                output.write(sb.toString().toByteArray(StandardCharsets.UTF_8))

                val buf = ByteArray(32768)
                val stream = conn.inputStream
                try {
                    while (true) {
                        val n = stream.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                    output.flush()
                } finally {
                    try { stream.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Proxy forwarding error: ${e.message}")
            try {
                sendResponse(output, 500, "Internal Server Error", "text/plain", body = "Proxy error: ${e.message}".toByteArray())
            } catch (_: Exception) {}
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun readBytesCapped(input: InputStream, maxBytes: Int): ByteArray {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        var total = 0
        input.use { stream ->
            while (true) {
                val n = stream.read(tmp)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw java.io.IOException("Response exceeds $maxBytes bytes limit")
                buf.write(tmp, 0, n)
            }
        }
        return buf.toByteArray()
    }

    private fun rewriteM3u8(content: String, upstreamUrl: URL): String {
        val lines = content.lines()
        val sb = StringBuilder(content.length + 1024)
        val proxyBase = "http://127.0.0.1:$currentPort/proxy/live.m3u8?url="

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                sb.append("\n")
                continue
            }
            if (trimmed.startsWith("#")) {
                if (trimmed.startsWith("#EXT-X-KEY:") && trimmed.contains("URI=\"")) {
                    val regex = Regex("""URI="([^"]+)"""")
                    val replaced = regex.replace(trimmed) { matchResult ->
                        val uri = matchResult.groupValues[1]
                        val fullUri = try { URL(upstreamUrl, uri).toString() } catch (_: Exception) { uri }
                        val encoded = URLEncoder.encode(fullUri, "UTF-8")
                        """URI="$proxyBase$encoded""""
                    }
                    sb.append(replaced).append("\n")
                } else {
                    sb.append(trimmed).append("\n")
                }
            } else {
                val fullUrl = try {
                    URL(upstreamUrl, trimmed).toString()
                } catch (_: Exception) {
                    trimmed
                }
                val encoded = URLEncoder.encode(fullUrl, "UTF-8")
                sb.append(proxyBase).append(encoded).append("\n")
            }
        }
        return sb.toString()
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String?,
        extraHeaders: String = "",
        body: ByteArray = ByteArray(0)
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: Content-Type, Accept, Authorization\r\n")
        if (contentType != null) {
            sb.append("Content-Type: ").append(contentType).append("\r\n")
        }
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        if (extraHeaders.isNotEmpty()) {
            sb.append(extraHeaders.trimEnd('\r', '\n')).append("\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
        if (body.isNotEmpty()) {
            output.write(body)
        }
        output.flush()
    }

    private fun trustAllSslSocketFactory(): javax.net.ssl.SSLSocketFactory {
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, tm, java.security.SecureRandom())
        return ctx.socketFactory
    }

    private val remoteControlHtml = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <title>SeleneTV 助手</title>
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
  <meta name="apple-mobile-web-app-capable" content="yes">
  <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
  <style>
    :root {
      --bg: #000000;
      --card-bg: rgba(28, 28, 30, 0.75);
      --card-border: rgba(255, 255, 255, 0.08);
      --input-bg: rgba(255, 255, 255, 0.06);
      --input-border: rgba(255, 255, 255, 0.12);
      --primary: #0A84FF;
      --primary-gradient: linear-gradient(135deg, #0A84FF 0%, #0066CC 100%);
      --success: #30D158;
      --error: #FF453A;
      --text: #FFFFFF;
      --text-secondary: rgba(235, 235, 245, 0.6);
      --text-tertiary: rgba(235, 235, 245, 0.38);
    }
    * {
      box-sizing: border-box;
      margin: 0;
      padding: 0;
      -webkit-tap-highlight-color: transparent;
    }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text", "Helvetica Neue", sans-serif;
      background-color: var(--bg);
      color: var(--text);
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: max(28px, env(safe-area-inset-top)) 16px max(32px, env(safe-area-inset-bottom));
      -webkit-font-smoothing: antialiased;
    }
    .header {
      text-align: center;
      margin-bottom: 24px;
      display: flex;
      flex-direction: column;
      align-items: center;
    }
    .header-icon-wrapper {
      width: 52px;
      height: 52px;
      border-radius: 14px;
      background: linear-gradient(135deg, rgba(10, 132, 255, 0.18), rgba(10, 132, 255, 0.06));
      border: 1px solid rgba(10, 132, 255, 0.3);
      display: flex;
      align-items: center;
      justify-content: center;
      margin-bottom: 12px;
      box-shadow: 0 4px 20px rgba(10, 132, 255, 0.18);
    }
    .header-icon-wrapper svg {
      color: var(--primary);
    }
    .header h1 {
      font-size: 22px;
      font-weight: 700;
      letter-spacing: -0.4px;
      color: var(--text);
      margin-bottom: 6px;
    }
    .badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      border-radius: 999px;
      background: rgba(48, 209, 88, 0.12);
      border: 1px solid rgba(48, 209, 88, 0.25);
      font-size: 12px;
      font-weight: 500;
      color: var(--success);
    }
    .badge-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background-color: var(--success);
      box-shadow: 0 0 8px var(--success);
    }
    .card {
      width: 100%;
      max-width: 440px;
      background: var(--card-bg);
      border: 1px solid var(--card-border);
      border-radius: 20px;
      padding: 20px;
      margin-bottom: 18px;
      backdrop-filter: blur(25px);
      -webkit-backdrop-filter: blur(25px);
      box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
    }
    .card-header {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 14px;
    }
    .card-icon {
      width: 32px;
      height: 32px;
      border-radius: 9px;
      background: rgba(255, 255, 255, 0.08);
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--primary);
      flex-shrink: 0;
    }
    .card-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text);
      letter-spacing: -0.2px;
    }
    .input-wrapper {
      position: relative;
      margin-bottom: 14px;
    }
    input[type="text"] {
      width: 100%;
      height: 48px;
      padding: 0 14px;
      font-size: 16px;
      color: #fff;
      background: var(--input-bg);
      border: 1px solid var(--input-border);
      border-radius: 12px;
      outline: none;
      transition: all 0.2s ease;
      -webkit-appearance: none;
    }
    input[type="text"]:focus {
      border-color: var(--primary);
      background: rgba(255, 255, 255, 0.09);
      box-shadow: 0 0 0 3px rgba(10, 132, 255, 0.25);
    }
    input[type="text"]::placeholder {
      color: var(--text-tertiary);
      font-size: 15px;
    }
    .btn {
      width: 100%;
      height: 48px;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
      background: var(--primary-gradient);
      color: #fff;
      font-size: 16px;
      font-weight: 600;
      letter-spacing: -0.2px;
      border: none;
      border-radius: 12px;
      cursor: pointer;
      transition: all 0.2s cubic-bezier(0.25, 1, 0.5, 1);
      box-shadow: 0 4px 14px rgba(10, 132, 255, 0.28);
    }
    .btn:active {
      transform: scale(0.98);
      opacity: 0.9;
    }
    .btn:disabled {
      opacity: 0.45;
      cursor: not-allowed;
      transform: none;
    }
    .file-dropzone {
      border: 1.5px dashed rgba(255, 255, 255, 0.18);
      border-radius: 14px;
      padding: 24px 16px;
      text-align: center;
      background: rgba(255, 255, 255, 0.02);
      margin-bottom: 14px;
      cursor: pointer;
      transition: all 0.2s ease;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 8px;
    }
    .file-dropzone:active, .file-dropzone.dragover {
      border-color: var(--primary);
      background: rgba(10, 132, 255, 0.08);
    }
    .file-dropzone svg {
      color: var(--text-secondary);
      transition: color 0.2s;
    }
    .file-dropzone:hover svg, .file-dropzone:active svg {
      color: var(--primary);
    }
    .dropzone-label {
      font-size: 15px;
      font-weight: 500;
      color: var(--text);
    }
    .dropzone-tags {
      display: flex;
      gap: 6px;
      margin-top: 4px;
    }
    .format-tag {
      font-size: 11px;
      font-weight: 500;
      padding: 2px 7px;
      border-radius: 6px;
      background: rgba(255, 255, 255, 0.08);
      color: var(--text-secondary);
    }
    .file-chip {
      display: none;
      align-items: center;
      gap: 8px;
      background: rgba(48, 209, 88, 0.12);
      border: 1px solid rgba(48, 209, 88, 0.25);
      border-radius: 10px;
      padding: 8px 12px;
      font-size: 13px;
      color: var(--success);
      margin-top: 4px;
      max-width: 100%;
      word-break: break-all;
    }
    .file-chip.show {
      display: flex;
    }
    .tip-text {
      font-size: 12px;
      color: var(--text-tertiary);
      line-height: 1.5;
      margin-top: 10px;
      text-align: center;
    }
    /* 虚拟遥控器盘面 */
    .dpad-container {
      width: 220px;
      height: 220px;
      margin: 10px auto 16px;
      position: relative;
      background: rgba(255, 255, 255, 0.04);
      border-radius: 50%;
      border: 1px solid rgba(255, 255, 255, 0.1);
    }
    .dpad-btn {
      position: absolute;
      background: transparent;
      border: none;
      color: var(--text);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: background 0.15s, transform 0.1s;
    }
    .dpad-btn:active {
      background: rgba(255, 255, 255, 0.12);
    }
    .dpad-up { top: 0; left: 70px; width: 80px; height: 65px; border-radius: 40px 40px 0 0; }
    .dpad-down { bottom: 0; left: 70px; width: 80px; height: 65px; border-radius: 0 0 40px 40px; }
    .dpad-left { top: 70px; left: 0; width: 65px; height: 80px; border-radius: 40px 0 0 40px; }
    .dpad-right { top: 70px; right: 0; width: 65px; height: 80px; border-radius: 0 40px 40px 0; }
    .dpad-ok {
      top: 70px; left: 70px; width: 80px; height: 80px;
      border-radius: 50%;
      background: rgba(255, 255, 255, 0.08);
      font-size: 16px;
      font-weight: 700;
      color: var(--primary);
      box-shadow: 0 2px 10px rgba(0,0,0,0.3);
    }
    .actions-row {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 10px;
      margin-top: 6px;
    }
    .action-btn {
      height: 44px;
      background: rgba(255, 255, 255, 0.06);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 12px;
      color: var(--text);
      font-size: 13px;
      font-weight: 600;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 2px;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .action-btn:active {
      transform: scale(0.95);
      background: rgba(255, 255, 255, 0.12);
    }
    /* Dynamic Island Toast */
    .toast {
      position: fixed;
      top: max(20px, env(safe-area-inset-top));
      left: 50%;
      transform: translateX(-50%) translateY(-70px);
      background: rgba(30, 30, 32, 0.94);
      backdrop-filter: blur(25px);
      -webkit-backdrop-filter: blur(25px);
      border: 1px solid rgba(255, 255, 255, 0.16);
      padding: 10px 18px;
      border-radius: 999px;
      box-shadow: 0 10px 30px rgba(0, 0, 0, 0.6);
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 14px;
      font-weight: 500;
      color: #fff;
      opacity: 0;
      pointer-events: none;
      transition: all 0.35s cubic-bezier(0.16, 1, 0.3, 1);
      z-index: 9999;
    }
    .toast.show {
      transform: translateX(-50%) translateY(0);
      opacity: 1;
    }
  </style>
</head>
<body>
  <!-- Toast Notification -->
  <div id="toast" class="toast">
    <span id="toastIcon"></span>
    <span id="toastMsg"></span>
  </div>

  <!-- Header -->
  <div class="header">
    <div class="header-icon-wrapper">
      <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <rect x="2" y="4" width="20" height="13" rx="3"></rect>
        <path d="M8 20h8"></path>
        <path d="M12 17v3"></path>
      </svg>
    </div>
    <h1>SeleneTV 助手</h1>
    <div class="badge">
      <span class="badge-dot"></span>
      <span>电视已连接</span>
    </div>
  </div>

  <!-- Card 1: 文本与接口直传 -->
  <div class="card">
    <div class="card-header">
      <div class="card-icon">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <line x1="22" y1="2" x2="11" y2="13"></line>
          <polygon points="22 2 15 22 11 13 2 9 22 2"></polygon>
        </svg>
      </div>
      <div class="card-title">发送文本或接口地址</div>
    </div>
    <div class="input-wrapper">
      <input id="textInput" type="text" placeholder="输入 API 地址、M3U 订阅或搜索词..." autocomplete="off" autocorrect="off" autocapitalize="off" />
    </div>
    <button id="sendBtn" class="btn" onclick="sendText()">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
        <path d="m22 2-7 20-4-9-9-4Z"></path>
        <path d="M22 2 11 13"></path>
      </svg>
      <span>发送到电视</span>
    </button>
  </div>

  <!-- Card 2: 本地直播源上传 -->
  <div class="card">
    <div class="card-header">
      <div class="card-icon">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
          <polyline points="14 2 14 8 20 8"></polyline>
          <line x1="12" y1="18" x2="12" y2="12"></line>
          <line x1="9" y1="15" x2="12" y2="12"></line>
          <line x1="15" y1="15" x2="12" y2="12"></line>
        </svg>
      </div>
      <div class="card-title">上传本地直播源文件</div>
    </div>
    <div class="file-dropzone" id="dropzone" onclick="document.getElementById('fileInput').click()">
      <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
        <path d="M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242"></path>
        <path d="M12 12v9"></path>
        <path d="m16 16-4-4-4 4"></path>
      </svg>
      <div class="dropzone-label">点击选择或拖入直播源文件</div>
      <div class="dropzone-tags">
        <span class="format-tag">.m3u</span>
        <span class="format-tag">.m3u8</span>
        <span class="format-tag">.txt</span>
        <span class="format-tag">.json</span>
      </div>
      <div id="fileChip" class="file-chip">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="20 6 9 17 4 12"></polyline>
        </svg>
        <span id="fileName"></span>
      </div>
      <input type="file" id="fileInput" style="display:none" accept=".m3u,.m3u8,.txt,.json" onchange="onFileSelected(event)" />
    </div>
    <button id="uploadBtn" class="btn" onclick="uploadFile()">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
        <polyline points="17 8 12 3 7 8"></polyline>
        <line x1="12" y1="3" x2="12" y2="15"></line>
      </svg>
      <span>导入并设为当前直播源</span>
    </button>
    <div class="tip-text">支持标准 M3U、带 #genre# 分类的 TXT，及 TVBox JSON</div>
  </div>

  <!-- Card 3: 极简遥控器 -->
  <div class="card">
    <div class="card-header">
      <div class="card-icon">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <rect x="5" y="2" width="14" height="20" rx="4"></rect>
          <circle cx="12" cy="7" r="1"></circle>
          <circle cx="12" cy="11" r="1"></circle>
          <circle cx="12" cy="15" r="1"></circle>
        </svg>
      </div>
      <div class="card-title">快捷遥控</div>
    </div>
    
    <div class="dpad-container">
      <button class="dpad-btn dpad-up" onclick="sendKey(19)">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="18 15 12 9 6 15"></polyline></svg>
      </button>
      <button class="dpad-btn dpad-down" onclick="sendKey(20)">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="6 9 12 15 18 9"></polyline></svg>
      </button>
      <button class="dpad-btn dpad-left" onclick="sendKey(21)">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="15 18 9 12 15 6"></polyline></svg>
      </button>
      <button class="dpad-btn dpad-right" onclick="sendKey(22)">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="9 18 15 12 9 6"></polyline></svg>
      </button>
      <button class="dpad-btn dpad-ok" onclick="sendKey(23)">OK</button>
    </div>

    <div class="actions-row">
      <button class="action-btn" onclick="sendKey(4)">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 14 4 9 9 4"></polyline><path d="M20 20v-7a4 4 0 0 0-4-4H4"></path></svg>
        <span>返回</span>
      </button>
      <button class="action-btn" onclick="sendKey(3)">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path></svg>
        <span>主页</span>
      </button>
      <button class="action-btn" onclick="sendKey(25)">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon><line x1="23" y1="9" x2="17" y2="15"></line><line x1="17" y1="9" x2="23" y2="15"></line></svg>
        <span>音量-</span>
      </button>
      <button class="action-btn" onclick="sendKey(24)">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon><path d="M15.54 8.46a5 5 0 0 1 0 7.07"></path></svg>
        <span>音量+</span>
      </button>
    </div>
  </div>

  <script>
    let currentFileContent = '';
    let currentFileName = '';
    let toastTimer = null;

    const ICONS = {
      success: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#30D158" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"></path></svg>',
      error: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#FF453A" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>'
    };

    window.addEventListener('DOMContentLoaded', () => {
      fetch('/handshake', { method: 'POST' }).catch(() => {});
    });

    function showToast(msg, isSuccess = true) {
      const toast = document.getElementById('toast');
      const icon = document.getElementById('toastIcon');
      const text = document.getElementById('toastMsg');

      icon.innerHTML = isSuccess ? ICONS.success : ICONS.error;
      text.innerText = msg;
      toast.classList.add('show');

      if (toastTimer) clearTimeout(toastTimer);
      toastTimer = setTimeout(() => {
        toast.classList.remove('show');
      }, 3000);
    }

    async function sendKey(keyCode) {
      if (navigator.vibrate) navigator.vibrate(20);
      try {
        await fetch('/api/key', {
          method: 'POST',
          headers: { 'Content-Type': 'text/plain' },
          body: String(keyCode)
        });
      } catch (err) {}
    }

    async function sendText() {
      const input = document.getElementById("textInput");
      const sendBtn = document.getElementById("sendBtn");
      const value = input.value.trim();
      if (!value) return;

      sendBtn.disabled = true;
      const originalText = sendBtn.innerHTML;
      sendBtn.innerHTML = '<span>发送中...</span>';

      try {
        const res = await fetch("/message", {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ message: value })
        });
        if (res.ok) {
          showToast('已成功发送到电视', true);
          input.value = '';
        } else {
          showToast('服务器处理失败 (' + res.status + ')', false);
        }
      } catch (err) {
        showToast('指令已发送到电视', true);
      } finally {
        sendBtn.disabled = false;
        sendBtn.innerHTML = originalText;
      }
    }

    function onFileSelected(event) {
      const file = event.target.files[0];
      if (!file) return;
      currentFileName = file.name;

      const sizeStr = file.size > 1024 * 1024 
        ? (file.size / (1024 * 1024)).toFixed(1) + ' MB' 
        : (file.size / 1024).toFixed(1) + ' KB';

      document.getElementById('fileName').innerText = file.name + ' (' + sizeStr + ')';
      document.getElementById('fileChip').classList.add('show');

      const reader = new FileReader();
      reader.onload = (e) => {
        currentFileContent = e.target.result;
      };
      reader.readAsText(file);
    }

    async function uploadFile() {
      const uploadBtn = document.getElementById("uploadBtn");
      if (!currentFileContent) {
        showToast('请先选择直播源文件', false);
        return;
      }

      uploadBtn.disabled = true;
      const originalText = uploadBtn.innerHTML;
      uploadBtn.innerHTML = '<span>正在导入...</span>';

      try {
        const res = await fetch("/upload", {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            filename: currentFileName,
            content: currentFileContent
          })
        });
        if (res.ok) {
          showToast('直播源已成功导入电视！', true);
        } else {
          showToast('导入失败，服务器错误', false);
        }
      } catch (err) {
        showToast('导入已提交至电视', true);
      } finally {
        uploadBtn.disabled = false;
        uploadBtn.innerHTML = originalText;
      }
    }

    // 拖拽支持
    const dropzone = document.getElementById('dropzone');
    dropzone.addEventListener('dragover', (e) => { e.preventDefault(); dropzone.classList.add('dragover'); });
    dropzone.addEventListener('dragleave', () => { dropzone.classList.remove('dragover'); });
    dropzone.addEventListener('drop', (e) => {
      e.preventDefault();
      dropzone.classList.remove('dragover');
      if (e.dataTransfer.files.length > 0) {
        document.getElementById('fileInput').files = e.dataTransfer.files;
        onFileSelected({ target: { files: e.dataTransfer.files } });
      }
    });
  </script>
</body>
</html>
    """.trimIndent()
}
