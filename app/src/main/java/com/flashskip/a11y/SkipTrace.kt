package com.flashskip.a11y

import android.util.Log

/** On-demand logcat diagnostics. Callers pass only stages, counters, and monotonic timing. */
internal object SkipTrace {
    private const val TAG = "FlashSkipTrace"

    inline fun log(engine: String, fields: () -> String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "engine=$engine ${fields()}")
    }
}
