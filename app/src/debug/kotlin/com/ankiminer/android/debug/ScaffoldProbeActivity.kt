package com.ankiminer.android.debug

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import com.ankiminer.android.BuildConfig
import com.ankiminer.android.R

/** Debug-only launcher used to confirm that the technical harness is installed. */
class ScaffoldProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(
            TextView(this).apply {
                gravity = Gravity.CENTER
                text = getString(
                    R.string.scaffold_probe_body,
                    Build.VERSION.SDK_INT,
                    Build.SUPPORTED_ABIS.joinToString(),
                    BuildConfig.PYTHON_VERSION,
                )
            },
        )
    }
}
