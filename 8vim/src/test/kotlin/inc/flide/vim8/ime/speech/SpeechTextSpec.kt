package inc.flide.vim8.ime.speech

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class SpeechTextSpec : FunSpec({
    context("lastSentence") {
        withData(
            nameFn = { "\"${it.first}\" -> \"${it.second}\"" },
            ("" to ""),
            ("   " to ""),
            ("hello world" to "hello world"),
            ("hello world  " to "hello world"),
            ("One. Two three" to "Two three"),
            ("One. Two three." to "Two three."),
            ("One. Two three?  " to "Two three?"),
            ("Line one\nline two" to "line two"),
            ("Wow!" to "Wow!")
        ) { (input, expected) ->
            lastSentence(input) shouldBe expected
        }
    }
})
