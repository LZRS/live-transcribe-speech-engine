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

import com.google.common.flogger.FluentLogger
import java.util.Arrays
import kotlin.math.min

/**
 * A storage unit for reading chunks of bytes at a time. Only a single writer may populate
 * data in the CircularByteBuffer, but multiple asynchronous reads are supported as long as the
 * write pointer doesn't overlap with any of the read pointers. If this fails to be true, the output
 * bytes may be corrupted.
 */
class CircularByteBuffer(val capacity: Int) {
    private val buffer: ByteArray = ByteArray(capacity)
    private var writeHead = 0
    private var cumulativeWritten = 0
    private val allReaders = ArrayList<Reader>()

    /** Reads from a CircularBuffer by maintaining its own position.  */
    class Reader(private val owner: CircularByteBuffer) {
        private var readHead = 0
        private var cumulativeRead: Long = 0

        // Can be used to see if samples were lost prior to most recent read.
        // If so many samples have been written that the write pointer overlaps one of the
        // read pointers, samples will be dropped. This is logged in read(), but you can also,
        // as the caller of read(), check whether samples were lost by checking to see if
        // droppedSamplesWarning is true. For example, the following lines would fail an assertion if
        // data were lost:
        //   reader.read(some_buffer);
        //   assert reader.droppedSamplesWarning;
        // This value is set, but never read, by the CircularByteBuffer or its Readers.
        var droppedSamplesWarning: Boolean = false

        /**
         * Read, updating the read pointer. numBytes must be less than buffer capacity.
         * @return true on success.
         */
        @JvmOverloads
        fun read(dst: ByteArray, offset: Int = 0, numBytes: Int = dst.size): Boolean {
            if (owner.read(this, dst, offset, numBytes)) {
                cumulativeRead += numBytes.toLong()
                return true
            }
            return false
        }

        /**
         * Read, without updating the read pointer. numBytes must be less than buffer capacity.
         * @return true on success.
         */
        @JvmOverloads
        fun peek(dst: ByteArray, offset: Int = 0, numBytes: Int = dst.size): Boolean {
            return owner.peek(this, dst, offset, numBytes)
        }

        fun advance(advanceBy: Int) {
            readHead = (readHead + advanceBy) % owner.capacity
        }

        fun availableBytes(): Int {
            return min(
                owner.capacity.toDouble(),
                (owner.getCumulativeWritten() - cumulativeRead).toInt().toDouble()
            ).toInt()
        }


        private fun reset() {
            readHead = 0
            cumulativeRead = 0
        }
    }

    /**
     * Get a reader for the circular buffer. You may use several of these independently.
     * You should call this before the first call to write() occurring after construction or
     * calling reset(). Otherwise, you may start reading from somewhere in the middle of the stream.
     */
    fun newReader(): Reader {
        synchronized(allReaders) {
            val reader = Reader(this)
            allReaders.add(reader)
            return reader
        }
    }

    /** Copy data from src into the circular buffer. Returns false when bytesToWrite
     * exceeds capacity.
     */
    @Synchronized
    fun write(src: ByteArray, offset: Int, bytesToWrite: Int): Boolean {
        if (bytesToWrite > capacity) {
            return false
        }
        if (bytesToWrite == 0) {
            return true
        }
        if (writeHead + bytesToWrite <= capacity) {
            System.arraycopy(src, offset, buffer, writeHead, bytesToWrite)
        } else {  // Data wraps around buffer edge.
            val entriesBeforeWrap = capacity - writeHead
            System.arraycopy(src, offset, buffer, writeHead, entriesBeforeWrap)
            System.arraycopy(
                src, offset + entriesBeforeWrap, buffer, 0, bytesToWrite - entriesBeforeWrap
            )
        }
        writeHead = (writeHead + bytesToWrite) % capacity
        cumulativeWritten += bytesToWrite
        return true
    }

    fun write(src: ByteArray): Boolean {
        return write(src, 0, src.size)
    }

    /**
     * Resets the circular buffer and all of the readers that have been issued. The class is reset to
     * its initial state upon construction. Readers that have been issued do not get removed.
     */
    @Synchronized
    fun reset() {
        Arrays.fill(buffer, 0.toByte())
        writeHead = 0
        cumulativeWritten = 0
        synchronized(allReaders) {
            for (reader in allReaders) {
                reader.reset()
            }
        }
    }

    /**
     * Read data from the buffer and update the read pointer. If the reader is far
     * enough behind (the write pointer passes the read pointer), samples will be dropped to catch up.
     */
    private fun read(reader: Reader, dst: ByteArray, offset: Int, numBytes: Int): Boolean {
        reader.droppedSamplesWarning = false
        if (cumulativeWritten - reader.cumulativeRead > capacity) {
            CircularByteBuffer.Companion.logger.atSevere().log("We lost data before this read!")
            // Skip ahead to the very end of the buffer.
            val skipAmount = (cumulativeWritten - reader.cumulativeRead).toInt() - numBytes
            reader.advance(skipAmount)
            reader.cumulativeRead += skipAmount.toLong()
            reader.droppedSamplesWarning = true
        }
        if (peek(reader, dst, offset, numBytes)) {
            reader.advance(numBytes)
            return true
        }
        return false
    }

    /**
     * Read data from the buffer without modifying the reader. Note that if the
     * reader is far enough behind, this will return corrupted data.
     */
    private fun peek(reader: Reader, dst: ByteArray, offset: Int, numBytes: Int): Boolean {
        val bytesToRead = numBytes
        if (bytesToRead == 0) {
            return true
        }
        if (bytesToRead < 0 || bytesToRead > reader.availableBytes()) {
            return false
        }
        val endOfReadSection = (reader.readHead + bytesToRead) % capacity
        if (reader.readHead < endOfReadSection) {
            System.arraycopy(
                buffer,
                reader.readHead,
                dst,
                offset,
                endOfReadSection - reader.readHead
            )
        } else {  // Data wraps around buffer edge.
            System.arraycopy(buffer, reader.readHead, dst, offset, capacity - reader.readHead)
            val entriesBeforeWrap = capacity - reader.readHead
            System.arraycopy(
                buffer, 0, dst, offset + capacity - reader.readHead, bytesToRead - entriesBeforeWrap
            )
        }
        return true
    }

    @Synchronized
    fun getCumulativeWritten(): Long {
        return cumulativeWritten.toLong()
    }

    companion object {
        private val logger: FluentLogger = FluentLogger.forEnclosingClass()
    }
}
