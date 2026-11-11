package com.kanpu.app

import android.app.Application
import androidx.room.Room
import com.kanpu.app.data.ImportCoordinator
import com.kanpu.app.data.KanPuDatabase
import com.kanpu.app.data.ScoreEntity
import com.kanpu.app.data.ScoreRepository
import com.kanpu.app.data.SettingsRepository
import com.kanpu.app.ui.ReaderSession

class KanPuApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, KanPuDatabase::class.java, "kanpu.db").build() }
    val scores by lazy { ScoreRepository(this, database) }
    val settings by lazy { SettingsRepository(this) }
    val importer by lazy { ImportCoordinator(this, scores) }

    private var activeReader: Pair<String, ReaderSession>? = null

    fun readerSession(score: ScoreEntity): ReaderSession {
        val existing = activeReader
        if (existing?.first == score.id) return existing.second
        existing?.second?.timer?.stop()
        return ReaderSession(score, scores).also { activeReader = score.id to it }
    }

    fun closeReader() {
        activeReader?.second?.timer?.stop()
        activeReader = null
    }
}
