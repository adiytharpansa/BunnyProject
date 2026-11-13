package com.bunny.app.i18n

import android.icu.text.CompactDecimalFormat
import java.util.Locale

object LocaleFormats {

    /** 304600 -> "305K" (Inggris), "305 rb" (Indonesia). */
    fun compactCount(n: Long, locale: Locale = Locale.getDefault()): String =
        CompactDecimalFormat
            .getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT)
            .format(n)
}
