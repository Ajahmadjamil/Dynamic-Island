package com.codewithaj.dynamicisland.media

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
import com.codewithaj.dynamicisland.island.IslandActivity
import com.codewithaj.dynamicisland.island.IslandStateManager
import com.codewithaj.dynamicisland.notifications.IslandNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    // Art cache for the current track.
    private var artKey: String? = null
    private var art: Bitmap? = null
    private var artOwned = false
    private var accent = ArtProcessor.DEFAULT_ACCENT
    private var artJob: Job? = null
    private var lingerJob: Job? = null

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
        active = null
        scope.coroutineContext.cancelChildren()
        artJob = null; lingerJob = null
        state.remove(ID)
        releaseArt()
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
        if (key != artKey) {
            // New track: process art first, then post, so title and art change together.
            artKey = key
            artJob?.cancel()
            artJob = scope.launch {
                val result = withContext(Dispatchers.Default) { loadArt(meta)?.let(ArtProcessor::process) }
                swapArt(result)
                artJob = null
                refresh()
            }
        }
        // While new art is being processed (typically a few ms), keep showing the previous
        // content; the job calls refresh() again when done.
        if (artJob != null) return

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
                    art = art,
                    accent = accent,
                    sessionActivity = chosen.sessionActivity,
                ),
            ),
        )
    }

    private fun clearActivity() {
        lingerJob?.cancel(); lingerJob = null
        artJob?.cancel(); artJob = null
        active = null
        artKey = null
        state.remove(ID)
        releaseArt()
    }

    /** Embedded bitmap first; else a content:// URI via the thumbnail API (API 29+). */
    private fun loadArt(meta: MediaMetadata): Bitmap? {
        val embedded = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (embedded != null) return embedded
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val uri = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: return null
        return try {
            val u = Uri.parse(uri)
            if (u.scheme != "content") return null
            context.contentResolver.loadThumbnail(u, Size(ArtProcessor.MAX_ART_PX, ArtProcessor.MAX_ART_PX), null)
        } catch (_: Exception) {
            null // no read grant for that URI (common) → no art
        }
    }

    private fun swapArt(result: ArtProcessor.Result?) {
        releaseArt()
        art = result?.bitmap
        artOwned = result?.owned == true
        accent = result?.accent ?: ArtProcessor.DEFAULT_ACCENT
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
    }
}
