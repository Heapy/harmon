package dev.yoda.harmon.util

import kotlin.math.absoluteValue
import kotlin.math.roundToLong

object Format {
    private const val KIBIBYTE = 1_024.0
    private const val MEBIBYTE = KIBIBYTE * 1_024.0
    private const val GIBIBYTE = MEBIBYTE * 1_024.0
    private const val TEBIBYTE = GIBIBYTE * 1_024.0

    fun decimal(value: Double): String {
        if (!value.isFinite()) {
            return "0.0"
        }
        val scaled = (value * 10.0).roundToLong()
        val integral = scaled / 10
        val fraction = (scaled % 10).absoluteValue
        return "$integral.$fraction"
    }

    fun bytes(value: ULong): String {
        val bytes = value.toDouble()
        return when {
            bytes >= TEBIBYTE -> "${decimal(bytes / TEBIBYTE)} TiB"
            bytes >= GIBIBYTE -> "${decimal(bytes / GIBIBYTE)} GiB"
            bytes >= MEBIBYTE -> "${decimal(bytes / MEBIBYTE)} MiB"
            bytes >= KIBIBYTE -> "${decimal(bytes / KIBIBYTE)} KiB"
            else -> "$value B"
        }
    }

    fun bytesPerSecond(value: Double): String =
        "${bytes(value.coerceAtLeast(0.0).toULong())}/s"

    fun power(value: Double): String {
        val watts = value.takeIf { it.isFinite() && it > 0.0 } ?: return "0 W"
        return when {
            watts >= 1.0 -> "${decimal(watts)} W"
            watts >= 0.001 -> "${decimal(watts * 1_000.0)} mW"
            else -> "${decimal(watts * 1_000_000.0)} \u00b5W"
        }
    }
}
