package com.zhouqishun.catchreport

import android.content.Context

class CapturePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("capture", Context.MODE_PRIVATE)

    fun load(): CaptureConfig {
        val mode = CaptureMode.entries.firstOrNull {
            it.name == prefs.getString(KEY_MODE, CaptureMode.CAPTURE_ONLY.name)
        } ?: CaptureMode.CAPTURE_ONLY
        val proxyType = UpstreamProxyConfig.Type.entries.firstOrNull {
            it.name == prefs.getString(KEY_PROXY_TYPE, UpstreamProxyConfig.Type.SOCKS5.name)
        } ?: UpstreamProxyConfig.Type.SOCKS5

        return CaptureConfig(
            mode = mode,
            proxy = UpstreamProxyConfig(
                enabled = mode == CaptureMode.UPSTREAM_PROXY,
                type = proxyType,
                host = prefs.getString(KEY_PROXY_HOST, "").orEmpty(),
                port = prefs.getInt(KEY_PROXY_PORT, UpstreamProxyConfig.DEFAULT_CLASH_MIXED_PORT)
            )
        )
    }

    fun save(config: CaptureConfig) {
        prefs.edit()
            .putString(KEY_MODE, config.mode.name)
            .putString(KEY_PROXY_TYPE, config.proxy.type.name)
            .putString(KEY_PROXY_HOST, config.proxy.host)
            .putInt(KEY_PROXY_PORT, config.proxy.port)
            .apply()
    }

    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_PROXY_TYPE = "proxy_type"
        private const val KEY_PROXY_HOST = "proxy_host"
        private const val KEY_PROXY_PORT = "proxy_port"
    }
}
