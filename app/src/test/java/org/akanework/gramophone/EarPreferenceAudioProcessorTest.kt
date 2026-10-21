/*
 * Copyright (C) 2026 The Gramophone authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.akanework.gramophone

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessingPipeline
import com.google.common.collect.ImmutableList
import org.akanework.gramophone.logic.utils.EarPreferenceAudioProcessor
import org.akanework.gramophone.logic.utils.EarPreferenceAudioProcessor.Mode
import org.akanework.gramophone.logic.utils.ReplayGainAudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EarPreferenceAudioProcessorTest {
    private fun processor(
        mode: Mode = Mode.LEFT,
        encoding: Int = C.ENCODING_PCM_16BIT,
        sampleRate: Int = 48000
    ) = EarPreferenceAudioProcessor().apply {
        this.mode = mode
        configure(AudioProcessor.AudioFormat(sampleRate, 2, encoding))
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    private fun pcm16(frames: Int, left: Int, right: Int): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(frames) {
                putShort(left.toShort())
                putShort(right.toShort())
            }
            flip()
        }

    private fun process(ap: EarPreferenceAudioProcessor, input: ByteBuffer): ByteBuffer {
        val size = input.remaining()
        ap.queueInput(input)
        assertFalse("Input must be consumed", input.hasRemaining())
        return ap.output.also { assertEquals("Frame count must be preserved", size, it.remaining()) }
    }

    private fun assertLastFrame(output: ByteBuffer, left: Int, right: Int) {
        assertEquals(left.toShort(), output.getShort(output.limit() - 4))
        assertEquals(right.toShort(), output.getShort(output.limit() - 2))
    }

    private fun bytes(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also {
        buffer.duplicate().get(it)
    }

    @Test
    fun offPreservesEveryByteAndHonoursBufferPositionAndLimit() {
        val input = pcm16(100, -32768, 32767).apply { position(8); limit(limit() - 4) }
        val expected = bytes(input)
        assertArrayEquals(expected, bytes(process(processor(Mode.OFF), input)))
    }

    @Test
    fun rightDominanceIsRoutedToLeftAndRemainsThereWithoutFeedback() {
        val ap = processor()
        repeat(6) { assertLastFrame(process(ap, pcm16(4800, 1000, 12000)), 12000, 1000) }
    }

    @Test
    fun leftDominanceIsRoutedToRight() {
        assertLastFrame(process(processor(Mode.RIGHT), pcm16(4800, -16000, 1000)), 1000, -16000)
    }

    @Test
    fun alreadyPreferredDominanceIsUnchangedForBothEars() {
        for (mode in listOf(Mode.LEFT, Mode.RIGHT)) {
            val input = if (mode == Mode.LEFT) pcm16(4800, -16000, 1000)
                else pcm16(4800, 1000, -16000)
            assertArrayEquals(bytes(input), bytes(process(processor(mode), input)))
        }
    }

    @Test
    fun routingReturnsWhenOriginalPreferredEarBecomesDominant() {
        val ap = processor()
        process(ap, pcm16(4800, 1000, 12000))
        assertLastFrame(process(ap, pcm16(4800, 12000, 1000)), 12000, 1000)
    }

    @Test
    fun centredAndSmallImbalanceAudioDoesNotTriggerSwapping() {
        val ap = processor()
        for (right in listOf(10000, 11000, -11000)) {
            val input = pcm16(9600, 10000, right)
            assertArrayEquals(bytes(input), bytes(process(ap, input)))
        }
    }

    @Test
    fun nearCentredAndSilentPassagesHoldCurrentRouting() {
        val ap = processor()
        process(ap, pcm16(4800, 1000, 12000))
        assertLastFrame(process(ap, pcm16(4800, 10000, -11000)), -11000, 10000)
        assertLastFrame(process(ap, pcm16(4800, 0, 1)), 1, 0)
    }

    @Test
    fun inaudibleImbalanceDoesNotTriggerSwapping() {
        val input = pcm16(9600, 0, 1)
        assertArrayEquals(bytes(input), bytes(process(processor(), input)))
    }

    @Test
    fun briefBurstsAndAlternatingDominanceDoNotTriggerSwapping() {
        val ap = processor()
        val burst = pcm16(2400, 1000, 12000) // 50 ms
        assertArrayEquals(bytes(burst), bytes(process(ap, burst)))
        process(ap, pcm16(2400, 12000, 1000))
        repeat(6) {
            assertLastFrame(process(ap, pcm16(960, 1000, 12000)), 1000, 12000)
            assertLastFrame(process(ap, pcm16(960, 12000, 1000)), 12000, 1000)
        }
    }

    @Test
    fun detectionIsIndependentOfBufferBoundariesAtDifferentSampleRates() {
        for (sampleRate in listOf(44100, 48000, 96000)) {
            val ap = processor(sampleRate = sampleRate)
            repeat(100) {
                val result = process(ap, pcm16(sampleRate / 1000, 1000, 12000))
                if (it == 99) assertLastFrame(result, 12000, 1000)
            }
        }
    }

    @Test
    fun buffersSplitInsideFramesProduceTheSameAudioAsWholeFrames() {
        for (mode in listOf(Mode.OFF, Mode.LEFT, Mode.RIGHT)) {
            val source = bytes(pcm16(4800, -1000, 12000))
            val expected = bytes(process(processor(mode), pcm16(4800, -1000, 12000)))
            val actual = ByteBuffer.allocate(source.size)
            val ap = processor(mode)
            var offset = 0
            val chunkSizes = listOf(1, 3, 7, 15, 61)
            var chunk = 0
            while (offset < source.size) {
                val size = minOf(chunkSizes[chunk++ % chunkSizes.size], source.size - offset)
                val input = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
                    .put(source, offset, size)
                input.flip()
                ap.queueInput(input)
                assertFalse(input.hasRemaining())
                actual.put(ap.output)
                offset += size
            }
            assertEquals(source.size, actual.position())
            assertArrayEquals(expected, actual.array())
        }
    }

    @Test
    fun switchingOffDuringPlaybackSmoothlyRestoresOriginalChannels() {
        val ap = processor()
        process(ap, pcm16(4800, 1000, 12000))
        ap.mode = Mode.OFF
        val restored = process(ap, pcm16(960, 1000, 12000))
        assertTrue(restored.getShort(0) > 1000)
        assertLastFrame(restored, 1000, 12000)
        val original = pcm16(4800, 1000, 12000)
        assertArrayEquals(bytes(original), bytes(process(ap, original)))
    }

    @Test
    fun enablingAndChangingPreferredEarTakesEffectWithoutReconfiguration() {
        val ap = processor(Mode.OFF)
        process(ap, pcm16(4800, 1000, 12000))
        ap.mode = Mode.LEFT
        assertLastFrame(process(ap, pcm16(4800, 1000, 12000)), 12000, 1000)
        ap.mode = Mode.RIGHT
        assertLastFrame(process(ap, pcm16(4800, 1000, 12000)), 1000, 12000)
        assertLastFrame(process(ap, pcm16(4800, 12000, 1000)), 1000, 12000)
    }

    @Test
    fun seekingClearsPreviousDirectionAndPendingDecisions() {
        val ap = processor()
        process(ap, pcm16(4800, 1000, 12000))
        ap.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertLastFrame(process(ap, pcm16(960, 1000, 12000)), 1000, 12000)
        ap.reset()
        ap.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        ap.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertEquals(Mode.LEFT, ap.mode)
        assertLastFrame(process(ap, pcm16(960, 1000, 12000)), 1000, 12000)
    }

    @Test
    fun monoAndSurroundAreInactiveAndUnknownPreferenceDefaultsToOff() {
        for (channels in listOf(1, 6)) {
            val ap = EarPreferenceAudioProcessor()
            assertEquals(AudioProcessor.AudioFormat.NOT_SET,
                ap.configure(AudioProcessor.AudioFormat(48000, channels, C.ENCODING_PCM_16BIT)))
            assertFalse(ap.isActive)
        }
        assertEquals(Mode.OFF, Mode.fromPreference(null))
        assertEquals(Mode.OFF, Mode.fromPreference("unknown"))
        assertEquals(Mode.LEFT, Mode.fromPreference("left"))
        assertEquals(Mode.RIGHT, Mode.fromPreference("right"))
    }

    @Test
    fun crossfadeIsContinuousAndDoesNotIncreasePeakAmplitude() {
        val output = process(processor(), pcm16(4800, -8000, 16000))
        var previousLeft = -8000
        var previousRight = 16000
        while (output.hasRemaining()) {
            val left = output.short.toInt()
            val right = output.short.toInt()
            assertTrue(left in -8000..16000)
            assertTrue(right in -8000..16000)
            assertTrue(abs(left - previousLeft) <= 51)
            assertTrue(abs(right - previousRight) <= 51)
            previousLeft = left
            previousRight = right
        }
    }

    @Test
    fun allPcmEncodingsDetectDominanceAndPreserveSwappedSampleBytes() {
        val encodings = listOf(
            C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN,
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN,
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN,
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_DOUBLE
        )
        for (encoding in encodings) {
            val format = AudioProcessor.AudioFormat(48000, 2, encoding)
            val sampleBytes = format.bytesPerFrame / 2
            val input = ByteBuffer.allocateDirect(4800 * format.bytesPerFrame)
                .order(ByteOrder.nativeOrder())
            repeat(4800) {
                putSample(input, encoding, -0.125, sampleBytes)
                putSample(input, encoding, 0.5, sampleBytes)
            }
            input.flip()
            val sourceFrame = ByteArray(format.bytesPerFrame)
            input.duplicate().get(sourceFrame)
            val expected = sourceFrame.copyOfRange(sampleBytes, format.bytesPerFrame) +
                sourceFrame.copyOfRange(0, sampleBytes)
            val ap = processor(encoding = encoding)
            val output = process(ap, input)
            output.position(output.limit() - format.bytesPerFrame)
            assertArrayEquals("encoding=$encoding", expected, bytes(output))
        }
    }

    @Test
    fun unsigned8BitSilenceIsCentredAt128() {
        val input = ByteBuffer.allocateDirect(9600).apply {
            repeat(9600) { put(128.toByte()) }
            flip()
        }
        assertArrayEquals(bytes(input), bytes(process(processor(encoding = C.ENCODING_PCM_8BIT), input)))
    }

    @Test
    fun sharedEmptyBufferBeforeFirstPcmDoesNotFailInAnyMode() {
        for (mode in Mode.entries) {
            val ap = processor(mode)
            ap.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertEquals(0, ap.output.remaining())
            ap.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertEquals(0, ap.output.remaining())
            assertLastFrame(process(ap, pcm16(4800, 1000, 12000)),
                if (mode == Mode.LEFT) 12000 else 1000,
                if (mode == Mode.LEFT) 1000 else 12000)
        }
    }

    @Test
    fun playbackPipelineCanDrainBeforeFirstPcmAndAtEndOfStream() {
        for (mode in Mode.entries) {
            val earPreference = EarPreferenceAudioProcessor().apply { this.mode = mode }
            val pipeline = AudioProcessingPipeline(ImmutableList.of<AudioProcessor>(
                ReplayGainAudioProcessor(), earPreference
            ))
            pipeline.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
            pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
            assertEquals(0, pipeline.output.remaining())
            pipeline.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertEquals(0, pipeline.output.remaining())
            val input = pcm16(4800, 1000, 12000)
            pipeline.queueInput(input)
            assertFalse(input.hasRemaining())
            val output = pipeline.output
            assertEquals(4800 * 4, output.remaining())
            assertLastFrame(output,
                if (mode == Mode.LEFT) 12000 else 1000,
                if (mode == Mode.LEFT) 1000 else 12000)
            output.position(output.limit())
            pipeline.queueEndOfStream()
            assertEquals(0, pipeline.output.remaining())
            assertTrue(pipeline.isEnded)
        }
    }

    @Test
    fun emptyInputAndEndOfStreamDoNotProduceExtraFrames() {
        val ap = processor()
        assertEquals(0, process(ap, pcm16(0, 0, 0)).remaining())
        ap.queueEndOfStream()
        assertEquals(0, ap.output.remaining())
        assertTrue(ap.isEnded)
    }

    private fun putSample(output: ByteBuffer, encoding: Int, sample: Double, size: Int) {
        when (encoding) {
            C.ENCODING_PCM_8BIT -> output.put((sample * 128 + 128).toInt().toByte())
            C.ENCODING_PCM_FLOAT -> output.putFloat(sample.toFloat())
            C.ENCODING_PCM_DOUBLE -> output.putDouble(sample)
            else -> {
                val bigEndian = encoding in listOf(C.ENCODING_PCM_16BIT_BIG_ENDIAN,
                    C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN)
                val value = (sample * (1L shl (size * 8 - 1))).toLong()
                for (i in 0 until size) {
                    output.put((value shr ((if (bigEndian) size - 1 - i else i) * 8)).toByte())
                }
            }
        }
    }
}
