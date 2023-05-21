package com.android.audioplayer

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
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




    fun playFromFile(file: File) {
        val mediaExtractor = MediaExtractor()
        mediaExtractor.setDataSource(file.absolutePath)

        var audioTrack = -1

        for (i in 0 until mediaExtractor.trackCount) {
            val format = mediaExtractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio")) {
                audioTrack = i
            }
        }

        if ((audioTrack >= 0).not()) {
            Log.d(TAG, "play: $file does not have any audio track")
            return
        }

        mediaExtractor.selectTrack(audioTrack)
        val audioFormat = mediaExtractor.getTrackFormat(audioTrack)

        val encodedSampleChannel = Channel<BufferWrapper>()
        playEncodedSamples(encodedSampleChannel, audioFormat)
        val avgEncodedSampleSize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        val byteBuffer = ByteBuffer.allocate(avgEncodedSampleSize)

        var len: Int
        launch {
            while (mediaExtractor.readSampleData(byteBuffer, 0).also { len = it } > 0) {
                val byteArray = ByteArray(len)
                byteBuffer.get(byteArray)
                mediaExtractor.advance()

                // send encoded sample to decoder
                encodedSampleChannel.send(BufferWrapper(byteArray, MediaCodec.BufferInfo().apply {
                    offset = 0
                    size = len
                    presentationTimeUs = mediaExtractor.sampleTime
                }))
            }

            encodedSampleChannel.close()
        }
    }

    fun playEncodedSamples(inputChannel: ReceiveChannel<BufferWrapper>, format: MediaFormat) {
        launch {
            val decoderOutputFormat = CompletableDeferred<MediaFormat>()
            val rawSampleChannel = audioDecoding(inputChannel, format) {decoderOutputFormat.complete(it)}
            audioPlayback(decoderOutputFormat.await(), rawSampleChannel)
        }
    }
}