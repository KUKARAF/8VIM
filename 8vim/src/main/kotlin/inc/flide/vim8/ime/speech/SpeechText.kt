package inc.flide.vim8.ime.speech

private val SENTENCE_TERMINATORS = charArrayOf('.', '!', '?', '\n')

/**
 * Returns the last sentence of [text]. A terminator at the very end of the text belongs to the
 * last sentence. When the text contains no earlier terminator the whole (trimmed) text is returned.
 */
internal fun lastSentence(text: String): String {
    val trimmed = text.trimEnd()
    if (trimmed.isEmpty()) return ""
    val boundary = trimmed.lastIndexOfAny(SENTENCE_TERMINATORS, trimmed.length - 2)
    return trimmed.substring(boundary + 1).trim()
}
