package com.flashskip

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.flashskip.data.SkipLog
import com.flashskip.data.StatsStore
import com.flashskip.experimental.ExperimentalSkip
import com.flashskip.ui.SkipSuccessFeedback
import com.flashskip.ui.SkipSuccessToast

class FlashSkipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(StatsStore(this).uiThemeMode)
        SkipLog.init(this)
        ExperimentalSkip.initialize(this)
        SkipSuccessFeedback.listener = { appContext ->
            SkipSuccessToast.onVerifiedClosed(appContext)
        }
    }
}
