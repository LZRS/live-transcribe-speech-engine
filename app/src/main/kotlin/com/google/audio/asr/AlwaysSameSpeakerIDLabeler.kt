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
package com.google.audio.asr

import com.google.audio.SpeakerIDLabeler
import com.google.audio.SpeakerIdInfo
import org.joda.time.Instant

/** A diarizer that always reports the same speaker.  */
class AlwaysSameSpeakerIDLabeler(private val fixedInfo: SpeakerIdInfo?) : SpeakerIDLabeler {
    override fun setReferenceTimestamp(now: Instant?) {}

    override fun getSpeakerIDForTimeInterval(start: Instant?, end: Instant?): SpeakerIdInfo? {
        return fixedInfo
    }

    override fun init(blockSizeSamples: Int) {}

    override fun clearSpeakerIDTimestamps() {}

    override fun reset() {}

    override fun processAudioBytes(bytes: ByteArray?, offset: Int, length: Int) {}

    override fun stop() {}
}
