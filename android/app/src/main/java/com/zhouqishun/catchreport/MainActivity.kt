package com.zhouqishun.catchreport

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var captureOnlyRadio: RadioButton
    private lateinit var upstreamProxyRadio: RadioButton
    private lateinit var proxyHostInput: EditText
    private lateinit var proxyPortInput: EditText
    private lateinit var preferences: CapturePreferences
    private var pendingConfig: CaptureConfig = CaptureConfig.default()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = CapturePreferences(this)
        pendingConfig = preferences.load()
        requestNotificationPermissionIfNeeded()
        setContentView(ScrollView(this).apply {
            addView(buildLayout())
        })
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
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            setPadding(36, 48, 36, 36)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
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

        val modeLabel = sectionLabel("模式")
        val modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }

        captureOnlyRadio = RadioButton(this).apply {
            text = "只记录 PCAP"
            id = 100
        }
        upstreamProxyRadio = RadioButton(this).apply {
            text = "挂自己的代理再抓包"
            id = 101
        }
        modeGroup.addView(captureOnlyRadio)
        modeGroup.addView(upstreamProxyRadio)

        proxyHostInput = EditText(this).apply {
            hint = "Clash 所在设备 IP，例如 192.168.1.10"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }

        proxyPortInput = EditText(this).apply {
            hint = "Clash mixed-port，常见 7890"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }

        applyConfigToForm(pendingConfig)

        val localClashButton = Button(this).apply {
            text = "使用安卓本机 Clash"
            setOnClickListener {
                upstreamProxyRadio.isChecked = true
                proxyHostInput.setText(UpstreamProxyConfig.LOCALHOST)
                proxyPortInput.setText(UpstreamProxyConfig.DEFAULT_CLASH_MIXED_PORT.toString())
                statusText.text = "已填入 127.0.0.1:${UpstreamProxyConfig.DEFAULT_CLASH_MIXED_PORT}"
            }
        }

        val windowsClashButton = Button(this).apply {
            text = "使用 Windows Clash"
            setOnClickListener {
                upstreamProxyRadio.isChecked = true
                proxyHostInput.setText("")
                proxyPortInput.setText(UpstreamProxyConfig.DEFAULT_CLASH_MIXED_PORT.toString())
                statusText.text = "请填写 Windows 的局域网 IP"
            }
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
            text = "代理链模式已经保存配置并写入元数据；完整 TCP/UDP 转发引擎下一阶段接入。"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 0)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)
        root.addView(modeLabel)
        root.addView(modeGroup, fullWidthLayoutParams())
        root.addView(sectionLabel("上游代理"))
        root.addView(proxyHostInput, fullWidthLayoutParams())
        root.addView(proxyPortInput, fullWidthLayoutParams())
        root.addView(localClashButton, buttonLayoutParams())
        root.addView(windowsClashButton, buttonLayoutParams())
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

    private fun fullWidthLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 12
        }
    }

    private fun sectionLabel(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 14f
            setPadding(0, 16, 0, 6)
        }
    }

    private fun prepareVpnAndStart() {
        val config = readConfigFromForm() ?: return
        pendingConfig = config
        preferences.save(config)

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
            pendingConfig.putInto(this)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusText.text = "抓包服务启动中"
    }

    private fun readConfigFromForm(): CaptureConfig? {
        val mode = if (upstreamProxyRadio.isChecked) {
            CaptureMode.UPSTREAM_PROXY
        } else {
            CaptureMode.CAPTURE_ONLY
        }
        val host = proxyHostInput.text.toString().trim()
        val port = proxyPortInput.text.toString().toIntOrNull() ?: 0

        if (mode == CaptureMode.UPSTREAM_PROXY && (host.isBlank() || port !in 1..65535)) {
            statusText.text = "请填写有效的代理地址和端口"
            return null
        }

        return CaptureConfig(
            mode = mode,
            proxy = UpstreamProxyConfig(
                enabled = mode == CaptureMode.UPSTREAM_PROXY,
                type = UpstreamProxyConfig.Type.SOCKS5,
                host = host,
                port = if (port > 0) port else UpstreamProxyConfig.DEFAULT_CLASH_MIXED_PORT
            )
        )
    }

    private fun applyConfigToForm(config: CaptureConfig) {
        captureOnlyRadio.isChecked = config.mode == CaptureMode.CAPTURE_ONLY
        upstreamProxyRadio.isChecked = config.mode == CaptureMode.UPSTREAM_PROXY
        proxyHostInput.setText(config.proxy.host)
        proxyPortInput.setText(if (config.proxy.port > 0) config.proxy.port.toString() else "")
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
