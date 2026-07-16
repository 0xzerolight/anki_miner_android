package com.ankiminer.android

import android.app.Application
import com.ankiminer.android.media.AndroidSafBroker
import com.ankiminer.android.media.SafBroker

class AnkiMinerApplication : Application() {
    /** One process-wide grant ledger prevents Activity recreation from splitting SAF ownership. */
    val safBroker: SafBroker by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidSafBroker(this)
    }
}
