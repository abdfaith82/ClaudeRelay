package com.relay.agents

import android.app.Application
import java.io.File

class RelayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val f = File(filesDir, "crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { f.writeText(e.stackTraceToString()) }
            prev?.uncaughtException(t, e)
        }
    }
}
