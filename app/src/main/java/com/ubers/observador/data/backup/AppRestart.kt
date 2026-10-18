package com.ubers.observador.data.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Opens the app again from scratch after a restore (Room was closed and replaced, so nothing in memory can
 * be reused). 1.32.0 started the app and then killed its own process, which killed the new start too (seen
 * on the phone). Now a tiny activity in a SEPARATE process (":restart") does the starting: the main process
 * can die without taking the relaunch with it.
 */
object AppRestart {
    const val EXTRA_TARGET = "target"

    fun now(context: Context) {
        val app = context.applicationContext
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) {
            app.startActivity(
                Intent(app, RestartActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_TARGET, launch)
            )
        }
        Process.killProcess(Process.myPid())
    }
}

/** Runs in the ":restart" process: waits for the main process to be gone, starts the app, and ends itself. */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val target = intent.getParcelableExtra<Intent>(AppRestart.EXTRA_TARGET)
        // Give the system a moment to finish removing the old process before the new one is created
        window.decorView.postDelayed({
            if (target != null) startActivity(target)
            finish()
            Process.killProcess(Process.myPid())
        }, 300)
    }
}
