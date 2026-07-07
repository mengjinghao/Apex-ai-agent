package com.ai.assistance.apex.provider;

import android.view.accessibility.AccessibilityEvent;

oneway interface IAccessibilityEventCallback {
    void onAccessibilityEvent(in AccessibilityEvent event);
}
