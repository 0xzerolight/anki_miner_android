package com.ankiminer.android.mining

import com.ankiminer.android.AnkiMinerApplication
import com.ankiminer.android.data.anki.MiningRunUndoManager

internal object MiningRunUndoManagerFactory {
    fun create(application: AnkiMinerApplication): MiningRunUndoManager = application.miningRunUndoManager
}
