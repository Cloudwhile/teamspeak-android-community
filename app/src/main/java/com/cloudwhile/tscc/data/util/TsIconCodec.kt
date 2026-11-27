package com.cloudwhile.tscc.data.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Normalizes TeamSpeak custom icon payloads downloaded from `/icon_<crc32>`.
 *
 * Desktop TeamSpeak 3/6 servers frequently host `16x16` or `32x32` Windows `.ico` icons
 * (either PNG-in-ICO or uncompressed 32/24/8/4/1-bit BMP DIB frames) which Android's
 * standard `BitmapFactory` / Coil cannot decode directly from an `.ico` container.
 */
object TsIconCodec {

    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    fun normalizeIconFileInPlace(file: File) {
        if (!file.exists() || file.length() <= 6L || file.length() > 4 * 1024 * 1024L) return
        runCatching {
            val raw = file.readBytes()
            val converted = normalizeIconBytes(raw)
            if (converted !== raw) {
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeBytes(converted)
                if (!tmp.renameTo(file)) {
                    file.writeBytes(converted)
                    tmp.delete()
                }
            }
        }
    }

    /**
     * If [raw] is a Windows `.ico` container, extracts its highest-quality frame as a standard
     * RGBA `.png` byte array; otherwise returns [raw] unchanged.
     */
    fun normalizeIconBytes(raw: ByteArray): ByteArray {
        if (raw.size < 22) return raw
        if (startsWithPng(raw)) return raw

        // ICO header: 00 00 (reserved), 01 00 (type=1 icon) or 02 00 (cursor), count > 0
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val reserved = buf.short.toInt() and 0xFFFF
        val type = buf.short.toInt() and 0xFFFF
        val count = buf.short.toInt() and 0xFFFF
        if (reserved != 0 || (type != 1 && type != 2) || count !in 1..64) return raw
        if (6 + count * 16 > raw.size) return raw

        var bestOffset = -1
        var bestSize = 0
        var bestScore = -1

        for (i in 0 until count) {
            val base = 6 + i * 16
            val wRaw = raw[base].toInt() and 0xFF
            val hRaw = raw[base + 1].toInt() and 0xFF
            val width = if (wRaw == 0) 256 else wRaw
            val height = if (hRaw == 0) 256 else hRaw
            val bpp = buf.getShort(base + 6).toInt() and 0xFFFF
            val size = buf.getInt(base + 8)
            val offset = buf.getInt(base + 12)
            if (size <= 0 || offset < 6 + count * 16 || offset.toLong() + size.toLong() > raw.size.toLong()) {
                continue
            }
            val score = (width * height) * 64 + bpp
            if (score > bestScore) {
                bestScore = score
                bestOffset = offset
                bestSize = size
            }
        }

        if (bestOffset < 0 || bestSize <= 0) return raw
        val slice = raw.copyOfRange(bestOffset, bestOffset + bestSize)
        if (startsWithPng(slice)) {
            return slice
        }
        return decodeDibFrameToPng(slice) ?: raw
    }

    private fun startsWithPng(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_MAGIC.size) return false
        for (i in PNG_MAGIC.indices) {
            if (bytes[i] != PNG_MAGIC[i]) return false
        }
        return true
    }

    private fun decodeDibFrameToPng(dib: ByteArray): ByteArray? {
        if (dib.size < 40) return null
        val buf = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = buf.getInt(0)
        if (headerSize < 40 || headerSize > dib.size) return null

        val width = buf.getInt(4)
        val rawHeight = buf.getInt(8) // In ICO, biHeight is XOR height + AND height (2 * height)
        val bitCount = buf.getShort(14).toInt() and 0xFFFF
        val compression = buf.getInt(16)
        val clrUsed = buf.getInt(32)

        if (width !in 1..512 || compression != 0) return null
        val topDown = rawHeight < 0
        val absH = kotlin.math.abs(rawHeight)
        val height = absH / 2
        if (height !in 1..512) return null

        val paletteEntries = when (bitCount) {
            1, 4, 8 -> if (clrUsed > 0) clrUsed else (1 shl bitCount)
            else -> 0
        }
        val paletteOffset = headerSize
        val xorOffset = paletteOffset + paletteEntries * 4
        if (xorOffset > dib.size) return null

        val xorRowBytes = ((width * bitCount + 31) / 32) * 4
        val xorTotalBytes = xorRowBytes * height
        if (xorOffset + xorTotalBytes > dib.size) return null

        val andOffset = xorOffset + xorTotalBytes
        val andRowBytes = ((width + 31) / 32) * 4
        val hasAndMask = andOffset + andRowBytes * height <= dib.size

        val rgba = ByteArray(width * height * 4)

        for (yOut in 0 until height) {
            val srcRow = if (topDown) yOut else (height - 1 - yOut)
            val xorRowStart = xorOffset + srcRow * xorRowBytes
            val andRowStart = andOffset + srcRow * andRowBytes

            for (x in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                var a = 255

                when (bitCount) {
                    32 -> {
                        val p = xorRowStart + x * 4
                        b = dib[p].toInt() and 0xFF
                        g = dib[p + 1].toInt() and 0xFF
                        r = dib[p + 2].toInt() and 0xFF
                        a = dib[p + 3].toInt() and 0xFF
                    }
                    24 -> {
                        val p = xorRowStart + x * 3
                        b = dib[p].toInt() and 0xFF
                        g = dib[p + 1].toInt() and 0xFF
                        r = dib[p + 2].toInt() and 0xFF
                    }
                    8 -> {
                        val idx = dib[xorRowStart + x].toInt() and 0xFF
                        if (idx < paletteEntries) {
                            val pal = paletteOffset + idx * 4
                            b = dib[pal].toInt() and 0xFF
                            g = dib[pal + 1].toInt() and 0xFF
                            r = dib[pal + 2].toInt() and 0xFF
                        }
                    }
                    4 -> {
                        val byteVal = dib[xorRowStart + (x shr 1)].toInt() and 0xFF
                        val idx = if ((x and 1) == 0) (byteVal ushr 4) else (byteVal and 0x0F)
                        if (idx < paletteEntries) {
                            val pal = paletteOffset + idx * 4
                            b = dib[pal].toInt() and 0xFF
                            g = dib[pal + 1].toInt() and 0xFF
                            r = dib[pal + 2].toInt() and 0xFF
                        }
                    }
                    1 -> {
                        val byteVal = dib[xorRowStart + (x shr 3)].toInt() and 0xFF
                        val idx = (byteVal ushr (7 - (x and 7))) and 1
                        if (idx < paletteEntries) {
                            val pal = paletteOffset + idx * 4
                            b = dib[pal].toInt() and 0xFF
                            g = dib[pal + 1].toInt() and 0xFF
                            r = dib[pal + 2].toInt() and 0xFF
                        }
                    }
                    else -> return null
                }

                if (bitCount < 32 && hasAndMask) {
                    val maskByte = dib[andRowStart + (x shr 3)].toInt() and 0xFF
                    val maskBit = (maskByte ushr (7 - (x and 7))) and 1
                    if (maskBit == 1) a = 0
                }

                val dst = (yOut * width + x) * 4
                rgba[dst] = r.toByte()
                rgba[dst + 1] = g.toByte()
                rgba[dst + 2] = b.toByte()
                rgba[dst + 3] = a.toByte()
            }
        }

        // If 32-bit ICO had all-zero alpha channel (legacy XP 32-bit RGB0 icons), fall back to AND mask
        if (bitCount == 32 && rgba.indices.step(4).all { rgba[it + 3].toInt() == 0 }) {
            for (yOut in 0 until height) {
                val srcRow = if (topDown) yOut else (height - 1 - yOut)
                val andRowStart = andOffset + srcRow * andRowBytes
                for (x in 0 until width) {
                    val transparent = if (hasAndMask) {
                        val maskByte = dib[andRowStart + (x shr 3)].toInt() and 0xFF
                        ((maskByte ushr (7 - (x and 7))) and 1) == 1
                    } else false
                    rgba[(yOut * width + x) * 4 + 3] = if (transparent) 0 else 0xFF.toByte()
                }
            }
        }

        return encodeRgbaToPng(width, height, rgba)
    }

    fun encodeRgbaToPng(width: Int, height: Int, rgba: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PNG_MAGIC)

        val ihdr = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(width)
            putInt(height)
            put(8) // bit depth = 8
            put(6) // color type = 6 (RGBA)
            put(0) // compression = deflate
            put(0) // filter = adaptive
            put(0) // interlace = none
        }.array()
        writePngChunk(out, "IHDR", ihdr)

        val rowStride = width * 4
        val rawScanlines = ByteArray((rowStride + 1) * height)
        for (y in 0 until height) {
            val dstRow = y * (rowStride + 1)
            rawScanlines[dstRow] = 0 // filter type 0 (None)
            System.arraycopy(rgba, y * rowStride, rawScanlines, dstRow + 1, rowStride)
        }

        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        val compressed = ByteArrayOutputStream()
        try {
            deflater.setInput(rawScanlines)
            deflater.finish()
            val tmp = ByteArray(4096)
            while (!deflater.finished()) {
                val n = deflater.deflate(tmp)
                if (n > 0) compressed.write(tmp, 0, n)
            }
        } finally {
            deflater.end()
        }
        writePngChunk(out, "IDAT", compressed.toByteArray())
        writePngChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writePngChunk(out: ByteArrayOutputStream, typeAscii: String, data: ByteArray) {
        val typeBytes = typeAscii.toByteArray(Charsets.US_ASCII)
        val lenBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array()
        out.write(lenBytes)
        out.write(typeBytes)
        out.write(data)

        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val crcBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.value.toInt()).array()
        out.write(crcBytes)
    }
}
