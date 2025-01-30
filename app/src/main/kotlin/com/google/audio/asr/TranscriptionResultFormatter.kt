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

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.google.audio.asr.TranscriptionResult.Word
import com.google.audio.asr.TranscriptionResultFormatterOptions.SpeakerIndicationStyle
import com.google.audio.asr.TranscriptionResultFormatterOptions.TextColormap
import com.google.audio.asr.TranscriptionResultFormatterOptions.TranscriptColoringStyle
import com.google.common.base.Strings
import com.google.common.collect.ImmutableList
import com.google.common.collect.Lists
import org.joda.time.Duration
import java.util.ArrayDeque
import java.util.Deque

/**
 * Creates a colored transcript in the format of [SpannedString] from [ ] according to the configuration of [Options].
 *
 *
 * This class is not thread-safe. If you intend to use this from multiple threads, consider
 * SafeTranscriptionResultFormatter.
 */
class TranscriptionResultFormatter {
    /** Formatted text and the TranscriptionResult that produced it.  */
    private class CachedResult(
        var result: TranscriptionResult,
        var text: Spanned?,
        var leadingWhitespace: Spanned?
    ) {
        val formattedText: CharSequence?
            get() = TextUtils.concat(leadingWhitespace, text)
    }

    private var options: TranscriptionResultFormatterOptions? = null

    private var resultsDeque: Deque<CachedResult> = ArrayDeque<CachedResult>()

    private var currentHypothesis: TranscriptionResult? = null

    // A stored string of whitespace to add between extended silences.
    private var silenceLineBreak: String? = null

    // A stored string of whitespace to add between extended language switch.
    private var languageSwitchLineBreak: String? = null

    // A joda.org.Duration version of the options field of the same name.
    private var extendedSilenceDurationForLineBreaks: Duration? = null

    // The index of the last speaker contained in the most recently finalized result. -1 indicates
    // that no results have been seen.
    private var lastSpeakerId = -1

    constructor() {
        setOptions(noFormattingOptions()!!)
    }

    constructor(options: TranscriptionResultFormatterOptions) {
        setOptions(options)
        reset()
    }

    /**
     * Sets the formatter options, which may include settings of current hypotheses in italics or
     * color transcripts by confidence.
     */
    fun setOptions(options: TranscriptionResultFormatterOptions) {
        this.options = options.toBuilder().build()

        lastSpeakerId = -1
        // Prepare the whitespace string.
        silenceLineBreak = createLineBreakString(options.getNumExtendedSilenceLineBreaks())
        languageSwitchLineBreak = createLineBreakString(options.getNumLanguageSwitchLineBreaks())
        extendedSilenceDurationForLineBreaks =
            TimeUtil.convert(options.getExtendedSilenceDurationForLineBreaks())

        // Reformat the old list.
        val oldResultsDeque = resultsDeque
        resultsDeque = ArrayDeque<CachedResult>()
        for (oldResult in oldResultsDeque) {
            addFinalizedResult(oldResult.result)
        }
    }

    /**
     * Creates the line break string.
     *
     * @param lineBreakCount line break count in the string.
     * @return the line break string according to the lineBreakCount.
     */
    private fun createLineBreakString(lineBreakCount: Int): String {
        return Strings.repeat("\n", lineBreakCount)
    }

    /** Reset to initial state, before any calls to addFinalizedResult() or setCurrentHypothesis().  */
    fun reset() {
        resultsDeque.clear()
        lastSpeakerId = -1
        clearCurrentHypothesis()
    }

    /**
     * Commits a result to the final transcript.
     *
     *
     * NOTE: This does not clear the hypothesis. Users who get partial results (hypotheses) should
     * prefer calling setCurrentHypothesis(...) and then finalizeCurrentHypothesis().
     */
    fun addFinalizedResult(resultSingleUtterance: TranscriptionResult) {
        val lineBreak = obtainLineBreaksFromLastFinalizedResult(resultSingleUtterance)
        resultsDeque.add(
            CachedResult(
                resultSingleUtterance.toBuilder().build(),
                formatSingleFinalized(resultSingleUtterance, !lineBreak.isEmpty()),
                SpannedString.valueOf(lineBreak)
            )
        )
        lastSpeakerId =
            TranscriptionResultFormatter.Companion.getLastSpeakerIdTag(resultSingleUtterance)
    }

    /**
     * Removes the current hypothesis so that only the finalized results will be in the transcript.
     */
    fun clearCurrentHypothesis() {
        currentHypothesis = null
    }

    /**
     * Commits the currently stored hypothesis to the finalized text buffer and clears the hypothesis.
     *
     * @return true if it has results to finalize, otherwise false.
     */
    fun finalizeCurrentHypothesis(): Boolean {
        if (currentHypothesis == null) {
            return false
        }

        addFinalizedResult(currentHypothesis!!)
        clearCurrentHypothesis()
        return true
    }

    /**
     * Sets the estimate of the current text, this result is expected to change. Once it is done
     * changing, commit it, by passing it to addFinalizedResult().
     */
    fun setCurrentHypothesis(resultSingleUtterance: TranscriptionResult) {
        currentHypothesis = resultSingleUtterance.toBuilder().build()
    }

    val formattedTranscript: Spanned
        /** Returns the current finalized text with the hypothesis appended to the end.  */
        get() {
            val builder = SpannableStringBuilder()
            for (timestampedAndCachedResult in resultsDeque) {
                builder.append(timestampedAndCachedResult.formattedText)
            }
            builder.append(this.formattedHypothesis)

            return SpannedString(builder)
        }

    val mostRecentTranscriptSegment: Spanned
        /** Returns the latest sentence from transcription result.  */
        get() {
            val builder = SpannableStringBuilder()
            builder.append(this.formattedHypothesis)
            if (!TextUtils.isEmpty(builder)) {
                return SpannedString(builder)
            }

            if (!resultsDeque.isEmpty()) {
                val timestampedAndCachedResult = resultsDeque.getLast()
                builder.append(timestampedAndCachedResult.formattedText)
            }

            return SpannedString(builder)
        }

    val transcriptDuration: Duration
        /** Get the transcription's duration time.  */
        get() {
            if (resultsDeque.isEmpty()) {
                return Duration.ZERO
            }
            return Duration(
                TimeUtil.toInstant(resultsDeque.peekFirst().result.getStartTimestamp()),
                TimeUtil.toInstant(resultsDeque.peekLast().result.getEndTimestamp())
            )
        }

    private val formattedHypothesis: Spannable?
        get() {
            if (currentHypothesis == null) {
                return SpannableString("")
            }

            val spannableStringBuilder = SpannableStringBuilder()
            val lineBreak =
                obtainLineBreaksFromLastFinalizedResult(currentHypothesis!!)
            val precededByLineBreak = !lineBreak.isEmpty()
            if (precededByLineBreak) {
                spannableStringBuilder.append(lineBreak)
            }
            spannableStringBuilder.append(
                formatHypothesis(
                    currentHypothesis!!,
                    precededByLineBreak
                )
            )

            return SpannableString.valueOf(spannableStringBuilder)
        }

    private fun formatHypothesis(
        result: TranscriptionResult,
        precededByLineBreak: Boolean
    ): Spannable {
        val spannable = formatSingleFinalized(result, precededByLineBreak)
        if (options!!.getItalicizeCurrentHypothesis()) {
            spannable.setSpan(
                StyleSpan(Typeface.ITALIC),
                0,
                spannable.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    /** A function that maps a Word to a six digit hex color (e.g. #a0b341).  */
    private interface ColorByWordFunction {
        fun getColor(w: Word?): String
    }

    /**
     * Format a single result. precededByLineBreak is used to determine if a speaker indicator should
     * be added to reestablish context after a newline.
     */
    private fun formatSingleFinalized(
        result: TranscriptionResult, precededByLineBreak: Boolean
    ): Spannable {
        // Trim leading spaces, but ensure that there will be a space before the next word.
        var normalizedTranscript = result.getText().trim { it <= ' ' } + " "
        if (result.getWordLevelDetailList().isEmpty()) {
            // Process the transcript as a whole.
            var color: String? = ""
            when (options!!.getTranscriptColoringStyle()) {
                TranscriptColoringStyle.COLOR_BY_SPEAKER_ID -> color =
                    getColorFromSpeakerId(result.getSpeakerInfo().getSpeakerId())

                TranscriptColoringStyle.COLOR_BY_UTTERANCE_LEVEL_CONFIDENCE -> color =
                    getColorFromConfidence(result)

                TranscriptColoringStyle.COLOR_BY_WORD_LEVEL_CONFIDENCE, TranscriptColoringStyle.NO_COLORING, TranscriptColoringStyle.UNSPECIFIED_COLORING_STYLE -> color =
                    this.defaultColorFromTheme
            }
            if (options!!.getSpeakerIndicationStyle() == SpeakerIndicationStyle.SHOW_SPEAKER_NUMBER
                && (precededByLineBreak || result.getSpeakerInfo().getSpeakerId() != lastSpeakerId)
            ) {
                val requiresLineBreak = lastSpeakerId != -1 && !precededByLineBreak
                normalizedTranscript =
                    (TranscriptionResultFormatter.Companion.newSpeakerChevron(
                        result.getSpeakerInfo().getSpeakerId(), requiresLineBreak
                    )
                            + normalizedTranscript)
            }
            // Make sure the utterance ends in a trailing space so that words don't get merged together.
            return makeColoredString(normalizedTranscript, color)
        } else {
            // Process each word of the transcript separately.
            var colorFunction = ColorByWordFunction { w: Word? -> this.defaultColorFromTheme }
            when (options!!.getTranscriptColoringStyle()) {
                TranscriptColoringStyle.COLOR_BY_WORD_LEVEL_CONFIDENCE -> colorFunction =
                    ColorByWordFunction { word: Word? ->
                        getColorFromConfidence(
                            word!!.getConfidence()
                        )
                    }

                TranscriptColoringStyle.COLOR_BY_UTTERANCE_LEVEL_CONFIDENCE -> colorFunction =
                    ColorByWordFunction { word: Word? -> getColorFromConfidence(result) } // Word-independent.
                TranscriptColoringStyle.COLOR_BY_SPEAKER_ID -> colorFunction =
                    ColorByWordFunction { word: Word? ->
                        getColorFromSpeakerId(
                            word!!.getSpeakerInfo().getSpeakerId()
                        )
                    }

                TranscriptColoringStyle.NO_COLORING, TranscriptColoringStyle.UNSPECIFIED_COLORING_STYLE -> colorFunction =
                    ColorByWordFunction { word: Word? -> this.defaultColorFromTheme }
            }
            return addPerWordColoredStringToResult(
                normalizedTranscript,
                result.getLanguageCode(),
                result.getWordLevelDetailList(),
                precededByLineBreak,
                colorFunction
            )
        }
    }

    /**
     * Obtains line breaks between the last finalized result and current result. It would return an
     * empty string if no finalized transcript result existed. (Current result is he first element.)
     */
    private fun obtainLineBreaksFromLastFinalizedResult(current: TranscriptionResult): String {
        return (if (resultsDeque.isEmpty())
            ""
        else
            obtainLineBreaksBetweenTwoResults(resultsDeque.getLast(), current))!!
    }

    private fun obtainLineBreaksBetweenTwoResults(
        previous: CachedResult, current: TranscriptionResult
    ): String? {
        val languageSwitched = previous.result.getLanguageCode() != current.getLanguageCode()
        if (options!!.getNumExtendedSilenceLineBreaks() > 0) { // Previous element is not whitespace.
            val timestampDifference =
                Duration(
                    TimeUtil.toInstant(previous.result.getEndTimestamp()),
                    TimeUtil.toInstant(current.getStartTimestamp())
                )
            if (timestampDifference.isLongerThan(extendedSilenceDurationForLineBreaks)) {
                // If language switch and silence both happened, return the longer line break.
                return if (languageSwitched) this.lineBreaksWhenSilenceAndLanguageSwitch else silenceLineBreak
            }
        }
        return if (languageSwitched) languageSwitchLineBreak else ""
    }

    private val lineBreaksWhenSilenceAndLanguageSwitch: String?
        /** Returns the String contains more new line breaks between language switch and silence.  */
        get() {
            if (options!!.getNumExtendedSilenceLineBreaks() >= options!!.getNumLanguageSwitchLineBreaks()) {
                return silenceLineBreak
            }
            return languageSwitchLineBreak
        }

    /**
     * Generates a Spannable with text formatted at the word level.
     *
     * @param wholeStringTranscript the whole transcript, formatted to have no leading spaces and a
     * single trailing space
     * @param languageCode string language code, for example "en-us" or "ja"
     * @param words the list of words contained in wholeStringTranscript
     * @param colorFunction maps a word to a hex color
     */
    private fun addPerWordColoredStringToResult(
        wholeStringTranscript: String,
        languageCode: String?,
        words: MutableList<Word?>,
        precededByLineBreak: Boolean,
        colorFunction: ColorByWordFunction
    ): Spannable {
        val rawTranscript = StringBuilder(wholeStringTranscript)
        var wordFound = false
        var color = ""
        val spannableStringBuilder = SpannableStringBuilder()
        var intermediateBuilder = StringBuilder()
        // Group adjacent words of the same color within the same span tag.
        // Traverse in reverse then a space divider will be at the end of word.
        val reverseWords: MutableList<Word> = Lists.reverse<Word?>(words)
        for (wordIndex in reverseWords.indices) {
            val word = reverseWords.get(wordIndex)

            val nextColor = colorFunction.getColor(word)
            if (wordFound) {
                if (color != nextColor) {
                    spannableStringBuilder.insert(
                        0, makeColoredString(intermediateBuilder.toString(), color)
                    )
                    intermediateBuilder = StringBuilder()
                    wordFound = false
                }

                if (options!!.getSpeakerIndicationStyle() == SpeakerIndicationStyle.SHOW_SPEAKER_NUMBER) {
                    // If the speaker has changed or if the text was preceded by a space, add a chevron.
                    val previousSpeaker =
                        reverseWords.get(wordIndex - 1).getSpeakerInfo().getSpeakerId()
                    if (word.getSpeakerInfo().getSpeakerId() != previousSpeaker) {
                        val needsAdditionalNewline = previousSpeaker != -1 && !precededByLineBreak
                        intermediateBuilder.insert(
                            0,
                            TranscriptionResultFormatter.Companion.newSpeakerChevron(
                                reverseWords.get(wordIndex - 1).getSpeakerInfo().getSpeakerId(),
                                needsAdditionalNewline
                            )
                        )

                        spannableStringBuilder.insert(
                            0, makeColoredString(intermediateBuilder.toString(), color)
                        )
                        intermediateBuilder = StringBuilder()
                        wordFound = false
                    }
                }
            }
            // We'll try to find previous word if we can't find current word in the rawTranscript.
            // Append the string started from the word to the end if found.
            wordFound = wordFound or
                    TranscriptionResultFormatter.Companion.checkWordExistedThenAdd(
                        rawTranscript,
                        intermediateBuilder,
                        TranscriptionResultFormatter.Companion.formatWord(
                            languageCode,
                            word.getText()
                        )!!
                    )
            color = nextColor
        }
        val forceChevron =
            precededByLineBreak || words.get(0)!!.getSpeakerInfo().getSpeakerId() != lastSpeakerId
        intermediateBuilder.insert(0, rawTranscript.toString())
        if (options!!.getSpeakerIndicationStyle() == SpeakerIndicationStyle.SHOW_SPEAKER_NUMBER && intermediateBuilder.length != 0 && forceChevron) {
            intermediateBuilder.insert(
                0,
                TranscriptionResultFormatter.Companion.newSpeakerChevron(
                    words.get(0)!!.getSpeakerInfo().getSpeakerId(),
                    lastSpeakerId != -1 && !precededByLineBreak
                )
            )
        }
        spannableStringBuilder.insert(0, makeColoredString(intermediateBuilder.toString(), color))
        return SpannableString.valueOf(spannableStringBuilder)
    }

    /**
     * Generates a [SpannableString] containing a colored string.
     *
     * @param message a string to append to cachedFinalizedResult
     * @param color a six-character hex string beginning with a pound sign
     */
    private fun makeColoredString(message: String?, color: String?): SpannableString {
        val textColor = Color.parseColor(color)
        val spannableString = SpannableString(message)
        spannableString.setSpan(
            ForegroundColorSpan(textColor),
            0,
            spannableString.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannableString
    }

    /**
     * Get a string hex color associated with a confidence value on the range [0, 1] according to the
     * confidence in [TranscriptionResult].
     */
    private fun getColorFromConfidence(result: TranscriptionResult): String? {
        if (result.hasConfidence()) {
            return getColorFromConfidence(result.getConfidence())
        }
        return this.defaultColorFromTheme
    }

    /**
     * Get a string hex color associated with a confidence value on the range [0, 1] according to
     * specified confidence.
     */
    private fun getColorFromConfidence(confidence: Float): String? {
        val colormap: ImmutableList<String?> = TranscriptionResultFormatter.Companion.getColorList(
            options!!.getTextColormap()
        )
        for (i in TranscriptionResultFormatter.Companion.UPPER_CONFIDENCE_THRESHOLDS.indices) {
            if (confidence <= TranscriptionResultFormatter.Companion.UPPER_CONFIDENCE_THRESHOLDS.get(
                    i
                )!!
            ) {
                return colormap.get(i)
            }
        }
        // Won't happen because upper bound of UPPER_CONFIDENCE_THRESHOLDS is infinity.
        return this.defaultColorFromTheme
    }

    private val defaultColorFromTheme: String
        /** Returns the hex code of the default text color according to the theme.  */
        get() {
            when (options!!.getTextColormap()) {
                TextColormap.DARK_THEME -> return TranscriptionResultFormatter.Companion.WHITE
                TextColormap.LIGHT_THEME, TextColormap.UNSPECIFIED_THEME -> return TranscriptionResultFormatter.Companion.BLACK
            }
            return TranscriptionResultFormatter.Companion.WHITE
        }

    /**
     * Get a string hex color associated with the speaker number. Currently this supports up to 4
     * speakers.
     */
    private fun getColorFromSpeakerId(speakerID: Int): String? {
        return TranscriptionResultFormatter.Companion.SPEAKER_ID_COLORS.get(speakerID % TranscriptionResultFormatter.Companion.SPEAKER_ID_COLORS.size)
    }

    companion object {
        private const val WHITE = "#ffffffff" // Alpha: 1
        private const val BLACK = "#de000000" // Alpha: .87

        // Color gradients can be generated using http://www.perbang.dk/rgbgradient/.
        // In order of ascending confidence.
        private val LIGHT_THEME_COLORS: ImmutableList<String?> = ImmutableList.of<String?>(
            "#004ffa",
            "#1b55c8",
            "#375b96",
            "#526164",
            "#6e6732",
            "#8a6e00"
        )
        private val DARK_THEME_COLORS: ImmutableList<String?> = ImmutableList.of<String?>(
            "#004ffa",
            "#306dc8",
            "#608c69",
            "#90aa64",
            "#c0c932",
            "#ffff00"
        )
        private val SPEAKER_ID_COLORS: ImmutableList<String?> = ImmutableList.of<String?>(
            "#4285f4",  // blue
            "#ea4335",  // red
            "#fbbc04",  // yellow
            "#34a853",  // green
            "#FA7B17",  // orange
            "#F439A0",  // pink
            "#A142F4",  // purple
            "#24C1E0" // cyan
        )

        private val UPPER_CONFIDENCE_THRESHOLDS: ImmutableList<Double?> =
            ImmutableList.of<Double?>(0.3, 0.55, 0.7, 0.8, 0.9, Double.Companion.POSITIVE_INFINITY)

        // The separator regex used to split a concatenated string of word values.
        private const val JAPANESE_SPLITTER_REGEX = "\\|"

        fun noFormattingOptions(): TranscriptionResultFormatterOptions? {
            return TranscriptionResultFormatterOptions.newBuilder()
                .setNumExtendedSilenceLineBreaks(0)
                .setNumLanguageSwitchLineBreaks(1)
                .setItalicizeCurrentHypothesis(false)
                .setTranscriptColoringStyle(TranscriptColoringStyle.NO_COLORING)
                .setTextColormap(TextColormap.DARK_THEME)
                .build()
        }

        private fun getLanguageWithoutDialect(languageCode: String?): String? {
            if (TextUtils.isEmpty(languageCode)) {
                return ""
            }
            return languageCode!!.split("-".toRegex()).toTypedArray()[0]
        }

        /**
         * Returns string with Hiragana only if language is Japanese. Otherwise, returned string is with
         * any leading and trailing whitespace removed.
         */
        private fun formatWord(languageCode: String?, word: String): String? {
            val language: String? =
                TranscriptionResultFormatter.Companion.getLanguageWithoutDialect(languageCode)
            if ("ja".equals(language, ignoreCase = true)) {
                // Japanese ASR results could contain two parts per word, the former would be one of
                // Hiragana, Katakana, or Kanji, and the latter would be Katakana or none. Here extract
                // the former.
                return word.split(TranscriptionResultFormatter.Companion.JAPANESE_SPLITTER_REGEX.toRegex())
                    .toTypedArray()[0]
            }
            return word.trim { it <= ' ' }
        }

        /**
         * If the word occurs as a substring within the rawTranscript, then the substring starting from
         * the last occurrence of the word and extends to the end is added to intermediateBuilder. We
         * assume the transcript is formatted perfectly, and then we don't worry about the word divider
         * between words for all languages if we construct the transcript by words level detail.
         */
        private fun checkWordExistedThenAdd(
            rawTranscript: StringBuilder, intermediateBuilder: StringBuilder, word: String
        ): Boolean {
            val index = rawTranscript.lastIndexOf(word)
            if (index == -1) {
                return false
            }
            val transcriptToTheEnd = rawTranscript.substring(index)
            intermediateBuilder.insert(0, transcriptToTheEnd)
            rawTranscript.delete(index, rawTranscript.length)
            return true
        }

        private fun getColorList(colormap: TextColormap): ImmutableList<String?> {
            when (colormap) {
                TextColormap.LIGHT_THEME, TextColormap.UNSPECIFIED_THEME -> return TranscriptionResultFormatter.Companion.LIGHT_THEME_COLORS
                TextColormap.DARK_THEME -> return TranscriptionResultFormatter.Companion.DARK_THEME_COLORS
            }
            return TranscriptionResultFormatter.Companion.DARK_THEME_COLORS
        }

        private fun newSpeakerChevron(tag: Int, includesNewline: Boolean): String {
            return (if (includesNewline) "\n≫ " else "≫ ") + tag.toString() + ": "
        }

        private fun getLastSpeakerIdTag(result: TranscriptionResult): Int {
            if (result.getWordLevelDetailCount() == 0) {
                return result.getSpeakerInfo().getSpeakerId()
            } else {
                return result
                    .getWordLevelDetailList()
                    .get(result.getWordLevelDetailCount() - 1)
                    .getSpeakerInfo()
                    .getSpeakerId()
            }
        }
    }
}
