package com.zhouqishun.catchreport

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.graphics.Color
import android.text.InputType
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
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
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private enum class WizardFlow {
        DIRECT,
        PROXY
    }

    private enum class AppSection {
        NONE,
        CAPTURE,
        PROXY,
        RESULT
    }

    private data class WizardStep(
        val title: String,
        val body: String,
        val section: AppSection
    )

    private data class PreviewRender(
        val file: File?,
        val pretty: String,
        val raw: String,
        val rows: List<PcapPreviewRow>
    )

    private lateinit var statusText: TextView
    private lateinit var captureOnlyRadio: RadioButton
    private lateinit var upstreamProxyRadio: RadioButton
    private lateinit var proxyHostInput: EditText
    private lateinit var proxyPortInput: EditText
    private lateinit var embeddedProxyCheck: CheckBox
    private lateinit var subscriptionUrlInput: EditText
    private lateinit var proxyGroupSpinner: Spinner
    private lateinit var proxyNodeSpinner: Spinner
    private lateinit var captureFileSpinner: Spinner
    private lateinit var previewSearchInput: EditText
    private lateinit var livePreviewButton: Button
    private lateinit var packetListContainer: LinearLayout
    private lateinit var paginationText: TextView
    private lateinit var prettyPreviewText: TextView
    private lateinit var rawPreviewText: TextView
    private lateinit var captureSection: LinearLayout
    private lateinit var proxySection: LinearLayout
    private lateinit var resultSection: LinearLayout
    private lateinit var guideTitleText: TextView
    private lateinit var guideStepText: TextView
    private lateinit var guidePreviousButton: Button
    private lateinit var guideNextButton: Button
    private lateinit var preferences: CapturePreferences
    private var pendingConfig: CaptureConfig = CaptureConfig.default()
    private var mihomoGroups: List<MihomoProxyGroup> = emptyList()
    private var captureFiles: List<File> = emptyList()
    private var currentPreviewFile: File? = null
    private var currentPreviewRows: List<PcapPreviewRow> = emptyList()
    private var currentPreviewPage: Int = 0
    private var wizardFlow: WizardFlow? = null
    private var wizardStep: Int = 0
    private var captureStartedThisSession: Boolean = false
    private var captureStoppedThisSession: Boolean = false
    private var proxyNodeReadyThisSession: Boolean = false
    @Volatile private var livePreviewEnabled: Boolean = false

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

    override fun onDestroy() {
        stopLivePreview()
        super.onDestroy()
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

        val usageButton = Button(this).apply {
            text = "使用说明"
            setOnClickListener { showUsageDialog() }
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
                stopLivePreview()
                captureStoppedThisSession = true
                statusText.text = "已发送停止命令"
                refreshCaptureFilesSoon()
                updateWizard()
            }
        }

        val note = TextView(this).apply {
            text = "只记录 PCAP 会写完整 .pcap；代理链模式会转发到 SOCKS5/Clash，并写入转发元数据。"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 0)
        }

        captureFileSpinner = Spinner(this)
        setSpinnerItems(captureFileSpinner, listOf("暂无抓包结果"))

        previewSearchInput = EditText(this).apply {
            hint = "搜索协议、IP、端口、域名，例如 TCP、1.1.1.1、example.com"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }

        val refreshPreviewButton = Button(this).apply {
            text = "刷新抓包结果"
            setOnClickListener { refreshCaptureFiles(autoPreview = true) }
        }

        val openPreviewButton = Button(this).apply {
            text = "搜索 / 预览"
            setOnClickListener { previewSelectedCapture() }
        }

        livePreviewButton = Button(this).apply {
            text = "实时预览：关"
            setOnClickListener { toggleLivePreview() }
        }

        val previousPageButton = Button(this).apply {
            text = "上一页"
            setOnClickListener {
                if (currentPreviewPage > 0) {
                    currentPreviewPage -= 1
                    renderPacketPage()
                }
            }
        }

        val nextPageButton = Button(this).apply {
            text = "下一页"
            setOnClickListener {
                val maxPage = maxOf(0, (currentPreviewRows.size - 1) / PREVIEW_PAGE_SIZE)
                if (currentPreviewPage < maxPage) {
                    currentPreviewPage += 1
                    renderPacketPage()
                }
            }
        }

        paginationText = TextView(this).apply {
            text = "还没有分页结果"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
        }

        packetListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val deleteSelectedButton = Button(this).apply {
            text = "删除选中文件"
            setOnClickListener { confirmDeleteSelectedCapture() }
        }

        val clearAllButton = Button(this).apply {
            text = "一键清空抓包结果"
            setOnClickListener { confirmClearCaptures() }
        }

        prettyPreviewText = previewPanel(monospace = false).apply {
            text = "还没有预览。停止抓包后点“刷新抓包结果”。"
        }

        rawPreviewText = previewPanel(monospace = true).apply {
            text = "RAW 数据会在这里显示：HEX/ASCII、JSON 元数据等。"
        }

        captureSection = formSection().apply {
            addView(modeLabel)
            addView(modeGroup, fullWidthLayoutParams())
            addView(buttonRow(startButton, stopButton), fullWidthLayoutParams())
            addView(note)
        }

        proxySection = formSection().apply {
            addView(sectionLabel("上游代理"))
            addView(proxyHostInput, fullWidthLayoutParams())
            addView(proxyPortInput, fullWidthLayoutParams())
            addView(embeddedProxyCheck, fullWidthLayoutParams())
            addView(buttonRow(localClashButton, windowsClashButton), fullWidthLayoutParams())
            addView(sectionLabel("内置 Mihomo"))
            addView(subscriptionUrlInput, fullWidthLayoutParams())
            addView(buttonRow(downloadSubscriptionButton, embeddedMihomoButton), fullWidthLayoutParams())
            addView(importMihomoButton, buttonLayoutParams())
            addView(sectionLabel("节点选择"))
            addView(proxyGroupSpinner, fullWidthLayoutParams())
            addView(proxyNodeSpinner, fullWidthLayoutParams())
            addView(buttonRow(refreshNodesButton, applyNodeButton), fullWidthLayoutParams())
        }

        resultSection = formSection().apply {
            addView(sectionLabel("抓包结果"))
            addView(captureFileSpinner, fullWidthLayoutParams())
            addView(previewSearchInput, fullWidthLayoutParams())
            addView(buttonRow(refreshPreviewButton, openPreviewButton), fullWidthLayoutParams())
            addView(livePreviewButton, buttonLayoutParams())
            addView(paginationText, fullWidthLayoutParams())
            addView(packetListContainer, fullWidthLayoutParams())
            addView(buttonRow(previousPageButton, nextPageButton), fullWidthLayoutParams())
            addView(buttonRow(deleteSelectedButton, clearAllButton), fullWidthLayoutParams())
            addView(panelTitle("美化结果"))
            addView(prettyPreviewText, fullWidthLayoutParams())
            addView(panelTitle("RAW 数据"))
            addView(rawPreviewText, fullWidthLayoutParams())
        }

        val directFlowButton = Button(this).apply {
            text = "不挂代理抓包"
            setOnClickListener { selectWizardFlow(WizardFlow.DIRECT) }
        }
        val proxyFlowButton = Button(this).apply {
            text = "挂代理抓包"
            setOnClickListener { selectWizardFlow(WizardFlow.PROXY) }
        }

        guideTitleText = TextView(this).apply {
            text = "选择抓包流程"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 34, 44))
            setPadding(0, 14, 0, 8)
        }
        guideStepText = previewPanel(monospace = false).apply {
            text = "先选择一种流程，后面会一步一步完成。"
        }
        guidePreviousButton = Button(this).apply {
            text = "上一步"
            setOnClickListener { previousWizardStep() }
        }
        guideNextButton = Button(this).apply {
            text = "下一步"
            setOnClickListener { nextWizardStep() }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)
        root.addView(usageButton, buttonLayoutParams())
        root.addView(buttonRow(directFlowButton, proxyFlowButton), fullWidthLayoutParams())
        root.addView(guideTitleText, fullWidthLayoutParams())
        root.addView(guideStepText, fullWidthLayoutParams())
        root.addView(buttonRow(guidePreviousButton, guideNextButton), fullWidthLayoutParams())
        root.addView(captureSection, fullWidthLayoutParams())
        root.addView(proxySection, fullWidthLayoutParams())
        root.addView(resultSection, fullWidthLayoutParams())
        refreshCaptureFiles(autoPreview = false)
        updateWizard()
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

    private fun formSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = fullWidthLayoutParams()
        }
    }

    private fun buttonRow(vararg buttons: Button): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEachIndexed { index, button ->
                addView(
                    button,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        if (index > 0) leftMargin = 8
                        if (index < buttons.lastIndex) rightMargin = 8
                    }
                )
            }
        }
    }

    private fun sectionLabel(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 14f
            setPadding(0, 16, 0, 6)
        }
    }

    private fun panelTitle(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(35, 45, 55))
            setPadding(0, 18, 0, 8)
        }
    }

    private fun previewPanel(monospace: Boolean): TextView {
        return TextView(this).apply {
            textSize = if (monospace) 11f else 13f
            typeface = if (monospace) Typeface.MONOSPACE else Typeface.DEFAULT
            setTextColor(Color.rgb(30, 34, 38))
            setLineSpacing(4f, 1.0f)
            setPadding(22, 18, 22, 18)
            background = GradientDrawable().apply {
                setColor(if (monospace) Color.rgb(249, 250, 252) else Color.rgb(245, 249, 248))
                setStroke(1, if (monospace) Color.rgb(220, 226, 234) else Color.rgb(202, 222, 218))
                cornerRadius = 18f
            }
        }
    }

    private fun selectWizardFlow(flow: WizardFlow) {
        wizardFlow = flow
        wizardStep = 0
        captureStartedThisSession = false
        captureStoppedThisSession = false
        proxyNodeReadyThisSession = false
        if (flow == WizardFlow.DIRECT) {
            captureOnlyRadio.isChecked = true
            upstreamProxyRadio.isChecked = false
            embeddedProxyCheck.isChecked = false
            statusText.text = "已选择：不挂代理抓包"
        } else {
            captureOnlyRadio.isChecked = false
            upstreamProxyRadio.isChecked = true
            statusText.text = "已选择：挂代理抓包"
        }
        updateWizard()
    }

    private fun nextWizardStep() {
        val flow = wizardFlow
        if (flow == null) {
            statusText.text = "请先选择抓包流程"
            updateWizard()
            return
        }
        if (!canAdvanceWizard()) {
            statusText.text = wizardBlockReason()
            updateWizard()
            return
        }

        if (flow == WizardFlow.PROXY && wizardStep == 0) {
            val config = readConfigFromForm() ?: return
            pendingConfig = config
            preferences.save(config)
        }

        val steps = wizardSteps(flow)
        if (wizardStep < steps.lastIndex) {
            wizardStep += 1
            if (steps[wizardStep].section == AppSection.RESULT) {
                refreshCaptureFiles(autoPreview = true)
            }
        } else {
            statusText.text = "流程完成，可以继续在结果页搜索和展开详情"
        }
        updateWizard()
    }

    private fun previousWizardStep() {
        if (wizardStep > 0) {
            wizardStep -= 1
        }
        updateWizard()
    }

    private fun updateWizard() {
        if (!::guideTitleText.isInitialized) return
        val flow = wizardFlow
        if (flow == null) {
            guideTitleText.text = "选择抓包流程"
            guideStepText.text = "先选择“不挂代理抓包”或“挂代理抓包”。选择后只能按步骤往下走，未完成当前步骤时“下一步”会锁住。"
            guidePreviousButton.isEnabled = false
            guideNextButton.isEnabled = false
            guideNextButton.text = "下一步"
            showSection(AppSection.NONE)
            return
        }

        val steps = wizardSteps(flow)
        wizardStep = wizardStep.coerceIn(0, steps.lastIndex)
        val step = steps[wizardStep]
        guideTitleText.text = "第 ${wizardStep + 1}/${steps.size} 步 · ${step.title}"
        guideStepText.text = buildString {
            append(step.body)
            append("\n\n")
            append(if (canAdvanceWizard()) "当前步骤已满足，可以继续。" else wizardBlockReason())
        }
        guidePreviousButton.isEnabled = wizardStep > 0
        guideNextButton.isEnabled = canAdvanceWizard()
        guideNextButton.text = if (wizardStep == steps.lastIndex) "完成" else "下一步"
        showSection(step.section)
    }

    private fun wizardSteps(flow: WizardFlow): List<WizardStep> {
        return when (flow) {
            WizardFlow.DIRECT -> listOf(
                WizardStep(
                    "开始不挂代理抓包",
                    "确认模式是“只记录 PCAP”，点击“开始抓包”并同意 Android VPN 授权。没有启动成功前不能进入下一步。",
                    AppSection.CAPTURE
                ),
                WizardStep(
                    "产生测试流量",
                    "打开你要测试的软件或网页，让它产生请求；需要边看边搜时可以先停留，完成测试后回到这里点下一步。",
                    AppSection.CAPTURE
                ),
                WizardStep(
                    "停止抓包",
                    "点击“停止抓包”，等待结果文件落盘。没有停止前不能进入结果页。",
                    AppSection.CAPTURE
                ),
                WizardStep(
                    "查看和搜索结果",
                    "在结果页刷新文件，按域名、IP、协议或字段值搜索。点击“详情 #包号”可查看字段 / Value、RAW HEX。",
                    AppSection.RESULT
                )
            )
            WizardFlow.PROXY -> listOf(
                WizardStep(
                    "配置上游代理",
                    "进入代理页，选择内置 Mihomo、安卓本机 Clash 或 Windows Clash，并确认代理地址和端口。常见 mixed-port 是 7890。",
                    AppSection.PROXY
                ),
                WizardStep(
                    "启动 VPN 抓包",
                    "回到抓包页点击“开始抓包”并同意 Android VPN 授权。Catch Report 会作为唯一 VPN，再把流量转给代理。",
                    AppSection.CAPTURE
                ),
                WizardStep(
                    "选择或确认代理节点",
                    "如果使用内置 Mihomo，点击“刷新代理节点”并选择节点；外部 Clash 已经在客户端里选好节点时可直接继续。",
                    AppSection.PROXY
                ),
                WizardStep(
                    "产生代理流量",
                    "打开目标软件测试。此时链路是 App -> Catch Report VPN -> 上游 Clash/Mihomo -> 出口节点。",
                    AppSection.CAPTURE
                ),
                WizardStep(
                    "停止抓包",
                    "测试完成后点击“停止抓包”，等待元数据或 PCAP 文件落盘。没有停止前不能进入结果页。",
                    AppSection.CAPTURE
                ),
                WizardStep(
                    "查看和搜索结果",
                    "在结果页搜索域名、IP、协议或字段值。代理链模式当前记录转发元数据；完整 PCAP 仍以只记录 PCAP 模式为准。",
                    AppSection.RESULT
                )
            )
        }
    }

    private fun canAdvanceWizard(): Boolean {
        val flow = wizardFlow ?: return false
        return when (flow) {
            WizardFlow.DIRECT -> when (wizardStep) {
                0 -> captureStartedThisSession
                1 -> captureStartedThisSession
                2 -> captureStoppedThisSession
                3 -> captureFiles.isNotEmpty()
                else -> true
            }
            WizardFlow.PROXY -> when (wizardStep) {
                0 -> isProxyFormValid()
                1 -> captureStartedThisSession
                2 -> !embeddedProxyCheck.isChecked || proxyNodeReadyThisSession || mihomoGroups.isNotEmpty()
                3 -> captureStartedThisSession
                4 -> captureStoppedThisSession
                5 -> captureFiles.isNotEmpty()
                else -> true
            }
        }
    }

    private fun wizardBlockReason(): String {
        val flow = wizardFlow ?: return "请先选择抓包流程。"
        return when (flow) {
            WizardFlow.DIRECT -> when (wizardStep) {
                0 -> "请先点击“开始抓包”并完成 VPN 授权。"
                1 -> "请先保持抓包并完成一次目标软件测试，然后点下一步。"
                2 -> "请先点击“停止抓包”。"
                3 -> "请先刷新抓包结果，确保至少有一个结果文件。"
                else -> "当前步骤未完成。"
            }
            WizardFlow.PROXY -> when (wizardStep) {
                0 -> "请先选择“挂自己的代理再抓包”，并填写有效代理地址和 1-65535 端口。"
                1 -> "请先点击“开始抓包”并完成 VPN 授权。"
                2 -> if (embeddedProxyCheck.isChecked) "请先刷新内置 Mihomo 代理节点，或应用一个节点。" else "外部 Clash 已确认，可继续。"
                3 -> "请先保持代理抓包并完成目标软件测试，然后点下一步。"
                4 -> "请先点击“停止抓包”。"
                5 -> "请先刷新抓包结果，确保至少有一个结果文件。"
                else -> "当前步骤未完成。"
            }
        }
    }

    private fun isProxyFormValid(): Boolean {
        if (!upstreamProxyRadio.isChecked) return false
        val host = if (embeddedProxyCheck.isChecked) {
            UpstreamProxyConfig.LOCALHOST
        } else {
            proxyHostInput.text.toString().trim()
        }
        val port = proxyPortInput.text.toString().toIntOrNull() ?: 0
        val effectivePort = if (port > 0) port else UpstreamProxyConfig.DEFAULT_CLASH_PROXY_PORT
        return host.isNotBlank() && effectivePort in 1..65535
    }

    private fun showSection(section: AppSection) {
        if (!::captureSection.isInitialized) return
        captureSection.visibility = if (section == AppSection.CAPTURE) View.VISIBLE else View.GONE
        proxySection.visibility = if (section == AppSection.PROXY) View.VISIBLE else View.GONE
        resultSection.visibility = if (section == AppSection.RESULT) View.VISIBLE else View.GONE
    }

    private fun showPreviewResult(pretty: String, raw: String) {
        prettyPreviewText.text = pretty
        rawPreviewText.text = raw
    }

    private fun renderPacketPage() {
        packetListContainer.removeAllViews()
        if (currentPreviewRows.isEmpty()) {
            paginationText.text = "没有匹配的数据包"
            return
        }

        val maxPage = (currentPreviewRows.size - 1) / PREVIEW_PAGE_SIZE
        currentPreviewPage = currentPreviewPage.coerceIn(0, maxPage)
        val start = currentPreviewPage * PREVIEW_PAGE_SIZE
        val end = minOf(currentPreviewRows.size, start + PREVIEW_PAGE_SIZE)
        paginationText.text = "第 ${currentPreviewPage + 1}/${maxPage + 1} 页，显示 ${start + 1}-$end / ${currentPreviewRows.size}"

        currentPreviewRows.subList(start, end).forEach { row ->
            packetListContainer.addView(
                Button(this).apply {
                    text = packetButtonText(row)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setAllCaps(false)
                    setOnClickListener { previewPacketDetail(row.index) }
                },
                fullWidthLayoutParams()
            )
        }
    }

    private fun packetButtonText(row: PcapPreviewRow): String {
        val info = if (row.info.isBlank()) "" else "\n${row.info}"
        return "详情 #${row.index}  ${row.protocol}  ${row.source} -> ${row.destination}  ${row.length}B$info"
    }

    private fun toggleLivePreview() {
        if (livePreviewEnabled) {
            stopLivePreview()
        } else {
            startLivePreview()
        }
    }

    private fun startLivePreview() {
        if (livePreviewEnabled) return
        livePreviewEnabled = true
        livePreviewButton.text = "实时预览：开"
        statusText.text = "实时预览已开启"
        thread(name = "catch-report-live-preview", isDaemon = true) {
            while (livePreviewEnabled) {
                Thread.sleep(LIVE_PREVIEW_INTERVAL_MS)
                runOnUiThread {
                    if (livePreviewEnabled) {
                        refreshCaptureFiles(autoPreview = true)
                    }
                }
            }
        }
    }

    private fun stopLivePreview() {
        livePreviewEnabled = false
        if (::livePreviewButton.isInitialized) {
            livePreviewButton.text = "实时预览：关"
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
        captureStartedThisSession = true
        captureStoppedThisSession = false
        statusText.text = "抓包服务启动中"
        if (pendingConfig.mode == CaptureMode.CAPTURE_ONLY) {
            startLivePreview()
        } else {
            stopLivePreview()
            showPreviewResult(
                "代理链模式已启动。\n当前阶段会记录转发元数据；完整 PCAP 抓取仍以“只记录 PCAP”模式为准。",
                "代理链路会发往配置的 Clash/Mihomo 上游。"
            )
        }
        updateWizard()
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
                            proxyNodeReadyThisSession = false
                            statusText.text = "没有读取到可选择的代理组"
                        } else {
                            proxyNodeReadyThisSession = true
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
                        updateWizard()
                    }
                    .onFailure { error ->
                        proxyNodeReadyThisSession = false
                        statusText.text = "读取节点失败：${error.message ?: "请先开始抓包"}"
                        updateWizard()
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
                        proxyNodeReadyThisSession = true
                        statusText.text = "已切换：${group.name} -> $node"
                        refreshMihomoNodes()
                        updateWizard()
                    }
                    .onFailure { error ->
                        statusText.text = "节点切换失败：${error.message ?: "未知错误"}"
                        updateWizard()
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

    private fun refreshCaptureFilesSoon() {
        thread(name = "catch-report-refresh-captures", isDaemon = true) {
            Thread.sleep(1500)
            runOnUiThread { refreshCaptureFiles(autoPreview = true) }
        }
    }

    private fun refreshCaptureFiles(autoPreview: Boolean) {
        val dir = capturesDir()
        dir.mkdirs()
        captureFiles = dir.listFiles { file ->
            file.isFile && (file.name.endsWith(".pcap") || file.name.endsWith(".pcap.pending.json"))
        }?.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name }) ?: emptyList()

        if (captureFiles.isEmpty()) {
            setSpinnerItems(captureFileSpinner, listOf("暂无抓包结果"))
            showPreviewResult(
                pretty = "目录为空\n${dir.absolutePath}",
                raw = "暂无 RAW 数据"
            )
            currentPreviewRows = emptyList()
            renderPacketPage()
            updateWizard()
            return
        }

        setSpinnerItems(captureFileSpinner, captureFiles.map { file ->
            "${file.name} (${formatBytes(file.length())})"
        })
        if (autoPreview) {
            previewSelectedCapture()
        } else {
            showPreviewResult(
                pretty = "已找到 ${captureFiles.size} 个抓包结果\n选择一个文件后点“搜索 / 预览”，或打开实时预览边抓边看。",
                raw = "RAW 数据会在选择具体包后显示。"
            )
        }
        updateWizard()
    }

    private fun previewSelectedCapture() {
        val file = captureFiles.getOrNull(captureFileSpinner.selectedItemPosition)
        if (file == null) {
            showPreviewResult("没有可预览的抓包文件。", "暂无 RAW 数据")
            return
        }

        val query = previewSearchInput.text.toString().trim()
        showPreviewResult("正在读取 ${file.name}", "RAW 数据稍后显示。")
        thread(name = "catch-report-preview", isDaemon = true) {
            val result = runCatching {
                if (file.name.endsWith(".pcap")) {
                    renderPcapPreview(file, query)
                } else {
                    renderMetadataPreview(file, query)
                }
            }
            runOnUiThread {
                result
                    .onSuccess { preview ->
                        currentPreviewFile = preview.file
                        currentPreviewRows = preview.rows
                        currentPreviewPage = 0
                        showPreviewResult(preview.pretty, preview.raw)
                        renderPacketPage()
                    }
                    .onFailure { error ->
                        currentPreviewRows = emptyList()
                        renderPacketPage()
                        showPreviewResult(
                            pretty = "预览失败：${error.message ?: "未知错误"}",
                            raw = "读取失败，没有 RAW 数据。"
                        )
                    }
                }
        }
    }

    private fun renderPcapPreview(file: File, query: String): PreviewRender {
        val preview = PcapPreviewParser.parse(file, query)
        val lines = mutableListOf<String>()
        lines.add("文件：${file.name}")
        lines.add("格式：${preview.format} / ${pcapLinkTypeName(preview.linkType)}")
        lines.add("扫描包数：${preview.scannedPackets}")
        lines.add("匹配结果：${preview.shownPackets.size} 条${if (query.isBlank()) "" else "，搜索：$query"}")
        val sidecar = File(file.parentFile, "${file.name}.json")
        if (sidecar.exists()) {
            lines.add("元数据：${sidecar.name}")
        }
        lines.add("")
        if (preview.shownPackets.isEmpty()) {
            lines.add("没有匹配的数据包。")
        } else {
            lines.add("下面是分页后的匹配包。点“详情 #包号”即可展开。")
            lines.add("每页 ${PREVIEW_PAGE_SIZE} 条，可用上一页/下一页翻页。")
        }
        return PreviewRender(
            file = file,
            pretty = lines.joinToString("\n"),
            raw = "选择某个数据包后，这里显示 HEX/ASCII 原始数据。",
            rows = preview.shownPackets
        )
    }

    private fun renderMetadataPreview(file: File, query: String): PreviewRender {
        val lines = file.readLines().map { it.trim() }.filter { it.isNotBlank() }
        val filtered = if (query.isBlank()) {
            lines
        } else {
            lines.filter { it.lowercase(Locale.US).contains(query.lowercase(Locale.US)) }
        }
        val pretty = buildString {
            appendLine("文件：${file.name}")
            appendLine("类型：代理链路元数据")
            appendLine("说明：代理模式当前记录转发统计；完整 PCAP 需要后续 native packet tap。")
            if (filtered.isEmpty()) {
                appendLine("没有匹配的元数据。")
            } else {
                appendLine("匹配行：${filtered.size}")
                appendLine("RAW 面板显示 JSON 原文。")
            }
        }
        return PreviewRender(
            file = null,
            pretty = pretty,
            raw = filtered.take(160).joinToString("\n").ifBlank { "没有匹配的元数据行。" },
            rows = emptyList()
        )
    }

    private fun previewPacketDetail(packetIndex: Int) {
        val file = currentPreviewFile ?: captureFiles.getOrNull(captureFileSpinner.selectedItemPosition)
        if (file == null) {
            showPreviewResult("没有可预览的抓包文件。", "暂无 RAW 数据")
            return
        }
        if (!file.name.endsWith(".pcap")) {
            showPreviewResult("代理元数据不是完整 PCAP，不能查看单包详情。", "暂无 RAW 数据")
            return
        }

        showPreviewResult("正在读取 #$packetIndex", "RAW 数据稍后显示。")
        thread(name = "catch-report-packet-detail", isDaemon = true) {
            val result = runCatching { PcapPreviewParser.packetDetail(file, packetIndex) }
            runOnUiThread {
                result
                    .onSuccess { detail -> showPreviewResult(detail.pretty, detail.raw) }
                    .onFailure { error ->
                        showPreviewResult(
                            pretty = "详情读取失败：${error.message ?: "未知错误"}",
                            raw = "读取失败，没有 RAW 数据。"
                        )
                    }
            }
        }
    }

    private fun confirmDeleteSelectedCapture() {
        val file = captureFiles.getOrNull(captureFileSpinner.selectedItemPosition)
        if (file == null) {
            showPreviewResult("没有可删除的抓包文件。", "暂无 RAW 数据")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("删除抓包文件")
            .setMessage("确定删除 ${file.name} 吗？相关元数据也会一起删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                deleteCaptureFileSet(file)
                refreshCaptureFiles(autoPreview = false)
                currentPreviewRows = emptyList()
                renderPacketPage()
                showPreviewResult("已删除：${file.name}", "暂无 RAW 数据")
            }
            .show()
    }

    private fun confirmClearCaptures() {
        if (captureFiles.isEmpty()) {
            showPreviewResult("没有可清空的抓包文件。", "暂无 RAW 数据")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("一键清空抓包结果")
            .setMessage("确定清空 ${captureFiles.size} 个抓包结果吗？这个操作不可撤销。")
            .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ ->
                val deleted = captureFiles.sumOf { file ->
                    if (deleteCaptureFileSet(file)) 1 else 0
                }
                refreshCaptureFiles(autoPreview = false)
                currentPreviewRows = emptyList()
                renderPacketPage()
                showPreviewResult("已清空 $deleted 个抓包结果。", "暂无 RAW 数据")
            }
            .show()
    }

    private fun deleteCaptureFileSet(file: File): Boolean {
        var deleted = false
        if (file.exists()) {
            deleted = file.delete()
        }
        listOf(
            File(file.parentFile, "${file.name}.json"),
            if (file.name.endsWith(".pcap.json")) File(file.parentFile, file.name.removeSuffix(".json")) else null
        ).filterNotNull().forEach { sidecar ->
            if (sidecar.exists()) sidecar.delete()
        }
        return deleted
    }

    private fun showUsageDialog() {
        AlertDialog.Builder(this)
            .setTitle("Catch Report 使用说明")
            .setMessage(
                listOf(
                    "1. 先选择流程：“不挂代理抓包”用于纯 PCAP；“挂代理抓包”用于 Catch Report 作为唯一 VPN，再转发给 Clash/Mihomo。",
                    "2. 按向导下一步执行：未点击开始抓包、未停止抓包、代理未配置好时，“下一步”会自动锁住。",
                    "3. 不挂代理流程：开始抓包 -> 产生测试流量 -> 停止抓包 -> 结果页搜索和查看详情。",
                    "4. 挂代理流程：配置代理 -> 启动 VPN 抓包 -> 刷新/确认节点 -> 产生代理流量 -> 停止抓包 -> 查看结果。",
                    "5. 结果页：选择 .pcap 文件，输入 TCP、UDP、IP、端口、域名或字段值，再点“搜索 / 预览”。",
                    "6. 单包详情：直接点击列表里的“详情 #包号”，可以看字段 / Value、IP/TCP/UDP/DNS、HTTP 明文字段、TLS SNI/ALPN 和原始 HEX。",
                    "7. HTTPS 正文默认是加密的；没有安装 MITM 证书或解密密钥时，只能看到 SNI/ALPN、IP、端口、DNS 等可见字段。",
                    "8. 清理文件：可删除选中文件，也可以一键清空历史抓包结果；完整 PCAP 会同时删除对应 .json 元数据。"
                ).joinToString("\n\n")
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun capturesDir(): File {
        return File(
            getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "captures"
        )
    }

    private fun pcapLinkTypeName(linkType: Int): String {
        return when (linkType) {
            1 -> "Ethernet"
            101 -> "Raw IP"
            228 -> "IPv4"
            229 -> "IPv6"
            else -> "LINKTYPE $linkType"
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kib = bytes / 1024.0
        if (kib < 1024) return "%.1f KiB".format(Locale.US, kib)
        return "%.1f MiB".format(Locale.US, kib / 1024.0)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    companion object {
        private const val VPN_REQUEST_CODE = 42
        private const val CONFIG_IMPORT_REQUEST_CODE = 43
        private const val PREVIEW_PAGE_SIZE = 12
        private const val LIVE_PREVIEW_INTERVAL_MS = 2500L
    }
}
