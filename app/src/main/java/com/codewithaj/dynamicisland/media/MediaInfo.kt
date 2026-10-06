package com.codewithaj.dynamicisland.media

import android.app.PendingIntent
import android.graphics.Bitmap

/**
 * Snapshot of the now-playing session.
 *
 * Playback position is *not* polled: we keep the last reported position, the time it was
 * reported ([positionUpdatedAt], SystemClock.elapsedRealtime) and the speed, and extrapolate
 * while drawing. That's how the seek bar moves smoothly with zero timers.
 */
data class MediaInfo(
    val packageName: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val playing: Boolean,
    val positionMs: Long,
    val positionUpdatedAt: Long,
    val speed: Float,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val canSeek: Boolean,
    /** Downscaled copy (≤ [ArtProcessor.MAX_ART_PX]); may be recycled shortly after it's replaced. */
    val art: Bitmap?,
    val accent: Int,
    val sessionActivity: PendingIntent?,
) {
    /** A different track (or app) → content cross-fades. */
    val trackKey: String get() = "$packageName|$title|$artist"

    fun positionAt(elapsedRealtime: Long): Long {
        if (!playing || positionUpdatedAt <= 0L) return positionMs.coerceAtLeast(0L)
        val p = positionMs + ((elapsedRealtime - positionUpdatedAt) * speed).toLong()
        return if (durationMs > 0) p.coerceIn(0L, durationMs) else p.coerceAtLeast(0L)
    }
}
