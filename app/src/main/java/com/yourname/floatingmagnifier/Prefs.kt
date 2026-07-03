package com.yourname.floatingmagnifier

import android.content.Context

/**
 * 앱 설정 저장/불러오기.
 * 확대창 비율 등 사용자 설정을 기기에 저장한다.
 */
object Prefs {
    private const val FILE = "ddoryeot_prefs"
    private const val KEY_RATIO = "viewer_ratio"
    private const val KEY_ZOOM = "viewer_zoom"

    // 확대창 비율 옵션
    const val RATIO_1_1 = "1:1"
    const val RATIO_4_3 = "4:3"
    const val RATIO_16_9 = "16:9"

    // 배율 범위
    const val ZOOM_MIN = 2.0f
    const val ZOOM_MAX = 3.0f
    const val ZOOM_DEFAULT = 2.5f

    fun getRatio(context: Context): String {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return sp.getString(KEY_RATIO, RATIO_1_1) ?: RATIO_1_1
    }

    fun setRatio(context: Context, ratio: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RATIO, ratio)
            .apply()
    }

    fun getZoom(context: Context): Float {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return sp.getFloat(KEY_ZOOM, ZOOM_DEFAULT).coerceIn(ZOOM_MIN, ZOOM_MAX)
    }

    fun setZoom(context: Context, zoom: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_ZOOM, zoom.coerceIn(ZOOM_MIN, ZOOM_MAX))
            .apply()
    }

    /** 비율 문자열 → 가로/세로 비 (width / height) */
    fun ratioToWH(ratio: String): Pair<Int, Int> = when (ratio) {
        RATIO_4_3 -> 4 to 3
        RATIO_16_9 -> 16 to 9
        else -> 1 to 1
    }
}
