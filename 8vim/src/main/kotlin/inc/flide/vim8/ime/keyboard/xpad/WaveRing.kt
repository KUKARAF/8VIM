package inc.flide.vim8.ime.keyboard.xpad

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Pure maths of the "working" wave: bulges travelling TOP, RIGHT, BOTTOM, LEFT, TOP... */
internal object WaveMath {
    const val BULGE_DURATION_MS = 1300L
    const val STEP_MS = 650L
    const val HALF_WIDTH_RAD = 1.15f
    const val MIN_HUE_DISTANCE = 60f

    private const val TOP_ANGLE_RAD = (-PI / 2).toFloat()
    private const val QUARTER_RAD = (PI / 2).toFloat()

    /** Index of the most recently started bulge at [elapsedMs]. */
    fun currentIndex(elapsedMs: Long): Int = (elapsedMs / STEP_MS).toInt()

    /** Screen angle (radians, y down so increasing is clockwise) of bulge [index]. */
    fun angleOf(index: Int): Float = TOP_ANGLE_RAD + QUARTER_RAD * (index % 4)

    /** Eased 0..1..0 envelope for a bulge of age [ageMs]; 0 outside its lifetime. */
    fun envelope(ageMs: Long): Float {
        if (ageMs <= 0 || ageMs >= BULGE_DURATION_MS) return 0f
        val s = sin(PI * ageMs / BULGE_DURATION_MS).toFloat()
        return s * s
    }

    /** Soft envelope: eases out with a small spring overshoot, then settles back to 0. */
    fun springEnvelope(ageMs: Long): Float {
        if (ageMs <= 0 || ageMs >= BULGE_DURATION_MS) return 0f
        val t = ageMs.toFloat() / BULGE_DURATION_MS
        if (t < RISE) {
            val u = t / RISE - 1f
            val c1 = 1.2f
            val c3 = c1 + 1f
            return 1f + c3 * u * u * u + c1 * u * u
        }
        val u = (t - RISE) / (1f - RISE)
        return 0.5f * (1f + cos(PI * u).toFloat())
    }

    private const val RISE = 0.35f

    /** Raised-cosine shape along the ring: 1 at the bulge centre, 0 at +-[HALF_WIDTH_RAD]. */
    fun profile(deltaRad: Float): Float {
        val d = abs(deltaRad)
        if (d >= HALF_WIDTH_RAD) return 0f
        return 0.5f * (1f + cos(PI * d / HALF_WIDTH_RAD).toFloat())
    }

    /** Hue in degrees at least [MIN_HUE_DISTANCE] away (circularly) from [previous]. */
    fun nextHue(previous: Float?, random: Random): Float {
        if (previous == null) return random.nextFloat() * 360f
        val span = 360f - 2 * MIN_HUE_DISTANCE
        val offset = MIN_HUE_DISTANCE + random.nextFloat() * span
        return (previous + offset) % 360f
    }

    fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }
}

/**
 * Animated multi-colour glow around the centre circle: a slowly rotating gradient ring whose
 * brightness and thickness follow the microphone loudness, plus big soft bulges that travel
 * TOP, RIGHT, BOTTOM, LEFT, each in a new random colour. Call [onFrame] from a frame clock while
 * active and [draw] from a draw scope; drawing reads [frameNanos] so only the draw phase is
 * invalidated each frame.
 */
class WaveRing {
    private var frameNanos by mutableLongStateOf(0L)
    private var startNanos = -1L
    private var lastDrawNanos = -1L
    private var waveStartNanos = -1L
    private var smoothLevel = 0f
    private val hues = ArrayList<Float>()
    private val random = Random.Default
    private val curve = Path()
    private val fill = Path()
    private val dropPath = Path()
    private val baseColors = ArrayList<Color>(BASE_STOPS + 1)
    private val bulgeColors = ArrayList<Color>(BULGE_STOPS)
    private val bulgeStops = FloatArray(BULGE_STOPS)

    /** Advances the animation; call once per frame while active. */
    fun onFrame(nanos: Long) {
        if (startNanos < 0) startNanos = nanos
        frameNanos = nanos
    }

    /** Resets so the next activation starts again at the top. */
    fun stop() {
        startNanos = -1L
        lastDrawNanos = -1L
        waveStartNanos = -1L
        smoothLevel = 0f
        hues.clear()
    }

    private fun colorOf(index: Int): Color {
        val i = maxOf(0, index)
        while (hues.size <= i) hues.add(WaveMath.nextHue(hues.lastOrNull(), random))
        return Color.hsv(hues[i], SATURATION, VALUE)
    }

    /**
     * [level] is the microphone loudness in 0..1 (0 while transcribing); [additive] blends layers
     * with [BlendMode.Plus], best on dark backgrounds.
     */
    fun draw(
        scope: DrawScope,
        centre: Offset,
        radius: Float,
        level: Float = 0f,
        listening: Boolean = false,
        transcribing: Boolean = false,
        additive: Boolean = true
    ) {
        val now = frameNanos
        if (startNanos < 0 || radius <= 0f || !(listening || transcribing)) return
        val dtMs = if (lastDrawNanos < 0) 16f else ((now - lastDrawNanos) / NANOS_PER_MS).toFloat()
        lastDrawNanos = now
        val target = if (listening) level.coerceIn(0f, 1f) else 0f
        val rate = if (target > smoothLevel) 0.5f else 0.12f
        smoothLevel += (target - smoothLevel) * (1f - Math.pow(1.0 - rate, dtMs / 16.0).toFloat())

        val elapsedMs = (now - startNanos) / NANOS_PER_MS
        val blend = if (additive) BlendMode.Plus else BlendMode.SrcOver
        if (!transcribing) {
            waveStartNanos = -1L
            drawBase(scope, centre, radius, elapsedMs, 3, blend)
            return
        }
        // Restart the TOP, RIGHT, BOTTOM, LEFT sequence for every new transcription.
        if (waveStartNanos < 0) waveStartNanos = now
        val waveMs = (now - waveStartNanos) / NANOS_PER_MS
        val current = WaveMath.currentIndex(waveMs)
        drawBase(scope, centre, radius, elapsedMs, current, blend)
        // A bulge lives longer than a step, so up to two overlap.
        val first = maxOf(0, current - (WaveMath.BULGE_DURATION_MS / WaveMath.STEP_MS).toInt())
        for (index in first..current) {
            val age = waveMs - index * WaveMath.STEP_MS
            val envelope = WaveMath.springEnvelope(age)
            if (envelope == 0f) continue
            drawBulge(scope, centre, radius, index, envelope, blend)
        }
    }

    private fun drawBase(
        scope: DrawScope,
        centre: Offset,
        radius: Float,
        elapsedMs: Long,
        current: Int,
        blend: BlendMode
    ) {
        val level = smoothLevel
        baseColors.clear()
        for (k in 0 until BASE_STOPS) baseColors.add(colorOf(current - (BASE_STOPS - 1) + k))
        baseColors.add(baseColors[0])
        val brush = Brush.sweepGradient(baseColors, centre)
        val breathe = 0.5f + 0.5f * sin(elapsedMs / BREATHE_PERIOD_MS * 2.0 * PI).toFloat()
        val intensity = (0.3f + 0.2f * breathe + 0.9f * level).coerceAtMost(1f)
        val ringRadius = radius * (1f + 0.05f * level) + GAP
        val core = 4f + 22f * level
        scope.rotate(elapsedMs * ROTATION_DEG_PER_MS, centre) {
            for (layer in BASE_LAYERS) {
                drawCircle(
                    brush,
                    ringRadius,
                    centre,
                    alpha = layer.alpha * intensity,
                    style = Stroke(width = core * layer.widthFactor + layer.extra),
                    blendMode = blend
                )
            }
        }
    }

    private fun drawBulge(
        scope: DrawScope,
        centre: Offset,
        radius: Float,
        index: Int,
        envelope: Float,
        blend: BlendMode
    ) {
        val cur = colorOf(index)
        val prev = colorOf(index - 1)
        val half = WaveMath.HALF_WIDTH_RAD
        val w = half / (2f * PI.toFloat())
        // Colour across the arc: previous colour fades in, flows into the current one, fades out.
        bulgeColors.clear()
        bulgeColors.add(prev.copy(alpha = 0f))
        bulgeColors.add(prev.copy(alpha = 0.9f))
        bulgeColors.add(lerp(prev, cur, 0.6f))
        bulgeColors.add(cur)
        bulgeColors.add(lerp(cur, Color.White, 0.35f))
        bulgeColors.add(cur.copy(alpha = 0f))
        bulgeStops[0] = 0.5f - w
        bulgeStops[1] = 0.5f - w * 0.7f
        bulgeStops[2] = 0.5f - w * 0.25f
        bulgeStops[3] = 0.5f
        bulgeStops[4] = 0.5f + w * 0.3f
        bulgeStops[5] = 0.5f + w
        val brush = Brush.sweepGradient(
            colorStops = Array(BULGE_STOPS) { bulgeStops[it] to bulgeColors[it] },
            center = centre
        )
        val ringRadius = radius * (1f + 0.05f * smoothLevel) + GAP
        val amplitude = radius * AMPLITUDE_FACTOR * (1f + 0.5f * smoothLevel) * envelope
        val alpha = envelope.coerceIn(0f, 1f).let { 0.35f + 0.65f * it }
        // Draw in a frame rotated so the bulge is centred on 180 degrees (sweep seam stays clear).
        val rotation = Math.toDegrees((WaveMath.angleOf(index) - PI)).toFloat()
        scope.rotate(rotation, centre) {
            for ((i, layer) in BULGE_LAYERS.withIndex()) {
                val height = amplitude * layer.heightFactor
                val bodyHeight = height * BODY_FRACTION
                buildCurve(curve, centre, ringRadius, bodyHeight, half)
                if (i == 0) {
                    for (g in GLOW_STROKES) {
                        drawPath(
                            curve,
                            brush,
                            alpha = g.alpha * alpha,
                            style = Stroke(width = g.width, cap = StrokeCap.Round),
                            blendMode = blend
                        )
                    }
                }
                closeBand(curve, fill, centre, ringRadius, half)
                // Droplet: a gooey neck pinches off the body and ends in a round blob that
                // nearly detaches at the peak, then merges back as the bulge retracts.
                val drop = height * DROP_RADIUS
                val sep = smooth((envelope - 0.5f) / 0.5f) * SEPARATION
                val dropHeight = bodyHeight + drop * 0.6f + sep * height * 0.38f
                val neck = drop * (0.7f - 0.55f * sep) + 1f
                if (i == 0) dropPath.rewind()
                for (k in 0..NECK_STEPS) {
                    val t = k.toFloat() / NECK_STEPS
                    val h = bodyHeight * 0.85f + (dropHeight - bodyHeight * 0.85f) * t
                    val r = neck + (drop - neck) * t * t * t
                    addBlob(fill, centre.x - (ringRadius + h), centre.y, r)
                    if (i == 0) addBlob(dropPath, centre.x - (ringRadius + h), centre.y, r)
                }
                drawPath(fill, brush, alpha = layer.alpha * alpha, blendMode = blend)
                if (i == 0 && drop > 2f) {
                    val c = Offset(centre.x - (ringRadius + dropHeight), centre.y)
                    drawCircle(brush, drop * 2.6f, c, alpha = 0.14f * alpha, blendMode = blend)
                    drawPath(dropPath, brush, alpha = 0.6f * alpha, blendMode = blend)
                }
            }
        }
    }

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun addBlob(path: Path, x: Float, y: Float, r: Float) {
        path.addOval(Rect(x - r, y - r, x + r, y + r))
    }

    private fun buildCurve(path: Path, centre: Offset, radius: Float, amplitude: Float, half: Float) {
        path.rewind()
        for (i in 0..SAMPLES) {
            val delta = (i.toFloat() / SAMPLES * 2f - 1f) * half
            val shape = WaveMath.profile(delta)
            val r = radius + amplitude * shape
            val angle = PI.toFloat() + delta
            val x = centre.x + r * cos(angle)
            val y = centre.y + r * sin(angle)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }

    /** Copies [curve] into [out] and closes it back along an inner arc, giving a fillable band. */
    private fun closeBand(curve: Path, out: Path, centre: Offset, radius: Float, half: Float) {
        out.rewind()
        out.addPath(curve)
        val inner = radius - BAND_INSET
        for (i in SAMPLES downTo 0 step 4) {
            val delta = (i.toFloat() / SAMPLES * 2f - 1f) * half
            val angle = PI.toFloat() + delta
            out.lineTo(centre.x + inner * cos(angle), centre.y + inner * sin(angle))
        }
        out.close()
    }

    private class Layer(val alpha: Float, val widthFactor: Float, val extra: Float)

    private class Glow(val alpha: Float, val width: Float)

    private companion object {
        const val SAMPLES = 48
        const val BODY_FRACTION = 0.5f
        const val DROP_RADIUS = 0.26f
        const val SEPARATION = 0.9f
        const val NECK_STEPS = 7
        const val AMPLITUDE_FACTOR = 0.6f
        const val SATURATION = 0.62f
        const val VALUE = 1f
        const val GAP = 14f
        const val BAND_INSET = 6f
        const val BASE_STOPS = 4
        const val BULGE_STOPS = 6
        const val BREATHE_PERIOD_MS = 2400.0
        const val ROTATION_DEG_PER_MS = 0.05f
        const val NANOS_PER_MS = 1_000_000L

        val BASE_LAYERS = arrayOf(
            Layer(0.10f, 1f, 56f),
            Layer(0.18f, 1f, 30f),
            Layer(0.30f, 1f, 14f),
            Layer(0.7f, 1f, 0f)
        )
        val BULGE_LAYERS = arrayOf(
            Layer(0.10f, 1f, 0f),
            Layer(0.10f, 0.86f, 0f),
            Layer(0.11f, 0.72f, 0f),
            Layer(0.12f, 0.58f, 0f),
            Layer(0.14f, 0.45f, 0f),
            Layer(0.16f, 0.32f, 0f),
            Layer(0.20f, 0.2f, 0f)
        )
        val GLOW_STROKES = arrayOf(
            Glow(0.10f, 56f),
            Glow(0.18f, 30f),
            Glow(0.35f, 14f),
            Glow(0.95f, 5f)
        )
    }

    private val Layer.heightFactor get() = widthFactor
}
