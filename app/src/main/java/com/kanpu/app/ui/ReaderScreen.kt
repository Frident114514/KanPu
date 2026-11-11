package com.kanpu.app.ui

import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kanpu.app.data.AnnotationEntity
import com.kanpu.app.KanPuApplication
import com.kanpu.app.data.PaperMode
import com.kanpu.app.data.ReaderSettings
import com.kanpu.app.data.ScoreEntity
import com.kanpu.app.data.ScoreRepository
import com.kanpu.app.data.SettingsRepository
import com.kanpu.app.domain.LayoutMode
import com.kanpu.app.domain.FingeringSize
import com.kanpu.app.domain.PageLayout
import com.kanpu.app.domain.TickResult
import com.kanpu.app.domain.TurnPhase
import com.kanpu.app.domain.VisiblePages
import com.kanpu.app.pdf.PageImage
import com.kanpu.app.pdf.PdfPageLoader
import com.kanpu.app.pdf.PdfScoreView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

private enum class ReadReason { INITIAL, MANUAL, AUTO, LAYOUT }
private data class ReadRequest(val page: Int, val reason: ReadReason, val generation: Long = 0, val resumeAfter: Boolean = false)

@Composable
fun ReaderScreen(
    scoreId: String,
    repository: ScoreRepository,
    settingsRepository: SettingsRepository,
    settings: ReaderSettings,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var score by remember(scoreId) { mutableStateOf<ScoreEntity?>(null) }
    var session by remember(scoreId) { mutableStateOf<ReaderSession?>(null) }
    var focus by remember(scoreId) { mutableIntStateOf(0) }
    var displayed by remember(scoreId) { mutableStateOf<VisiblePages?>(null) }
    var pageImages by remember(scoreId) { mutableStateOf<Map<Int, PageImage>>(emptyMap()) }
    var pending by remember(scoreId) { mutableStateOf<ReadRequest?>(null) }
    var awaitingArrival by remember(scoreId) { mutableStateOf<ReadRequest?>(null) }
    var transitionRunning by remember(scoreId) { mutableStateOf(false) }
    var failedRequest by remember(scoreId) { mutableStateOf<ReadRequest?>(null) }
    var showTools by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var showTiming by remember { mutableStateOf(false) }
    var showScoreSettings by remember { mutableStateOf(false) }
    var showMarkList by remember { mutableStateOf(false) }
    var showMarks by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var finger by remember { mutableStateOf<Int?>(null) }
    var hand by remember { mutableStateOf("NONE") }
    var markerColor by remember { mutableIntStateOf(android.graphics.Color.rgb(25, 74, 160)) }
    var markerSize by remember { mutableStateOf(FingeringSize.MEDIUM) }
    var selectedMarkerId by remember { mutableStateOf<String?>(null) }
    var tickVersion by remember { mutableIntStateOf(0) }
    val current = score
    val currentSession = session
    val loader = remember(current?.id) { current?.let { PdfPageLoader(File(it.privatePath)) } }

    LaunchedEffect(scoreId) {
        val loaded = repository.dao.score(scoreId)
        if (loaded == null) { errorText = "琴谱不存在"; return@LaunchedEffect }
        score = loaded
        session = (context.applicationContext as KanPuApplication).readerSession(loaded)
        focus = (if (session?.timer?.phase == TurnPhase.PAUSED) session!!.timer.focusPage else loaded.lastPageIndex)
            .coerceIn(0, loaded.pageCount - 1)
        pending = ReadRequest(focus, ReadReason.INITIAL)
    }
    LaunchedEffect(scoreId, currentSession) {
        repository.timings(scoreId).collectLatest { values ->
            currentSession?.pageDurations = values.associate { it.pageIndex to it.durationSec }
        }
    }
    LaunchedEffect(showTools, editing) {
        val accessibility = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (showTools && !editing && !accessibility.isTouchExplorationEnabled) {
            delay(3_000)
            showTools = false
        }
    }
    LaunchedEffect(editing, showTiming, showScoreSettings, showJump) {
        if (editing || showTiming || showScoreSettings || showJump) {
            currentSession?.timer?.pause(SystemClock.elapsedRealtime())
            tickVersion++
        }
    }
    DisposableEffect(currentSession, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                currentSession?.timer?.pause(SystemClock.elapsedRealtime())
                tickVersion++
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            currentSession?.timer?.pause(SystemClock.elapsedRealtime())
        }
    }
    DisposableEffect(settings.keepAwake, activity) {
        if (settings.keepAwake) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(activity) {
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView)
        }
        val previousBehavior = controller?.systemBarsBehavior
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (previousBehavior != null) controller?.systemBarsBehavior = previousBehavior
        }
    }
    fun finishEditing() {
        if (currentSession?.hasPendingSaves == true) showTools = true
        else { editing = false; selectedMarkerId = null }
    }
    fun leaveReader() {
        if (currentSession?.hasPendingSaves == true) {
            editing = true
            showTools = true
        } else {
            currentSession?.timer?.stop()
            onBack()
        }
    }
    BackHandler {
        when {
            showMarkList -> showMarkList = false
            showTiming -> showTiming = false
            showScoreSettings -> showScoreSettings = false
            showJump -> showJump = false
            editing -> finishEditing()
            else -> leaveReader()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        val mode = current?.layoutOverride?.let { runCatching { LayoutMode.valueOf(it) }.getOrNull() } ?: settings.layout
        val double = PageLayout.isDouble(mode, widthPx, heightPx)
        val paper = current?.paperOverride?.let { runCatching { PaperMode.valueOf(it) }.getOrNull() } ?: settings.paper

        LaunchedEffect(double, widthPx, heightPx, currentSession) {
            val s = currentSession ?: return@LaunchedEffect
            if (displayed != null) {
                s.timer.setLayout(double, SystemClock.elapsedRealtime())
                s.timer.pause(SystemClock.elapsedRealtime())
                pending = ReadRequest(focus, ReadReason.LAYOUT)
                tickVersion++
            }
        }

        LaunchedEffect(pending, loader, double, widthPx) {
            val request = pending ?: return@LaunchedEffect
            val source = current ?: return@LaunchedEffect
            val engine = loader ?: return@LaunchedEffect
            val target = PageLayout.visible(request.page, source.pageCount, double)
            try {
                val bitmaps = withTimeout(5_000) { engine.loadGroup(target, (widthPx / if (double) 2 else 1) * 2) }
                if (pending != request) return@LaunchedEffect
                if (request.reason == ReadReason.AUTO &&
                    (currentSession?.timer?.phase != TurnPhase.WAITING || currentSession?.timer?.generation != request.generation)) {
                    pending = null
                    return@LaunchedEffect
                }
                currentSession?.loadMarks(scoreId, listOfNotNull(target.first, target.second))
                if (pending != request) return@LaunchedEffect
                if (request.reason == ReadReason.AUTO &&
                    (currentSession?.timer?.phase != TurnPhase.WAITING || currentSession?.timer?.generation != request.generation)) {
                    pending = null
                    return@LaunchedEffect
                }
                val sameVisibleGroup = target == displayed
                if (sameVisibleGroup && request.reason == ReadReason.MANUAL) {
                    val timer = currentSession?.timer
                    val now = SystemClock.elapsedRealtime()
                    val mayResume = request.resumeAfter && timer?.phase == TurnPhase.PAUSED &&
                        timer.generation == request.generation &&
                        owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    timer?.manuallyArrived(request.page, now)
                    if (mayResume) timer?.resume(now)
                }
                if (!sameVisibleGroup) {
                    awaitingArrival = request
                    transitionRunning = true
                }
                pageImages = bitmaps
                displayed = target
                focus = request.page
                pending = null
                failedRequest = null
                errorText = null
                repository.updateProgress(scoreId, focus)
                tickVersion++
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (pending != request) return@LaunchedEffect
                if (request.reason == ReadReason.AUTO &&
                    (currentSession?.timer?.phase != TurnPhase.WAITING || currentSession?.timer?.generation != request.generation)) {
                    pending = null
                    return@LaunchedEffect
                }
                if (request.reason == ReadReason.AUTO) currentSession?.timer?.loadingFailed(request.generation, SystemClock.elapsedRealtime())
                else currentSession?.timer?.pause(SystemClock.elapsedRealtime())
                errorText = "页面加载失败：${error.message ?: "请重试"}"
                failedRequest = request
                pending = null
                tickVersion++
            }
        }

        LaunchedEffect(displayed, loader, double, widthPx, current) {
            val source = current ?: return@LaunchedEffect
            val shown = displayed ?: return@LaunchedEffect
            val engine = loader ?: return@LaunchedEffect
            listOf(1, -1).map { PageLayout.turn(shown.first, source.pageCount, double, it) }
                .filter { it != shown.first }.distinct().forEach { page ->
                    launch {
                        engine.prefetch(PageLayout.visible(page, source.pageCount, double),
                            (widthPx / if (double) 2 else 1) * 2)
                    }
                }
        }

        LaunchedEffect(currentSession) {
            val s = currentSession ?: return@LaunchedEffect
            while (true) {
                delay(50)
                val event = s.timer.tick(SystemClock.elapsedRealtime())
                when (event) {
                    is TickResult.FocusChanged -> {
                        focus = event.page
                        repository.updateProgress(scoreId, focus)
                    }
                    is TickResult.NeedsPage -> if (pending == null) pending = ReadRequest(event.page, ReadReason.AUTO, event.generation)
                    TickResult.Finished -> showTools = true
                    TickResult.None -> Unit
                }
                tickVersion++
            }
        }

        fun navigate(direction: Int) {
            val source = current ?: return
            val s = currentSession ?: return
            if (transitionRunning) return
            if (pending != null && pending?.reason != ReadReason.AUTO) return
            val target = PageLayout.turn(focus, source.pageCount, double, direction)
            if (target == focus || PageLayout.visible(target, source.pageCount, double) == displayed) {
                return
            }
            val resume = s.timer.phase in setOf(TurnPhase.RUNNING, TurnPhase.WAITING)
            s.timer.pause(SystemClock.elapsedRealtime())
            pending = ReadRequest(target, ReadReason.MANUAL, generation = s.timer.generation, resumeAfter = resume)
            tickVersion++
        }

        fun jumpTo(page: Int) {
            val source = current ?: return
            val s = currentSession ?: return
            if (transitionRunning) return
            if (page !in 0 until source.pageCount || (pending != null && pending?.reason != ReadReason.AUTO)) return
            val resume = s.timer.phase in setOf(TurnPhase.RUNNING, TurnPhase.WAITING)
            s.timer.pause(SystemClock.elapsedRealtime())
            pending = ReadRequest(page, ReadReason.MANUAL, generation = s.timer.generation, resumeAfter = resume)
            tickVersion++
        }

        fun changeSelected(change: (AnnotationEntity) -> AnnotationEntity) {
            val s = currentSession ?: return
            val selected = s.marks.firstOrNull { it.id == selectedMarkerId } ?: return
            scope.launch {
                val after = s.marks.filter { it.pageIndex == selected.pageIndex }.map {
                    if (it.id == selected.id) change(it) else it
                }
                s.changePage(scoreId, selected.pageIndex, after)
            }
        }

        AndroidView(
            factory = { PdfScoreView(it) },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                val arrival = awaitingArrival
                view.onTurnAnimationFinished = {
                    if (arrival != null && awaitingArrival === arrival) {
                        val timer = currentSession?.timer
                        val now = SystemClock.elapsedRealtime()
                        when (arrival.reason) {
                            ReadReason.AUTO -> {
                                if (timer?.pageReady(arrival.page, arrival.generation, now) != true &&
                                    timer?.phase == TurnPhase.PAUSED) timer.manuallyArrived(arrival.page, now)
                            }
                            ReadReason.MANUAL -> {
                                val mayResume = arrival.resumeAfter && timer?.phase == TurnPhase.PAUSED &&
                                    timer.generation == arrival.generation &&
                                    owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                                    !editing && !showTiming && !showScoreSettings && !showJump
                                timer?.manuallyArrived(arrival.page, now)
                                if (mayResume) timer?.resume(now)
                            }
                            else -> Unit
                        }
                        awaitingArrival = null
                        transitionRunning = false
                        tickVersion++
                    }
                }
                displayed?.let { view.setPageData(it, pageImages, if (showMarks) currentSession?.marks.orEmpty() else emptyList()) }
                view.paperMode = paper
                view.doubleLayout = double
                view.editing = editing
                view.canPlaceMarker = editing && finger != null
                view.tapTurn = settings.tapTurn
                view.swipeTurn = settings.swipeTurn
                view.selectedMarkerId = selectedMarkerId
                view.contentDescription = displayed?.let {
                    if (it.second == null) "琴谱第 ${it.first + 1} 页" else "琴谱第 ${it.first + 1} 到 ${it.second + 1} 页"
                }
                view.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                view.onTurn = ::navigate
                view.onCenterTap = { showTools = !showTools }
                view.onZoomOrPan = {
                    currentSession?.timer?.pause(SystemClock.elapsedRealtime())
                    tickVersion++
                }
                view.onMarkerSelected = { selectedMarkerId = it; finger = null; showTools = true }
                view.onMarkerTap = tap@{ page, u, v ->
                    val s = currentSession ?: return@tap
                    val selectedFinger = finger ?: return@tap
                    val mark = AnnotationEntity(UUID.randomUUID().toString(), scoreId, page, u, v,
                        selectedFinger, hand, markerColor, markerSize, System.currentTimeMillis())
                    scope.launch { s.changePage(scoreId, page, s.marks.filter { it.pageIndex == page } + mark) }
                }
                view.onMarkerMoved = move@{ mark, u, v ->
                    val s = currentSession ?: return@move
                    scope.launch { s.changePage(scoreId, mark.pageIndex,
                        s.marks.filter { it.pageIndex == mark.pageIndex }.map {
                            if (it.id == mark.id) it.copy(u = u, v = v, updatedAt = System.currentTimeMillis()) else it
                        }) }
                }
            }
        )

        if (displayed == null) {
            Surface(Modifier.align(Alignment.Center), tonalElevation = 4.dp) {
                Text("正在打开琴谱…", Modifier.padding(12.dp))
            }
        }
        errorText?.let { message ->
            Surface(Modifier.align(Alignment.Center), tonalElevation = 6.dp) {
                Column(Modifier.padding(12.dp)) {
                    Text(message)
                    TextButton(onClick = {
                        val failed = failedRequest
                        errorText = null
                        if (failed?.reason == ReadReason.AUTO) {
                            val event = currentSession?.timer?.resume(SystemClock.elapsedRealtime())
                            if (event is TickResult.NeedsPage) pending = ReadRequest(event.page, ReadReason.AUTO, event.generation)
                        } else pending = failed ?: ReadRequest(focus, ReadReason.INITIAL)
                    }) { Text("重试") }
                    TextButton(onClick = { errorText = null }) { Text("稍后") }
                }
            }
        }
        currentSession?.saveError?.let { message ->
            Surface(Modifier.align(Alignment.Center), tonalElevation = 6.dp) {
                Column(Modifier.padding(12.dp)) {
                    Text("指法尚未保存：$message")
                    TextButton(onClick = { scope.launch { currentSession?.retryPending(scoreId) } }) { Text("重试保存") }
                }
            }
        }
        if (currentSession?.timer?.phase == TurnPhase.COMPLETED) {
            Surface(Modifier.align(Alignment.TopCenter).padding(top = 64.dp), tonalElevation = 4.dp) {
                Text("本次演奏结束", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }

        if (showTools) {
            Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), tonalElevation = 6.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::leaveReader) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回书库") }
                    Text(current?.title ?: "看谱", modifier = Modifier.weight(1f), maxLines = 1)
                    IconButton(onClick = { showMarks = !showMarks }) {
                        Icon(if (showMarks) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (showMarks) "隐藏指法" else "显示指法")
                    }
                    IconButton(onClick = { showScoreSettings = true }) { Icon(Icons.Default.MoreVert, contentDescription = "本谱设置") }
                }
            }
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), tonalElevation = 6.dp) {
                Column {
                    val s = currentSession
                    if (s != null && s.timer.phase !in setOf(TurnPhase.STOPPED, TurnPhase.COMPLETED)) {
                        @Suppress("UNUSED_VARIABLE") val refresh = tickVersion
                        val remaining = s.timer.remaining(SystemClock.elapsedRealtime()) / 1000
                        Text("第 ${s.timer.focusPage + 1} 页 · ${if (s.timer.phase == TurnPhase.PAUSED) "暂停" else "剩余"} $remaining 秒",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    if (editing) {
                        Text(if (finger == null) "指法编辑 · 选择数字后才能添加；点谱面中间可隐藏工具栏" else "指法编辑 · 点击音符附近添加指法；再次点数字可取消选择",
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                        if (s?.hasPendingSaves == true && s.saveError == null) {
                            Text("正在保存指法…", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            (1..5).forEach { value -> TextButton(onClick = {
                                if (selectedMarkerId != null) changeSelected { it.copy(finger = value, updatedAt = System.currentTimeMillis()) }
                                else finger = if (finger == value) null else value
                            }) { Text(if (selectedMarkerId == null && finger == value) "●$value" else "$value") } }
                            TextButton(onClick = { scope.launch { currentSession?.undo(scoreId) } }) { Text("撤销") }
                            TextButton(onClick = { scope.launch { currentSession?.redo(scoreId) } }) { Text("重做") }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf("NONE" to "不区分", "LEFT" to "左手", "RIGHT" to "右手").forEach { (value, label) ->
                                TextButton(onClick = {
                                    if (selectedMarkerId != null) changeSelected { it.copy(hand = value, updatedAt = System.currentTimeMillis()) }
                                    else hand = value
                                }) { Text(label) }
                            }
                            TextButton(onClick = { showTools = true; finishEditing() }) { Text("完成") }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            listOf(android.graphics.Color.rgb(25, 74, 160), android.graphics.Color.rgb(170, 50, 50), android.graphics.Color.BLACK).forEach { color ->
                                TextButton(onClick = {
                                    if (selectedMarkerId != null) changeSelected { it.copy(color = color, updatedAt = System.currentTimeMillis()) }
                                    else markerColor = color
                                }) { Text("●", color = Color(color)) }
                            }
                            listOf(FingeringSize.SMALL to "小", FingeringSize.MEDIUM to "中", FingeringSize.LARGE to "大").forEach { (size, label) ->
                                TextButton(onClick = {
                                    if (selectedMarkerId != null) changeSelected { it.copy(fontSizeRatio = size, updatedAt = System.currentTimeMillis()) }
                                    else markerSize = size
                                }) { Text(label) }
                            }
                            TextButton(onClick = { showMarkList = true }) { Text("标记列表") }
                            if (selectedMarkerId != null) TextButton(onClick = {
                                val s2 = currentSession ?: return@TextButton
                                val mark = s2.marks.firstOrNull { it.id == selectedMarkerId } ?: return@TextButton
                                scope.launch { s2.changePage(scoreId, mark.pageIndex, s2.marks.filter { it.pageIndex == mark.pageIndex && it.id != mark.id }); selectedMarkerId = null }
                            }) { Text("删除") }
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { navigate(-1) }) { Icon(Icons.Default.ChevronLeft, contentDescription = "上一页") }
                        TextButton(onClick = { showJump = true }) {
                            val shown = displayed
                            Text(if (shown == null || current == null) "—" else if (shown.second == null) "${shown.first + 1} / ${current.pageCount}" else "${shown.first + 1}–${shown.second + 1} / ${current.pageCount}")
                        }
                        IconButton(onClick = { navigate(1) }) { Icon(Icons.Default.ChevronRight, contentDescription = "下一页") }
                        IconButton(onClick = {
                            if (editing) finishEditing()
                            else { editing = true; finger = null; selectedMarkerId = null; showTools = true; showMarks = true }
                        }) { Icon(Icons.Default.Edit, contentDescription = if (editing) "完成指法编辑" else "指法编辑") }
                        if (s != null) {
                            when (s.timer.phase) {
                                TurnPhase.RUNNING, TurnPhase.WAITING -> IconButton(onClick = { s.timer.pause(SystemClock.elapsedRealtime()); tickVersion++ }) { Icon(Icons.Default.Pause, contentDescription = "暂停自动翻页") }
                                TurnPhase.PAUSED -> IconButton(enabled = !transitionRunning, onClick = {
                                    val result = s.timer.resume(SystemClock.elapsedRealtime())
                                    if (result is TickResult.NeedsPage) pending = ReadRequest(result.page, ReadReason.AUTO, result.generation)
                                    tickVersion++
                                }) { Icon(Icons.Default.PlayArrow, contentDescription = "继续自动翻页") }
                                else -> IconButton(enabled = !transitionRunning, onClick = { if (displayed != null) { s.timer.start(focus, double, SystemClock.elapsedRealtime()); tickVersion++ } }) { Icon(Icons.Default.PlayArrow, contentDescription = "开始自动翻页") }
                            }
                        }
                        IconButton(onClick = { s?.timer?.stop(); tickVersion++ }) { Icon(Icons.Default.Stop, contentDescription = "停止自动翻页") }
                        TextButton(onClick = { showTiming = true }) { Text("时长") }
                    }
                }
            }
        } else if (currentSession?.timer?.phase == TurnPhase.RUNNING) {
            Surface(Modifier.align(Alignment.TopEnd), tonalElevation = 3.dp) {
                @Suppress("UNUSED_VARIABLE") val refresh = tickVersion
                Text("第 ${currentSession?.timer?.focusPage?.plus(1)} 页 · ${currentSession?.timer?.remaining(SystemClock.elapsedRealtime())?.div(1000)} 秒",
                    Modifier.clickable { showTools = true }.padding(8.dp))
            }
        }

        if (showJump && current != null) JumpDialog(current.pageCount, onDismiss = { showJump = false }) { page ->
            showJump = false
            jumpTo(page)
        }
        if (showMarkList && currentSession != null) AlertDialog(
            onDismissRequest = { showMarkList = false },
            title = { Text("本组指法标记") },
            text = {
                val visibleMarks = currentSession.marks.filter { displayed?.contains(it.pageIndex) == true }
                if (visibleMarks.isEmpty()) Text("当前页面还没有标记")
                else LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(visibleMarks, key = { it.id }) { mark ->
                        TextButton(onClick = { selectedMarkerId = mark.id; showMarkList = false }) {
                            Text("第 ${mark.pageIndex + 1} 页 · ${when (mark.hand) { "LEFT" -> "左手"; "RIGHT" -> "右手"; else -> "不区分" }} ${mark.finger} 指")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMarkList = false }) { Text("完成") } }
        )
        if (showTiming && current != null && currentSession != null) TimingDialog(current, currentSession,
            onDismiss = { showTiming = false },
            onSave = { durations ->
                val currentPageDuration = currentSession.durationSec(focus)
                repository.setPageDurations(scoreId, durations)
                currentSession.pageDurations = durations.withIndex().associate { it.index to it.value }
                if (currentSession.durationSec(focus) != currentPageDuration) {
                    currentSession.timer.currentDurationChanged(SystemClock.elapsedRealtime())
                }
                tickVersion++
            }
        )
        if (showScoreSettings && current != null) ScoreOptionsDialog(current, onDismiss = { showScoreSettings = false },
            onLayout = { mode -> scope.launch { repository.dao.setLayoutOverride(scoreId, mode?.name); score = (score ?: current).copy(layoutOverride = mode?.name) } },
            onPaper = { mode -> scope.launch { repository.dao.setPaperOverride(scoreId, mode?.name); score = (score ?: current).copy(paperOverride = mode?.name) } })
    }
}

@Composable
private fun JumpDialog(pageCount: Int, onDismiss: () -> Unit, onJump: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val page = text.toIntOrNull()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("跳转页码") },
        text = { OutlinedTextField(text, { text = it }, label = { Text("1—$pageCount") }, singleLine = true, isError = page != null && page !in 1..pageCount) },
        confirmButton = { TextButton(enabled = page != null && page in 1..pageCount, onClick = { onJump(page!! - 1) }) { Text("跳转") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun TimingDialog(score: ScoreEntity, session: ReaderSession, onDismiss: () -> Unit,
    onSave: suspend (List<Int>) -> Unit) {
    val scope = rememberCoroutineScope()
    val durations = remember(score.id, session) {
        mutableStateListOf<String>().apply {
            addAll((0 until score.pageCount).map { session.durationSec(it).toString() })
        }
    }
    val listState = rememberLazyListState()
    var invalidPage by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("逐页翻页时长") },
        text = {
            Column {
                Text("每页填写停留秒数；双页显示时也按页码依次计时。", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(score.pageCount) { page ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("第 ${page + 1} 页", Modifier.width(74.dp))
                            OutlinedTextField(
                                value = durations[page],
                                onValueChange = { durations[page] = it; if (invalidPage == page) invalidPage = null },
                                modifier = Modifier.weight(1f),
                                label = { Text("秒") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = invalidPage == page
                            )
                        }
                    }
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(enabled = !saving, onClick = {
            val parsed = durations.map { it.trim().toIntOrNull() }
            val invalid = parsed.indexOfFirst { it == null || it !in 1..3600 }
            if (invalid >= 0) {
                invalidPage = invalid
                error = "第 ${invalid + 1} 页请输入 1—3600 秒"
                scope.launch { listState.animateScrollToItem(invalid) }
            } else scope.launch {
                saving = true
                error = ""
                runCatching { onSave(parsed.filterNotNull()) }.fold(
                    onSuccess = { onDismiss() },
                    onFailure = { error = it.message ?: "保存失败，请重试" }
                )
                saving = false
            }
        }) { Text(if (saving) "保存中…" else "保存") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ScoreOptionsDialog(score: ScoreEntity, onDismiss: () -> Unit,
    onLayout: (LayoutMode?) -> Unit, onPaper: (PaperMode?) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("本谱显示设置") },
        text = { Column {
            Text("页面布局")
            listOf(null to "跟随全局", LayoutMode.AUTO to "自动", LayoutMode.SINGLE to "单页", LayoutMode.DOUBLE to "双页").forEach { (mode, label) ->
                TextButton(onClick = { onLayout(mode) }) { Text(if (score.layoutOverride == mode?.name) "✓ $label" else label) }
            }
            Text("琴谱颜色")
            listOf(null to "跟随全局", PaperMode.ORIGINAL to "正常", PaperMode.INVERTED to "反色").forEach { (mode, label) ->
                TextButton(onClick = { onPaper(mode) }) { Text(if (score.paperOverride == mode?.name) "✓ $label" else label) }
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
