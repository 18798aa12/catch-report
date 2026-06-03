package com.zhouqishun.catchreport

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var captureOnlyRadio: RadioButton
    private lateinit var upstreamProxyRadio: RadioButton
    private lateinit var proxyHostInput: EditText
    private lateinit var proxyPortInput: EditText
    private lateinit var embeddedProxyCheck: CheckBox
    private lateinit var subscriptionUrlInput: EditText
    private lateinit var proxyGroupSpinner: Spinner
    private lateinit var proxyNodeSpinner: Spinner
    private lateinit var preferences: CapturePreferences
    private var pendingConfig: CaptureConfig = CaptureConfig.default()
    private var mihomoGroups: List<MihomoProxyGroup> = emptyList()

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
        if (requestCode == CONFIG_IMPORT_REQUEST_CODE && resultCode == RESULT_OK) {
            data?.data?.let { importMihomoConfig(it) }
        } else if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
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
            hint = "Clash mixed/socks 端口，常见 7890"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }

        embeddedProxyCheck = CheckBox(this).apply {
            text = "启动内置 Mihomo 代理核心"
        }

        subscriptionUrlInput = EditText(this).apply {
            hint = "Clash/Mihomo 订阅链接"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(preferences.loadSubscriptionUrl())
        }

        proxyGroupSpinner = Spinner(this)
        proxyNodeSpinner = Spinner(this)
        setSpinnerItems(proxyGroupSpinner, listOf("先开始抓包，再刷新代理组"))
        setSpinnerItems(proxyNodeSpinner, listOf("先选择代理组"))

        applyConfigToForm(pendingConfig)

        val embeddedMihomoButton = Button(this).apply {
            text = "使用内置 Mihomo 代理"
            setOnClickListener {
                selectEmbeddedProxy(if (MihomoCore.hasUserConfig(this@MainActivity)) {
                    "已选择内置 Mihomo：127.0.0.1:${UpstreamProxyConfig.DEFAULT_CLASH_PROXY_PORT}"
                } else {
                    "已选择内置 Mihomo；未导入配置时默认直连"
                })
            }
        }

        val importMihomoButton = Button(this).apply {
            text = "导入 Clash/Mihomo 配置"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivityForResult(intent, CONFIG_IMPORT_REQUEST_CODE)
            }
        }

        val downloadSubscriptionButton = Button(this).apply {
            text = "下载订阅配置"
            setOnClickListener { downloadMihomoSubscription() }
        }

        val refreshNodesButton = Button(this).apply {
            text = "刷新代理节点"
            setOnClickListener { refreshMihomoNodes() }
        }

        val applyNodeButton = Button(this).apply {
            text = "应用代理节点"
            setOnClickListener { applyMihomoNode() }
        }

        val localClashButton = Button(this).apply {
            text = "使用安卓本机 Clash"
            setOnClickListener {
                upstreamProxyRadio.isChecked = true
                embeddedProxyCheck.isChecked = false
                proxyHostInput.setText(UpstreamProxyConfig.LOCALHOST)
                proxyPortInput.setText(UpstreamProxyConfig.DEFAULT_CLASH_PROXY_PORT.toString())
                statusText.text = "已填入 127.0.0.1:${UpstreamProxyConfig.DEFAULT_CLASH_PROXY_PORT}"
            }
        }

        val windowsClashButton = Button(this).apply {
            text = "使用 Windows Clash"
            setOnClickListener {
                upstreamProxyRadio.isChecked = true
                embeddedProxyCheck.isChecked = false
                proxyHostInput.setText("")
                proxyPortInput.setText(UpstreamProxyConfig.DEFAULT_CLASH_PROXY_PORT.toString())
                statusText.text = "请填写 Windows Clash 可访问的地址"
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
            text = "代理链模式会转发到 SOCKS5/Clash；当前写入 .pcap.pending.json 与转发统计。"
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
        root.addView(embeddedProxyCheck, fullWidthLayoutParams())
        root.addView(subscriptionUrlInput, fullWidthLayoutParams())
        root.addView(downloadSubscriptionButton, buttonLayoutParams())
        root.addView(embeddedMihomoButton, buttonLayoutParams())
        root.addView(importMihomoButton, buttonLayoutParams())
        root.addView(sectionLabel("节点选择"))
        root.addView(proxyGroupSpinner, fullWidthLayoutParams())
        root.addView(proxyNodeSpinner, fullWidthLayoutParams())
        root.addView(refreshNodesButton, buttonLayoutParams())
        root.addView(applyNodeButton, buttonLayoutParams())
        root.addView(localClashButton, buttonLayoutParams())
        root.addView(windowsClashButton, buttonLayoutParams())
        root.addView(startButton, buttonLayoutParams())
        root.addView(stopButton, buttonLayoutParams())
        root.addView(note)
        return root
    }

    private fun setSpinnerItems(spinner: Spinner, items: List<String>) {
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            items
        )
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
        val embedded = embeddedProxyCheck.isChecked
        val host = if (embedded) {
            UpstreamProxyConfig.LOCALHOST
        } else {
            proxyHostInput.text.toString().trim()
        }
        val port = proxyPortInput.text.toString().toIntOrNull() ?: 0
        val effectivePort = if (port > 0) port else UpstreamProxyConfig.DEFAULT_CLASH_PROXY_PORT

        if (mode == CaptureMode.UPSTREAM_PROXY && (host.isBlank() || effectivePort !in 1..65535)) {
            statusText.text = "请填写有效的代理地址和端口"
            return null
        }

        return CaptureConfig(
            mode = mode,
            proxy = UpstreamProxyConfig(
                enabled = mode == CaptureMode.UPSTREAM_PROXY,
                type = UpstreamProxyConfig.Type.SOCKS5,
                host = host,
                port = effectivePort,
                embedded = mode == CaptureMode.UPSTREAM_PROXY && embedded
            )
        )
    }

    private fun applyConfigToForm(config: CaptureConfig) {
        captureOnlyRadio.isChecked = config.mode == CaptureMode.CAPTURE_ONLY
        upstreamProxyRadio.isChecked = config.mode == CaptureMode.UPSTREAM_PROXY
        proxyHostInput.setText(config.proxy.host)
        proxyPortInput.setText(if (config.proxy.port > 0) config.proxy.port.toString() else "")
        embeddedProxyCheck.isChecked = config.proxy.embedded
    }

    private fun importMihomoConfig(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        val imported = contentResolver.openInputStream(uri)?.use { input ->
            MihomoCore.installConfig(this, input)
        }
        if (imported != null) {
            selectEmbeddedProxy("已导入内置 Mihomo 配置")
        } else {
            statusText.text = "配置导入失败"
        }
    }

    private fun downloadMihomoSubscription() {
        val url = subscriptionUrlInput.text.toString().trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            statusText.text = "请填写 http/https 订阅链接"
            return
        }

        preferences.saveSubscriptionUrl(url)
        statusText.text = "正在下载订阅配置"
        thread(name = "catch-report-subscription-download", isDaemon = true) {
            val result = runCatching {
                MihomoCore.downloadConfig(applicationContext, url)
            }
            runOnUiThread {
                result
                    .onSuccess { selectEmbeddedProxy("已下载订阅配置并启用内置 Mihomo") }
                    .onFailure { error ->
                        statusText.text = "订阅下载失败：${error.message ?: "未知错误"}"
                    }
            }
        }
    }

    private fun refreshMihomoNodes() {
        statusText.text = "正在读取 Mihomo 节点"
        thread(name = "catch-report-mihomo-refresh", isDaemon = true) {
            val result = runCatching { MihomoController.listGroups() }
            runOnUiThread {
                result
                    .onSuccess { groups ->
                        mihomoGroups = groups
                        if (groups.isEmpty()) {
                            setSpinnerItems(proxyGroupSpinner, listOf("没有可选择的代理组"))
                            setSpinnerItems(proxyNodeSpinner, listOf("没有可选择的节点"))
                            statusText.text = "没有读取到可选择的代理组"
                        } else {
                            val groupLabels = groups.map { group ->
                                if (group.selected.isBlank()) group.name else "${group.name} -> ${group.selected}"
                            }
                            setSpinnerItems(proxyGroupSpinner, groupLabels)
                            setSpinnerItems(proxyNodeSpinner, groups.first().nodes)
                            proxyGroupSpinner.setOnItemSelectedListener(SimpleItemSelectedListener {
                                val group = mihomoGroups.getOrNull(proxyGroupSpinner.selectedItemPosition)
                                setSpinnerItems(proxyNodeSpinner, group?.nodes.orEmpty())
                            })
                            statusText.text = "已读取 ${groups.size} 个代理组"
                        }
                    }
                    .onFailure { error ->
                        statusText.text = "读取节点失败：${error.message ?: "请先开始抓包"}"
                    }
            }
        }
    }

    private fun applyMihomoNode() {
        val group = mihomoGroups.getOrNull(proxyGroupSpinner.selectedItemPosition)
        val node = proxyNodeSpinner.selectedItem?.toString()
        if (group == null || node.isNullOrBlank()) {
            statusText.text = "请先刷新并选择代理组和节点"
            return
        }

        statusText.text = "正在应用节点"
        thread(name = "catch-report-mihomo-select", isDaemon = true) {
            val result = runCatching { MihomoController.selectNode(group.name, node) }
            runOnUiThread {
                result
                    .onSuccess {
                        statusText.text = "已切换：${group.name} -> $node"
                        refreshMihomoNodes()
                    }
                    .onFailure { error ->
                        statusText.text = "节点切换失败：${error.message ?: "未知错误"}"
                    }
            }
        }
    }

    private fun selectEmbeddedProxy(message: String) {
        upstreamProxyRadio.isChecked = true
        embeddedProxyCheck.isChecked = true
        proxyHostInput.setText(UpstreamProxyConfig.LOCALHOST)
        proxyPortInput.setText(UpstreamProxyConfig.DEFAULT_CLASH_PROXY_PORT.toString())
        statusText.text = message
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    companion object {
        private const val VPN_REQUEST_CODE = 42
        private const val CONFIG_IMPORT_REQUEST_CODE = 43
    }
}
