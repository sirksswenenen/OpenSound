package com.soundcloud.lite.util

import android.content.Context

object CrashLogger {
    fun install(context: Context) {}
    fun startLiveLogcat(context: Context) {}
    fun readAndClear(context: Context): String? = null
    fun readLiveLog(context: Context): String = ""
    fun recordHandled(context: Context, label: String, throwable: Throwable) {}
}
