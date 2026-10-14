package com.sportcamhud.core.telemetry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One telemetry stream: sorted epoch-millisecond timestamps (UTC) and values.
 *
 * Mirrors the `[(datetime, value), ...]` sample lists of the Windows app.
 */
class Series(val times: LongArray, val values: DoubleArray) {
    init {
        require(times.size == values.size) { "times/values size mismatch" }
    }

    val size: Int get() = times.size
    fun isEmpty(): Boolean = times.isEmpty()
    val start: Long get() = times.first()
    val end: Long get() = times.last()

    fun minValue(): Double = values.minOrNull() ?: 0.0
    fun maxValue(): Double = values.maxOrNull() ?: 0.0

    /** Index of the last sample with time <= [t], or -1 when [t] precedes all samples. */
    fun floorIndex(t: Long): Int {
        var lo = 0
        var hi = times.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** True when [t] lies inside the covered time range extended by [toleranceMs]. */
    fun covers(t: Long, toleranceMs: Long = DEFAULT_EDGE_TOLERANCE_MS): Boolean =
        !isEmpty() && t >= start - toleranceMs && t <= end + toleranceMs

    /**
     * Linear interpolation. Across gaps longer than [maxGapMs] (pauses) the last
     * value is held instead of drawing a ramp through the pause.
     */
    fun linear(t: Long, maxGapMs: Long = DEFAULT_MAX_GAP_MS): Double? {
        if (!covers(t)) return null
        val i = floorIndex(t)
        if (i < 0) return values[0]
        if (i >= size - 1) return values[size - 1]
        val t0 = times[i]
        val t1 = times[i + 1]
        val span = t1 - t0
        if (span <= 0 || span > maxGapMs) return values[i]
        val frac = (t - t0).toDouble() / span
        return values[i] + (values[i + 1] - values[i]) * frac
    }

    /** Step (sample-and-hold) lookup, used for integer streams such as heart rate. */
    fun step(t: Long): Double? {
        if (!covers(t)) return null
        val i = floorIndex(t)
        return if (i < 0) values[0] else values[i]
    }

    /** Circular interpolation in degrees (0..360) for headings. */
    fun angular(t: Long, maxGapMs: Long = DEFAULT_MAX_GAP_MS): Double? {
        if (!covers(t)) return null
        val i = floorIndex(t)
        if (i < 0) return values[0]
        if (i >= size - 1) return values[size - 1]
        val span = times[i + 1] - times[i]
        if (span <= 0 || span > maxGapMs) return values[i]
        val frac = (t - times[i]).toDouble() / span
        var delta = (values[i + 1] - values[i]) % 360.0
        if (delta > 180) delta -= 360.0
        if (delta < -180) delta += 360.0
        return ((values[i] + delta * frac) % 360.0 + 360.0) % 360.0
    }

    fun shifted(offsetMs: Long): Series =
        if (offsetMs == 0L) this else Series(LongArray(size) { times[it] + offsetMs }, values.copyOf())

    fun mapValues(f: (Double) -> Double): Series = Series(times.copyOf(), DoubleArray(size) { f(values[it]) })

    /** Centred moving average, the `moving_average` smoothing of the Windows app. */
    fun movingAverage(window: Int): Series {
        if (window <= 1 || size < 3) return this
        val half = window / 2
        val out = DoubleArray(size)
        for (i in 0 until size) {
            val a = max(0, i - half)
            val b = min(size - 1, i + half)
            var sum = 0.0
            for (j in a..b) sum += values[j]
            out[i] = sum / (b - a + 1)
        }
        return Series(times.copyOf(), out)
    }

    /** Samples whose timestamps fall inside [from, to]. */
    fun slice(from: Long, to: Long): Series {
        val a = max(0, floorIndex(from))
        var b = floorIndex(to)
        if (b < 0) return EMPTY
        if (b < size - 1 && times[b] < to) b += 1
        b = min(b, size - 1)
        if (a > b) return EMPTY
        return Series(times.copyOfRange(a, b + 1), values.copyOfRange(a, b + 1))
    }

    companion object {
        const val DEFAULT_MAX_GAP_MS = 10_000L
        const val DEFAULT_EDGE_TOLERANCE_MS = 2_000L
        val EMPTY = Series(LongArray(0), DoubleArray(0))

        /** Builds a sorted, de-duplicated series (last value wins for equal timestamps). */
        fun of(samples: List<Pair<Long, Double>>): Series {
            if (samples.isEmpty()) return EMPTY
            val sorted = samples.filter { it.second.isFinite() }.sortedBy { it.first }
            val t = ArrayList<Long>(sorted.size)
            val v = ArrayList<Double>(sorted.size)
            for ((time, value) in sorted) {
                if (t.isNotEmpty() && t.last() == time) {
                    v[v.size - 1] = value
                } else {
                    t.add(time)
                    v.add(value)
                }
            }
            return Series(t.toLongArray(), v.toDoubleArray())
        }
    }
}

/** Angular difference helper in degrees, result in (-180, 180]. */
internal fun angleDelta(a: Double, b: Double): Double {
    var d = (b - a) % 360.0
    if (d > 180) d -= 360.0
    if (d <= -180) d += 360.0
    return d
}

internal fun approxEquals(a: Double, b: Double, eps: Double = 1e-9) = abs(a - b) <= eps
