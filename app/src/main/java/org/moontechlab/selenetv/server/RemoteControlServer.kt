package org.moontechlab.selenetv.server

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 遥控器与手机传输 Web 服务器 (原生 ServerSocket 高性能稳定实现)
 * 摆脱 Ktor Netty 反射与混淆崩溃问题，支持局域网遥控、订阅推送与本地文件上传。
 */
object RemoteControlServer {
    private const val TAG = "RemoteControlServer"
    private const val START_PORT = 28256
    private const val MAX_PORT_ATTEMPTS = 100

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val isRunning = AtomicBoolean(false)
    private var currentPort: Int = START_PORT

    private var keyEventCallback: ((Int) -> Unit)? = null
    private var textInputCallback: ((String) -> Unit)? = null
    private var subscriptionCallback: ((String) -> Unit)? = null

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

    fun clearCallbacks() {
        keyEventCallback = null
        textInputCallback = null
        subscriptionCallback = null
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

                val pool = Executors.newCachedThreadPool()
                executor = pool

                pool.execute {
                    Log.i(TAG, "RemoteControlServer listening on 0.0.0.0:$port")
                    while (isRunning.get() && !ss.isClosed) {
                        try {
                            val client = ss.accept()
                            pool.execute { handleClient(client) }
                        } catch (e: Exception) {
                            if (!isRunning.get()) break
                            Log.w(TAG, "Error accepting client: ${e.message}")
                        }
                    }
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
            val headerBytes = java.io.ByteArrayOutputStream()
            var state = 0
            var b: Int
            while (input.read().also { b = it } != -1) {
                headerBytes.write(b)
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

            var contentLength = 0
            for (i in 1 until lines.size) {
                val line = lines[i]
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            // 2. 精确按字节读取 Body，杜绝 UTF-8 字符数与字节数不一致导致卡死
            val bodyBytes = if (contentLength in 1..(10 * 1024 * 1024)) {
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

            if (method == "OPTIONS") {
                sendResponse(output, 204, "No Content", null)
                return
            }

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
                method == "POST" && path == "/api/text" -> {
                    val text = body.trim()
                    if (text.isNotEmpty()) {
                        Log.d(TAG, "Received text: $text")
                        textInputCallback?.invoke(text)
                        val bytes = """{"success":true}""".toByteArray(StandardCharsets.UTF_8)
                        sendResponse(output, 200, "OK", "application/json; charset=utf-8", body = bytes)
                    } else {
                        sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8", body = """{"error":"Empty text"}""".toByteArray(StandardCharsets.UTF_8))
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
        sb.append("Access-Control-Allow-Headers: Content-Type\r\n")
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

    private val remoteControlHtml = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no, maximum-scale=1.0">
    <title>Selene TV 控制中心</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Segoe UI', Roboto, Helvetica, sans-serif;
            background: #101211;
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: flex-start;
            padding: 24px 16px;
            color: #fff;
            user-select: none;
        }
        .header-bar {
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 8px;
            margin-bottom: 20px;
            font-size: 14px;
            font-weight: 500;
            color: rgba(255,255,255,0.7);
        }
        .status-dot {
            width: 10px; height: 10px;
            border-radius: 50%;
            background: #00bb5e;
            box-shadow: 0 0 10px #00bb5e;
            transition: all 0.3s ease;
        }
        .status-dot.error { background: #ff3b30; box-shadow: 0 0 10px #ff3b30; }

        .tabs-container {
            display: flex;
            background: rgba(255,255,255,0.08);
            border-radius: 24px;
            padding: 4px;
            width: 100%;
            max-width: 320px;
            margin-bottom: 24px;
            backdrop-filter: blur(20px);
        }
        .tab-btn {
            flex: 1;
            padding: 10px 16px;
            background: transparent;
            border: none;
            border-radius: 20px;
            color: rgba(255,255,255,0.6);
            font-size: 15px;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
        }
        .tab-btn.active {
            background: #00bb5e;
            color: #ffffff;
            box-shadow: 0 4px 12px rgba(0, 187, 94, 0.35);
        }

        .tab-panel {
            display: none;
            width: 100%;
            max-width: 360px;
            flex-direction: column;
            align-items: center;
        }
        .tab-panel.active { display: flex; }

        /* Remote Panel */
        .remote-body {
            width: 100%;
            max-width: 250px;
            background: linear-gradient(180deg, #444645 0%, #2a2c2b 12%, #1e201f 70%, #151716 100%);
            border-radius: 48px;
            padding: 32px 28px 44px;
            box-shadow: 0 40px 80px rgba(0,0,0,0.8), 0 0 0 1px rgba(255,255,255,0.1), inset 0 1px 0 rgba(255,255,255,0.25);
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 32px;
            position: relative;
        }
        .touchpad-ring {
            width: 186px; height: 186px;
            background: linear-gradient(180deg, #0e100f 0%, #1b1d1c 100%);
            border-radius: 50%;
            padding: 6px;
            box-shadow: inset 0 4px 12px rgba(0,0,0,0.9), 0 1px 0 rgba(255,255,255,0.1);
        }
        .touchpad-inner {
            width: 100%; height: 100%;
            background: linear-gradient(180deg, #2a2c2b 0%, #1c1e1d 100%);
            border-radius: 50%;
            display: flex; align-items: center; justify-content: center;
            position: relative;
        }
        .dpad-container {
            width: 156px; height: 156px;
            display: grid;
            grid-template-columns: 1fr 1fr 1fr;
            grid-template-rows: 1fr 1fr 1fr;
            position: relative;
        }
        .dpad-btn {
            background: transparent; border: none;
            display: flex; align-items: center; justify-content: center;
            cursor: pointer; border-radius: 50%;
            position: relative; z-index: 2;
        }
        .dpad-btn:active { background: rgba(255,255,255,0.12); }
        .dpad-btn svg { width: 28px; height: 28px; fill: rgba(255,255,255,0.5); transition: fill 0.1s ease; }
        .dpad-btn:active svg { fill: rgba(255,255,255,0.95); }
        .center-btn {
            grid-column: 2; grid-row: 2;
            width: 62px; height: 62px;
            background: linear-gradient(180deg, #3d403e 0%, #282a29 100%);
            border: none; border-radius: 50%; cursor: pointer;
            box-shadow: 0 4px 14px rgba(0,0,0,0.6), inset 0 1px 0 rgba(255,255,255,0.2);
            justify-self: center; align-self: center;
            position: relative; z-index: 3;
        }
        .center-btn:active { transform: scale(0.96); background: linear-gradient(180deg, #4d504e 0%, #353837 100%); }
        .up-btn { grid-column: 2; grid-row: 1; }
        .down-btn { grid-column: 2; grid-row: 3; }
        .left-btn { grid-column: 1; grid-row: 2; }
        .right-btn { grid-column: 3; grid-row: 2; }

        .btn-row { display: flex; gap: 32px; width: 100%; justify-content: center; }
        .action-btn {
            width: 66px; height: 66px;
            background: linear-gradient(180deg, #181a19 0%, #242725 100%);
            border: none; border-radius: 50%; cursor: pointer;
            display: flex; align-items: center; justify-content: center;
            box-shadow: inset 0 3px 8px rgba(0,0,0,0.6), 0 1px 0 rgba(255,255,255,0.1);
        }
        .action-btn:active { background: #2f3330; }
        .action-btn svg { width: 26px; height: 26px; fill: rgba(255,255,255,0.6); }
        .brand-text { font-size: 13px; font-weight: 700; color: rgba(255,255,255,0.25); letter-spacing: 4px; text-transform: uppercase; }

        /* Transfer Panel */
        .transfer-card {
            width: 100%;
            background: rgba(255,255,255,0.05);
            border: 1px solid rgba(255,255,255,0.08);
            border-radius: 20px;
            padding: 20px;
            margin-bottom: 16px;
            backdrop-filter: blur(20px);
        }
        .card-title { font-size: 16px; font-weight: 700; color: #fff; margin-bottom: 6px; display: flex; align-items: center; gap: 8px; }
        .card-desc { font-size: 13px; color: rgba(255,255,255,0.5); margin-bottom: 14px; line-height: 1.4; }
        .form-input {
            width: 100%; height: 46px;
            background: rgba(0,0,0,0.3);
            border: 1px solid rgba(255,255,255,0.12);
            border-radius: 12px;
            padding: 0 14px;
            color: #fff; font-size: 14px; outline: none;
            margin-bottom: 12px;
            user-select: text;
        }
        .form-input:focus { border-color: #00bb5e; background: rgba(0, 187, 94, 0.05); }
        .file-box {
            width: 100%;
            border: 1px dashed rgba(255,255,255,0.2);
            border-radius: 12px;
            padding: 16px;
            text-align: center;
            margin-bottom: 12px;
            cursor: pointer;
            background: rgba(0,0,0,0.15);
        }
        .file-box:active { background: rgba(255,255,255,0.05); }
        .file-box input[type="file"] { display: none; }
        .file-box-text { font-size: 14px; color: rgba(255,255,255,0.7); word-break: break-all; }

        .primary-btn {
            width: 100%; height: 46px;
            background: #00bb5e;
            border: none; border-radius: 12px;
            color: #fff; font-size: 15px; font-weight: 600;
            cursor: pointer;
            box-shadow: 0 4px 14px rgba(0, 187, 94, 0.35);
            transition: all 0.15s ease;
        }
        .primary-btn:active { transform: scale(0.98); background: #00a453; }

        /* Toast & Modal */
        .toast {
            position: fixed; top: 20px; left: 50%; transform: translateX(-50%) translateY(-100px);
            background: rgba(30, 32, 31, 0.95);
            border: 1px solid rgba(255,255,255,0.15);
            color: #fff; padding: 12px 24px; border-radius: 30px;
            font-size: 14px; font-weight: 500;
            box-shadow: 0 10px 30px rgba(0,0,0,0.7);
            z-index: 1000; opacity: 0;
            transition: all 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
            pointer-events: none; max-width: 90%; text-align: center;
        }
        .toast.show { transform: translateX(-50%) translateY(0); opacity: 1; }

        .modal-overlay {
            position: fixed; top: 0; left: 0; right: 0; bottom: 0;
            background: rgba(0,0,0,0.6);
            backdrop-filter: blur(10px);
            display: flex; align-items: flex-end; justify-content: center;
            z-index: 200; visibility: hidden; opacity: 0;
            transition: all 0.25s ease;
        }
        .modal-overlay.show { visibility: visible; opacity: 1; }
        .modal-card {
            width: 100%; max-width: 420px;
            background: #242625;
            border-radius: 24px 24px 0 0;
            padding: 20px 20px 36px;
            transform: translateY(100%);
            transition: transform 0.25s cubic-bezier(0.32, 0.72, 0, 1);
        }
        .modal-overlay.show .modal-card { transform: translateY(0); }
        .modal-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
        .modal-title { font-size: 17px; font-weight: 700; }
        .modal-btn { background: none; border: none; color: #00bb5e; font-size: 16px; font-weight: 600; cursor: pointer; }
    </style>
</head>
<body>
    <div class="header-bar">
        <div class="status-dot" id="statusDot"></div>
        <span id="statusText">已连接到电视</span>
    </div>

    <div class="tabs-container">
        <button class="tab-btn active" id="tabRemoteBtn" onclick="selectTab('remote')">遥控器</button>
        <button class="tab-btn" id="tabTransferBtn" onclick="selectTab('transfer')">传输 &amp; 订阅</button>
    </div>

    <!-- 遥控面板 -->
    <div class="tab-panel active" id="panelRemote">
        <div class="remote-body">
            <div class="touchpad-ring">
                <div class="touchpad-inner">
                    <div class="dpad-container">
                        <button class="dpad-btn up-btn" data-key="19"><svg viewBox="0 0 24 24"><path d="M7.41 15.41L12 10.83l4.59 4.58L18 14l-6-6-6 6z"/></svg></button>
                        <button class="dpad-btn left-btn" data-key="21"><svg viewBox="0 0 24 24"><path d="M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z"/></svg></button>
                        <button class="center-btn" data-key="23"></button>
                        <button class="dpad-btn right-btn" data-key="22"><svg viewBox="0 0 24 24"><path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"/></svg></button>
                        <button class="dpad-btn down-btn" data-key="20"><svg viewBox="0 0 24 24"><path d="M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6z"/></svg></button>
                    </div>
                </div>
            </div>
            <div class="btn-row">
                <button class="action-btn" data-key="4"><svg viewBox="0 0 24 24"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg></button>
                <button class="action-btn" id="keyboardBtn"><svg viewBox="0 0 24 24"><path d="M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm-9 3h2v2h-2V8zm0 3h2v2h-2v-2zM8 8h2v2H8V8zm0 3h2v2H8v-2zm-1 2H5v-2h2v2zm0-3H5V8h2v2zm9 7H8v-2h8v2zm0-4h-2v-2h2v2zm0-3h-2V8h2v2zm3 3h-2v-2h2v2zm0-3h-2V8h2v2z"/></svg></button>
            </div>
            <div class="brand-text">SELENE TV</div>
        </div>
    </div>

    <!-- 传输 & 订阅面板 -->
    <div class="tab-panel" id="panelTransfer">
        <div class="transfer-card">
            <div class="card-title">🔗 推送订阅链接</div>
            <div class="card-desc">输入在线订阅 URL 或电视本地路径 (/sdcard/... 或 file://)</div>
            <input type="text" id="subUrlInput" class="form-input" placeholder="http://... 或 /sdcard/tv.json">
            <button class="primary-btn" onclick="pushSubscriptionUrl()">推送到电视</button>
        </div>

        <div class="transfer-card">
            <div class="card-title">📁 上传手机本地文件</div>
            <div class="card-desc">选择手机中的 .json 或 .txt 订阅文件，一键传至电视</div>
            <label class="file-box" for="subFileInput">
                <div class="file-box-text" id="fileChosenText">📄 点击选择订阅文件 (.json / .txt)</div>
                <input type="file" id="subFileInput" accept=".json,.txt" onchange="onFileSelected(this)">
            </label>
            <button class="primary-btn" onclick="uploadSubscriptionFile()">上传并推送到电视</button>
        </div>

        <div class="transfer-card">
            <div class="card-title">⌨️ 推送文本至电视输入框</div>
            <div class="card-desc">将手机剪贴板或长文本直接发送到电视当前聚焦的输入框</div>
            <input type="text" id="quickTextInput" class="form-input" placeholder="输入要传输的文本...">
            <button class="primary-btn" onclick="pushQuickText()">发送文本</button>
        </div>
    </div>

    <div id="toast" class="toast"></div>

    <div class="modal-overlay" id="modalOverlay">
        <div class="modal-card">
            <div class="modal-header">
                <button class="modal-btn" onclick="closeModal()">取消</button>
                <div class="modal-title">键盘文本输入</div>
                <button class="modal-btn" onclick="sendModalText()">发送</button>
            </div>
            <input type="text" class="form-input" id="modalInput" placeholder="输入文本按发送...">
        </div>
    </div>

    <script>
        const statusDot = document.getElementById('statusDot');
        const statusText = document.getElementById('statusText');
        const toast = document.getElementById('toast');
        let selectedFileText = '';

        function showToast(msg) {
            toast.textContent = msg;
            toast.classList.add('show');
            setTimeout(() => toast.classList.remove('show'), 2500);
        }

        function setStatus(ok) {
            if (ok) {
                statusDot.className = 'status-dot';
                statusText.textContent = '已连接到电视';
            } else {
                statusDot.className = 'status-dot error';
                statusText.textContent = '连接中断';
            }
        }

        function selectTab(tab) {
            const isRemote = tab === 'remote';
            document.getElementById('tabRemoteBtn').classList.toggle('active', isRemote);
            document.getElementById('tabTransferBtn').classList.toggle('active', !isRemote);
            document.getElementById('panelRemote').classList.toggle('active', isRemote);
            document.getElementById('panelTransfer').classList.toggle('active', !isRemote);
            if(navigator.vibrate) navigator.vibrate(10);
        }

        async function sendKey(k) {
            try {
                const r = await fetch('/api/key', { method: 'POST', body: k.toString() });
                setStatus(r.ok);
            } catch (e) { setStatus(false); }
        }

        document.querySelectorAll('[data-key]').forEach(btn => {
            const k = parseInt(btn.dataset.key);
            btn.addEventListener('touchstart', e => { e.preventDefault(); sendKey(k); if(navigator.vibrate) navigator.vibrate(10); });
            btn.addEventListener('click', e => { if(e.pointerType !== 'touch') sendKey(k); });
        });

        async function pushSubscriptionUrl() {
            const val = document.getElementById('subUrlInput').value.trim();
            if (!val) { showToast('请输入订阅链接'); return; }
            try {
                const r = await fetch('/api/subscription', { method: 'POST', body: val });
                if (r.ok) {
                    showToast('✅ 订阅已推送到电视！');
                    document.getElementById('subUrlInput').value = '';
                } else {
                    showToast('❌ 推送失败');
                }
                setStatus(r.ok);
            } catch (e) { showToast('❌ 连接失败'); setStatus(false); }
        }

        function onFileSelected(input) {
            const file = input.files && input.files[0];
            if (!file) return;
            document.getElementById('fileChosenText').textContent = '已选文件: ' + file.name + ' (' + Math.round(file.size / 1024) + ' KB)';
            const reader = new FileReader();
            reader.onload = e => { selectedFileText = e.target.result; };
            reader.readAsText(file);
        }

        async function uploadSubscriptionFile() {
            if (!selectedFileText) { showToast('请先选择订阅文件'); return; }
            try {
                const r = await fetch('/api/subscription', { method: 'POST', body: selectedFileText });
                if (r.ok) {
                    showToast('✅ 本地订阅文件已成功传输到电视！');
                } else {
                    showToast('❌ 传输失败');
                }
                setStatus(r.ok);
            } catch (e) { showToast('❌ 连接失败'); setStatus(false); }
        }

        async function pushQuickText() {
            const text = document.getElementById('quickTextInput').value.trim();
            if (!text) { showToast('请输入要发送的文本'); return; }
            try {
                const r = await fetch('/api/text', { method: 'POST', body: text });
                if (r.ok) {
                    showToast('✅ 文本已发送');
                    document.getElementById('quickTextInput').value = '';
                } else {
                    showToast('❌ 发送失败');
                }
                setStatus(r.ok);
            } catch (e) { showToast('❌ 连接失败'); setStatus(false); }
        }

        const modalOverlay = document.getElementById('modalOverlay');
        const modalInput = document.getElementById('modalInput');
        document.getElementById('keyboardBtn').addEventListener('click', () => {
            modalOverlay.classList.add('show');
            setTimeout(() => modalInput.focus(), 100);
        });
        function closeModal() {
            modalOverlay.classList.remove('show');
            modalInput.value = '';
        }
        async function sendModalText() {
            const text = modalInput.value.trim();
            if (!text) return;
            try {
                const r = await fetch('/api/text', { method: 'POST', body: text });
                if (r.ok) { closeModal(); showToast('✅ 已发送'); }
                setStatus(r.ok);
            } catch (e) { setStatus(false); }
        }
        modalInput.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); sendModalText(); } });

        setInterval(async () => {
            try {
                const r = await fetch('/api/ping');
                setStatus(r.ok);
            } catch (e) { setStatus(false); }
        }, 2500);
    </script>
</body>
</html>
    """.trimIndent()
}
