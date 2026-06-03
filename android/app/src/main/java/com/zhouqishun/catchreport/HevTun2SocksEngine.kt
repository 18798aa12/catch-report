package com.zhouqishun.catchreport

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class HevTun2SocksEngine(
    private val vpnService: android.net.VpnService,
    private val cacheDir: File,
    private val config: UpstreamProxyConfig
) : Closeable {
    private val running = AtomicBoolean(false)
    private var configFile: File? = null

    fun start(tunFd: ParcelFileDescriptor) {
        if (!running.compareAndSet(false, true)) return
        val file = File(cacheDir, "hev-tun2socks.yml")
        val logFile = File(cacheDir, "hev-tun2socks.log")
        logFile.delete()
        file.writeText(buildConfig(config, logFile))
        configFile = file
        TProxyService.TProxyStartService(vpnService, file.absolutePath, tunFd.fd)
    }

    fun stats(): Tun2SocksStats {
        val raw = runCatching { TProxyService.TProxyGetStats() }.getOrNull()
        return Tun2SocksStats(
            txPackets = raw?.getOrNull(0) ?: 0,
            txBytes = raw?.getOrNull(1) ?: 0,
            rxPackets = raw?.getOrNull(2) ?: 0,
            rxBytes = raw?.getOrNull(3) ?: 0
        )
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        runCatching { TProxyService.TProxyStopService() }
        configFile = null
    }

    private fun buildConfig(proxy: UpstreamProxyConfig, logFile: File): String {
        val host = proxy.host.ifBlank { UpstreamProxyConfig.LOCALHOST }
        val port = if (proxy.port in 1..65535) proxy.port else UpstreamProxyConfig.DEFAULT_CLASH_PROXY_PORT
        return """
            misc:
              task-stack-size: 81920
              tcp-buffer-size: 4096
              connect-timeout: 5000
              tcp-read-write-timeout: 60000
              udp-read-write-timeout: 60000
              log-file: '${escapeYaml(logFile.absolutePath)}'
              log-level: info
            tunnel:
              mtu: 8500
            socks5:
              address: '${escapeYaml(host)}'
              port: $port
              udp: 'udp'
            mapdns:
              address: $MAPPED_DNS_ADDRESS
              port: 53
              network: 100.64.0.0
              netmask: 255.192.0.0
              cache-size: 10000
        """.trimIndent() + "\n"
    }

    private fun escapeYaml(value: String): String {
        return value.replace("'", "''")
    }

    companion object {
        const val MAPPED_DNS_ADDRESS = "198.18.0.2"
    }
}

data class Tun2SocksStats(
    val txPackets: Long,
    val txBytes: Long,
    val rxPackets: Long,
    val rxBytes: Long
)
