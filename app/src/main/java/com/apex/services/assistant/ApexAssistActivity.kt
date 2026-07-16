package com.apex.services.assistant

import android.app.Activity
import android.os.Bundle

/** ApexAssistActivity — 透明占位 Activity。 */
class ApexAssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
