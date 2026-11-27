package com.cloudwhile.tscc.data.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TsIconCodecTest {

    private val pngMagic = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    @Test
    fun `normalizeIconBytes keeps standard PNG payload untouched`() {
        val samplePng = TsIconCodec.encodeRgbaToPng(
            width = 2,
            height = 2,
            rgba = ByteArray(16) { 0xFF.toByte() }
        )
        val result = TsIconCodec.normalizeIconBytes(samplePng)
        assertSame(samplePng, result)
    }

    @Test
    fun `normalizeIconBytes extracts embedded PNG from modern ICO container`() {
        val innerPng = TsIconCodec.encodeRgbaToPng(
            width = 4,
            height = 4,
            rgba = ByteArray(4 * 4 * 4) { 0xAA.toByte() }
        )
        val ico = ByteBuffer.allocate(22 + innerPng.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0) // reserved
            putShort(1) // type = ICO
            putShort(1) // count = 1
            put(4)      // width
            put(4)      // height
            put(0)      // colorCount
            put(0)      // reserved
            putShort(1) // planes
            putShort(32)// bitCount
            putInt(innerPng.size)
            putInt(22)  // offset
            put(innerPng)
        }.array()

        val extracted = TsIconCodec.normalizeIconBytes(ico)
        assertArrayEquals(innerPng, extracted)
    }

    @Test
    fun `normalizeIconBytes transcodes classic 32-bit BMP DIB ICO to valid PNG`() {
        val width = 2
        val height = 2
        val xorBytes = width * height * 4 // 16 bytes
        val andRowBytes = 4               // 32-bit aligned AND mask row
        val andBytes = andRowBytes * height
        val dibSize = 40 + xorBytes + andBytes

        val ico = ByteBuffer.allocate(22 + dibSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0)
            putShort(1)
            putShort(1)
            put(width.toByte())
            put(height.toByte())
            put(0)
            put(0)
            putShort(1)
            putShort(32)
            putInt(dibSize)
            putInt(22)

            // BITMAPINFOHEADER (40 bytes)
            putInt(40)
            putInt(width)
            putInt(height * 2) // XOR + AND combined height in ICO
            putShort(1)
            putShort(32)
            putInt(0) // BI_RGB
            putInt(xorBytes)
            putInt(0)
            putInt(0)
            putInt(0)
            putInt(0)

            // 4 BGRA pixels
            repeat(4) {
                put(0xFF.toByte()) // B
                put(0x80.toByte()) // G
                put(0x20.toByte()) // R
                put(0xFF.toByte()) // A
            }
            // AND mask
            put(ByteArray(andBytes))
        }.array()

        val converted = TsIconCodec.normalizeIconBytes(ico)
        assertTrue(converted.size > pngMagic.size + 16)
        assertArrayEquals(pngMagic, converted.copyOfRange(0, pngMagic.size))

        // Verify IHDR dimensions (bytes 16..24 big-endian)
        val ihdrView = ByteBuffer.wrap(converted, 16, 8).order(ByteOrder.BIG_ENDIAN)
        assertEquals(width, ihdrView.int)
        assertEquals(height, ihdrView.int)
    }
}
