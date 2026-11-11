package com.kanpu.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kanpu.app.data.ImportCoordinator
import com.kanpu.app.data.ScoreEntity
import com.kanpu.app.data.ScoreListSummary
import com.kanpu.app.data.ScoreRepository
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: ScoreRepository,
    importer: ImportCoordinator,
    onSettings: () -> Unit,
    onOpen: (String) -> Unit,
    takeReadPermission: (Intent) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val lists by repository.lists.collectAsState(initial = emptyList())
    val all by repository.allScores.collectAsState(initial = emptyList())
    var selectedList by rememberSaveable { mutableStateOf<String?>(null) }
    val shownFlow = remember(selectedList) {
        selectedList?.let(repository::inList) ?: repository.allScores
    }
    val shown by shownFlow.collectAsState(initial = emptyList())
    val progress by importer.progress.collectAsState()
    val recentJobs by repository.dao.recentImportJobs().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("最近阅读") }
    var importMenu by remember { mutableStateOf(false) }
    var createList by remember { mutableStateOf(false) }
    var editList by remember { mutableStateOf(false) }
    var deleteList by remember { mutableStateOf(false) }
    var selectedScore by remember { mutableStateOf<ScoreEntity?>(null) }
    var scoreAction by remember { mutableStateOf("") }
    var showImportDetails by remember { mutableStateOf(false) }
    var gridLists by rememberSaveable { mutableStateOf(true) }
    var coverVersion by remember { mutableIntStateOf(0) }
    var coverTarget by remember { mutableStateOf<String?>(null) }
    var coverListId by remember { mutableStateOf<String?>(null) }

    val targetList = selectedList
    val coverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = coverTarget
        coverTarget = null
        if (uri != null && target != null) scope.launch {
            runCatching {
                if (target.startsWith("score:")) repository.covers.setScoreImage(target.removePrefix("score:"), uri)
                else repository.covers.setListImage(target.removePrefix("list:"), uri)
            }.onSuccess { coverVersion++ }
                .onFailure { snack.showSnackbar(it.message ?: "封面保存失败") }
        }
    }
    fun selectedUris(intent: Intent): List<Uri> {
        val result = mutableListOf<Uri>()
        intent.data?.let(result::add)
        intent.clipData?.let { clip ->
            repeat(clip.itemCount) { result.add(clip.getItemAt(it).uri) }
        }
        return result.distinct()
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.let { data ->
            takeReadPermission(data)
            importer.startFiles(selectedUris(data), targetList)
        }
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.let { data ->
            takeReadPermission(data)
            data.data?.let { importer.startTree(it, targetList) }
        }
    }
    fun launchFiles(multiple: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        fileLauncher.launch(intent)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的琴谱") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "设置") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    Button(onClick = { importMenu = true }) { Icon(Icons.Default.Add, null); Text("导入") }
                    DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                        DropdownMenuItem(text = { Text("导入一个 PDF") }, onClick = { importMenu = false; launchFiles(false) })
                        DropdownMenuItem(text = { Text("批量导入 PDF") }, onClick = { importMenu = false; launchFiles(true) })
                        DropdownMenuItem(text = { Text("导入文件夹（含子文件夹）") }, onClick = {
                            importMenu = false
                            treeLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                        })
                    }
                }
                OutlinedButton(onClick = { createList = true }) { Text("新建琴谱单") }
            }
            Spacer(Modifier.height(12.dp))
            if (targetList != null) {
                TextButton(onClick = { selectedList = null }) { Text("‹ 返回全部琴谱") }
                Text(lists.firstOrNull { it.id == targetList }?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { scoreAction = "ADD_EXISTING" }) { Text("添加已有琴谱") }
                    TextButton(onClick = { coverListId = targetList }) { Text("更改封面") }
                    TextButton(onClick = { editList = true }) { Text("重命名琴谱单") }
                    TextButton(onClick = { deleteList = true }) { Text("删除琴谱单") }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("琴谱单", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { gridLists = true }) {
                        Icon(Icons.Default.GridView, contentDescription = "网格显示琴谱单",
                            tint = if (gridLists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { gridLists = false }) {
                        Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "列表显示琴谱单",
                            tint = if (!gridLists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (targetList != null) "搜索当前琴谱单" else "搜索琴谱或琴谱单") },
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("排序", style = MaterialTheme.typography.labelMedium)
                listOf("最近阅读", "导入时间", "名称").forEach { label ->
                    TextButton(onClick = { sort = label }) { Text(if (sort == label) "✓ $label" else label) }
                }
            }
            progress?.let { p ->
                val itemFlow = remember(p.jobId) { repository.dao.importItems(p.jobId) }
                val importItems by itemFlow.collectAsState(initial = emptyList())
                if (p.status == "RUNNING") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (p.scanning) "扫描中 · 已发现 ${p.discovered} 个 PDF" else "导入 ${p.completed} / ${p.discovered}")
                            if (p.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else if (p.discovered > 0) LinearProgressIndicator(progress = { p.completed.toFloat() / p.discovered }, modifier = Modifier.fillMaxWidth())
                            TextButton(onClick = importer::cancel) { Text("取消导入") }
                        }
                    }
                } else if (p.status != "DISMISSED") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("导入${if (p.status == "COMPLETED") "完成" else "已中断"}：成功 ${p.succeeded} · 重复 ${p.duplicated} · 失败 ${p.failed} · 未处理 ${(p.discovered - p.completed).coerceAtLeast(0)} · 已加入琴谱单 ${p.addedToList}")
                            p.errors.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { showImportDetails = true }) { Text("查看详细结果") }
                            if (p.status in setOf("CANCELLED", "INTERRUPTED") || p.failed > 0) {
                                TextButton(onClick = { importer.resume(p.jobId) }) { Text("重试未完成或失败项") }
                            }
                            TextButton(onClick = { importer.dismissProgress() }) { Text("关闭结果") }
                        }
                    }
                }
                if (showImportDetails) AlertDialog(
                    onDismissRequest = { showImportDetails = false },
                    title = { Text("导入结果") },
                    text = {
                        LazyColumn(Modifier.height(360.dp)) {
                            items(importItems, key = { it.id }) { item ->
                                Text("${item.relativePath} · ${when (item.status) {
                                    "SUCCESS" -> "成功"
                                    "DUPLICATE" -> "重复"
                                    "FAILED" -> "失败：${item.errorCode ?: "未知错误"}"
                                    else -> "未处理"
                                }}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showImportDetails = false }) { Text("完成") } }
                )
            }
            recentJobs.firstOrNull { it.status in setOf("INTERRUPTED", "CANCELLED") }?.let { job ->
                TextButton(onClick = { importer.resume(job.id) }) { Text("继续上次中断的导入") }
            }
            val filtered = shown.filter { it.title.contains(query, ignoreCase = true) }
            val displayed = when {
                targetList != null && sort == "最近阅读" -> filtered
                sort == "名称" -> filtered.sortedBy { it.title.lowercase() }
                sort == "导入时间" -> filtered.sortedByDescending { it.createdAt }
                else -> filtered.sortedByDescending { it.lastOpenedAt }
            }
            val visibleLists = if (targetList == null) lists.filter { it.name.contains(query, ignoreCase = true) } else emptyList()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (targetList == null) {
                    if (visibleLists.isEmpty() && query.isBlank()) item { Text("还没有琴谱单", style = MaterialTheme.typography.bodySmall) }
                    if (gridLists) {
                        items(visibleLists.chunked(2), key = { "lists-${it.first().id}" }) { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { list ->
                                    Box(Modifier.weight(1f)) {
                                        ScoreListCard(list, true, repository.covers.listFile(list.id), coverVersion,
                                            onOpen = { selectedList = list.id }, onCover = { coverListId = list.id })
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    } else {
                        items(visibleLists, key = { "list-${it.id}" }) { list ->
                            ScoreListCard(list, false, repository.covers.listFile(list.id), coverVersion,
                                onOpen = { selectedList = list.id }, onCover = { coverListId = list.id })
                        }
                    }
                    item { Text("全部琴谱 (${all.size})", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp)) }
                }
                if (displayed.isEmpty()) {
                    item { Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Text(if (query.isNotBlank()) "没有找到匹配的琴谱" else if (all.isEmpty()) "导入 PDF 琴谱，开始练习" else "这里还没有琴谱")
                    } }
                } else {
                    items(displayed, key = { "score-${it.id}" }) { score ->
                        ScoreRow(score, coverVersion, repository.covers.scoreFile(score.id), onOpen = { onOpen(score.id) }, onAction = { action ->
                            selectedScore = score
                            scoreAction = action
                        }, onMove = { delta ->
                            if (targetList != null) scope.launch {
                                val ids = shown.map { it.id }.toMutableList()
                                val from = ids.indexOf(score.id)
                                val to = from + delta
                                if (from >= 0 && to in ids.indices) {
                                    ids.removeAt(from)
                                    ids.add(to, score.id)
                                    runCatching { repository.reorderList(targetList, ids) }
                                        .onFailure { snack.showSnackbar(it.message ?: "排序失败") }
                                }
                            }
                        }, inList = targetList != null)
                    }
                }
            }
        }
    }

    if (createList) NameDialog("新建琴谱单", "琴谱单名称", "", onDismiss = { createList = false }) { name ->
        scope.launch { runCatching { repository.createList(name) }
            .onSuccess { selectedList = it; createList = false }
            .onFailure { snack.showSnackbar(it.message ?: "创建失败") } }
    }
    if (editList && targetList != null) NameDialog("重命名琴谱单", "琴谱单名称", lists.firstOrNull { it.id == targetList }?.name.orEmpty(), onDismiss = { editList = false }) { name ->
        scope.launch { runCatching { repository.renameList(targetList, name) }
            .onSuccess { editList = false }
            .onFailure { snack.showSnackbar(it.message ?: "重命名失败") } }
    }
    if (deleteList && targetList != null) ConfirmDialog("删除琴谱单？", "只删除分类，琴谱仍保留在书库。", onDismiss = { deleteList = false }) {
        scope.launch { repository.deleteList(targetList); selectedList = null; deleteList = false }
    }
    coverListId?.let { id ->
        AlertDialog(onDismissRequest = { coverListId = null }, title = { Text("琴谱单封面") },
            text = { Text("选择本地图片作为琴谱单封面，或恢复默认封面。") },
            confirmButton = { TextButton(onClick = {
                coverListId = null
                coverTarget = "list:$id"
                coverLauncher.launch("image/*")
            }) { Text("上传图片") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { scope.launch {
                        repository.covers.clearList(id); coverVersion++; coverListId = null
                    } }) { Text("恢复默认") }
                    TextButton(onClick = { coverListId = null }) { Text("取消") }
                }
            })
    }
    val currentScore = selectedScore
    when (scoreAction) {
        "COVER" -> if (currentScore != null) AlertDialog(
            onDismissRequest = { scoreAction = "" }, title = { Text("琴谱封面") },
            text = { Text("可上传图片，或选择这份琴谱中的任意一页作为封面。") },
            confirmButton = { TextButton(onClick = {
                scoreAction = ""
                coverTarget = "score:${currentScore.id}"
                coverLauncher.launch("image/*")
            }) { Text("上传图片") } },
            dismissButton = { Row {
                TextButton(onClick = { scoreAction = "COVER_PAGE" }) { Text("选择谱页") }
                TextButton(onClick = { scope.launch {
                    repository.covers.clearScore(currentScore.id); coverVersion++; scoreAction = ""
                } }) { Text("恢复默认") }
            } }
        )
        "COVER_PAGE" -> if (currentScore != null) {
            var pageText by remember(currentScore.id) { mutableStateOf("1") }
            var pageError by remember(currentScore.id) { mutableStateOf("") }
            AlertDialog(onDismissRequest = { scoreAction = "" }, title = { Text("选择封面页") },
                text = { Column {
                    Text("输入 1—${currentScore.pageCount} 页")
                    OutlinedTextField(pageText, { pageText = it; pageError = "" },
                        label = { Text("页码") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    if (pageError.isNotEmpty()) Text(pageError, color = MaterialTheme.colorScheme.error)
                } },
                confirmButton = { TextButton(onClick = {
                    val page = pageText.toIntOrNull()
                    if (page == null || page !in 1..currentScore.pageCount) pageError = "请输入有效页码"
                    else scope.launch {
                        runCatching { repository.covers.setScorePage(currentScore.id, page - 1) }
                            .onSuccess { coverVersion++; scoreAction = "" }
                            .onFailure { pageError = it.message ?: "封面保存失败" }
                    }
                }) { Text("保存") } },
                dismissButton = { TextButton(onClick = { scoreAction = "" }) { Text("取消") } })
        }
        "ADD_EXISTING" -> if (targetList != null) {
            AddExistingDialog(all, shown.map { it.id }.toSet(), onDismiss = { scoreAction = "" }) { ids ->
                scope.launch {
                    ids.forEach { repository.addToList(targetList, it) }
                    scoreAction = ""
                }
            }
        }
        "ADD_TO_LIST" -> if (currentScore != null) AlertDialog(
            onDismissRequest = { scoreAction = "" }, title = { Text("加入琴谱单") },
            text = { Column { lists.forEach { list -> TextButton(onClick = {
                scope.launch { repository.addToList(list.id, currentScore.id); scoreAction = "" }
            }) { Text(list.name) } } } },
            confirmButton = { TextButton(onClick = { scoreAction = "" }) { Text("完成") } }
        )
        "RENAME" -> if (currentScore != null) NameDialog("重命名琴谱", "显示名称", currentScore.title,
            onDismiss = { scoreAction = "" }) { name ->
            scope.launch {
                if (name.trim().isBlank()) snack.showSnackbar("名称不能为空")
                else { repository.dao.renameScore(currentScore.id, name.trim()); scoreAction = "" }
            }
        }
        "REMOVE" -> if (currentScore != null && targetList != null) ConfirmDialog("移出琴谱单？", "琴谱和指法仍保留在书库。", onDismiss = { scoreAction = "" }) {
            scope.launch { repository.removeFromList(targetList, currentScore.id); scoreAction = "" }
        }
        "DELETE" -> if (currentScore != null) ConfirmDialog("从书库删除琴谱？", "应用内副本、指法和计时会删除；原 PDF 不受影响。", onDismiss = { scoreAction = "" }) {
            scope.launch { repository.deleteScore(currentScore.id); scoreAction = "" }
        }
    }
}

@Composable
private fun ScoreListCard(list: ScoreListSummary, grid: Boolean, file: File, coverVersion: Int,
    onOpen: () -> Unit, onCover: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        if (grid) {
            Column {
                Box {
                    CoverArt(file, coverVersion, list.name, Modifier.fillMaxWidth().height(110.dp))
                    Box(Modifier.align(Alignment.TopEnd)) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "${list.name} 更多操作") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("更改封面") }, onClick = { menu = false; onCover() })
                        }
                    }
                }
                Column(Modifier.padding(10.dp)) {
                    Text(list.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text("${list.count} 首琴谱", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverArt(file, coverVersion, list.name, Modifier.size(72.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(list.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text("${list.count} 首琴谱", style = MaterialTheme.typography.bodySmall)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "${list.name} 更多操作") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("更改封面") }, onClick = { menu = false; onCover() })
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverArt(file: File, version: Int, title: String, modifier: Modifier) {
    val bitmap = remember(file.absolutePath, version) { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
    if (bitmap != null) Image(bitmap, contentDescription = "$title 封面", modifier = modifier,
        contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text("♫", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun ScoreRow(score: ScoreEntity, coverVersion: Int, coverFile: File, onOpen: () -> Unit,
    onAction: (String) -> Unit, onMove: (Int) -> Unit, inList: Boolean) {
    val context = LocalContext.current
    val thumb = remember(score.id, coverVersion) {
        BitmapFactory.decodeFile((if (coverFile.exists()) coverFile else File(context.cacheDir, "thumbnails/${score.id}.png")).absolutePath)?.asImageBitmap()
    }
    var menu by remember { mutableStateOf(false) }
    val currentMove by rememberUpdatedState(onMove)
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (thumb != null) Image(thumb, contentDescription = "${score.title} 封面", modifier = Modifier.size(54.dp, 72.dp), contentScale = ContentScale.Crop)
            else Box(Modifier.size(54.dp, 72.dp), contentAlignment = Alignment.Center) { Text("PDF") }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(score.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text("${score.pageCount} 页", style = MaterialTheme.typography.bodySmall)
            }
            if (inList) {
                Icon(Icons.Default.DragHandle, contentDescription = "长按拖动排序",
                    modifier = Modifier.size(48.dp).pointerInput(score.id) {
                        var dragY = 0f
                        detectDragGesturesAfterLongPress(
                            onDragEnd = {
                                val rows = (dragY / 80.dp.toPx()).roundToInt()
                                if (rows != 0) currentMove(rows)
                                dragY = 0f
                            },
                            onDragCancel = { dragY = 0f },
                            onDrag = { change, amount -> change.consume(); dragY += amount.y }
                        )
                    })
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "更多操作") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    if (inList) {
                        DropdownMenuItem(text = { Text("上移") }, onClick = { menu = false; onMove(-1) })
                        DropdownMenuItem(text = { Text("下移") }, onClick = { menu = false; onMove(1) })
                    }
                    listOf("COVER" to "更改封面", "ADD_TO_LIST" to "加入琴谱单", "RENAME" to "重命名", "REMOVE" to "从当前琴谱单移出", "DELETE" to "从书库删除").forEach { (action, label) ->
                        if (action != "REMOVE" || inList) DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onAction(action) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ConfirmDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun AddExistingDialog(all: List<ScoreEntity>, existing: Set<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val selected = remember { mutableStateOf(setOf<String>()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加已有琴谱") },
        text = { LazyColumn(Modifier.height(360.dp)) {
            items(all.filterNot { it.id in existing }) { score ->
                Row(Modifier.fillMaxWidth().clickable {
                    selected.value = if (score.id in selected.value) selected.value - score.id else selected.value + score.id
                }.padding(8.dp)) { Text(if (score.id in selected.value) "✓ ${score.title}" else score.title) }
            }
        } },
        confirmButton = { TextButton(onClick = { onSave(selected.value.toList()) }) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
