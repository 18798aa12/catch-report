package com.zhouqishun.catchreport

import android.content.Intent

data class CaptureConfig(
    val mode: CaptureMode = CaptureMode.CAPTURE_ONLY,
    val proxy: UpstreamProxyConfig = UpstreamProxyConfig()
) {
    fun putInto(intent: Intent) {
        intent.putExtra(EXTRA_MODE, mode.name)
        intent.putExtra(EXTRA_PROXY_ENABLED, proxy.enabled)
        intent.putExtra(EXTRA_PROXY_TYPE, proxy.type.name)
        intent.putExtra(EXTRA_PROXY_HOST, proxy.host)
        intent.putExtra(EXTRA_PROXY_PORT, proxy.port)
        intent.putExtra(EXTRA_PROXY_EMBEDDED, proxy.embedded)
    }

    companion object {
        private const val EXTRA_MODE = "capture_mode"
        private const val EXTRA_PROXY_ENABLED = "proxy_enabled"
        private const val EXTRA_PROXY_TYPE = "proxy_type"
        private const val EXTRA_PROXY_HOST = "proxy_host"
        private const val EXTRA_PROXY_PORT = "proxy_port"
        private const val EXTRA_PROXY_EMBEDDED = "proxy_embedded"

        fun default(): CaptureConfig = CaptureConfig()

        fun fromIntent(intent: Intent?): CaptureConfig {
            if (intent == null) return default()
            val mode = parseMode(intent.getStringExtra(EXTRA_MODE))
            val proxy = UpstreamProxyConfig(
                enabled = intent.getBooleanExtra(EXTRA_PROXY_ENABLED, false),
                type = parseProxyType(intent.getStringExtra(EXTRA_PROXY_TYPE)),
                host = intent.getStringExtra(EXTRA_PROXY_HOST).orEmpty(),
                port = intent.getIntExtra(EXTRA_PROXY_PORT, 0),
                embedded = intent.getBooleanExtra(EXTRA_PROXY_EMBEDDED, false)
            )
            return CaptureConfig(mode = mode, proxy = proxy)
        }

        private fun parseMode(value: String?): CaptureMode {
            return CaptureMode.entries.firstOrNull { it.name == value } ?: CaptureMode.CAPTURE_ONLY
        }

        private fun parseProxyType(value: String?): UpstreamProxyConfig.Type {
            return UpstreamProxyConfig.Type.entries.firstOrNull { it.name == value }
                ?: UpstreamProxyConfig.Type.SOCKS5
        }
    }
}
