package com.ubers.observador.data.backup

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process

/** Opens the app again from scratch (Room was closed and replaced, so nothing in memory can be reused). */
object AppRestart {
    fun now(context: Context) {
        val app = context.applicationContext
        val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) app.startActivity(intent)
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 400)
    }
}
