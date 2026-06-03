package com.zhouqishun.catchreport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class CaptureVpnService : VpnService() {
    private val running = AtomicBoolean(false)
    private val packetCount = AtomicLong(0)
    private val byteCount = AtomicLong(0)
    private val recordedOnlyCount = AtomicLong(0)
    private val unsupportedForwardCount = AtomicLong(0)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var captureThread: Thread? = null
    private var pcapWriter: PcapWriter? = null
    private var packetForwarder: PacketForwarder? = null
    private var tun2SocksEngine: HevTun2SocksEngine? = null
    private var mihomoCore: MihomoCore? = null
    private var captureFile: File? = null
    private var captureConfig: CaptureConfig = CaptureConfig.default()
    private var startedAtMillis: Long = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_START, null -> startCapture(CaptureConfig.fromIntent(intent))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun startCapture(config: CaptureConfig) {
        if (!running.compareAndSet(false, true)) return
        startedAtMillis = System.currentTimeMillis()
        packetCount.set(0)
        byteCount.set(0)
        recordedOnlyCount.set(0)
        unsupportedForwardCount.set(0)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(config))

        val effectiveConfig = try {
            startEmbeddedProxyIfNeeded(config)
        } catch (error: Exception) {
            Log.e(LOG_TAG, "Failed to start embedded proxy", error)
            stopCapture()
            return
        }
        captureConfig = effectiveConfig
        startForeground(NOTIFICATION_ID, buildNotification(effectiveConfig))

        captureFile = createCaptureFile(effectiveConfig)
        if (effectiveConfig.mode == CaptureMode.CAPTURE_ONLY) {
            pcapWriter = PcapWriter.open(captureFile ?: return, PcapWriter.LINKTYPE_RAW)
            packetForwarder = createForwarder(effectiveConfig)
        }

        vpnInterface = createVpnBuilder()
            .setSession("Catch Report")
            .setBlocking(false)
            .setMtu(8500)
            .addAddress("198.18.0.1", 32)
            .addAddress("fc00::1", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(dnsServerFor(effectiveConfig))
            .establish()

        val descriptor = vpnInterface ?: run {
            Log.w(LOG_TAG, "VPN establish returned null")
            stopCapture()
            return
        }

        if (effectiveConfig.mode == CaptureMode.UPSTREAM_PROXY) {
            tun2SocksEngine = HevTun2SocksEngine(this, cacheDir, effectiveConfig.proxy).also {
                it.start(descriptor)
            }
            Log.i(LOG_TAG, "Started upstream proxy capture: ${captureFile?.absolutePath}")
        } else {
            captureThread = thread(name = "catch-report-capture", isDaemon = true) {
                readTunLoop(descriptor, pcapWriter, packetForwarder)
            }
            Log.i(LOG_TAG, "Started capture-only: ${captureFile?.absolutePath}")
        }
    }

    private fun readTunLoop(
        descriptor: ParcelFileDescriptor,
        writer: PcapWriter?,
        forwarder: PacketForwarder?
    ) {
        val buffer = ByteArray(65535)
        FileInputStream(descriptor.fileDescriptor).use { input ->
            while (running.get()) {
                val read = try {
                    input.read(buffer)
                } catch (_: Exception) {
                    -1
                }
                if (read <= 0) continue

                packetCount.incrementAndGet()
                byteCount.addAndGet(read.toLong())
                writer?.writePacket(buffer, read)
                when (forwarder?.handlePacket(buffer, read)?.status) {
                    ForwardingResult.Status.RECORDED_ONLY -> recordedOnlyCount.incrementAndGet()
                    ForwardingResult.Status.UNSUPPORTED -> unsupportedForwardCount.incrementAndGet()
                    ForwardingResult.Status.FORWARDED -> Unit
                    null -> Unit
                }
            }
        }
    }

    private fun stopCapture() {
        if (!running.getAndSet(false)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val finalTun2SocksStats = tun2SocksEngine?.stats()

        try {
            tun2SocksEngine?.close()
        } catch (_: Exception) {
        }
        tun2SocksEngine = null

        try {
            mihomoCore?.close()
        } catch (_: Exception) {
        }
        mihomoCore = null

        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null

        try {
            pcapWriter?.close()
        } catch (_: Exception) {
        }
        pcapWriter = null

        try {
            packetForwarder?.close()
        } catch (_: Exception) {
        }
        packetForwarder = null

        captureFile?.let { file ->
            try {
                val sidecar = CaptureMetadataWriter.write(
                    captureFile = file,
                    config = captureConfig,
                    startedAtMillis = startedAtMillis,
                    endedAtMillis = System.currentTimeMillis(),
                    packetCount = packetCount.get(),
                    byteCount = byteCount.get(),
                    recordedOnlyCount = recordedOnlyCount.get(),
                    unsupportedForwardCount = unsupportedForwardCount.get(),
                    tun2SocksStats = finalTun2SocksStats
                )
                Log.i(LOG_TAG, "Wrote capture metadata: ${sidecar.absolutePath}")
            } catch (error: Exception) {
                Log.e(LOG_TAG, "Failed to write capture metadata for ${file.absolutePath}", error)
            }
        }
        captureFile = null

        captureThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createCaptureFile(config: CaptureConfig): File {
        val dir = File(
            getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "captures"
        )
        dir.mkdirs()
        val suffix = if (config.mode == CaptureMode.UPSTREAM_PROXY) ".pcap.pending" else ".pcap"
        return File(dir, "catch-report-${System.currentTimeMillis()}$suffix")
    }

    private fun buildNotification(config: CaptureConfig): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_capture_title))
            .setContentText(notificationText(config))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun createForwarder(config: CaptureConfig): PacketForwarder {
        return when (config.mode) {
            CaptureMode.CAPTURE_ONLY -> CaptureOnlyForwarder()
            CaptureMode.UPSTREAM_PROXY -> UpstreamProxyForwarder(this, config.proxy)
        }
    }

    private fun startEmbeddedProxyIfNeeded(config: CaptureConfig): CaptureConfig {
        if (config.mode != CaptureMode.UPSTREAM_PROXY || !config.proxy.embedded) {
            return config
        }

        val core = MihomoCore(this)
        val upstream = core.start()
        mihomoCore = core
        return config.copy(proxy = upstream)
    }

    private fun createVpnBuilder(): Builder {
        val builder = Builder()
        val excludedPackages = buildSet {
            add(packageName)
            addAll(KNOWN_CLASH_PACKAGES)
        }
        excludedPackages.forEach { packageName ->
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return builder
    }

    private fun dnsServerFor(config: CaptureConfig): String {
        return when (config.mode) {
            CaptureMode.UPSTREAM_PROXY -> HevTun2SocksEngine.MAPPED_DNS_ADDRESS
            CaptureMode.CAPTURE_ONLY -> DEFAULT_DNS_SERVER
        }
    }

    private fun notificationText(config: CaptureConfig): String {
        return when (config.mode) {
            CaptureMode.CAPTURE_ONLY -> getString(R.string.notification_capture_text)
            CaptureMode.UPSTREAM_PROXY -> {
                val prefix = if (config.proxy.embedded) "内置 Mihomo" else config.proxy.type.toString()
                "转发到代理：$prefix ${config.proxy.host}:${config.proxy.port}"
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_capture),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.zhouqishun.catchreport.action.START"
        const val ACTION_STOP = "com.zhouqishun.catchreport.action.STOP"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 100
        private const val DEFAULT_DNS_SERVER = "1.1.1.1"
        private const val LOG_TAG = "CatchReport"
        private val KNOWN_CLASH_PACKAGES = listOf(
            "com.github.kr328.clash",
            "com.github.kr328.clash.foss",
            "com.github.metacubex.clash.meta",
            "com.metacubex.clash.meta",
            "com.github.kr328.clash.meta"
        )
    }
}
