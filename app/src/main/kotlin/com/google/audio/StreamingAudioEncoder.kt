/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.audio

import android.media.MediaCodec
import android.media.MediaCodec.CodecException
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.flogger.FluentLogger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Converts from uncompressed 16-bit PCM data to encoded data.
 *
 *
 * You may call the sequence (init, processAudioBytes, ..., processAudioBytes, flush, stop)
 * multiple times.
 *
 *
 * Note that AMR-WB encoding is mandatory for handheld devices and OggOpus is supported
 * regardless of device.
 */
// Based on examples from https://developer.android.com/reference/android/media/MediaCodec
// and some reference tests:
// https://android.googlesource.com/platform/cts/+/jb-mr2-release/tests/tests/media/src/android/media/cts/EncoderTest.java
class StreamingAudioEncoder @JvmOverloads constructor(useDeprecatedEncoder: Boolean = VERSION.SDK_INT <= VERSION_CODES.KITKAT_WATCH) {
    // This is not meaningful for OggOpus, which does not rely on the AndroidSystemEncoder.
    private var useDeprecatedEncoder = false

    /** State variables for basic control flow management.  */
    private var flushed = false

    private var initialized = false

    /** An exception for anything that goes wrong with the coder as a result of misuse.  */
    class EncoderException(message: String?) : Exception(message)

    /** Describes the general class of codecs.  */
    enum class CodecType {
        UNSPECIFIED,
        AMRWB,
        FLAC,
        OGG_OPUS,
    }

    var codecType: CodecType = CodecType.UNSPECIFIED

    private var impl: StreamingAudioInternalEncoder? = null

    private interface StreamingAudioInternalEncoder {
        @Throws(EncoderException::class, IOException::class)
        fun init(sampleRateHz: Int, codecAndBitrate: CodecAndBitrate?, useVbr: Boolean)

        fun processAudioBytes(input: ByteArray?, offset: Int, length: Int): ByteArray?

        fun flushAndStop(): ByteArray?
    }

    /**
     * Prepares a codec to stream. This may be called only if instance is uninitialized (prior to a
     * call to init() or after a call to stop()).
     *
     * @throws IOException if codec cannot be created.
     * @throws EncoderException if sample rate is not 16kHz or if no suitable encoder exists on device
     * for the requested format.
     */
    @Throws(EncoderException::class, IOException::class)
    fun init(sampleRateHz: Int, codecAndBitrate: CodecAndBitrate, allowVbr: Boolean) {
        codecType = StreamingAudioEncoder.Companion.lookupCodecType(codecAndBitrate)

        if (codecType == CodecType.OGG_OPUS) {
            impl = OggOpusEncoder()
        } else {
            impl = AndroidSystemEncoder(useDeprecatedEncoder)
        }
        impl!!.init(sampleRateHz, codecAndBitrate, allowVbr)
        initialized = true
        flushed = false
    }

    /**
     * Encodes 16-bit PCM audio. This will not always return bytes and will block until the codec has
     * no output to offer. Must be called after init().
     *
     * @param input array of audio samples formatted as raw bytes (i.e., two bytes per sample). buffer
     * may be of any size.
     * @param offset the offset of the first byte to process
     * @param length the number of bytes to process from input
     * @return bytes of compressed audio
     */
    fun processAudioBytes(input: ByteArray?, offset: Int, length: Int): ByteArray? {
        check(initialized) { "You forgot to call init()!" }
        check(!flushed) { "Cannot process more bytes after flushing." }
        return impl!!.processAudioBytes(input, offset, length)
    }

    fun processAudioBytes(input: ByteArray): ByteArray? {
        return processAudioBytes(input, 0, input.size)
    }

    /** Stop the codec. Call init() before using again.  */
    fun flushAndStop(): ByteArray? {
        check(initialized) { "You forgot to call init()!" }
        check(!flushed) { "Already flushed. You must reinitialize." }
        flushed = true
        val flushedBytes = impl!!.flushAndStop()
        initialized = false
        codecType = CodecType.UNSPECIFIED
        return flushedBytes
    }

    /** An encoder that relies on the Android framework's multimedia encoder.  */
    private class AndroidSystemEncoder(useDeprecatedEncoder: Boolean) :
        StreamingAudioInternalEncoder {
        /**
         * Notes when the codec formatting change has occurred. This should happen only once at the
         * start of streaming. Otherwise, there is an error.
         */
        private var formatChangeReportedOnce = false

        private var codec: MediaCodec? = null
        private var useDeprecatedEncoder = false
        private var codecType: CodecType
        private val sampleRateHz = 0

        /** Prevents trying to flush multiple times.  */
        private var successfullyFlushed = false

        /** Keeps track of whether the header was injected into the stream.  */
        private var addedHeader = false

        // Used only on very old SDKs (pre VERSION_CODES.KITKAT_WATCH).
        private var inputBuffersPreKitKat: Array<ByteBuffer>
        private var outputBuffersPreKitKat: Array<ByteBuffer>

        /** Creates an audio encoder.  */
        init {
            this.useDeprecatedEncoder = useDeprecatedEncoder
            this.codecType = CodecType.UNSPECIFIED
        }

        // Note that VBR is not currently supported for the AndroidStreamingEncoder.
        @Throws(EncoderException::class, IOException::class)
        override fun init(sampleRateHz: Int, codecAndBitrate: CodecAndBitrate, allowVbr: Boolean) {
            codecType = StreamingAudioEncoder.Companion.lookupCodecType(codecAndBitrate)
            if (codecType == CodecType.UNSPECIFIED || codecType == CodecType.OGG_OPUS) {
                throw EncoderException("Codec not set properly.")
            }
            if (codecType == CodecType.AMRWB && sampleRateHz != 16000) {
                throw EncoderException("AMR-WB encoder requires a sample rate of 16kHz.")
            }
            val codecInfo: MediaCodecInfo? =
                StreamingAudioEncoder.Companion.searchAmongAndroidSupportedCodecs(
                    StreamingAudioEncoder.Companion.getMime(codecType)
                )
            if (codecInfo == null) {
                throw EncoderException("Encoder not found.")
            }
            this.codec = MediaCodec.createByCodecName(codecInfo.getName())

            val format =
                AndroidSystemEncoder.Companion.getMediaFormat(codecAndBitrate, sampleRateHz)
            codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec!!.start()
            initBuffers()

            addedHeader = false
            successfullyFlushed = false
            formatChangeReportedOnce = false
        }

        override fun processAudioBytes(input: ByteArray, offset: Int, length: Int): ByteArray? {
            val outputBytes = ByteArrayOutputStream()
            if (!addedHeader) {
                try {
                    outputBytes.write(this.headerBytes)
                } catch (e: IOException) {
                    StreamingAudioEncoder.Companion.logger.atSevere()
                        .log("Unable to write bytes into buffer!")
                }
                addedHeader = true
            }
            var startByte = 0
            while (startByte < length) {
                val thisChunkSizeBytes = min(
                    AndroidSystemEncoder.Companion.CHUNK_SIZE_BYTES.toDouble(),
                    (length - startByte).toDouble()
                ).toInt()
                processAudioBytesInternal(
                    input, offset + startByte, thisChunkSizeBytes, false, outputBytes
                )
                startByte += thisChunkSizeBytes
            }
            return outputBytes.toByteArray()
        }

        override fun flushAndStop(): ByteArray? {
            val outputBytes = ByteArrayOutputStream()
            try {
                processAudioBytesInternal(null, 0, 0, true, outputBytes) // Flush!
                codec!!.stop()
            } catch (e: CodecException) {
                StreamingAudioEncoder.Companion.logger.atSevere()
                    .log("Something went wrong in the underlying codec!")
            }
            codec!!.release()
            return outputBytes.toByteArray()
        }

        // length must be less than or equal to CHUNK_SIZE_BYTES.
        fun processAudioBytesInternal(
            input: ByteArray,
            offset: Int,
            length: Int,
            flush: Boolean,
            outputBytes: ByteArrayOutputStream
        ) {
            Preconditions.checkArgument(
                length <= AndroidSystemEncoder.Companion.CHUNK_SIZE_BYTES,
                "length must be less than or equal to CHUNK_SIZE_BYTES!"
            )
            var processedInput = false
            // There are a limited number of buffers allocated in the codec. As long as we're not
            // holding on to them, they should always be available. Sometimes all buffers will be occupied
            // by the output and we need to process them before pushing input. Sometimes multiple output
            // buffers will be available at once. Append them together and return. It is common for
            // outputBytes to not receive any samples upon returning.
            val bufferInfo = MediaCodec.BufferInfo()
            // Loop until input is processed and outputs are unavailable.
            while (!processedInput || flush) {
                if (!processedInput) {
                    check(!(flush && successfullyFlushed)) { "Already flushed!" }
                    // Push the input only once.
                    val inputBufferIndex =
                        codec!!.dequeueInputBuffer(AndroidSystemEncoder.Companion.WAIT_TIME_MICROSECONDS)
                    if (inputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (flush) {
                            // Signal that the input stream is complete.
                            codec!!.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            successfullyFlushed = true
                        } else {
                            // Push audio data into the codec.
                            val inputBuffer = getInputBuffer(inputBufferIndex)
                            inputBuffer.put(input, offset, length)
                            codec!!.queueInputBuffer(inputBufferIndex, 0, length, 0, 0)
                        }
                        processedInput = true
                    }
                }
                // See if outputs are available.
                val outputBufferIndex = codec!!.dequeueOutputBuffer(
                    bufferInfo,
                    AndroidSystemEncoder.Companion.WAIT_TIME_MICROSECONDS
                )
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // There will not be an output buffer for every input buffer.
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Shouldn't happen after the very first output.
                    check(!formatChangeReportedOnce) { "The codec format was unexpectedly changed." }
                    formatChangeReportedOnce = true
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // Shouldn't happen for SDK > 21.
                    updateOutputBuffers()
                } else {
                    // Get an output buffer and add it to the stream.
                    val outputBuffer = getOutputBuffer(outputBufferIndex)
                    val outData = ByteArray(bufferInfo.size)
                    outputBuffer.get(outData)
                    codec!!.releaseOutputBuffer(outputBufferIndex, false)
                    try {
                        outputBytes.write(outData)
                    } catch (e: IOException) {
                        StreamingAudioEncoder.Companion.logger.atSevere()
                            .log("Unable to write bytes into buffer!")
                    }
                }

                val processedAllOutput =
                    (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                if (processedAllOutput) {
                    check(processedInput) { "Didn't process input yet." }
                    break
                }
            }
        }

        val headerBytes: ByteArray?
            /** The data does not include a header. Some applications will require one anyhow.  */
            get() {
                when (this.codecType) {
                    CodecType.AMRWB -> {
                        val amrWbHeader = "#!AMR-WB\n"
                        return amrWbHeader.toByteArray()
                    }

                    CodecType.FLAC -> {
                        val noHeader = ByteArray(0)
                        return noHeader
                    }

                    CodecType.OGG_OPUS -> throw IllegalStateException("Should never happen! Use OggOpusEncoder instead.")
                    CodecType.UNSPECIFIED -> throw IllegalStateException("Trying to make header for unspecified codec!")
                }
                return null
            }

        // The following methods are used to resolve differences between SDK versions.
        fun initBuffers() {
            if (useDeprecatedEncoder) {
                inputBuffersPreKitKat = codec!!.getInputBuffers()
                outputBuffersPreKitKat = codec!!.getOutputBuffers()
            }
        }

        fun getInputBuffer(index: Int): ByteBuffer {
            if (useDeprecatedEncoder) {
                return inputBuffersPreKitKat[index]
            } else {
                return codec!!.getInputBuffer(index)!!
            }
        }

        fun getOutputBuffer(index: Int): ByteBuffer {
            if (useDeprecatedEncoder) {
                return outputBuffersPreKitKat[index]
            } else {
                return codec!!.getOutputBuffer(index)!!
            }
        }

        fun updateOutputBuffers() {
            if (useDeprecatedEncoder) {
                outputBuffersPreKitKat = codec!!.getOutputBuffers()
            }
        }

        companion object {
            // If we can't supply a buffer immediately, we wait until the next one, which is timed at the
            // microphone & block rate of the audio supplier. Waiting less than that time and getting
            // samples
            // before the next input buffer would reduce latency.
            private const val WAIT_TIME_MICROSECONDS: Long =
                1000 // Joda doesn't support microseconds.

            /**
             * The number of samples that are passed to the underlying codec at once. It's not clear that
             * one value for this will work better than any other, but powers of two are usually fast, and a
             * larger CHUNK_SIZE_SAMPLES both reduces the number of buffers we have to wait for and doesn't
             * prevent sending smaller blocks of samples.
             */
            private const val CHUNK_SIZE_SAMPLES = 2048

            private val CHUNK_SIZE_BYTES: Int =
                StreamingAudioEncoder.Companion.BYTES_PER_SAMPLE * AndroidSystemEncoder.Companion.CHUNK_SIZE_SAMPLES

            /** Configure the codec at a specified bitrate for a fixed sample block size.  */
            private fun getMediaFormat(
                codecAndBitrate: CodecAndBitrate,
                sampleRateHz: Int
            ): MediaFormat {
                val format = MediaFormat()
                val codecType: CodecType =
                    StreamingAudioEncoder.Companion.lookupCodecType(codecAndBitrate)
                format.setString(
                    MediaFormat.KEY_MIME,
                    StreamingAudioEncoder.Companion.getMime(codecType)
                )
                format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRateHz)
                format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
                format.setInteger(
                    MediaFormat.KEY_MAX_INPUT_SIZE,
                    StreamingAudioEncoder.Companion.BYTES_PER_SAMPLE * AndroidSystemEncoder.Companion.CHUNK_SIZE_SAMPLES
                )
                if (codecType != CodecType.FLAC) {
                    // FLAC is lossless, we can't request a bitrate.
                    format.setInteger(MediaFormat.KEY_BIT_RATE, codecAndBitrate.getNumber())
                }
                return format
            }
        }
    }

    private class OggOpusEncoder : StreamingAudioInternalEncoder {
        // This is a pointer to the native object that we're working with. Zero when unallocated.
        private var instance: Long = 0

        var validSampleRates: ImmutableList<Int?> =
            ImmutableList.of<Int?>(8000, 12000, 16000, 24000, 48000)

        @Throws(EncoderException::class)
        override fun init(sampleRateHz: Int, codecAndBitrate: CodecAndBitrate, allowVbr: Boolean) {
            if (instance != 0L) {
                flushAndStop()
            }
            val codecType: CodecType =
                StreamingAudioEncoder.Companion.lookupCodecType(codecAndBitrate)
            if (codecType != CodecType.OGG_OPUS) {
                throw RuntimeException("Made OggOpusEncoder for non OGG_OPUS encoding type.")
            }
            if (!validSampleRates.contains(sampleRateHz)) {
                throw EncoderException(
                    "Opus encoder requires a sample rate of 8kHz, 12kHz, 16kHz, 24kHz, or 48kHz."
                )
            }
            this.instance =
                init(1,  /* Mono audio. */codecAndBitrate.getNumber(), sampleRateHz, allowVbr)
        }

        external fun init(channels: Int, bitrate: Int, sampleRateHz: Int, allowVbr: Boolean): Long

        override fun processAudioBytes(bytes: ByteArray?, offset: Int, length: Int): ByteArray? {
            return processAudioBytes(instance, bytes, offset, length)
        }

        external fun processAudioBytes(
            instance: Long,
            samples: ByteArray?,
            offset: Int,
            length: Int
        ): ByteArray?

        /**
         * Complete the input stream, return any remaining bits of the output stream, and stop.
         * This should only be called once. Must be called after init().
         *
         * @return bytes of compressed audio
         */
        override fun flushAndStop(): ByteArray? {
            if (instance != 0L) {
                val flushedBytes = flush(instance)
                free(instance)
                instance = 0
                return flushedBytes
            } else {
                StreamingAudioEncoder.Companion.logger.atSevere()
                    .log("stop() called multiple times or without call to init()!")
                return ByteArray(0)
            }
        }

        @Throws(Throwable::class)
        protected fun finalize() {
            super.finalize()
            if (instance != 0L) {
                StreamingAudioEncoder.Companion.logger.atSevere().log(
                    "Native OggOpusEncoder resources weren't cleaned up. You must call stop()!"
                )
                free(instance)
            }
        }

        external fun flush(instance: Long): ByteArray?
        external fun free(instance: Long)
    }

    /** Creates an audio encoder.  */
    init {
        this.useDeprecatedEncoder = useDeprecatedEncoder
    }

    companion object {
        private val logger: FluentLogger = FluentLogger.forEnclosingClass()
        private const val BYTES_PER_SAMPLE = 2

        /**
         * Can be used to test if codec will work or not on a given device. This will always return the
         * same value no matter when you call it.
         */
        fun isEncoderSupported(encoderInfo: CodecAndBitrate): Boolean {
            val type: CodecType = StreamingAudioEncoder.Companion.lookupCodecType(encoderInfo)
            if (type == CodecType.OGG_OPUS) { // We support Opus directly via the OggOpusEncoder class.
                return true
            }
            return StreamingAudioEncoder.Companion.searchAmongAndroidSupportedCodecs(
                StreamingAudioEncoder.Companion.getMime(type)
            ) != null
        }

        private fun getMime(codecAndBitrate: CodecType): String {
            // MediaFormat.MIMETYPE_AUDIO_AMR_WB requires SDK >= 21.
            when (codecAndBitrate) {
                CodecType.AMRWB -> return "audio/amr-wb"
                CodecType.FLAC -> return "audio/flac"
                CodecType.OGG_OPUS, CodecType.UNSPECIFIED -> return ""
            }
            return ""
        }

        private fun lookupCodecType(codecAndBitrate: CodecAndBitrate): CodecType {
            when (codecAndBitrate) {
                CodecAndBitrate.AMRWB_BITRATE_6KBPS, CodecAndBitrate.AMRWB_BITRATE_8KBPS, CodecAndBitrate.AMRWB_BITRATE_12KBPS, CodecAndBitrate.AMRWB_BITRATE_14KBPS, CodecAndBitrate.AMRWB_BITRATE_15KBPS, CodecAndBitrate.AMRWB_BITRATE_18KBPS, CodecAndBitrate.AMRWB_BITRATE_19KBPS, CodecAndBitrate.AMRWB_BITRATE_23KBPS, CodecAndBitrate.AMRWB_BITRATE_24KBPS -> return CodecType.AMRWB
                CodecAndBitrate.FLAC -> return CodecType.FLAC
                CodecAndBitrate.OGG_OPUS_BITRATE_12KBPS, CodecAndBitrate.OGG_OPUS_BITRATE_16KBPS, CodecAndBitrate.OGG_OPUS_BITRATE_24KBPS, CodecAndBitrate.OGG_OPUS_BITRATE_32KBPS, CodecAndBitrate.OGG_OPUS_BITRATE_64KBPS, CodecAndBitrate.OGG_OPUS_BITRATE_96KBPS, CodecAndBitrate.OGG_OPUS_BITRATE_128KBPS -> return CodecType.OGG_OPUS
                CodecAndBitrate.UNDEFINED -> return CodecType.UNSPECIFIED
            }
            return CodecType.UNSPECIFIED
        }

        /**
         * Searches for a codec that implements the requested format conversion. Android framework encoder
         * only.
         */
        private fun searchAmongAndroidSupportedCodecs(mimeType: String?): MediaCodecInfo? {
            val numCodecs = MediaCodecList.getCodecCount()
            for (i in 0..<numCodecs) {
                val codecAndBitrate = MediaCodecList.getCodecInfoAt(i)
                if (!codecAndBitrate.isEncoder()) {
                    continue
                }
                val codecTypes = codecAndBitrate.getSupportedTypes()
                for (j in codecTypes.indices) {
                    if (codecTypes[j].equals(mimeType, ignoreCase = true)) {
                        return codecAndBitrate
                    }
                }
            }
            return null
        }
    }
}
