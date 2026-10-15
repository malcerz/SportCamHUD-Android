package com.sportcamhud.app

import android.app.Application

class SportCamHudApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
