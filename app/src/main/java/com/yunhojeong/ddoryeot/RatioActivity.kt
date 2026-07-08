package com.yunhojeong.ddoryeot

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlin.math.roundToInt

class RatioActivity : AppCompatActivity() {

    private lateinit var ratio11: TextView
    private lateinit var ratio43: TextView
    private lateinit var ratio169: TextView

    private lateinit var zoomSeek: SeekBar
    private lateinit var zoomValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ratio)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // ===== 비율 =====
        ratio11 = findViewById(R.id.ratio11)
        ratio43 = findViewById(R.id.ratio43)
        ratio169 = findViewById(R.id.ratio169)

        ratio11.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); select(Prefs.RATIO_1_1) }
        ratio43.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); select(Prefs.RATIO_4_3) }
        ratio169.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); select(Prefs.RATIO_16_9) }

        updateSelection(Prefs.getRatio(this))

        // ===== 언어 선택 =====
        val langKo = findViewById<TextView>(R.id.langKo)
        val langEn = findViewById<TextView>(R.id.langEn)
        val langJa = findViewById<TextView>(R.id.langJa)

        langKo.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); setLanguage("ko") }
        langEn.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); setLanguage("en") }
        langJa.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); setLanguage("ja") }

        updateLangSelection(langKo, langEn, langJa)

        // ===== 배율 슬라이더 =====
        // SeekBar progress 0~130 → 배율 1.2~2.5 (progress/100 + 1.2)
        zoomSeek = findViewById(R.id.zoomSeek)
        zoomValue = findViewById(R.id.zoomValue)

        val curZoom = Prefs.getZoom(this)
        zoomSeek.progress = zoomToProgress(curZoom)
        zoomValue.text = formatZoom(curZoom)

        zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var lastProgress = zoomSeek.progress
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val zoom = progressToZoom(progress)
                zoomValue.text = formatZoom(zoom)
                Prefs.setZoom(this@RatioActivity, zoom)
                sendZoom(zoom)
                // 일정 간격마다 가벼운 햅틱 (너무 잦지 않게 5단위로)
                if (fromUser && kotlin.math.abs(progress - lastProgress) >= 5) {
                    lastProgress = progress
                    sb?.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                sb?.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
        })
    }

    private fun progressToZoom(progress: Int): Float =
        (Prefs.ZOOM_MIN + progress / 100f).coerceIn(Prefs.ZOOM_MIN, Prefs.ZOOM_MAX)

    private fun zoomToProgress(zoom: Float): Int =
        ((zoom - Prefs.ZOOM_MIN) * 100f).roundToInt().coerceIn(0, 100)

    private fun formatZoom(zoom: Float): String = String.format("%.1f", zoom)

    private fun select(ratio: String) {
        Prefs.setRatio(this, ratio)
        updateSelection(ratio)
        if (FloatingService.isRunning) {
            val intent = Intent(this, FloatingService::class.java).apply {
                action = FloatingService.ACTION_CHANGE_RATIO
                putExtra("ratio", ratio)
            }
            startService(intent)
        }
    }

    private fun sendZoom(zoom: Float) {
        if (FloatingService.isRunning) {
            val intent = Intent(this, FloatingService::class.java).apply {
                action = FloatingService.ACTION_CHANGE_ZOOM
                putExtra("zoom", zoom)
            }
            startService(intent)
        }
    }

    private fun updateSelection(selected: String) {
        applyStyle(ratio11, selected == Prefs.RATIO_1_1)
        applyStyle(ratio43, selected == Prefs.RATIO_4_3)
        applyStyle(ratio169, selected == Prefs.RATIO_16_9)
    }

    // 앱 언어 변경 (앱 재시작 없이 즉시 적용)
    private fun setLanguage(langCode: String) {
        val locales = LocaleListCompat.forLanguageTags(langCode)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    // 현재 선택된 언어 강조 표시
    private fun updateLangSelection(langKo: TextView, langEn: TextView, langJa: TextView) {
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        applyStyle(langKo, current.startsWith("ko"))
        applyStyle(langEn, current.startsWith("en"))
        applyStyle(langJa, current.startsWith("ja"))
    }

    private fun applyStyle(view: TextView, isSelected: Boolean) {
        if (isSelected) {
            view.setBackgroundResource(R.drawable.ratio_selected)
            view.setTextColor(Color.WHITE)
        } else {
            view.setBackgroundResource(R.drawable.ratio_unselected)
            view.setTextColor(Color.parseColor("#FF3B30"))
        }
    }
}
