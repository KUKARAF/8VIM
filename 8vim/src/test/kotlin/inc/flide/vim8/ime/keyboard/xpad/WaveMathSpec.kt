package inc.flide.vim8.ime.keyboard.xpad

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.random.Random

class WaveMathSpec : FunSpec({
    test("bulges go top, right, bottom, left, top") {
        val half = (PI / 2).toFloat()
        WaveMath.angleOf(0) shouldBe (-half plusOrMinus 1e-5f)
        WaveMath.angleOf(1) shouldBe (0f plusOrMinus 1e-5f)
        WaveMath.angleOf(2) shouldBe (half plusOrMinus 1e-5f)
        WaveMath.angleOf(3) shouldBe ((PI).toFloat() plusOrMinus 1e-5f)
        WaveMath.angleOf(4) shouldBe WaveMath.angleOf(0)
    }

    test("index advances every step") {
        WaveMath.currentIndex(0) shouldBe 0
        WaveMath.currentIndex(WaveMath.STEP_MS - 1) shouldBe 0
        WaveMath.currentIndex(WaveMath.STEP_MS) shouldBe 1
    }

    test("envelope is eased in and out") {
        WaveMath.envelope(0) shouldBe 0f
        WaveMath.envelope(WaveMath.BULGE_DURATION_MS) shouldBe 0f
        WaveMath.envelope(WaveMath.BULGE_DURATION_MS / 2) shouldBe (1f plusOrMinus 1e-5f)
    }

    test("spring envelope starts and ends at zero and overshoots once") {
        WaveMath.springEnvelope(0) shouldBe 0f
        WaveMath.springEnvelope(WaveMath.BULGE_DURATION_MS) shouldBe 0f
        val peak = (1L until WaveMath.BULGE_DURATION_MS).maxOf { WaveMath.springEnvelope(it) }
        (peak > 1f) shouldBe true
        (peak < 1.2f) shouldBe true
    }

    test("profile peaks at centre and vanishes at the edges") {
        WaveMath.profile(0f) shouldBe (1f plusOrMinus 1e-5f)
        WaveMath.profile(WaveMath.HALF_WIDTH_RAD) shouldBe 0f
        WaveMath.profile(-2f) shouldBe 0f
        WaveMath.profile(0.3f) shouldBe WaveMath.profile(-0.3f)
    }

    test("consecutive hues are never close") {
        val random = Random(42)
        var previous: Float? = null
        repeat(1000) {
            val hue = WaveMath.nextHue(previous, random)
            (hue in 0f..360f) shouldBe true
            previous?.let {
                WaveMath.hueDistance(it, hue) shouldBeGreaterThanOrEqual
                    WaveMath.MIN_HUE_DISTANCE - 1e-3f
            }
            previous = hue
        }
    }
})
