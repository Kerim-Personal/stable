package com.codenzi.snapnote

import android.app.Activity
import android.content.Context
import androidx.preference.PreferenceManager

object ThemeManager {
    fun applyTheme(activity: Activity) {
        activity.setTheme(getThemeResId(activity))
    }

    fun getThemeResId(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val colorValue = prefs.getString("color_selection", "rose")

        return when (colorValue) {
            "bordo" -> R.style.Theme_AcilNotUygulamasi_Bordo
            "blue" -> R.style.Theme_AcilNotUygulamasi_Blue
            "green" -> R.style.Theme_AcilNotUygulamasi_Green
            "rose" -> R.style.Theme_AcilNotUygulamasi_Rose
            "teal" -> R.style.Theme_AcilNotUygulamasi_Teal
            "indigo" -> R.style.Theme_AcilNotUygulamasi_Indigo
            "orange" -> R.style.Theme_AcilNotUygulamasi_Orange
            "brown" -> R.style.Theme_AcilNotUygulamasi_Brown
            "grey" -> R.style.Theme_AcilNotUygulamasi_Grey
            "cyan" -> R.style.Theme_AcilNotUygulamasi_Cyan
            "lime" -> R.style.Theme_AcilNotUygulamasi_Lime
            else -> R.style.Theme_AcilNotUygulamasi_Rose
        }
    }
}