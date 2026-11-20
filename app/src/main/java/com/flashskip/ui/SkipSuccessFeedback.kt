package com.flashskip.ui

import android.content.Context
import android.os.Handler
import android.os.Looper

/** Delivers an already-verified close to the optional presentation layer. */
object SkipSuccessFeedback {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var listener: ((Context) -> Unit)? = null

    internal fun onVerifiedClosed(context: Context) {
        if (listener == null) return
        val appContext = context.applicationContext
        mainHandler.post { listener?.invoke(appContext) }
    }
}
