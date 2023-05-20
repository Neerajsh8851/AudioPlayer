package com.android.audioplayer

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class AudioPlayer: CoroutineScope {
    companion object { val TAG = AudioPlayer::class.simpleName}
    override val coroutineContext: CoroutineContext = Dispatchers.Default + SupervisorJob()

    /**
     * Play the raw Audio described by the mediaFormat. it plays the audio until inputChannel is open.
     * @param mediaFormat provided media format should have values for keys [MediaFormat.KEY_PCM_ENCODING] [MediaFormat.KEY_SAMPLE_RATE] and [MediaFormat.KEY_CHANNEL_COUNT]
     */
    fun audioPlayback(mediaFormat: MediaFormat, inputChannel: ReceiveChannel<BufferWrapper>) {
        val sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelConfig = if (mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val audioFormat = mediaFormat.getInteger("pcm-encoding")
        val trackBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        Log.d(TAG, "audio track buffer size = $trackBufferSize")

        val audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelConfig,
            audioFormat,
            trackBufferSize,
            AudioTrack.MODE_STREAM
        )

        audioTrack.play()
        launch {
            inputChannel.consumeAsFlow().collect {
                audioTrack.write(it.bytes, 0, it.info.size)
            }
        }
    }
}