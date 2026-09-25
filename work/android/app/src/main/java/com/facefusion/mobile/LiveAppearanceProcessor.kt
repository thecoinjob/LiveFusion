package com.facefusion.mobile

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight source-appearance pass for Live.
 *
 * It analyses every source once, then uses the already-loaded detector to locate faces in
 * the final swapped frame. No second swap pipeline is created. Complexion is harmonised in
 * the face oval and existing moustache/beard pixels are recoloured toward the source while
 * their live shading and motion remain intact.
 */
class LiveAppearanceProcessor private constructor(
    private val profiles: List<Profile?>,
) : LiveFrameProcessor {
    data class SourceFrame(val bgr: ByteArray, val width: Int, val height: Int)

    @Volatile
    var activeSource: Int = 0

    override fun process(bgr: ByteArray, width: Int, height: Int) {
        val profile = profiles.getOrNull(activeSource) ?: return
        val boxes = NativePipe.detectFaces(bgr, width, height)
        var i = 0
        while (i + 4 < boxes.size) {
            val box = Box(
                boxes[i].toInt().coerceIn(0, width - 1),
                boxes[i + 1].toInt().coerceIn(0, height - 1),
                boxes[i + 2].toInt().coerceIn(1, width),
                boxes[i + 3].toInt().coerceIn(1, height),
            )
            if (box.width > 24 && box.height > 24) applyProfile(bgr, width, height, box, profile)
            i += 5
        }
    }

    private fun applyProfile(
        bgr: ByteArray, width: Int, height: Int, box: Box, profile: Profile,
    ) {
        val targetSkin = sampleSkin(bgr, width, height, box) ?: return
        applyComplexion(bgr, width, height, box, targetSkin, profile.skin)
        profile.moustache?.let {
            applyHair(bgr, width, height, box, targetSkin, it, moustache = true)
        }
        profile.beard?.let {
            applyHair(bgr, width, height, box, targetSkin, it, moustache = false)
        }
    }

    private fun applyComplexion(
        bgr: ByteArray, width: Int, height: Int, box: Box, from: Tone, to: Tone,
    ) {
        val x0 = box.x0.coerceAtLeast(0)
        val x1 = box.x1.coerceAtMost(width)
        val y0 = box.y0.coerceAtLeast(0)
        val y1 = box.y1.coerceAtMost(height)
        for (y in y0 until y1) {
            val ny = (y - box.y0).toFloat() / box.height
            if (ny < 0.18f || ny > 0.88f) continue
            for (x in x0 until x1) {
                val nx = (x - box.x0).toFloat() / box.width
                val ex = (nx - 0.5f) / 0.48f
                val ey = (ny - 0.52f) / 0.52f
                if (ex * ex + ey * ey > 1f) continue
                val p = (y * width + x) * 3
                val b = bgr[p].toInt() and 255
                val g = bgr[p + 1].toInt() and 255
                val r = bgr[p + 2].toInt() and 255
                val distance = abs(b - from.b) + abs(g - from.g) + abs(r - from.r)
                if (distance > 115 || maxOf(b, g, r) - minOf(b, g, r) > 135) continue
                // Preserve lighting: transfer the source-vs-target colour offset, not a flat colour.
                bgr[p] = blend(b, b + (to.b - from.b), 0.34f)
                bgr[p + 1] = blend(g, g + (to.g - from.g), 0.34f)
                bgr[p + 2] = blend(r, r + (to.r - from.r), 0.34f)
            }
        }
    }

    private fun applyHair(
        bgr: ByteArray, width: Int, height: Int, box: Box,
        skin: Tone, desired: Tone, moustache: Boolean,
    ) {
        val yStart = if (moustache) 0.50f else 0.62f
        val yEnd = if (moustache) 0.69f else 0.99f
        val xStart = if (moustache) 0.24f else 0.10f
        val xEnd = if (moustache) 0.76f else 0.90f
        val x0 = (box.x0 + box.width * xStart).toInt().coerceIn(0, width)
        val x1 = (box.x0 + box.width * xEnd).toInt().coerceIn(0, width)
        val y0 = (box.y0 + box.height * yStart).toInt().coerceIn(0, height)
        val y1 = (box.y0 + box.height * yEnd).toInt().coerceIn(0, height)
        for (y in y0 until y1) {
            val ny = (y - box.y0).toFloat() / box.height
            for (x in x0 until x1) {
                val nx = (x - box.x0).toFloat() / box.width
                if (!moustache && nx in 0.30f..0.70f && ny in 0.62f..0.77f) continue
                val p = (y * width + x) * 3
                val b = bgr[p].toInt() and 255
                val g = bgr[p + 1].toInt() and 255
                val r = bgr[p + 2].toInt() and 255
                val luma = luma(b, g, r)
                val neutral = maxOf(b, g, r) - minOf(b, g, r) < 82
                val separated = abs(luma - skin.luma) > 19
                if (!neutral || !separated) continue
                // Retain per-pixel texture while moving the overall hair tone to the source.
                val shade = (0.72f + 0.28f * (luma / skin.luma.coerceAtLeast(1f)))
                    .coerceIn(0.58f, 1.18f)
                bgr[p] = blend(b, (desired.b * shade).toInt(), 0.78f)
                bgr[p + 1] = blend(g, (desired.g * shade).toInt(), 0.78f)
                bgr[p + 2] = blend(r, (desired.r * shade).toInt(), 0.78f)
            }
        }
    }

    private data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val width: Int get() = x1 - x0
        val height: Int get() = y1 - y0
    }

    private data class Tone(val b: Int, val g: Int, val r: Int) {
        val luma: Float get() = LiveAppearanceProcessor.luma(b, g, r)
    }

    private data class Profile(val skin: Tone, val moustache: Tone?, val beard: Tone?)

    companion object {
        fun create(sources: List<SourceFrame>): LiveAppearanceProcessor? {
            val profiles = sources.map { analyse(it) }
            return if (profiles.any { it != null }) LiveAppearanceProcessor(profiles) else null
        }

        private fun analyse(source: SourceFrame): Profile? {
            val box = largestBox(
                NativePipe.detectFaces(source.bgr, source.width, source.height),
                source.width, source.height,
            ) ?: return null
            val skin = sampleSkin(source.bgr, source.width, source.height, box) ?: return null
            val moustache = sampleHairTone(
                source.bgr, source.width, source.height, box, skin,
                0.24f, 0.76f, 0.50f, 0.70f,
            )
            val beard = sampleHairTone(
                source.bgr, source.width, source.height, box, skin,
                0.10f, 0.90f, 0.66f, 0.99f,
            )
            return Profile(skin, moustache, beard)
        }

        private fun largestBox(values: FloatArray, width: Int, height: Int): Box? {
            var best: Box? = null
            var bestArea = 0
            var i = 0
            while (i + 4 < values.size) {
                val box = Box(
                    values[i].toInt().coerceIn(0, width - 1),
                    values[i + 1].toInt().coerceIn(0, height - 1),
                    values[i + 2].toInt().coerceIn(1, width),
                    values[i + 3].toInt().coerceIn(1, height),
                )
                val area = box.width * box.height
                if (area > bestArea) { best = box; bestArea = area }
                i += 5
            }
            return best
        }

        private fun sampleSkin(
            bgr: ByteArray, width: Int, height: Int, box: Box,
        ): Tone? {
            var sb = 0L; var sg = 0L; var sr = 0L; var count = 0
            val y0 = (box.y0 + box.height * 0.38f).toInt().coerceIn(0, height)
            val y1 = (box.y0 + box.height * 0.64f).toInt().coerceIn(0, height)
            val left0 = (box.x0 + box.width * 0.12f).toInt().coerceIn(0, width)
            val left1 = (box.x0 + box.width * 0.42f).toInt().coerceIn(0, width)
            val right0 = (box.x0 + box.width * 0.58f).toInt().coerceIn(0, width)
            val right1 = (box.x0 + box.width * 0.88f).toInt().coerceIn(0, width)
            for (y in y0 until y1) {
                for (x in left0 until left1) {
                    val p = (y * width + x) * 3
                    val b = bgr[p].toInt() and 255
                    val g = bgr[p + 1].toInt() and 255
                    val r = bgr[p + 2].toInt() and 255
                    val lum = luma(b, g, r)
                    if (lum in 30f..245f && maxOf(b, g, r) - minOf(b, g, r) < 145) {
                        sb += b; sg += g; sr += r; count++
                    }
                }
                for (x in right0 until right1) {
                    val p = (y * width + x) * 3
                    val b = bgr[p].toInt() and 255
                    val g = bgr[p + 1].toInt() and 255
                    val r = bgr[p + 2].toInt() and 255
                    val lum = luma(b, g, r)
                    if (lum in 30f..245f && maxOf(b, g, r) - minOf(b, g, r) < 145) {
                        sb += b; sg += g; sr += r; count++
                    }
                }
            }
            return if (count < 40) null else Tone(
                (sb / count).toInt(), (sg / count).toInt(), (sr / count).toInt(),
            )
        }

        private fun sampleHairTone(
            bgr: ByteArray, width: Int, height: Int, box: Box, skin: Tone,
            nx0: Float, nx1: Float, ny0: Float, ny1: Float,
        ): Tone? {
            var lb = 0L; var lg = 0L; var lr = 0L; var light = 0
            var db = 0L; var dg = 0L; var dr = 0L; var dark = 0
            val x0 = (box.x0 + box.width * nx0).toInt().coerceIn(0, width)
            val x1 = (box.x0 + box.width * nx1).toInt().coerceIn(0, width)
            val y0 = (box.y0 + box.height * ny0).toInt().coerceIn(0, height)
            val y1 = (box.y0 + box.height * ny1).toInt().coerceIn(0, height)
            for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
                val p = (y * width + x) * 3
                val b = bgr[p].toInt() and 255
                val g = bgr[p + 1].toInt() and 255
                val r = bgr[p + 2].toInt() and 255
                if (maxOf(b, g, r) - minOf(b, g, r) >= 78) continue
                val lum = luma(b, g, r)
                if (lum > skin.luma + 14f) {
                    lb += b; lg += g; lr += r; light++
                } else if (lum < skin.luma - 18f) {
                    db += b; dg += g; dr += r; dark++
                }
            }
            val minimum = max(10, ((x1 - x0) * (y1 - y0)) / 280)
            return when {
                light >= dark && light >= minimum ->
                    Tone((lb / light).toInt(), (lg / light).toInt(), (lr / light).toInt())
                dark >= minimum ->
                    Tone((db / dark).toInt(), (dg / dark).toInt(), (dr / dark).toInt())
                else -> null
            }
        }

        private fun luma(b: Int, g: Int, r: Int): Float =
            0.114f * b + 0.587f * g + 0.299f * r

        private fun blend(from: Int, to: Int, amount: Float): Byte =
            (from + (to.coerceIn(0, 255) - from) * amount)
                .toInt().coerceIn(0, 255).toByte()
    }
}
