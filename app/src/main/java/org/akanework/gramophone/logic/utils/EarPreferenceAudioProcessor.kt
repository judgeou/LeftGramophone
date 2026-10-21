/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * Gramophone is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * Gramophone is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

package org.akanework.gramophone.logic.utils

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.Log
import org.akanework.gramophone.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToLong

/** Routes the louder side of stereo PCM to the preferred ear, without changing its format. */
class EarPreferenceAudioProcessor : BaseAudioProcessor() {
    enum class Mode(val preferenceValue: String) {
        OFF("off"), LEFT("left"), RIGHT("right");

        companion object {
            fun fromPreference(value: String?) = entries.firstOrNull {
                it.preferenceValue == value
            } ?: OFF
        }
    }

    // Written by the preference listener; all detection and mixing state belongs to playback.
    @Volatile
    var mode = Mode.OFF

    private var appliedMode = Mode.OFF
    private var sampleBytes = 0
    private var bigEndian = false
    private var windowFrames = 1
    private var fadeFrames = 1
    private var framesInWindow = 0
    private var leftEnergy = 0.0
    private var rightEnergy = 0.0
    private var candidateSwapped = false
    private var candidateWindows = 0
    private var targetSwapped = false
    private var swapMix = 0.0
    private val partialFrame = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Keep supported stereo streams in the chain even when OFF, for live preference changes.
        // Mono and surround have no unambiguous pair of ears and pass through unchanged.
        return if (inputAudioFormat.channelCount == 2 &&
            inputAudioFormat.sampleRate > 0 && inputAudioFormat.encoding in SUPPORTED_ENCODINGS
        ) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        sampleBytes = inputAudioFormat.bytesPerFrame / 2
        bigEndian = inputAudioFormat.encoding in BIG_ENDIAN_ENCODINGS
        windowFrames = max(1, inputAudioFormat.sampleRate / 50) // 20 ms
        fadeFrames = max(1, inputAudioFormat.sampleRate / 100) // 10 ms
        partialFrame.clear()
        partialFrame.limit(inputAudioFormat.bytesPerFrame.coerceIn(0, partialFrame.capacity()))
        resetDetection()
        targetSwapped = false
        swapMix = 0.0
        appliedMode = mode
        logRouting("configured", mode)
    }

    override fun onReset() {
        resetDetection()
        targetSwapped = false
        swapMix = 0.0
        partialFrame.clear()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 drains the chain with the shared EMPTY_BUFFER before the first PCM frame.
        // BaseAudioProcessor also starts with that buffer, so replacing/copying it would self-copy.
        if (!inputBuffer.hasRemaining()) return
        val currentMode = mode
        if (appliedMode != currentMode) {
            resetDetection()
            targetSwapped = false
            appliedMode = currentMode
            logRouting("mode changed", currentMode)
        }
        val frameBytes = inputAudioFormat.bytesPerFrame
        val output = replaceOutputBuffer(
            (partialFrame.position() + inputBuffer.remaining()) / frameBytes * frameBytes
        )
        if (currentMode == Mode.OFF && swapMix == 0.0 && partialFrame.position() == 0 &&
            inputBuffer.remaining() % frameBytes == 0) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        // A decoder buffer may end mid-frame. Retain those bytes instead of stalling or dropping them.
        if (partialFrame.position() > 0) {
            while (inputBuffer.hasRemaining() && partialFrame.hasRemaining()) {
                partialFrame.put(inputBuffer.get())
            }
            if (!partialFrame.hasRemaining()) {
                partialFrame.flip()
                processFrame(partialFrame, output, 0, currentMode)
                partialFrame.clear()
                partialFrame.limit(frameBytes)
            }
        }
        while (inputBuffer.remaining() >= frameBytes) {
            val offset = inputBuffer.position()
            processFrame(inputBuffer, output, offset, currentMode)
            inputBuffer.position(offset + frameBytes)
        }
        while (inputBuffer.hasRemaining()) partialFrame.put(inputBuffer.get())
        output.flip()
    }

    private fun processFrame(input: ByteBuffer, output: ByteBuffer, offset: Int, currentMode: Mode) {
        val left = readSample(input, offset)
        val right = readSample(input, offset + sampleBytes)
        if (currentMode != Mode.OFF) {
            // Always measure the ORIGINAL channels, so swapping cannot feed back into detection.
            leftEnergy += if (left.isFinite()) left * left else 0.0
            rightEnergy += if (right.isFinite()) right * right else 0.0
            framesInWindow++
            if (framesInWindow == windowFrames) finishWindow(currentMode)
        }
        val step = 1.0 / fadeFrames
        swapMix = if (targetSwapped) (swapMix + step).coerceAtMost(1.0)
            else (swapMix - step).coerceAtLeast(0.0)
        when (swapMix) {
            0.0 -> {
                copySample(input, output, offset)
                copySample(input, output, offset + sampleBytes)
            }
            1.0 -> {
                copySample(input, output, offset + sampleBytes)
                copySample(input, output, offset)
            }
            else -> {
                // A convex crossfade avoids an abrupt waveform discontinuity or added gain.
                writeSample(output, left * (1.0 - swapMix) + right * swapMix)
                writeSample(output, right * (1.0 - swapMix) + left * swapMix)
            }
        }
    }

    private fun finishWindow(currentMode: Mode) {
        val preferred = if (currentMode == Mode.LEFT) leftEnergy else rightEnergy
        val other = if (currentMode == Mode.LEFT) rightEnergy else leftEnergy
        val decision = when {
            // Ignore windows below -60 dBFS RMS, and hold the current routing near the centre.
            max(preferred, other) < MIN_MEAN_SQUARE * framesInWindow -> null
            other > preferred * DOMINANCE_ENERGY_RATIO -> true
            preferred > other * DOMINANCE_ENERGY_RATIO -> false
            else -> null
        }
        if (decision == null || decision == targetSwapped) {
            candidateWindows = 0
        } else {
            if (decision != candidateSwapped) {
                candidateSwapped = decision
                candidateWindows = 0
            }
            if (++candidateWindows >= REQUIRED_WINDOWS) {
                targetSwapped = decision
                logRouting("routing changed", currentMode)
                candidateWindows = 0
            }
        }
        framesInWindow = 0
        leftEnergy = 0.0
        rightEnergy = 0.0
    }

    private fun resetDetection() {
        framesInWindow = 0
        leftEnergy = 0.0
        rightEnergy = 0.0
        candidateSwapped = false
        candidateWindows = 0
    }

    private fun logRouting(reason: String, currentMode: Mode) {
        if (BuildConfig.DEBUG) Log.d("AsmrRouting", "$reason: mode=$currentMode, swapped=$targetSwapped, " +
            "sampleRate=${inputAudioFormat.sampleRate}, encoding=${inputAudioFormat.encoding}")
    }

    private fun copySample(input: ByteBuffer, output: ByteBuffer, offset: Int) {
        // Preserve exact sample bytes outside a transition, including high resolution PCM.
        for (i in 0 until sampleBytes) output.put(input.get(offset + i))
    }

    private fun readSample(input: ByteBuffer, offset: Int): Double = when (inputAudioFormat.encoding) {
        C.ENCODING_PCM_8BIT -> ((input.get(offset).toInt() and 0xff) - 128) / 128.0
        C.ENCODING_PCM_FLOAT -> input.getFloat(offset).toDouble()
        C.ENCODING_PCM_DOUBLE -> input.getDouble(offset)
        else -> {
            var value = 0
            for (i in 0 until sampleBytes) {
                val index = if (bigEndian) offset + i else offset + sampleBytes - 1 - i
                value = (value shl 8) or (input.get(index).toInt() and 0xff)
            }
            val shift = 32 - sampleBytes * 8
            (value shl shift shr shift) / (1L shl (sampleBytes * 8 - 1)).toDouble()
        }
    }

    private fun writeSample(output: ByteBuffer, sample: Double) {
        val finiteSample = if (sample.isFinite()) sample else 0.0
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_8BIT -> output.put(
                (finiteSample * 128.0 + 128.0).roundToLong().coerceIn(0, 255).toByte()
            )
            C.ENCODING_PCM_FLOAT -> output.putFloat(finiteSample.toFloat())
            C.ENCODING_PCM_DOUBLE -> output.putDouble(finiteSample)
            else -> {
                val scale = 1L shl (sampleBytes * 8 - 1)
                val value = (finiteSample * scale).roundToLong().coerceIn(-scale, scale - 1)
                for (i in 0 until sampleBytes) {
                    val shift = (if (bigEndian) sampleBytes - 1 - i else i) * 8
                    output.put((value shr shift).toByte())
                }
            }
        }
    }

    companion object {
        private const val DOMINANCE_ENERGY_RATIO = 2.0 // approximately 3 dB
        private const val MIN_MEAN_SQUARE = 0.000001
        private const val REQUIRED_WINDOWS = 3 // 60 ms of sustained dominance
        private val BIG_ENDIAN_ENCODINGS = setOf(
            C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT_BIG_ENDIAN,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN
        )
        private val SUPPORTED_ENCODINGS = BIG_ENDIAN_ENCODINGS + setOf(
            C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_DOUBLE
        )
    }
}
