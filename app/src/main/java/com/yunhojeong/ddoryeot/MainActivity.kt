package com.yunhojeong.ddoryeot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val OVERLAY_REQUEST = 1001
    private val PROJECTION_REQUEST = 1002
    private lateinit var mpManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Android 13+ 알림 권한 요청 (재시작 알림을 띄우기 위해 필요)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001)
            }
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            checkPermissions()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            stopService(Intent(this, FloatingService::class.java))
            Toast.makeText(this, getString(R.string.toast_stopped), Toast.LENGTH_SHORT).show()
        }

        // 설정(톱니바퀴) 버튼이 레이아웃에 있으면 비율 설정 화면 열기
        val settingsId = resources.getIdentifier("btnSettings", "id", packageName)
        if (settingsId != 0) {
            findViewById<android.view.View>(settingsId)?.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                startActivity(Intent(this, RatioActivity::class.java))
            }
        }

        // 화면 잠금으로 캡처가 죽은 뒤, 조준점 탭/알림으로 들어온 경우 재동의 진행
        if (intent?.getBooleanExtra("auto_restart", false) == true) {
            checkPermissions(reconsent = true)
        }
    }

    private fun checkPermissions(reconsent: Boolean = false) {
        // 이미 실행 중이면 다시 시작하지 않음 (중복 시작 시 캡처가 죽는 문제 방지)
        // 단, 재동의(reconsent) 모드에서는 캡처만 다시 연결해야 하므로 통과시킴
        if (FloatingService.isRunning && !reconsent) {
            Toast.makeText(this, getString(R.string.toast_already_running), Toast.LENGTH_SHORT).show()
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.toast_need_overlay), Toast.LENGTH_LONG).show()
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                OVERLAY_REQUEST
            )
            return
        }
        // 화면 캡처 권한 요청
        // Android 14+에서는 "전체 화면"으로 고정해서 앱 선택 팝업 없이 바로 전체화면 동의창이 뜨게 함
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpManager.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            mpManager.createScreenCaptureIntent()
        }
        startActivityForResult(captureIntent, PROJECTION_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OVERLAY_REQUEST -> {
                if (Settings.canDrawOverlays(this)) checkPermissions()
                else Toast.makeText(this, getString(R.string.toast_need_permission), Toast.LENGTH_SHORT).show()
            }
            PROJECTION_REQUEST -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // 서비스 시작 — Intent에 결과 담아서 전달
                    val serviceIntent = Intent(this, FloatingService::class.java).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("data", data)
                    }
                    startForegroundService(serviceIntent)
                    Toast.makeText(this, getString(R.string.toast_started), Toast.LENGTH_LONG).show()
                    // 홈으로 이동
                    startActivity(Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                } else {
                    Toast.makeText(this, getString(R.string.toast_need_capture), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
