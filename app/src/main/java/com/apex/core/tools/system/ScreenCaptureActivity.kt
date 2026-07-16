package com.apex.core.tools.system

import android.app.Activity
import android.os.Bundle

/** ScreenCaptureActivity — 透明占位 Activity。 */
class ScreenCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
