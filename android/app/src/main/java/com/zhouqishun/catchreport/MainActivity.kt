package com.zhouqishun.catchreport

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContentView(buildLayout())
    }

    @Deprecated("Deprecated in platform API; kept for minimal dependency-free MVP.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startCaptureService()
        } else if (requestCode == VPN_REQUEST_CODE) {
            statusText.text = "VPN 授权被取消"
        }
    }

    private fun buildLayout(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(36, 48, 36, 36)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "Catch Report"
            textSize = 24f
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "非 root VpnService 抓包"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 28)
        }

        statusText = TextView(this).apply {
            text = "未开始"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 28)
        }

        val startButton = Button(this).apply {
            text = "开始抓包"
            setOnClickListener { prepareVpnAndStart() }
        }

        val stopButton = Button(this).apply {
            text = "停止抓包"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureVpnService::class.java).apply {
                    action = CaptureVpnService.ACTION_STOP
                })
                statusText.text = "已发送停止命令"
            }
        }

        val note = TextView(this).apply {
            text = "第一阶段写出 PCAP；完整转发和上游代理链将在下一阶段接入。"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 0)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)
        root.addView(startButton, buttonLayoutParams())
        root.addView(stopButton, buttonLayoutParams())
        root.addView(note)
        return root
    }

    private fun buttonLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 14
        }
    }

    private fun prepareVpnAndStart() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            statusText.text = "等待 VPN 授权"
            startActivityForResult(prepareIntent, VPN_REQUEST_CODE)
        } else {
            startCaptureService()
        }
    }

    private fun startCaptureService() {
        val intent = Intent(this, CaptureVpnService::class.java).apply {
            action = CaptureVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusText.text = "抓包服务启动中"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    companion object {
        private const val VPN_REQUEST_CODE = 42
    }
}
