package com.kanpu.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kanpu.app.data.PaperMode
import com.kanpu.app.data.ReaderSettings
import com.kanpu.app.data.SettingsRepository
import com.kanpu.app.data.ThemeMode
import com.kanpu.app.domain.LayoutMode
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: SettingsRepository, settings: ReaderSettings, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("设置") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回书库") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("界面明暗", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(settings.theme == mode, onClick = { scope.launch { repository.setTheme(mode) } },
                        label = { Text(when (mode) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }) })
                }
            }
            Text("页面布局", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LayoutMode.entries.forEach { mode ->
                    FilterChip(settings.layout == mode, onClick = { scope.launch { repository.setLayout(mode) } },
                        label = { Text(when (mode) { LayoutMode.AUTO -> "自动"; LayoutMode.SINGLE -> "单页"; LayoutMode.DOUBLE -> "双页" }) })
                }
            }
            Text("琴谱颜色", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Text("只改变显示，不修改原 PDF。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperMode.entries.forEach { mode ->
                    FilterChip(settings.paper == mode, onClick = { scope.launch { repository.setPaper(mode) } },
                        label = { Text(if (mode == PaperMode.ORIGINAL) "正常" else "反色") })
                }
            }
            Text("翻页", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            ListItem(headlineContent = { Text("点击左右侧翻页") }, trailingContent = {
                Switch(settings.tapTurn, onCheckedChange = { scope.launch { repository.setTapTurn(it) } })
            })
            ListItem(headlineContent = { Text("左右滑动翻页") }, trailingContent = {
                Switch(settings.swipeTurn, onCheckedChange = { scope.launch { repository.setSwipeTurn(it) } })
            })
            ListItem(headlineContent = { Text("阅读时屏幕常亮") }, trailingContent = {
                Switch(settings.keepAwake, onCheckedChange = { scope.launch { repository.setKeepAwake(it) } })
            })
            Text("存储", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            OutlinedButton(onClick = { File(context.cacheDir, "thumbnails").deleteRecursively() }) { Text("清理页面缩略图缓存") }
            Text("琴谱、指法和逐页时间保存在本设备。卸载应用会清除应用内数据。", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
        }
    }
}
