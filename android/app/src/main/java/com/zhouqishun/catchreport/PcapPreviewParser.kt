package com.zhouqishun.catchreport

import java.net.URLDecoder
import java.io.File
import java.io.RandomAccessFile
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class PcapPreview(
    val format: String,
    val linkType: Int,
    val scannedPackets: Int,
    val shownPackets: List<PcapPreviewRow>
)

data class PcapPreviewRow(
    val index: Int,
    val timestampMillis: Long,
    val protocol: String,
    val source: String,
    val destination: String,
    val length: Int,
    val info: String
) {
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val haystack = listOf(
            index.toString(),
            protocol,
            source,
            destination,
            length.toString(),
            info
        ).joinToString(" ").lowercase(Locale.US)
        return haystack.contains(query.lowercase(Locale.US))
    }

    fun toDisplayLine(): String {
        val time = String.format(Locale.US, "%tT", Date(timestampMillis))
        val base = "#%-5d %-8s %-6s %-24s -> %-24s %6dB".format(
            Locale.US,
            index,
            time,
            protocol,
            source,
            destination,
            length
        )
        return if (info.isBlank()) base else "$base  $info"
    }
}

data class PcapPacketDetail(
    val pretty: String,
    val raw: String
)

object PcapPreviewParser {
    private const val MAX_SCAN_PACKETS = 20000
    private const val MAX_SHOWN_PACKETS = 2000
    private const val DETAIL_HEX_BYTES = 512
    private const val FIELD_PREVIEW_BYTES = 8192
    private const val FIELD_VALUE_LIMIT = 360

    fun parse(file: File, query: String): PcapPreview {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 24) {
                throw IllegalArgumentException("PCAP 文件太小或尚未写完")
            }

            val header = ByteArray(24)
            input.readFully(header)
            val magicLe = u32(header, 0, little = true)
            val magicBe = u32(header, 0, little = false)
            val little = magicLe == 0xa1b2c3d4L || magicLe == 0xa1b23c4dL
            val nanos = magicLe == 0xa1b23c4dL || magicBe == 0xa1b23c4dL
            if (!little && magicBe != 0xa1b2c3d4L && magicBe != 0xa1b23c4dL) {
                throw IllegalArgumentException("未识别的 PCAP 魔数")
            }

            val linkType = u32(header, 20, little).toInt()
            val rows = mutableListOf<PcapPreviewRow>()
            val packetHeader = ByteArray(16)
            var index = 0

            while (input.filePointer + 16 <= input.length() && index < MAX_SCAN_PACKETS) {
                input.readFully(packetHeader)
                val seconds = u32(packetHeader, 0, little)
                val fraction = u32(packetHeader, 4, little)
                val capturedLength = u32(packetHeader, 8, little).toInt()
                val originalLength = u32(packetHeader, 12, little).toInt()
                if (capturedLength <= 0 || capturedLength.toLong() > input.length() - input.filePointer) {
                    break
                }

                val packet = ByteArray(capturedLength)
                input.readFully(packet)
                index += 1

                val payload = ipPayload(packet, linkType) ?: packet
                val summary = PacketSummaryParser.parse(payload, payload.size)
                val row = PcapPreviewRow(
                    index = index,
                    timestampMillis = seconds * 1000L + fraction / if (nanos) 1_000_000L else 1_000L,
                    protocol = summary.protocol,
                    source = summary.source,
                    destination = summary.destination,
                    length = if (originalLength > 0) originalLength else capturedLength,
                    info = summaryInfo(payload)
                )
                if (row.matches(query) && rows.size < MAX_SHOWN_PACKETS) {
                    rows.add(row)
                }
            }

            return PcapPreview(
                format = "PCAP",
                linkType = linkType,
                scannedPackets = index,
                shownPackets = rows
            )
        }
    }

    fun packetDetail(file: File, packetIndex: Int): PcapPacketDetail {
        require(packetIndex > 0) { "包号必须大于 0" }
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 24) {
                throw IllegalArgumentException("PCAP 文件太小或尚未写完")
            }

            val header = ByteArray(24)
            input.readFully(header)
            val magicLe = u32(header, 0, little = true)
            val magicBe = u32(header, 0, little = false)
            val little = magicLe == 0xa1b2c3d4L || magicLe == 0xa1b23c4dL
            val nanos = magicLe == 0xa1b23c4dL || magicBe == 0xa1b23c4dL
            if (!little && magicBe != 0xa1b2c3d4L && magicBe != 0xa1b23c4dL) {
                throw IllegalArgumentException("未识别的 PCAP 魔数")
            }

            val linkType = u32(header, 20, little).toInt()
            val packetHeader = ByteArray(16)
            var index = 0

            while (input.filePointer + 16 <= input.length() && index < MAX_SCAN_PACKETS) {
                input.readFully(packetHeader)
                val seconds = u32(packetHeader, 0, little)
                val fraction = u32(packetHeader, 4, little)
                val capturedLength = u32(packetHeader, 8, little).toInt()
                val originalLength = u32(packetHeader, 12, little).toInt()
                if (capturedLength <= 0 || capturedLength.toLong() > input.length() - input.filePointer) {
                    break
                }

                val packet = ByteArray(capturedLength)
                input.readFully(packet)
                index += 1

                if (index == packetIndex) {
                    val timestampMillis = seconds * 1000L + fraction / if (nanos) 1_000_000L else 1_000L
                    val pretty = buildString {
                        appendLine("包 #$index")
                        appendLine("时间：${String.format(Locale.US, "%tF %<tT.%<tL", Date(timestampMillis))}")
                        appendLine("文件：${file.name}")
                        appendLine("格式：PCAP / ${linkTypeName(linkType)}")
                        appendLine("长度：captured=$capturedLength B, original=$originalLength B")
                        appendLine("")
                        decodePacketDetails(packet, linkType).forEach { appendLine(it) }
                    }
                    val raw = buildString {
                        appendLine("HEX / ASCII（前 $DETAIL_HEX_BYTES 字节）")
                        appendLine(hexDump(packet.copyOfRange(0, minOf(packet.size, DETAIL_HEX_BYTES))))
                    }
                    return PcapPacketDetail(pretty = pretty, raw = raw)
                }
            }
        }

        throw IllegalArgumentException("没有找到 #$packetIndex；可能包号超过文件包数")
    }

    private fun ipPayload(packet: ByteArray, linkType: Int): ByteArray? {
        if (packet.isEmpty()) return null
        return when (linkType) {
            1 -> ethernetPayload(packet)
            101, 228, 229 -> packet
            else -> packet
        }
    }

    private fun ethernetPayload(packet: ByteArray): ByteArray? {
        if (packet.size < 14) return null
        var typeOffset = 12
        var etherType = u16(packet, typeOffset)
        if (etherType == 0x8100 && packet.size >= 18) {
            typeOffset = 16
            etherType = u16(packet, typeOffset)
        }
        return if (etherType == 0x0800 || etherType == 0x86dd) {
            packet.copyOfRange(typeOffset + 2, packet.size)
        } else {
            null
        }
    }

    private fun summaryInfo(packet: ByteArray): String {
        if (packet.isEmpty()) return ""
        return when ((packet[0].toInt() ushr 4) and 0x0f) {
            4 -> {
                if (packet.size < 28) return ""
                val ihl = (packet[0].toInt() and 0x0f) * 4
                val protocol = packet[9].toInt() and 0xff
                when {
                    protocol == 6 && packet.size >= ihl + 20 -> tcpApplicationSummary(packet, ihl, packet.size)
                    protocol == 17 && packet.size >= ihl + 8 -> {
                        val sport = u16(packet, ihl)
                        val dport = u16(packet, ihl + 2)
                        if (sport == 53 || dport == 53) dnsSummary(packet, ihl + 8) else ""
                    }
                    else -> ""
                }
            }
            6 -> {
                if (packet.size < 48) return ""
                val protocol = packet[6].toInt() and 0xff
                val offset = 40
                when {
                    protocol == 6 && packet.size >= offset + 20 -> tcpApplicationSummary(packet, offset, packet.size)
                    protocol == 17 && packet.size >= offset + 8 -> {
                        val sport = u16(packet, offset)
                        val dport = u16(packet, offset + 2)
                        if (sport == 53 || dport == 53) dnsSummary(packet, offset + 8) else ""
                    }
                    else -> ""
                }
            }
            else -> ""
        }
    }

    private fun decodePacketDetails(packet: ByteArray, linkType: Int): List<String> {
        val lines = mutableListOf<String>()
        val ipPacket = when (linkType) {
            1 -> {
                if (packet.size < 14) {
                    lines.add("二层：Ethernet 帧太短")
                    null
                } else {
                    val dst = mac(packet, 0)
                    val src = mac(packet, 6)
                    var typeOffset = 12
                    var etherType = u16(packet, typeOffset)
                    if (etherType == 0x8100 && packet.size >= 18) {
                        val vlan = u16(packet, 14)
                        lines.add("二层：Ethernet VLAN=${vlan and 0x0fff}")
                        typeOffset = 16
                        etherType = u16(packet, typeOffset)
                    } else {
                        lines.add("二层：Ethernet")
                    }
                    lines.add("MAC：$src -> $dst")
                    lines.add("EtherType：0x${etherType.toString(16).padStart(4, '0')}")
                    if (etherType == 0x0800 || etherType == 0x86dd) {
                        packet.copyOfRange(typeOffset + 2, packet.size)
                    } else {
                        null
                    }
                }
            }
            101, 228, 229 -> {
                lines.add("链路层：${linkTypeName(linkType)}")
                packet
            }
            else -> {
                lines.add("链路层：${linkTypeName(linkType)}，暂按 IP 尝试解析")
                packet
            }
        }

        if (ipPacket == null || ipPacket.isEmpty()) {
            lines.add("网络层：没有可解析的 IP payload")
            return lines
        }

        when ((ipPacket[0].toInt() ushr 4) and 0x0f) {
            4 -> decodeIpv4Details(ipPacket, lines)
            6 -> decodeIpv6Details(ipPacket, lines)
            else -> lines.add("网络层：未知 IP version ${(ipPacket[0].toInt() ushr 4) and 0x0f}")
        }
        return lines
    }

    private fun decodeIpv4Details(packet: ByteArray, lines: MutableList<String>) {
        if (packet.size < 20) {
            lines.add("IPv4：包太短")
            return
        }
        val ihl = (packet[0].toInt() and 0x0f) * 4
        val totalLength = u16(packet, 2)
        val identification = u16(packet, 4)
        val flagsFragment = u16(packet, 6)
        val ttl = packet[8].toInt() and 0xff
        val protocol = packet[9].toInt() and 0xff
        val src = ipv4(packet, 12)
        val dst = ipv4(packet, 16)
        val effectiveLength = minOf(packet.size, if (totalLength > 0) totalLength else packet.size)
        lines.add("IPv4：$src -> $dst")
        lines.add("IPv4 字段：ihl=$ihl, totalLength=$totalLength, id=$identification, ttl=$ttl, protocol=${protocolName(protocol)}")
        lines.add("分片：flags=${ipv4Flags(flagsFragment)}, fragmentOffset=${flagsFragment and 0x1fff}")
        appendFields(lines, "IPv4 字段 / Value", listOf(
            "ip.version" to "4",
            "ip.source" to src,
            "ip.destination" to dst,
            "ip.header_length" to ihl.toString(),
            "ip.total_length" to totalLength.toString(),
            "ip.identification" to identification.toString(),
            "ip.ttl" to ttl.toString(),
            "ip.protocol" to protocolName(protocol),
            "ip.flags" to ipv4Flags(flagsFragment),
            "ip.fragment_offset" to (flagsFragment and 0x1fff).toString()
        ))
        decodeTransportDetails(packet, ihl, effectiveLength, protocol, lines)
    }

    private fun decodeIpv6Details(packet: ByteArray, lines: MutableList<String>) {
        if (packet.size < 40) {
            lines.add("IPv6：包太短")
            return
        }
        val payloadLength = u16(packet, 4)
        val nextHeader = packet[6].toInt() and 0xff
        val hopLimit = packet[7].toInt() and 0xff
        val src = ipv6(packet, 8)
        val dst = ipv6(packet, 24)
        val effectiveLength = minOf(packet.size, 40 + payloadLength)
        lines.add("IPv6：$src -> $dst")
        lines.add("IPv6 字段：payloadLength=$payloadLength, nextHeader=${protocolName(nextHeader)}, hopLimit=$hopLimit")
        appendFields(lines, "IPv6 字段 / Value", listOf(
            "ip.version" to "6",
            "ip.source" to src,
            "ip.destination" to dst,
            "ip.payload_length" to payloadLength.toString(),
            "ip.next_header" to protocolName(nextHeader),
            "ip.hop_limit" to hopLimit.toString()
        ))
        decodeTransportDetails(packet, 40, effectiveLength, nextHeader, lines)
    }

    private fun decodeTransportDetails(
        packet: ByteArray,
        offset: Int,
        packetLength: Int,
        protocol: Int,
        lines: MutableList<String>
    ) {
        when (protocol) {
            6 -> decodeTcpDetails(packet, offset, packetLength, lines)
            17 -> decodeUdpDetails(packet, offset, packetLength, lines)
            1, 58 -> decodeIcmpDetails(packet, offset, protocol, lines)
            else -> lines.add("传输层：${protocolName(protocol)} 暂未展开")
        }
    }

    private fun decodeTcpDetails(packet: ByteArray, offset: Int, packetLength: Int, lines: MutableList<String>) {
        if (packetLength < offset + 20 || packet.size < offset + 20) {
            lines.add("TCP：头部太短")
            return
        }
        val sourcePort = u16(packet, offset)
        val destinationPort = u16(packet, offset + 2)
        val sequence = u32(packet, offset + 4, little = false)
        val acknowledgment = u32(packet, offset + 8, little = false)
        val dataOffset = ((packet[offset + 12].toInt() ushr 4) and 0x0f) * 4
        val flags = packet[offset + 13].toInt() and 0xff
        val window = u16(packet, offset + 14)
        val payloadStart = offset + dataOffset
        val payloadLength = maxOf(0, packetLength - payloadStart)
        lines.add("TCP：$sourcePort -> $destinationPort")
        lines.add("TCP 字段：seq=$sequence, ack=$acknowledgment, headerLength=$dataOffset, flags=${tcpFlags(flags)}, window=$window")
        lines.add("TCP Payload：$payloadLength B")
        appendFields(lines, "TCP 字段 / Value", listOf(
            "tcp.source_port" to sourcePort.toString(),
            "tcp.destination_port" to destinationPort.toString(),
            "tcp.sequence" to sequence.toString(),
            "tcp.acknowledgment" to acknowledgment.toString(),
            "tcp.header_length" to dataOffset.toString(),
            "tcp.flags" to tcpFlags(flags),
            "tcp.window" to window.toString(),
            "tcp.payload_length" to payloadLength.toString()
        ))
        appendApplicationFields(packet, payloadStart, payloadLength, sourcePort, destinationPort, lines)
        appendPayloadText(packet, payloadStart, payloadLength, lines)
    }

    private fun decodeUdpDetails(packet: ByteArray, offset: Int, packetLength: Int, lines: MutableList<String>) {
        if (packetLength < offset + 8 || packet.size < offset + 8) {
            lines.add("UDP：头部太短")
            return
        }
        val sourcePort = u16(packet, offset)
        val destinationPort = u16(packet, offset + 2)
        val udpLength = u16(packet, offset + 4)
        val payloadStart = offset + 8
        val payloadLength = maxOf(0, minOf(packetLength - payloadStart, udpLength - 8))
        lines.add("UDP：$sourcePort -> $destinationPort")
        lines.add("UDP 字段：length=$udpLength, checksum=0x${u16(packet, offset + 6).toString(16).padStart(4, '0')}")
        appendFields(lines, "UDP 字段 / Value", listOf(
            "udp.source_port" to sourcePort.toString(),
            "udp.destination_port" to destinationPort.toString(),
            "udp.length" to udpLength.toString(),
            "udp.checksum" to "0x${u16(packet, offset + 6).toString(16).padStart(4, '0')}",
            "udp.payload_length" to payloadLength.toString()
        ))
        if (sourcePort == 53 || destinationPort == 53) {
            lines.add("DNS：${dnsSummary(packet, payloadStart)}")
            appendDnsFields(packet, payloadStart, lines)
        }
        lines.add("UDP Payload：$payloadLength B")
        appendPayloadText(packet, payloadStart, payloadLength, lines)
    }

    private fun decodeIcmpDetails(packet: ByteArray, offset: Int, protocol: Int, lines: MutableList<String>) {
        if (packet.size < offset + 4) {
            lines.add("${protocolName(protocol)}：头部太短")
            return
        }
        val type = packet[offset].toInt() and 0xff
        val code = packet[offset + 1].toInt() and 0xff
        lines.add("${protocolName(protocol)}：type=$type, code=$code, checksum=0x${u16(packet, offset + 2).toString(16).padStart(4, '0')}")
    }

    private fun appendPayloadText(packet: ByteArray, offset: Int, length: Int, lines: MutableList<String>) {
        if (length <= 0 || offset >= packet.size) return
        val preview = packet.copyOfRange(offset, minOf(packet.size, offset + minOf(length, 160)))
        val text = ascii(preview).replace(Regex("[^\\x20-\\x7e]"), ".")
        if (text.isNotBlank()) {
            lines.add("Payload ASCII 预览：$text")
        }
    }

    private fun appendApplicationFields(
        packet: ByteArray,
        offset: Int,
        length: Int,
        sourcePort: Int,
        destinationPort: Int,
        lines: MutableList<String>
    ) {
        if (length <= 0 || offset >= packet.size) return
        val payload = packet.copyOfRange(offset, minOf(packet.size, offset + length))
        val httpFields = parseHttpFields(payload)
        if (httpFields.isNotEmpty()) {
            appendFields(lines, "HTTP 发送字段 / Value", httpFields)
            return
        }

        val tlsFields = parseTlsFields(payload)
        if (tlsFields.isNotEmpty()) {
            appendFields(lines, "TLS 可见字段 / Value", tlsFields)
            return
        }

        if (sourcePort == 443 || destinationPort == 443) {
            appendFields(lines, "HTTPS 字段 / Value", listOf(
                "https.body" to "已加密，未安装 MITM 证书时无法从 PCAP 直接看到 HTTP header/body 字段值"
            ))
        }
    }

    private fun tcpApplicationSummary(packet: ByteArray, offset: Int, packetLength: Int): String {
        if (packetLength < offset + 20 || packet.size < offset + 20) return ""
        val dataOffset = ((packet[offset + 12].toInt() ushr 4) and 0x0f) * 4
        val payloadStart = offset + dataOffset
        if (payloadStart >= packetLength || payloadStart >= packet.size) return ""
        val payload = packet.copyOfRange(payloadStart, minOf(packet.size, packetLength))
        httpSummary(payload)?.let { return it }
        tlsSummary(payload)?.let { return it }
        return ""
    }

    private fun httpSummary(payload: ByteArray): String? {
        val fields = parseHttpFields(payload)
        if (fields.isEmpty()) return null
        val map = fields.toMap()
        val method = map["http.method"]
        val target = map["http.target"]
        val host = map["header.Host"] ?: map["header.host"]
        val status = map["http.status_code"]
        return when {
            method != null -> "HTTP $method ${listOfNotNull(host, target).joinToString("")}"
            status != null -> "HTTP $status ${map["http.reason"].orEmpty()}".trim()
            else -> "HTTP"
        }
    }

    private fun tlsSummary(payload: ByteArray): String? {
        val fields = parseTlsFields(payload)
        if (fields.isEmpty()) return null
        val map = fields.toMap()
        return when {
            map["tls.sni"] != null -> "TLS SNI ${map["tls.sni"]}"
            map["tls.record_type"] != null -> "TLS ${map["tls.record_type"]}"
            else -> "TLS"
        }
    }

    private fun parseHttpFields(payload: ByteArray): List<Pair<String, String>> {
        if (payload.isEmpty()) return emptyList()
        val text = ascii(payload.copyOfRange(0, minOf(payload.size, FIELD_PREVIEW_BYTES)))
        val normalized = text.replace("\r\n", "\n")
        if (!looksLikeHttp(normalized)) return emptyList()

        val splitIndex = normalized.indexOf("\n\n")
        val headerText = if (splitIndex >= 0) normalized.substring(0, splitIndex) else normalized
        val bodyText = if (splitIndex >= 0) normalized.substring(splitIndex + 2) else ""
        val headerLines = headerText.split("\n").filter { it.isNotBlank() }
        if (headerLines.isEmpty()) return emptyList()

        val fields = mutableListOf<Pair<String, String>>()
        val first = headerLines.first()
        val headers = linkedMapOf<String, String>()
        if (first.startsWith("HTTP/", ignoreCase = true)) {
            val parts = first.split(" ", limit = 3)
            fields.add("http.version" to parts.getOrElse(0) { "" })
            fields.add("http.status_code" to parts.getOrElse(1) { "" })
            if (parts.size >= 3) fields.add("http.reason" to parts[2])
        } else {
            val parts = first.split(" ", limit = 3)
            fields.add("http.method" to parts.getOrElse(0) { "" })
            val target = parts.getOrElse(1) { "" }
            fields.add("http.target" to target)
            fields.add("http.version" to parts.getOrElse(2) { "" })
            val queryIndex = target.indexOf('?')
            if (queryIndex >= 0 && queryIndex + 1 < target.length) {
                fields.addAll(parseFormEncoded(target.substring(queryIndex + 1), "query"))
            }
        }

        headerLines.drop(1).forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) {
                val name = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                headers[name] = value
                fields.add("header.$name" to value)
            }
        }

        if (bodyText.isNotBlank()) {
            val contentType = headers.entries.firstOrNull {
                it.key.equals("content-type", ignoreCase = true)
            }?.value.orEmpty()
            when {
                contentType.contains("application/x-www-form-urlencoded", ignoreCase = true) -> {
                    fields.addAll(parseFormEncoded(bodyText.trim(), "form"))
                }
                contentType.contains("json", ignoreCase = true) || bodyText.trimStart().startsWith("{") -> {
                    fields.addAll(parseJsonFields(bodyText.trim()))
                }
                else -> fields.add("body.preview" to bodyText.take(FIELD_VALUE_LIMIT))
            }
        }

        return fields.map { it.first to compactValue(it.second) }
    }

    private fun looksLikeHttp(text: String): Boolean {
        val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        return methods.any { text.startsWith("$it ") } || text.startsWith("HTTP/")
    }

    private fun parseFormEncoded(value: String, prefix: String): List<Pair<String, String>> {
        return value.split("&")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(80)
            .map { part ->
                val equals = part.indexOf('=')
                val key = if (equals >= 0) part.substring(0, equals) else part
                val fieldValue = if (equals >= 0) part.substring(equals + 1) else ""
                "$prefix.${decodeUrl(key)}" to decodeUrl(fieldValue)
            }
            .toList()
    }

    private fun parseJsonFields(body: String): List<Pair<String, String>> {
        return runCatching {
            when (val parsed = JSONTokener(body).nextValue()) {
                is JSONObject -> parsed.keys().asSequence().take(80).map { key ->
                    "json.$key" to jsonValue(parsed.opt(key))
                }.toList()
                is JSONArray -> listOf("json[]" to "array length=${parsed.length()}")
                else -> emptyList()
            }
        }.getOrElse {
            listOf("body.json" to body.take(FIELD_VALUE_LIMIT))
        }
    }

    private fun jsonValue(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.toString()
            is JSONArray -> value.toString()
            else -> value.toString()
        }
    }

    private fun parseTlsFields(payload: ByteArray): List<Pair<String, String>> {
        if (payload.size < 5) return emptyList()
        val contentType = payload[0].toInt() and 0xff
        val major = payload[1].toInt() and 0xff
        val minor = payload[2].toInt() and 0xff
        if (major != 3 || contentType !in listOf(20, 21, 22, 23)) return emptyList()

        val fields = mutableListOf<Pair<String, String>>()
        fields.add("tls.record_type" to tlsRecordType(contentType))
        fields.add("tls.record_version" to "0x${major.toString(16)} 0x${minor.toString(16)}")
        fields.add("tls.record_length" to u16(payload, 3).toString())
        if (contentType == 23) {
            fields.add("tls.application_data" to "encrypted")
            return fields
        }
        if (contentType != 22 || payload.size < 9) return fields

        val handshakeType = payload[5].toInt() and 0xff
        fields.add("tls.handshake_type" to tlsHandshakeType(handshakeType))
        if (handshakeType != 1) return fields

        var cursor = 9
        if (payload.size < cursor + 34) return fields
        fields.add("tls.client_version" to "0x${(payload[cursor].toInt() and 0xff).toString(16)} 0x${(payload[cursor + 1].toInt() and 0xff).toString(16)}")
        cursor += 34
        if (cursor >= payload.size) return fields
        val sessionIdLength = payload[cursor++].toInt() and 0xff
        cursor += sessionIdLength
        if (cursor + 2 > payload.size) return fields
        val cipherSuitesLength = u16(payload, cursor)
        cursor += 2 + cipherSuitesLength
        if (cursor >= payload.size) return fields
        val compressionLength = payload[cursor++].toInt() and 0xff
        cursor += compressionLength
        if (cursor + 2 > payload.size) return fields
        val extensionsEnd = minOf(payload.size, cursor + 2 + u16(payload, cursor))
        cursor += 2

        while (cursor + 4 <= extensionsEnd) {
            val type = u16(payload, cursor)
            val length = u16(payload, cursor + 2)
            val start = cursor + 4
            val end = start + length
            if (end > extensionsEnd) break
            when (type) {
                0 -> parseTlsSni(payload, start, end)?.let { fields.add("tls.sni" to it) }
                16 -> parseTlsAlpn(payload, start, end).takeIf { it.isNotEmpty() }?.let {
                    fields.add("tls.alpn" to it.joinToString(", "))
                }
                43 -> parseTlsSupportedVersions(payload, start, end).takeIf { it.isNotBlank() }?.let {
                    fields.add("tls.supported_versions" to it)
                }
            }
            cursor = end
        }
        return fields
    }

    private fun parseTlsSni(payload: ByteArray, start: Int, end: Int): String? {
        if (start + 5 > end) return null
        var cursor = start + 2
        while (cursor + 3 <= end) {
            val nameType = payload[cursor++].toInt() and 0xff
            val nameLength = u16(payload, cursor)
            cursor += 2
            if (cursor + nameLength > end) return null
            val name = ascii(payload.copyOfRange(cursor, cursor + nameLength))
            if (nameType == 0) return name
            cursor += nameLength
        }
        return null
    }

    private fun parseTlsAlpn(payload: ByteArray, start: Int, end: Int): List<String> {
        if (start + 2 > end) return emptyList()
        var cursor = start + 2
        val protocols = mutableListOf<String>()
        while (cursor < end) {
            val length = payload[cursor++].toInt() and 0xff
            if (cursor + length > end) break
            protocols.add(ascii(payload.copyOfRange(cursor, cursor + length)))
            cursor += length
        }
        return protocols
    }

    private fun parseTlsSupportedVersions(payload: ByteArray, start: Int, end: Int): String {
        if (start >= end) return ""
        var cursor = start + 1
        val versions = mutableListOf<String>()
        while (cursor + 1 < end) {
            versions.add("0x${(payload[cursor].toInt() and 0xff).toString(16)}${(payload[cursor + 1].toInt() and 0xff).toString(16)}")
            cursor += 2
        }
        return versions.joinToString(", ")
    }

    private fun appendDnsFields(packet: ByteArray, offset: Int, lines: MutableList<String>) {
        if (packet.size < offset + 12) return
        val fields = mutableListOf<Pair<String, String>>()
        fields.add("dns.transaction_id" to "0x${u16(packet, offset).toString(16).padStart(4, '0')}")
        fields.add("dns.flags" to "0x${u16(packet, offset + 2).toString(16).padStart(4, '0')}")
        fields.add("dns.questions" to u16(packet, offset + 4).toString())
        readDnsQuestion(packet, offset)?.let { question ->
            fields.add("dns.query.name" to question.name)
            fields.add("dns.query.type" to dnsTypeName(question.type))
            fields.add("dns.query.class" to question.clazz.toString())
        }
        appendFields(lines, "DNS 字段 / Value", fields)
    }

    private fun dnsSummary(packet: ByteArray, offset: Int): String {
        if (packet.size < offset + 12) return "DNS 头部太短"
        val qdCount = u16(packet, offset + 4)
        if (qdCount < 1) return "没有 question"
        return readDnsQuestion(packet, offset)?.let { "query ${it.name}" } ?: "DNS question 解析失败"
    }

    private data class DnsQuestion(val name: String, val type: Int, val clazz: Int)

    private fun readDnsQuestion(packet: ByteArray, offset: Int): DnsQuestion? {
        if (packet.size < offset + 12 || u16(packet, offset + 4) < 1) return null
        var cursor = offset + 12
        val labels = mutableListOf<String>()
        repeat(40) {
            if (cursor >= packet.size) return null
            val length = packet[cursor++].toInt() and 0xff
            if (length == 0) {
                if (cursor + 4 > packet.size || labels.isEmpty()) return null
                return DnsQuestion(labels.joinToString("."), u16(packet, cursor), u16(packet, cursor + 2))
            }
            if ((length and 0xc0) != 0 || cursor + length > packet.size) return null
            labels.add(ascii(packet.copyOfRange(cursor, cursor + length)))
            cursor += length
        }
        return null
    }

    private fun appendFields(lines: MutableList<String>, title: String, fields: List<Pair<String, String>>) {
        val cleanFields = fields
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .map { it.first to compactValue(it.second) }
        if (cleanFields.isEmpty()) return
        lines.add("")
        lines.add(title)
        cleanFields.forEach { (name, value) ->
            lines.add("$name: $value")
        }
    }

    private fun compactValue(value: String): String {
        val clean = value.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        return if (clean.length <= FIELD_VALUE_LIMIT) clean else clean.take(FIELD_VALUE_LIMIT) + "...[truncated]"
    }

    private fun decodeUrl(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun tlsRecordType(type: Int): String {
        return when (type) {
            20 -> "change_cipher_spec"
            21 -> "alert"
            22 -> "handshake"
            23 -> "application_data"
            else -> "type_$type"
        }
    }

    private fun tlsHandshakeType(type: Int): String {
        return when (type) {
            1 -> "client_hello"
            2 -> "server_hello"
            11 -> "certificate"
            20 -> "finished"
            else -> "handshake_$type"
        }
    }

    private fun dnsTypeName(type: Int): String {
        return when (type) {
            1 -> "A"
            2 -> "NS"
            5 -> "CNAME"
            15 -> "MX"
            16 -> "TXT"
            28 -> "AAAA"
            33 -> "SRV"
            65 -> "HTTPS"
            else -> type.toString()
        }
    }

    private fun hexDump(bytes: ByteArray): String {
        val lines = mutableListOf<String>()
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + 16)
            val chunk = bytes.copyOfRange(offset, end)
            val hex = chunk.joinToString(" ") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }.padEnd(47, ' ')
            val text = ascii(chunk).replace(Regex("[^\\x20-\\x7e]"), ".")
            lines.add(offset.toString(16).padStart(4, '0') + "  " + hex + "  " + text)
            offset += 16
        }
        return lines.joinToString("\n")
    }

    private fun protocolName(protocol: Int): String {
        return when (protocol) {
            1 -> "ICMP"
            6 -> "TCP"
            17 -> "UDP"
            58 -> "ICMPv6"
            else -> "IP/$protocol"
        }
    }

    private fun ipv4Flags(flagsFragment: Int): String {
        val flags = mutableListOf<String>()
        if ((flagsFragment and 0x4000) != 0) flags.add("DF")
        if ((flagsFragment and 0x2000) != 0) flags.add("MF")
        return flags.joinToString(",").ifBlank { "-" }
    }

    private fun tcpFlags(flags: Int): String {
        val names = mutableListOf<String>()
        if ((flags and 0x01) != 0) names.add("FIN")
        if ((flags and 0x02) != 0) names.add("SYN")
        if ((flags and 0x04) != 0) names.add("RST")
        if ((flags and 0x08) != 0) names.add("PSH")
        if ((flags and 0x10) != 0) names.add("ACK")
        if ((flags and 0x20) != 0) names.add("URG")
        if ((flags and 0x40) != 0) names.add("ECE")
        if ((flags and 0x80) != 0) names.add("CWR")
        return names.joinToString(",").ifBlank { "-" }
    }

    private fun linkTypeName(linkType: Int): String {
        return when (linkType) {
            1 -> "Ethernet"
            101 -> "Raw IP"
            228 -> "IPv4"
            229 -> "IPv6"
            else -> "LINKTYPE $linkType"
        }
    }

    private fun ipv4(packet: ByteArray, offset: Int): String {
        return (0 until 4).joinToString(".") { (packet[offset + it].toInt() and 0xff).toString() }
    }

    private fun ipv6(packet: ByteArray, offset: Int): String {
        return (0 until 16 step 2).joinToString(":") {
            u16(packet, offset + it).toString(16)
        }
    }

    private fun mac(packet: ByteArray, offset: Int): String {
        return (0 until 6).joinToString(":") {
            (packet[offset + it].toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun ascii(bytes: ByteArray): String {
        return bytes.joinToString("") { (it.toInt() and 0xff).toChar().toString() }
    }

    private fun u32(bytes: ByteArray, offset: Int, little: Boolean): Long {
        val b0 = bytes[offset].toLong() and 0xff
        val b1 = bytes[offset + 1].toLong() and 0xff
        val b2 = bytes[offset + 2].toLong() and 0xff
        val b3 = bytes[offset + 3].toLong() and 0xff
        return if (little) {
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        } else {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }
}
