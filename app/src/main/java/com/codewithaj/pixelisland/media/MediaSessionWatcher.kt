package com.codewithaj.pixelisland.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import com.codewithaj.pixelisland.island.IslandActivity
import com.codewithaj.pixelisland.island.IslandStateManager
import com.codewithaj.pixelisland.notifications.IslandNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Turns the active MediaSessions into a single [IslandActivity.Media].
 *
 * Entirely event-driven: session list changes and MediaController callbacks. No polling.
 * Requires notification access (MediaSessionManager checks that our listener is enabled),
 * so it's started/stopped by [IslandNotificationListener].
 *
 * Which session wins:
 *  1. the highest-priority session that is playing (the system orders the list by priority);
 *  2. otherwise the last one we showed, while paused, for [PAUSED_LINGER_MS];
 *  3. otherwise nothing.
 */
class MediaSessionWatcher(
    private val context: Context,
    private val state: IslandStateManager,
) {
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, IslandNotificationListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var sessions: List<MediaController> = emptyList()
    private val callbacks = HashMap<MediaSession.Token, MediaController.Callback>()
    private var active: MediaController? = null
    private var started = false

    // Current track and its art.
    /** title|artist|album of the track on screen. A change = a new track. */
    private var trackKey: String? = null
    /** Identity of the art *source* last processed (bitmap instance or URI) for [trackKey]. */
    private var artSourceKey: String? = null
    /** Which track [art] belongs to; never show one track's art with another's title. */
    private var artTrackKey: String? = null
    private var art: Bitmap? = null
    private var artOwned = false
    private var accent = ArtProcessor.DEFAULT_ACCENT
    private var artJob: Job? = null
    private var lingerJob: Job? = null
    /** New track: wait at most [ART_GRACE_MS] for its art before showing it without art. */
    private var artGraceOver = true
    private val artGraceRunnable = Runnable { artGraceOver = true; refresh() }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        onSessions(list.orEmpty())
    }

    fun start() {
        if (started) return
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main)
            started = true
            onSessions(msm.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification access missing; media disabled", e)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) { }
        for (c in sessions) callbacks.remove(c.sessionToken)?.let { c.unregisterCallback(it) }
        callbacks.clear()
        sessions = emptyList()
        scope.coroutineContext.cancelChildren()
        clearActivity() // also resets track/art keys so a restart starts clean
    }

    // ---- Transport controls (from the expanded island) -------------------------------------------

    fun playPause() {
        val c = active ?: return
        if (isPlaying(c.playbackState)) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() { active?.transportControls?.skipToNext() }
    fun previous() { active?.transportControls?.skipToPrevious() }
    fun seekTo(positionMs: Long) { active?.transportControls?.seekTo(positionMs) }

    // ---- Session tracking ------------------------------------------------------------------------

    private fun onSessions(list: List<MediaController>) {
        val tokens = list.mapTo(HashSet()) { it.sessionToken }
        for (old in sessions) {
            if (old.sessionToken !in tokens) callbacks.remove(old.sessionToken)?.let { old.unregisterCallback(it) }
        }
        for (c in list) {
            if (c.sessionToken !in callbacks) {
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
                    override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
                    override fun onSessionDestroyed() = refresh()
                }
                c.registerCallback(cb, main)
                callbacks[c.sessionToken] = cb
            }
        }
        sessions = list
        if (active != null && sessions.none { it.sessionToken == active?.sessionToken }) active = null
        refresh()
    }

    private fun refresh() {
        if (!started) return
        val playing = sessions.firstOrNull { isPlaying(it.playbackState) && it.metadata != null }
        val chosen = playing ?: active?.takeIf { a -> sessions.any { it.sessionToken == a.sessionToken } }
        val meta = chosen?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (chosen == null || meta == null || title.isNullOrBlank()) {
            clearActivity()
            return
        }
        active = chosen
        val pb = chosen.playbackState
        val isPlaying = isPlaying(pb)

        // Paused: keep showing for a while, then step aside. The only timer in this class, and it
        // exists only while something is paused.
        if (isPlaying) {
            lingerJob?.cancel(); lingerJob = null
        } else if (lingerJob == null) {
            lingerJob = scope.launch {
                delay(PAUSED_LINGER_MS)
                lingerJob = null
                clearActivity()
            }
        }

        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val pkg = chosen.packageName
        val key = "$pkg|$title|$artist|${meta.getString(MediaMetadata.METADATA_KEY_ALBUM)}"
        if (key != trackKey) {
            // New track. Give its art a short grace period so title and art usually change
            // together, but never let slow art hold the old track on screen.
            trackKey = key
            artGraceOver = false
            main.removeCallbacks(artGraceRunnable)
            main.postDelayed(artGraceRunnable, ART_GRACE_MS)
        }
        // (Re)process art whenever its *source* changes — players often send the title first
        // and the artwork a moment later, under the same track.
        val sourceKey = "$key#${artSourceIdentity(meta)}"
        if (sourceKey != artSourceKey) {
            artSourceKey = sourceKey
            loadArtFor(key, meta)
        }
        // Hold the previous content only while waiting for this track's FIRST art, and only
        // during the grace period; the job / grace timer call refresh() again.
        if (artJob != null && artTrackKey != key && !artGraceOver) return
        val showArt = if (artTrackKey == key) art else null
        val showAccent = if (artTrackKey == key) accent else ArtProcessor.DEFAULT_ACCENT

        val actions = pb?.actions ?: 0L
        state.post(
            IslandActivity.Media(
                MediaInfo(
                    packageName = pkg,
                    title = title,
                    artist = artist,
                    durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION),
                    playing = isPlaying,
                    positionMs = pb?.position ?: 0L,
                    positionUpdatedAt = pb?.lastPositionUpdateTime ?: 0L,
                    speed = pb?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
                    canPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
                    canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
                    canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
                    art = showArt,
                    accent = showAccent,
                    sessionActivity = chosen.sessionActivity,
                ),
            ),
        )
    }

    private fun clearActivity() {
        lingerJob?.cancel(); lingerJob = null
        artJob?.cancel(); artJob = null
        main.removeCallbacks(artGraceRunnable)
        artGraceOver = true
        active = null
        trackKey = null
        artSourceKey = null
        artTrackKey = null
        state.remove(ID)
        releaseArt()
    }

    /**
     * Loads + processes art for [forTrack] in the background. Loading a content:// URI can
     * block (some players fetch the image from the network inside their provider), so it runs
     * on IO and is abandoned after [ART_TIMEOUT_MS]; the track is shown without art instead.
     */
    private fun loadArtFor(forTrack: String, meta: MediaMetadata) {
        artJob?.cancel()
        artJob = scope.launch {
            val loading = async(Dispatchers.IO) {
                try { loadArt(meta) } catch (_: Exception) { null }
            }
            val source = withTimeoutOrNull(ART_TIMEOUT_MS) { loading.await() }
            if (source == null) loading.cancel()
            val result = source?.let { withContext(Dispatchers.Default) { ArtProcessor.process(it) } }
            swapArt(result, forTrack)
            artJob = null
            refresh()
        }
    }

    /** Embedded bitmap first; else a content:// URI via the thumbnail API (API 29+). Blocking. */
    private fun loadArt(meta: MediaMetadata): Bitmap? {
        embeddedArt(meta)?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val uri = artUri(meta) ?: return null
        return try {
            val u = Uri.parse(uri)
            if (u.scheme != "content") return null
            context.contentResolver.loadThumbnail(u, Size(ArtProcessor.MAX_ART_PX, ArtProcessor.MAX_ART_PX), null)
        } catch (_: Exception) {
            null // no read grant for that URI (common) → no art
        }
    }

    private fun embeddedArt(meta: MediaMetadata): Bitmap? =
        meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    private fun artUri(meta: MediaMetadata): String? =
        meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)

    /** Cheap identity of the art source, to notice when a player sends new artwork. */
    private fun artSourceIdentity(meta: MediaMetadata): String {
        val bmp = embeddedArt(meta)
        if (bmp != null) return "bmp:${System.identityHashCode(bmp)}:${bmp.generationId}:${bmp.width}x${bmp.height}"
        return "uri:${artUri(meta)}"
    }

    private fun swapArt(result: ArtProcessor.Result?, forTrack: String) {
        releaseArt()
        art = result?.bitmap
        artOwned = result?.owned == true
        accent = result?.accent ?: ArtProcessor.DEFAULT_ACCENT
        artTrackKey = forTrack
    }

    /**
     * Drops the current art. Our own downscaled copy is recycled a little later, because the
     * outgoing content may still be fading out with it on screen (the renderer skips
     * recycled bitmaps anyway).
     */
    private fun releaseArt() {
        val old = art
        val owned = artOwned
        art = null
        artOwned = false
        accent = ArtProcessor.DEFAULT_ACCENT
        if (old != null && owned) main.postDelayed({ if (!old.isRecycled) old.recycle() }, RECYCLE_DELAY_MS)
    }

    private fun isPlaying(pb: PlaybackState?) = when (pb?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> true
        else -> false
    }

    companion object {
        private const val TAG = "MediaWatcher"
        const val ID = "media"
        private const val PAUSED_LINGER_MS = 60_000L
        private const val RECYCLE_DELAY_MS = 2_000L
        /** Max wait for a new track's art before showing the new title without it. */
        private const val ART_GRACE_MS = 300L
        /** Give up on a slow artwork load (e.g. a player's provider fetching from network). */
        private const val ART_TIMEOUT_MS = 2_500L
    }
}
