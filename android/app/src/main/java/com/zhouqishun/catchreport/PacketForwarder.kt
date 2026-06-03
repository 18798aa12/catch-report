package com.zhouqishun.catchreport

import android.net.VpnService
import java.io.Closeable

interface PacketForwarder : Closeable {
    fun handlePacket(packet: ByteArray, length: Int): ForwardingResult

    override fun close() {
    }
}

data class ForwardingResult(
    val status: Status,
    val message: String = ""
) {
    enum class Status {
        RECORDED_ONLY,
        FORWARDED,
        UNSUPPORTED
    }
}

class CaptureOnlyForwarder : PacketForwarder {
    override fun handlePacket(packet: ByteArray, length: Int): ForwardingResult {
        return ForwardingResult(ForwardingResult.Status.RECORDED_ONLY)
    }
}

class UpstreamProxyForwarder(
    @Suppress("unused") private val vpnService: VpnService,
    private val config: UpstreamProxyConfig
) : PacketForwarder {
    override fun handlePacket(packet: ByteArray, length: Int): ForwardingResult {
        val summary = PacketSummaryParser.parse(packet, length)
        return ForwardingResult(
            status = ForwardingResult.Status.UNSUPPORTED,
            message = "proxy=${config.type} ${config.host}:${config.port}, packet=${summary.protocol}"
        )
    }
}
