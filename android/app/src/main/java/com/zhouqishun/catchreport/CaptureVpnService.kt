package com.zhouqishun.catchreport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class CaptureVpnService : VpnService() {
    private val running = AtomicBoolean(false)
    private val packetCount = AtomicLong(0)
    private val byteCount = AtomicLong(0)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var captureThread: Thread? = null
    private var pcapWriter: PcapWriter? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_START, null -> startCapture()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun startCapture() {
        if (!running.compareAndSet(false, true)) return

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val captureFile = createCaptureFile()
        pcapWriter = PcapWriter.open(captureFile, PcapWriter.LINKTYPE_RAW)

        vpnInterface = Builder()
            .setSession("Catch Report")
            .setMtu(1500)
            .addAddress("10.77.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .establish()

        val descriptor = vpnInterface ?: run {
            stopCapture()
            return
        }

        captureThread = thread(name = "catch-report-capture", isDaemon = true) {
            readTunLoop(descriptor, pcapWriter)
        }
    }

    private fun readTunLoop(
        descriptor: ParcelFileDescriptor,
        writer: PcapWriter?
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
            }
        }
    }

    private fun stopCapture() {
        if (!running.getAndSet(false)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

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

        captureThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createCaptureFile(): File {
        val dir = File(
            getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "captures"
        )
        dir.mkdirs()
        return File(dir, "catch-report-${System.currentTimeMillis()}.pcap")
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_capture_title))
            .setContentText(getString(R.string.notification_capture_text))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
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
    }
}
