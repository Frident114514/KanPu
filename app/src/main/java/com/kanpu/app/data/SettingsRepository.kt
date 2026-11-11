package com.kanpu.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kanpu.app.domain.LayoutMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.kanPuDataStore by preferencesDataStore(name = "kanpu_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class PaperMode { ORIGINAL, INVERTED }

data class ReaderSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val layout: LayoutMode = LayoutMode.AUTO,
    val paper: PaperMode = PaperMode.ORIGINAL,
    val tapTurn: Boolean = true,
    val swipeTurn: Boolean = true,
    val keepAwake: Boolean = true
)

class SettingsRepository(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme")
    private val layoutKey = stringPreferencesKey("layout")
    private val paperKey = stringPreferencesKey("paper")
    private val tapKey = booleanPreferencesKey("tap_turn")
    private val swipeKey = booleanPreferencesKey("swipe_turn")
    private val awakeKey = booleanPreferencesKey("keep_awake")

    val settings: Flow<ReaderSettings> = context.kanPuDataStore.data.map { p ->
        ReaderSettings(
            theme = p[themeKey].safeEnum(ThemeMode.SYSTEM),
            layout = p[layoutKey].safeEnum(LayoutMode.AUTO),
            paper = p[paperKey].safeEnum(PaperMode.ORIGINAL),
            tapTurn = p[tapKey] ?: true,
            swipeTurn = p[swipeKey] ?: true,
            keepAwake = p[awakeKey] ?: true
        )
    }

    suspend fun setTheme(value: ThemeMode) = context.kanPuDataStore.edit { it[themeKey] = value.name }
    suspend fun setLayout(value: LayoutMode) = context.kanPuDataStore.edit { it[layoutKey] = value.name }
    suspend fun setPaper(value: PaperMode) = context.kanPuDataStore.edit { it[paperKey] = value.name }
    suspend fun setTapTurn(value: Boolean) = context.kanPuDataStore.edit { it[tapKey] = value }
    suspend fun setSwipeTurn(value: Boolean) = context.kanPuDataStore.edit { it[swipeKey] = value }
    suspend fun setKeepAwake(value: Boolean) = context.kanPuDataStore.edit { it[awakeKey] = value }
}

private inline fun <reified T : Enum<T>> String?.safeEnum(fallback: T): T =
    runCatching { enumValueOf<T>(this ?: "") }.getOrDefault(fallback)
