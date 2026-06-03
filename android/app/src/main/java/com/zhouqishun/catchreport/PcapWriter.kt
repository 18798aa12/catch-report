package com.zhouqishun.catchreport

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcapWriter private constructor(
    private val output: BufferedOutputStream
) : Closeable {

    @Synchronized
    fun writePacket(buffer: ByteArray, length: Int) {
        val now = System.currentTimeMillis()
        val seconds = now / 1000
        val micros = (now % 1000) * 1000
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(seconds.toInt())
            .putInt(micros.toInt())
            .putInt(length)
            .putInt(length)
            .array()

        output.write(header)
        output.write(buffer, 0, length)
        output.flush()
    }

    override fun close() {
        output.flush()
        output.close()
    }

    companion object {
        const val LINKTYPE_RAW = 101

        fun open(file: File, linkType: Int): PcapWriter {
            val output = BufferedOutputStream(FileOutputStream(file))
            val globalHeader = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0xa1b2c3d4u.toInt())
                .putShort(2.toShort())
                .putShort(4.toShort())
                .putInt(0)
                .putInt(0)
                .putInt(65535)
                .putInt(linkType)
                .array()

            output.write(globalHeader)
            return PcapWriter(output)
        }
    }
}
