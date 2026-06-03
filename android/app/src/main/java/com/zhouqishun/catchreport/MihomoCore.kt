package com.zhouqishun.catchreport

import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MihomoCore(private val context: Context) : Closeable {
    private var process: Process? = null
    private var logThread: Thread? = null

    fun start(port: Int = UpstreamProxyConfig.DEFAULT_CLASH_PROXY_PORT): UpstreamProxyConfig {
        if (process?.isAlive == true) {
            return upstreamConfig(port)
        }

        val home = homeDir(context)
        home.mkdirs()
        installBundledGeodata(context, home)
        val config = ensureConfig(context)
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) {
            throw IllegalStateException("mihomo binary missing: ${binary.absolutePath}")
        }

        val command = listOf(binary.absolutePath, "-d", home.absolutePath, "-f", config.absolutePath)
        Log.i(LOG_TAG, "Starting mihomo: $command")
        val started = ProcessBuilder(command)
            .directory(home)
            .redirectErrorStream(true)
            .start()
        process = started
        logThread = thread(name = "catch-report-mihomo-log", isDaemon = true) {
            try {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> Log.i(LOG_TAG, line) }
                }
            } catch (error: Exception) {
                Log.w(LOG_TAG, "mihomo log stream closed", error)
            }
        }

        if (!awaitPort(port, 5000)) {
            val exitCode = runCatching { started.exitValue() }.getOrNull()
            if (exitCode != null) {
                close()
                throw IllegalStateException("mihomo exited before 127.0.0.1:$port opened, exitCode=$exitCode")
            }
            Log.w(LOG_TAG, "mihomo port probe timed out; continuing because the process is still running")
        }

        return upstreamConfig(port)
    }

    override fun close() {
        val running = process ?: return
        process = null
        if (running.isAlive) {
            running.destroy()
            runCatching { running.waitFor(1500, TimeUnit.MILLISECONDS) }
            if (running.isAlive) {
                running.destroyForcibly()
            }
        }
        logThread = null
    }

    private fun upstreamConfig(port: Int): UpstreamProxyConfig {
        return UpstreamProxyConfig(
            enabled = true,
            type = UpstreamProxyConfig.Type.SOCKS5,
            host = UpstreamProxyConfig.LOCALHOST,
            port = port,
            embedded = true
        )
    }

    private fun awaitPort(port: Int, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (canConnect(port)) return true
            Thread.sleep(100)
        }
        return canConnect(port)
    }

    private fun canConnect(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(UpstreamProxyConfig.LOCALHOST, port), 250)
            }
        }.isSuccess
    }

    companion object {
        private const val LOG_TAG = "MihomoCore"
        private const val BINARY_NAME = "libmihomo.so"
        private const val CONFIG_NAME = "config.yaml"

        fun installConfig(context: Context, input: InputStream): File {
            return installConfigText(context, input.readTextWithLimit(MAX_CONFIG_BYTES))
        }

        fun downloadConfig(context: Context, urlText: String): File {
            val url = URL(urlText)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("User-Agent", "CatchReport/0.1 mihomo-subscription")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("subscription download failed: HTTP $code")
                }
                val body = connection.inputStream.use { input ->
                    input.readTextWithLimit(MAX_CONFIG_BYTES)
                }
                return installConfigText(context, body)
            } finally {
                connection.disconnect()
            }
        }

        fun installConfigText(context: Context, rawConfig: String): File {
            val target = configFile(context)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "$CONFIG_NAME.tmp")
            temp.writeText(normalizeEmbeddedConfig(rawConfig))
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            return target
        }

        fun configFile(context: Context): File {
            return File(homeDir(context), CONFIG_NAME)
        }

        fun hasUserConfig(context: Context): Boolean {
            val file = configFile(context)
            return file.exists() && file.length() > 0
        }

        private fun ensureConfig(context: Context): File {
            val file = configFile(context)
            if (!file.exists() || file.length() == 0L) {
                file.parentFile?.mkdirs()
                file.writeText(defaultConfig())
            }
            return file
        }

        private fun homeDir(context: Context): File {
            return File(context.filesDir, "mihomo")
        }

        private fun InputStream.readTextWithLimit(maxBytes: Int): String {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = read(buffer)
                if (read <= 0) break
                total += read
                if (total > maxBytes) {
                    throw IllegalStateException("mihomo config is larger than ${maxBytes / 1024 / 1024} MB")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray().toString(Charsets.UTF_8)
        }

        private fun normalizeEmbeddedConfig(rawConfig: String): String {
            val normalized = rawConfig.removePrefix("\uFEFF")
            val lines = normalized.lineSequence().toList()
            val filtered = mutableListOf<String>()
            var skipIndentedBlock = false

            for (line in lines) {
                val trimmedEnd = line.trimEnd()
                val trimmedStart = trimmedEnd.trimStart()
                val isTopLevel = trimmedEnd.isNotBlank() && !trimmedEnd.first().isWhitespace()

                if (skipIndentedBlock) {
                    if (!isTopLevel || trimmedStart.startsWith("#")) {
                        continue
                    }
                    skipIndentedBlock = false
                }

                if (isTopLevel) {
                    val key = trimmedStart.substringBefore(':', missingDelimiterValue = "").trim()
                    if (key in TOP_LEVEL_KEYS_TO_REPLACE) {
                        continue
                    }
                    if (key in TOP_LEVEL_BLOCKS_TO_REMOVE) {
                        skipIndentedBlock = true
                        continue
                    }
                }

                filtered += line
            }

            return """
                mixed-port: ${UpstreamProxyConfig.DEFAULT_CLASH_PROXY_PORT}
                bind-address: ${UpstreamProxyConfig.LOCALHOST}
                allow-lan: false
                external-controller: ${UpstreamProxyConfig.LOCALHOST}:9090
                secret: ""
                geo-auto-update: false
                geox-url:
                  geoip: "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.dat"
                  geosite: "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat"
                  asn: "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb"
            """.trimIndent() + "\n\n" + filtered.joinToString("\n").trim() + "\n"
        }

        private fun installBundledGeodata(context: Context, home: File) {
            val available = context.assets.list(GEODATA_ASSET_DIR)?.toSet().orEmpty()
            for (name in GEODATA_FILES) {
                if (name !in available) continue
                val target = File(home, name)
                val temp = File(home, "$name.tmp")
                context.assets.open("$GEODATA_ASSET_DIR/$name").use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            }
        }

        private fun defaultConfig(): String {
            return """
                mixed-port: 7890
                bind-address: 127.0.0.1
                allow-lan: false
                mode: rule
                log-level: info
                ipv6: false
                external-controller: 127.0.0.1:9090
                secret: ""

                dns:
                  enable: true
                  listen: 127.0.0.1:1053
                  enhanced-mode: fake-ip
                  nameserver:
                    - 223.5.5.5
                    - 1.1.1.1

                proxies: []

                proxy-groups:
                  - name: PROXY
                    type: select
                    proxies:
                      - DIRECT

                rules:
                  - MATCH,DIRECT
            """.trimIndent() + "\n"
        }

        private const val MAX_CONFIG_BYTES = 8 * 1024 * 1024
        private val TOP_LEVEL_KEYS_TO_REPLACE = setOf(
            "mixed-port",
            "port",
            "socks-port",
            "redir-port",
            "tproxy-port",
            "bind-address",
            "allow-lan",
            "external-controller",
            "secret",
            "geo-auto-update",
            "geo-update-interval"
        )
        private val TOP_LEVEL_BLOCKS_TO_REMOVE = setOf("tun", "geox-url")
        private const val GEODATA_ASSET_DIR = "mihomo-geodata"
        private val GEODATA_FILES = listOf("GeoIP.dat", "GeoSite.dat", "ASN.mmdb")
    }
}
