package org.moontechlab.selenetv.screen.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.moontechlab.selenetv.model.LiveChannel
import org.moontechlab.selenetv.service.MediaHttp

private const val LIVE_AUTO_HIDE_MS = 5_000L
// 二次返回退出的确认窗口：首按弹 toast，窗口内再按才退出
private const val LIVE_BACK_EXIT_WINDOW_MS = 2_000L
// 实测可用源（如 CCTV-1 慢源）首帧可能要 ~12s，阈值留足余量避免对慢源误报无响应
private const val LIVE_STALL_TIMEOUT_MS = 18_000L
private val LiveGreen = Color(0xFF4CAF50)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun LivePlayerScreen(launch: LiveLaunch, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val controller: VideoPlayer = remember { PlayerFactory.create(context, launch.source.ua) }

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var currentChannel by remember { mutableStateOf(launch.channel) }
    var controlsVisible by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    var stalled by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }
    // 频道网格（LazyHorizontalGrid）困焦：网格内向上 requestFocus 回标签栏恒失败，需清焦解困后
    // 从网格外回交焦点。详见 PlayerScreen 的同名处理（与点播选集/换源同一根因）。
    var routeContentUpToTab by remember { mutableStateOf(false) }

    val osdFocus = remember { FocusRequester() }
    val panelTabFocus = remember { FocusRequester() }
    val isPlaying = controller.isPlaying

    fun wake() {
        controlsVisible = true
        interactionTick++
    }

    fun togglePlay() {
        if (!controller.isPlaying) {
            controller.seekToLiveEdge()
        }
        controller.togglePlay()
        wake()
    }

    fun openPanel() {
        controlsVisible = true
        panelOpen = true
    }

    fun getEffectiveLiveUrl(rawUrl: String): String {
        val proxy = org.moontechlab.selenetv.service.AppDataService.getM3u8ProxyUrlSync()
        return if (proxy == "off" || proxy == "direct") {
            rawUrl
        } else if (proxy.isNotEmpty() && proxy != "local") {
            PlayProgress.buildPlayUrl(rawUrl, proxy)
        } else {
            val port = org.moontechlab.selenetv.server.RemoteControlServer.getPort()
            "http://127.0.0.1:$port/proxy/live.m3u8?url=${java.net.URLEncoder.encode(rawUrl, "UTF-8")}"
        }
    }

    var currentEpg by remember { mutableStateOf<org.moontechlab.selenetv.service.EpgService.ChannelEpg?>(null) }
    LaunchedEffect(currentChannel, controlsVisible) {
        if (controlsVisible || currentEpg == null) {
            currentEpg = org.moontechlab.selenetv.service.EpgService.getChannelEpg(currentChannel.name, currentChannel.tvgId)
        }
    }

    fun pickChannel(channel: LiveChannel) {
        currentChannel = channel
        stalled = false
        controller.load(getEffectiveLiveUrl(channel.url), 0)
        panelOpen = false
        wake()
    }

    LaunchedEffect(Unit) {
        controller.load(getEffectiveLiveUrl(currentChannel.url), 0)
    }

    LaunchedEffect(Unit) {
        while (true) {
            controller.syncProgress()
            delay(500)
        }
    }

    LaunchedEffect(controller.error) {
        if (controller.error != null) {
            controlsVisible = true
            panelOpen = true
        }
    }

    LaunchedEffect(controller.isBuffering, controller.isPlaying, currentChannel) {
        stalled = false
        if (controller.isBuffering && !controller.isPlaying) {
            delay(LIVE_STALL_TIMEOUT_MS)
            if (controller.isBuffering && !controller.isPlaying && controller.error == null) {
                stalled = true
                controlsVisible = true
                panelOpen = true
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    // 播放/缓冲期间保持屏幕常亮，防止机器进入休眠/屏保；离开直播页后恢复系统息屏超时
    val keepScreenOnView = LocalView.current
    val keepScreenOn = controller.isPlaying || controller.isBuffering
    DisposableEffect(keepScreenOn) {
        keepScreenOnView.keepScreenOn = keepScreenOn
        onDispose { keepScreenOnView.keepScreenOn = false }
    }

    LaunchedEffect(controlsVisible, isPlaying, panelOpen, interactionTick) {
        if (controlsVisible && isPlaying && !panelOpen) {
            delay(LIVE_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    LaunchedEffect(Unit) {
        repeat(8) {
            delay(70)
            try { osdFocus.requestFocus() } catch (_: Exception) {}
        }
    }

    // 面板每次收起（BACK / 选台 / 上滑等任意路径）后，确定性把焦点交回 OSD。
    // 单次同步 requestFocus 会被「面板焦点项被移除→焦点孤立→下层 LiveTab 的 focusRestorer 抢焦」
    // 盖过，故连续重试数帧，确保焦点留在覆盖层而非穿透到下层直播 Tab。
    LaunchedEffect(panelOpen) {
        if (panelOpen) return@LaunchedEffect
        repeat(6) {
            try { osdFocus.requestFocus() } catch (_: Exception) {}
            delay(40)
        }
    }

    // 频道网格按「上」回标签栏：清焦已让焦点离开 Lazy 容器，期间抑制 OSD「收面板」；
    // 从容器外把焦点重试交回标签栏，落定后解除抑制。
    LaunchedEffect(routeContentUpToTab) {
        if (!routeContentUpToTab) return@LaunchedEffect
        var landed = false
        repeat(5) {
            delay(16)
            if (!landed && runCatching { panelTabFocus.requestFocus() }.getOrDefault(false)) landed = true
        }
        routeContentUpToTab = false
    }

    // BACK 优先级（互斥）：① 面板开 → 收面板；② 控制条可见 → 仅隐藏控制条；
    // ③ 控制条已隐藏 → 二次确认退出（首按弹 toast，窗口内再按才退出）。
    var backExitArmed by remember { mutableStateOf(false) }
    LaunchedEffect(backExitArmed) {
        if (backExitArmed) {
            delay(LIVE_BACK_EXIT_WINDOW_MS)
            backExitArmed = false
        }
    }
    BackHandler(enabled = panelOpen) { panelOpen = false }
    BackHandler(enabled = !panelOpen && controlsVisible) { controlsVisible = false }
    BackHandler(enabled = !panelOpen && !controlsVisible) {
        if (backExitArmed) onExit() else backExitArmed = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusGroup()
            // 焦点陷阱：禁止任何方向把焦点带出播放页覆盖层、穿透到下层直播 Tab
            .focusProperties { exit = { FocusRequester.Cancel } },
    ) {
        controller.VideoSurface(Modifier.fillMaxSize())

        val dim by animateFloatAsState(if (panelOpen) 0.5f else 0f, tween(280), label = "live_dim")
        if (dim > 0f) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        }

        if (controller.isBuffering) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 比详情页全屏加载更轻的径向压暗：播放画面在后，短暂缓冲不喧宾夺主
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.25f),
                                Color.Black.copy(alpha = 0.50f),
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    org.moontechlab.selenetv.ui.MoonSpinner(size = 104.dp)
                    Text(
                        text = "正在缓冲",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 12.sp,
                        letterSpacing = 6.sp,
                    )
                }
            }
        }

        if (controller.error != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "频道播放失败，请选择其他频道",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W600,
                )
            }
        }

        if (stalled && controller.error == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "频道无响应，请尝试其他频道",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W600,
                )
            }
        }

        val ctrlAlpha by animateFloatAsState(
            targetValue = if (controlsVisible) 1f else 0f,
            animationSpec = tween(if (controlsVisible) 220 else 280),
            label = "live_ctrl_alpha",
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(280)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = ctrlAlpha }
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.25f to Color(0x99000000),
                            1f to Color(0xEE000000),
                        ),
                    )
                    .padding(start = 56.dp, end = 56.dp, top = 36.dp, bottom = if (panelOpen) 2.dp else 18.dp),
            ) {
                Column {
                    LiveOsd(
                        channelName = currentChannel.name,
                        epg = currentEpg,
                        controlsVisible = controlsVisible,
                        panelOpen = panelOpen,
                        focusRequester = osdFocus,
                        // 频道网格「上」回标签栏的中转期间，OSD 可能短暂获焦——此时不收面板。
                        onFocused = { if (!routeContentUpToTab) panelOpen = false },
                        onWake = { wake() },
                        onToggle = { togglePlay() },
                        onOpenPanel = { openPanel() },
                    )
                    if (!panelOpen) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.35f),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "频道列表", color = Color.White.copy(alpha = 0.35f), fontSize = 11.sp)
                        }
                    }
                }
            }

            if (panelOpen) {
                LiveChannelPanel(
                    groups = launch.groups,
                    currentChannelId = currentChannel.id,
                    userAgent = MediaHttp.resolveLiveUa(launch.source.ua),
                    onPickChannel = ::pickChannel,
                    osdFocus = osdFocus,
                    activeTabFocus = panelTabFocus,
                    onContentExitUp = {
                        // 顺序要紧：先置抑制标志再清焦（clearFocus 同步把焦点丢给 OSD 并触发 onFocused）
                        routeContentUpToTab = true
                        focusManager.clearFocus(force = true)
                    },
                )
            }
        }

        // 二次返回退出提示（自绘，不用系统 Toast）
        BackExitHint(
            visible = backExitArmed,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 130.dp),
        )
    }
}

@Composable
private fun LiveOsd(
    channelName: String,
    epg: org.moontechlab.selenetv.service.EpgService.ChannelEpg?,
    controlsVisible: Boolean,
    panelOpen: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onWake: () -> Unit,
    onToggle: () -> Unit,
    onOpenPanel: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val allPrograms = epg?.allPrograms.orEmpty()
    val now = System.currentTimeMillis()
    val currentIndex = remember(allPrograms, epg?.currentProgram) {
        val idx = allPrograms.indexOfFirst { it == epg?.currentProgram }
        if (idx >= 0) idx else 0
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))

    LaunchedEffect(currentIndex, controlsVisible) {
        if (allPrograms.isNotEmpty() && currentIndex >= 0) {
            listState.scrollToItem((currentIndex - 1).coerceAtLeast(0))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .focusProperties { up = FocusRequester.Cancel }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                // 返回键一律交给 BackHandler 处理：不在此唤醒控制条，否则会与「隐藏/退出」逻辑打架
                if (e.key == Key.Back) return@onKeyEvent false
                if (!controlsVisible) {
                    if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) {
                        onToggle()
                    } else {
                        onWake()
                    }
                    return@onKeyEvent true
                }
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        onToggle()
                        true
                    }
                    Key.DirectionDown -> {
                        onOpenPanel()
                        true
                    }
                    Key.DirectionUp -> {
                        if (listState.firstVisibleItemIndex > 0) {
                            coroutineScope.launch { listState.animateScrollToItem((listState.firstVisibleItemIndex - 3).coerceAtLeast(0)) }
                            onWake()
                            true
                        } else false
                    }
                    Key.DirectionLeft -> {
                        if (listState.firstVisibleItemIndex > 0) {
                            coroutineScope.launch { listState.animateScrollToItem((listState.firstVisibleItemIndex - 4).coerceAtLeast(0)) }
                            onWake()
                            true
                        } else false
                    }
                    Key.DirectionRight -> {
                        if (listState.firstVisibleItemIndex + 3 < allPrograms.size) {
                            coroutineScope.launch { listState.animateScrollToItem(listState.firstVisibleItemIndex + 4) }
                            onWake()
                            true
                        } else false
                    }
                    else -> {
                        onWake()
                        false
                    }
                }
            }
            .focusable(),
    ) {
        // 顶部行：频道名与 LIVE 徽标
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channelName,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.W700,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (allPrograms.isNotEmpty() && !panelOpen) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "全天节目单 [◄/►翻看]",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(LiveGreen))
                Text(text = "LIVE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.W800)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 竖项全天节目单区域
        if (panelOpen) {
            // 当选台面板展开时，只显示单行当前节目，给频道网格留出充裕空间
            val prog = epg?.currentProgram
            if (prog != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(LiveGreen))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "正在播出: ${prog.title} (${prog.start}-${prog.stop})",
                        color = Color.White.copy(alpha = 0.90f),
                        fontSize = 13.sp,
                    )
                }
            }
        } else if (allPrograms.isNotEmpty()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 168.dp)
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(allPrograms, key = { idx, item -> "$idx-${item.startMs}-${item.title}" }) { index, item ->
                    val isCurrent = item == epg?.currentProgram || (index == currentIndex && epg?.currentProgram != null)
                    if (isCurrent) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.09f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(LiveGreen))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${item.start} - ${item.stop}",
                                color = LiveGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(96.dp),
                            )
                            Text(
                                text = item.title,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .border(1.dp, LiveGreen.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    text = "正在播出",
                                    color = LiveGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    } else {
                        val isPast = item.stopMs > 0 && item.stopMs < now
                        val contentAlpha = if (isPast) 0.38f else 0.82f
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = if (isPast) 0.22f else 0.45f)),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "${item.start} - ${item.stop}",
                                color = Color.White.copy(alpha = contentAlpha),
                                fontSize = 12.sp,
                                modifier = Modifier.width(96.dp),
                            )
                            Text(
                                text = item.title,
                                color = Color.White.copy(alpha = contentAlpha),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        } else {
            // EPG 未就绪或仅有当前/下一节目时的坚挺竖向降级显示
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val cur = epg?.currentProgram
                if (cur != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(LiveGreen))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${cur.start} - ${cur.stop}  ${cur.title}",
                            color = LiveGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "[正在播出]", color = LiveGreen, fontSize = 10.sp)
                    }
                }
                val nxt = epg?.nextProgram
                if (nxt != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.4f)))
                        Spacer(modifier = Modifier.width(9.dp))
                        Text(
                            text = "${nxt.start} - ${nxt.stop}  ${nxt.title}",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 12.sp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "[稍后]", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                    }
                }
                if (cur == null && nxt == null) {
                    Text(
                        text = "暂无节目单信息",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
