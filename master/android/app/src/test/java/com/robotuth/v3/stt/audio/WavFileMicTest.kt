package com.robotuth.v3.stt.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WavFileMic — nguồn PCM giả mic cho E2E AVD (debug-only).
 * Pure JVM: paced=false trừ bài pacing.
 */
class WavFileMicTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val frame = ByteArray(3200) { (it % 251).toByte() } // frame 100 ms @ 16k mono 16-bit

    private fun wavFile(pcm: ByteArray, rate: Int = 16000, channels: Int = 1, bits: Int = 16): File {
        val out = ByteArrayOutputStream()
        fun str(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun i32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun i16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        str("RIFF"); i32(36 + pcm.size); str("WAVE")
        str("fmt "); i32(16); i16(1); i16(channels); i32(rate); i32(rate * channels * bits / 8); i16(channels * bits / 8); i16(bits)
        str("data"); i32(pcm.size); out.write(pcm)
        val f = tmp.newFile("test.wav")
        f.writeBytes(out.toByteArray())
        return f
    }

    @Test
    fun `read returns exact frames from file then tail-pads from next`() {
        val pcm = frame + frame + ByteArray(100) { 7 }
        val mic = WavFileMic.open(wavFile(pcm).absolutePath, paced = false)!!
        mic.startRecording()
        val buf = ByteArray(3200)
        assertEquals(3200, mic.read(buf, 0, buf.size))
        assertArrayEquals(frame, buf)
        assertEquals(3200, mic.read(buf, 0, buf.size))
        assertArrayEquals(frame, buf)
        // frame 3: 100 B dữ liệu còn lại + pad im lặng tới 3200
        assertEquals(3200, mic.read(buf, 0, buf.size))
        assertArrayEquals(ByteArray(100) { 7 }, buf.copyOf(100))
        assertTrue(buf.copyOfRange(100, 3200).all { it == 0.toByte() })
    }

    @Test
    fun `EOF yields silence forever so endpointer closes segments`() {
        val mic = WavFileMic.open(wavFile(frame).absolutePath, paced = false)!!
        mic.startRecording()
        val buf = ByteArray(3200)
        assertEquals(3200, mic.read(buf, 0, buf.size)) // frame tiếng thật
        repeat(3) {
            assertEquals(3200, mic.read(buf, 0, buf.size))
            assertTrue(buf.all { it == 0.toByte() })
        }
    }

    @Test
    fun `read before startRecording fails like a cold mic`() {
        val mic = WavFileMic.open(wavFile(frame).absolutePath, paced = false)!!
        assertTrue(!mic.recording)
        val buf = ByteArray(3200)
        assertEquals(-1, mic.read(buf, 0, buf.size))
    }

    @Test
    fun `release kills subsequent reads`() {
        val mic = WavFileMic.open(wavFile(frame).absolutePath, paced = false)!!
        mic.startRecording()
        mic.release()
        assertTrue(!mic.stateInitialized)
        assertEquals(-1, mic.read(ByteArray(3200), 0, 3200))
    }

    @Test
    fun `wrong sample rate is rejected - service must not test on a real mic silently`() {
        assertNull(WavFileMic.open(wavFile(frame, rate = 8000).absolutePath, paced = false))
    }

    @Test
    fun `stereo and non-PCM are rejected`() {
        assertNull(WavFileMic.open(wavFile(frame, channels = 2).absolutePath, paced = false))
        val notWav = tmp.newFile("junk.wav")
        notWav.writeBytes(ByteArray(64))
        assertNull(WavFileMic.open(notWav.absolutePath, paced = false))
    }

    @Test
    fun `paced mode sleeps roughly one frame duration`() {
        val mic = WavFileMic.open(wavFile(frame).absolutePath, paced = true)!!
        mic.startRecording()
        val t0 = System.currentTimeMillis()
        assertEquals(3200, mic.read(ByteArray(3200), 0, 3200))
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("paced read too fast: ${elapsed}ms", elapsed >= 60)
    }
}
