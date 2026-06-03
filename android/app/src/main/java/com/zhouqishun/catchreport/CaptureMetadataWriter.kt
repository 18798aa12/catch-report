package com.zhouqishun.catchreport

import java.io.File

object CaptureMetadataWriter {
    fun write(
        captureFile: File,
        config: CaptureConfig,
        startedAtMillis: Long,
        endedAtMillis: Long,
        packetCount: Long,
        byteCount: Long,
        recordedOnlyCount: Long,
        unsupportedForwardCount: Long,
        tun2SocksStats: Tun2SocksStats? = null
    ): File {
        val sidecar = File(captureFile.parentFile, "${captureFile.name}.json")
        val json = """
            {
              "captureFile": "${escape(captureFile.name)}",
              "startedAtMillis": $startedAtMillis,
              "endedAtMillis": $endedAtMillis,
              "packetCount": $packetCount,
              "byteCount": $byteCount,
              "recordedOnlyCount": $recordedOnlyCount,
              "unsupportedForwardCount": $unsupportedForwardCount,
              "tun2SocksStats": ${tun2SocksStats?.toJson() ?: "null"},
              "mode": "${config.mode}",
              "proxy": {
                "enabled": ${config.proxy.enabled},
                "type": "${config.proxy.type}",
                "host": "${escape(config.proxy.host)}",
                "port": ${config.proxy.port},
                "embedded": ${config.proxy.embedded}
              }
            }
        """.trimIndent()
        sidecar.writeText(json)
        return sidecar
    }

    private fun escape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun Tun2SocksStats.toJson(): String {
        return """
            {
              "txPackets": $txPackets,
              "txBytes": $txBytes,
              "rxPackets": $rxPackets,
              "rxBytes": $rxBytes
            }
        """.trimIndent()
    }
}
