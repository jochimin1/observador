package com.ubers.observador.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/** 1.35.0: three short pulses for a card in a sector set to «Evitar», different from any other alert. */
object SectorAlert {

    private val PATTERN = longArrayOf(0, 120, 90, 120, 90, 120)

    fun buzz(context: Context) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            if (vibrator?.hasVibrator() == true) vibrator.vibrate(VibrationEffect.createWaveform(PATTERN, -1))
        } catch (e: Exception) {
            Log.w("SectorAlert", "Could not vibrate", e)
        }
    }
}
