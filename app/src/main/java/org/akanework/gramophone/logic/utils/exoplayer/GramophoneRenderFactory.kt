/*
 *     Copyright (C) 2024 nift4
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.logic.utils.exoplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import org.akanework.gramophone.logic.utils.EarPreferenceAudioProcessor
import org.akanework.gramophone.logic.utils.PostAmpAudioOutputProvider
import org.akanework.gramophone.logic.utils.ReplayGainAudioProcessor
import org.nift4.alacdecoder.AlacRenderer

class GramophoneRenderFactory(
    context: Context,
    private val rgAp: ReplayGainAudioProcessor,
    private val earPreferenceAp: EarPreferenceAudioProcessor,
    private val configurationListener: (Format?) -> Unit,
    private val audioSinkListener: (PostAmpAudioOutputProvider) -> Unit
) :
    DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        // empty
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: @ExtensionRendererMode Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: java.util.ArrayList<Renderer>
    ) {
        out.add(AlacRenderer(eventHandler, eventListener, audioSink))
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: java.util.ArrayList<Renderer>
    ) {
        // empty
    }

    override fun buildImageRenderers(context: Context, out: java.util.ArrayList<Renderer>) {
        // empty
    }

    override fun buildCameraMotionRenderers(
        context: Context,
        extensionRendererMode: Int,
        out: java.util.ArrayList<Renderer>
    ) {
        // empty
    }

    override fun buildAudioSink(
        context: Context,
        pcmEncodingRestrictionLifted: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
        val aop = PostAmpAudioOutputProvider(
            ExtendedAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context)
                .setMaxPlaybackSpeed(4f).build()), rgAp, context)
        builder.setAudioOutputProvider(aop)
        builder.setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
        builder.setEnableHighResolutionPcmOutput(pcmEncodingRestrictionLifted)
        builder.setAudioProcessorChain(object : AudioProcessorChain {
            override fun getAudioProcessors(inputFormat: Format): Array<out AudioProcessor> {
                rgAp.setRootFormat(inputFormat)
                return arrayOf(rgAp, earPreferenceAp)
            }

            override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
                return PlaybackParameters.DEFAULT
            }

            override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
                return false
            }

            override fun getMediaDuration(playoutDuration: Long): Long {
                return playoutDuration
            }

            override fun getSkippedOutputFrameCount(): Long {
                return 0
            }
        })
        audioSinkListener(aop)
        return MyForwardingAudioSink(builder.build())
    }

    inner class MyForwardingAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
        override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
            super.configure(audioSinkConfig)
            configurationListener(audioSinkConfig.format)
        }

        override fun reset() {
            super.reset()
            configurationListener(null)
        }

        override fun release() {
            super.release()
            configurationListener(null)
        }
    }
}
