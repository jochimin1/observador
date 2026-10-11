package com.ubers.observador.domain

/** "hace 3 min", "hace 2 h", "hace 1 día": how long ago something happened, in plain Spanish. */
object TimeAgo {

    fun format(nowMillis: Long, thenMillis: Long): String {
        // A clock that moved backwards must not print "hace -2 min"
        val minutes = (nowMillis - thenMillis).coerceAtLeast(0L) / 60_000L
        return when {
            minutes < 1 -> "hace menos de 1 min"
            minutes < 60 -> "hace $minutes min"
            minutes < 24 * 60 -> "hace ${minutes / 60} h"
            else -> {
                val days = minutes / (24 * 60)
                if (days == 1L) "hace 1 día" else "hace $days días"
            }
        }
    }
}
