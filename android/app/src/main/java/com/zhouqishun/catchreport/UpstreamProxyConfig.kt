package com.zhouqishun.catchreport

data class UpstreamProxyConfig(
    val enabled: Boolean = false,
    val type: Type = Type.SOCKS5,
    val host: String = "",
    val port: Int = DEFAULT_CLASH_PROXY_PORT,
    val embedded: Boolean = false
) {
    enum class Type {
        SOCKS5,
        HTTP_CONNECT
    }

    companion object {
        const val LOCALHOST = "127.0.0.1"
        const val DEFAULT_CLASH_PROXY_PORT = 7890
        const val DEFAULT_CLASH_SOCKS_PORT = 7891
    }
}
