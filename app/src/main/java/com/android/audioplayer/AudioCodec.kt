package com.android.audioplayer

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

class BufferWrapper(val bytes: ByteArray, val info: BufferInfo)

fun CoroutineScope.audioDecoding(
    inputChannel: ReceiveChannel<BufferWrapper>,
    mediaFormat: MediaFormat,
    onMediaFormat: (MediaFormat) -> Unit
): Channel<BufferWrapper> {
    val TAG = "audioDecoder"
    var decoder: MediaCodec? = null
    val timeout = 100_000L
    val outputChannel = Channel<BufferWrapper>()
    try {
        decoder = MediaCodec.createDecoderByType(mediaFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(mediaFormat, null, null, 0)
        decoder.start()

        Log.i(TAG, "input format = $mediaFormat")

        val job = launch(Dispatchers.Default) {
            inputChannel.consumeAsFlow().collect {
                val inBuffIx = decoder.dequeueInputBuffer(timeout)
                if (inBuffIx >= 0) {
                    Log.d(TAG, "input data size = ${it.bytes.size}")
                    val inputBuffer = decoder.getInputBuffer(inBuffIx)!!
                    inputBuffer.clear()
                    inputBuffer.put(it.bytes)
                    inputBuffer.flip()

                    decoder.queueInputBuffer(inBuffIx, 0, it.bytes.size, it.info.presentationTimeUs, 0)
                }

                val info  = MediaCodec.BufferInfo()
                val outBuffIx = decoder.dequeueOutputBuffer(info, timeout)
                if (outBuffIx >= 0) {
                    val outBuffer = decoder.getOutputBuffer(outBuffIx)!!
                    outBuffer.position(info.offset)
                    outBuffer.limit(info.size)

                    val byteArray = ByteArray(info.size)
                    outBuffer.get(byteArray)
                    outBuffer.clear()

                    Log.d(TAG, "decoded data size = ${info.size}")
                    outputChannel.send(BufferWrapper(byteArray, info))

                    decoder.releaseOutputBuffer(outBuffIx, false)
                } else if (outBuffIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    onMediaFormat(decoder.outputFormat)
                }
            }
        }

        job.invokeOnCompletion {
            decoder.run {
                stop()
                release()
                Log.d(TAG, "decoder is stopped")
            }
            outputChannel.close()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return outputChannel
}