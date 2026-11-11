package com.kanpu.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.kanpu.app.data.ThemeMode
import com.kanpu.app.ui.LibraryScreen
import com.kanpu.app.ui.ReaderScreen
import com.kanpu.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val services by lazy { application as KanPuApplication }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KanPuApp() }
    }

    @Composable
    private fun KanPuApp() {
        val prefs by services.settings.settings.collectAsState(initial = com.kanpu.app.data.ReaderSettings())
        val systemDark = isSystemInDarkTheme()
        val dark = when (prefs.theme) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        var screen by rememberSaveable { mutableStateOf("LIBRARY") }
        var readingId by rememberSaveable { mutableStateOf<String?>(null) }
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            when (screen) {
                "READER" -> readingId?.let { id ->
                    ReaderScreen(id, services.scores, services.settings, prefs, onBack = {
                        services.closeReader()
                        screen = "LIBRARY"
                    })
                }
                "SETTINGS" -> SettingsScreen(services.settings, prefs, onBack = { screen = "LIBRARY" })
                else -> LibraryScreen(
                    repository = services.scores,
                    importer = services.importer,
                    onSettings = { screen = "SETTINGS" },
                    onOpen = { id -> readingId = id; screen = "READER" },
                    takeReadPermission = { intent -> persistReadPermission(intent) }
                )
            }
        }
    }

    private fun persistReadPermission(intent: Intent) {
        val flags = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        val uris = mutableListOf<android.net.Uri>()
        intent.data?.let(uris::add)
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) uris.add(clip.getItemAt(index).uri)
        }
        uris.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        }
    }
}
