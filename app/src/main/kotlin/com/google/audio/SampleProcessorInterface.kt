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

/** An interface for sending samples to an object.  */
interface SampleProcessorInterface {
    fun init(blockSizeSamples: Int)

    /**
     * Samples are PCM, 16-bit samples, formatted as a byte stream.
     */
    fun processAudioBytes(bytes: ByteArray?, offset: Int, length: Int)

    fun processAudioBytes(bytes: ByteArray) {
        processAudioBytes(bytes, 0, bytes.size)
    }

    /**
     * Call when you want the interface to stop playing. Playing may restart, so don't
     * deallocate resources here.
     */
    fun stop()
}
