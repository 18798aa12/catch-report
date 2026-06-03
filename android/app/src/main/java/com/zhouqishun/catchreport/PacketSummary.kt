package com.zhouqishun.catchreport

data class PacketSummary(
    val protocol: String,
    val source: String,
    val destination: String,
    val length: Int
)

object PacketSummaryParser {
    fun parse(packet: ByteArray, length: Int): PacketSummary {
        if (length < 1) return PacketSummary("UNKNOWN", "-", "-", length)
        val version = packet[0].toInt().ushr(4) and 0x0f
        return when (version) {
            4 -> parseIpv4(packet, length)
            6 -> parseIpv6(packet, length)
            else -> PacketSummary("IP$version", "-", "-", length)
        }
    }

    private fun parseIpv4(packet: ByteArray, length: Int): PacketSummary {
        if (length < 20) return PacketSummary("IPv4", "-", "-", length)
        val ihl = (packet[0].toInt() and 0x0f) * 4
        val protocol = packet[9].toInt() and 0xff
        val source = ipv4(packet, 12)
        val destination = ipv4(packet, 16)
        return withPorts(packet, length, ihl, protocol, source, destination)
    }

    private fun parseIpv6(packet: ByteArray, length: Int): PacketSummary {
        if (length < 40) return PacketSummary("IPv6", "-", "-", length)
        val protocol = packet[6].toInt() and 0xff
        val source = ipv6(packet, 8)
        val destination = ipv6(packet, 24)
        return withPorts(packet, length, 40, protocol, source, destination)
    }

    private fun withPorts(
        packet: ByteArray,
        length: Int,
        offset: Int,
        protocol: Int,
        source: String,
        destination: String
    ): PacketSummary {
        val name = when (protocol) {
            6 -> "TCP"
            17 -> "UDP"
            1 -> "ICMP"
            58 -> "ICMPv6"
            else -> "IP/$protocol"
        }

        if ((protocol == 6 || protocol == 17) && length >= offset + 4) {
            val sport = u16(packet, offset)
            val dport = u16(packet, offset + 2)
            return PacketSummary(name, "$source:$sport", "$destination:$dport", length)
        }

        return PacketSummary(name, source, destination, length)
    }

    private fun ipv4(packet: ByteArray, offset: Int): String {
        return (0 until 4).joinToString(".") { (packet[offset + it].toInt() and 0xff).toString() }
    }

    private fun ipv6(packet: ByteArray, offset: Int): String {
        return (0 until 16 step 2).joinToString(":") {
            u16(packet, offset + it).toString(16)
        }
    }

    private fun u16(packet: ByteArray, offset: Int): Int {
        return ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)
    }
}
