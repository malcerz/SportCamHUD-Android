package com.sportcamhud.core.garmin

import com.sportcamhud.core.telemetry.Derive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Correlates the video interval with remote activities (port of activity_matcher.py). */
object ActivityMatcher {
    const val AUTO_MATCH_THRESHOLD = 0.90
    const val SELECTION_THRESHOLD = 0.60

    enum class Decision { AUTO_ACCEPT, NEEDS_SELECTION, NO_MATCH }

    data class Ranked(val candidate: ActivityCandidate, val score: Double)

    fun score(
        c: ActivityCandidate,
        videoStart: Long,
        videoEnd: Long,
        videoGps: Pair<Double, Double>? = null,
        ignoreTimeMatch: Boolean = false,
    ): Double {
        val vDur = max(1.0, (videoEnd - videoStart) / 1000.0)
        val aDur = max(1.0, c.durationS)

        val overlap = max(0L, min(c.end, videoEnd) - max(c.start, videoStart)) / 1000.0
        val overlapScore = min(1.0, overlap / vDur)
        val startScore = max(0.0, 1.0 - abs(c.start - videoStart) / 1000.0 / 3600.0)
        val durScore = min(aDur, vDur) / max(aDur, vDur)

        val total = if (videoGps != null && c.startLat != null && c.startLon != null) {
            val km = Derive.haversineM(videoGps.first, videoGps.second, c.startLat, c.startLon) / 1000.0
            val gpsScore = max(0.0, 1.0 - km / 10.0)
            if (ignoreTimeMatch) 0.60 * gpsScore + 0.40 * durScore
            else 0.45 * overlapScore + 0.25 * startScore + 0.15 * durScore + 0.15 * gpsScore
        } else {
            0.55 * overlapScore + 0.30 * startScore + 0.15 * durScore
        }
        return total.coerceIn(0.0, 1.0)
    }

    fun rank(
        candidates: List<ActivityCandidate>,
        videoStart: Long,
        videoEnd: Long,
        videoGps: Pair<Double, Double>? = null,
        ignoreTimeMatch: Boolean = false,
    ): Pair<Decision, List<Ranked>> {
        if (candidates.isEmpty()) return Decision.NO_MATCH to emptyList()
        val ranked = candidates.map { Ranked(it, score(it, videoStart, videoEnd, videoGps, ignoreTimeMatch)) }
            .sortedByDescending { it.score }
        val best = ranked[0].score
        val decision = when {
            best >= AUTO_MATCH_THRESHOLD ->
                if (ranked.size == 1 || best - ranked[1].score >= 0.10) Decision.AUTO_ACCEPT else Decision.NEEDS_SELECTION
            best >= SELECTION_THRESHOLD -> Decision.NEEDS_SELECTION
            else -> Decision.NO_MATCH
        }
        return decision to ranked
    }
}
