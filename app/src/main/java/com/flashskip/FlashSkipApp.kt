package com.flashskip

import android.app.Application
import com.flashskip.data.SkipLog

class FlashSkipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SkipLog.init(this)
    }
}
