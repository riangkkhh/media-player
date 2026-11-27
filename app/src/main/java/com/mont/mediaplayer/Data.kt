package com.mont.mediaplayer

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.Equalizer
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Item(
    val key: String,
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val folder: String,
    val folderPath: String,
    val durationMs: Long,
    val isVideo: Boolean,
    val albumId: Long
) {
    fun toMediaItem(): MediaItem {
        val art = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
        return MediaItem.Builder()
            .setMediaId(key)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist.ifBlank { folder })
                    .setArtworkUri(if (isVideo) null else art)
                    .build()
            ).build()
    }
}

object Library {
    var items by mutableStateOf<List<Item>>(emptyList())
    var byKey by mutableStateOf<Map<String, Item>>(emptyMap())

    fun set(list: List<Item>) {
        items = list
        byKey = list.associateBy { it.key }
    }

    fun scan(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        val cr = ctx.contentResolver

        val aUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val aProj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID
        )
        try {
            cr.query(aUri, aProj, "${MediaStore.Audio.Media.DURATION}>=15000", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val path = c.getString(iData) ?: ""
                    val parent = File(path).parentFile
                    val artist = c.getString(iArtist) ?: ""
                    out.add(
                        Item(
                            "a$id", id, ContentUris.withAppendedId(aUri, id),
                            c.getString(iTitle) ?: File(path).nameWithoutExtension,
                            if (artist.contains("unknown", true)) "" else artist,
                            parent?.name ?: "?", parent?.path ?: "?",
                            c.getLong(iDur), false, c.getLong(iAlbum)
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        val vUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val vProj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DATA, MediaStore.Video.Media.DURATION
        )
        try {
            cr.query(vUri, vProj, "${MediaStore.Video.Media.DURATION}>0", null,
                "${MediaStore.Video.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val iData = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val path = c.getString(iData) ?: ""
                    val parent = File(path).parentFile
                    out.add(
                        Item(
                            "v$id", id, ContentUris.withAppendedId(vUri, id),
                            c.getString(iTitle) ?: File(path).nameWithoutExtension,
                            "", parent?.name ?: "?", parent?.path ?: "?",
                            c.getLong(iDur), true, 0L
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return out
    }
}

object Store {
    private lateinit var sp: SharedPreferences
    private var ready = false

    val favorites = mutableStateListOf<String>()
    val playlists = mutableStateMapOf<String, List<String>>()
    val recents = mutableStateListOf<String>()
    val counts = mutableStateMapOf<String, Int>()
    var eqLevels: List<Int> = emptyList()
        private set

    fun init(ctx: Context) {
        if (ready) return
        ready = true
        sp = ctx.applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE)
        try {
            sp.getString("fav", null)?.let { s ->
                val a = JSONArray(s); for (i in 0 until a.length()) favorites.add(a.getString(i))
            }
            sp.getString("rec", null)?.let { s ->
                val a = JSONArray(s); for (i in 0 until a.length()) recents.add(a.getString(i))
            }
            sp.getString("pl", null)?.let { s ->
                val o = JSONObject(s)
                for (k in o.keys()) {
                    val a = o.getJSONArray(k)
                    playlists[k] = List(a.length()) { i -> a.getString(i) }
                }
            }
            sp.getString("cnt", null)?.let { s ->
                val o = JSONObject(s)
                for (k in o.keys()) counts[k] = o.getInt(k)
            }
            sp.getString("eq", null)?.let { s ->
                val a = JSONArray(s)
                eqLevels = List(a.length()) { i -> a.getInt(i) }
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        val pl = JSONObject()
        for ((k, v) in playlists) pl.put(k, JSONArray(v))
        val cnt = JSONObject()
        for ((k, v) in counts) cnt.put(k, v)
        sp.edit()
            .putString("fav", JSONArray(favorites.toList()).toString())
            .putString("rec", JSONArray(recents.toList()).toString())
            .putString("pl", pl.toString())
            .putString("cnt", cnt.toString())
            .apply()
    }

    fun saveEq(levels: List<Int>) {
        eqLevels = levels
        sp.edit().putString("eq", JSONArray(levels).toString()).apply()
    }

    fun toggleFav(key: String) {
        if (!favorites.remove(key)) favorites.add(0, key)
        save()
    }

    fun markPlayed(key: String) {
        recents.remove(key)
        recents.add(0, key)
        while (recents.size > 60) recents.removeAt(recents.size - 1)
        counts[key] = (counts[key] ?: 0) + 1
        save()
    }

    fun createPlaylist(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || playlists.containsKey(n)) return false
        playlists[n] = emptyList()
        save()
        return true
    }

    fun deletePlaylist(name: String) { playlists.remove(name); save() }

    fun addToPlaylist(name: String, key: String) {
        val cur = playlists[name] ?: emptyList()
        if (!cur.contains(key)) playlists[name] = cur + key
        save()
    }

    fun removeFromPlaylist(name: String, key: String) {
        playlists[name] = (playlists[name] ?: emptyList()).filter { it != key }
        save()
    }
}

object Eq {
    private var eq: Equalizer? = null
    val levels = mutableStateListOf<Int>()
    val freqs = mutableStateListOf<Int>()
    var min by mutableIntStateOf(-1500)
    var max by mutableIntStateOf(1500)
    val available get() = eq != null

    fun attach(sessionId: Int) {
        if (sessionId == 0) return
        try {
            eq?.release()
            val e = Equalizer(0, sessionId)
            e.enabled = true
            eq = e
            min = e.bandLevelRange[0].toInt()
            max = e.bandLevelRange[1].toInt()
            levels.clear(); freqs.clear()
            val saved = Store.eqLevels
            for (b in 0 until e.numberOfBands.toInt()) {
                val lv = (saved.getOrNull(b) ?: e.getBandLevel(b.toShort()).toInt()).coerceIn(min, max)
                e.setBandLevel(b.toShort(), lv.toShort())
                levels.add(lv)
                freqs.add(e.getCenterFreq(b.toShort()) / 1000)
            }
        } catch (_: Exception) {}
    }

    fun set(band: Int, value: Int) {
        try {
            eq?.setBandLevel(band.toShort(), value.toShort())
            levels[band] = value
            Store.saveEq(levels.toList())
        } catch (_: Exception) {}
    }
}

/** Connection to the playback service (audio). */
object Pb {
    var controller by mutableStateOf<MediaController?>(null)
    var currentKey by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var shuffle by mutableStateOf(false)

    fun connect(ctx: Context) {
        if (controller != null) return
        val app = ctx.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        f.addListener({
            val c = try { f.get() } catch (e: Exception) { null }
            if (c != null) {
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { Pb.isPlaying = isPlaying }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        currentKey = mediaItem?.mediaId?.takeIf { it.isNotEmpty() }
                        if (mediaItem != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                            Store.markPlayed(mediaItem.mediaId)
                        }
                    }
                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                        speed = playbackParameters.speed
                    }
                    override fun onRepeatModeChanged(repeatMode: Int) { repeat = repeatMode }
                    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { shuffle = shuffleModeEnabled }
                })
                currentKey = c.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() }
                isPlaying = c.isPlaying
                speed = c.playbackParameters.speed
                repeat = c.repeatMode
                shuffle = c.shuffleModeEnabled
                controller = c
            }
        }, ContextCompat.getMainExecutor(app))
    }

    fun play(list: List<Item>, index: Int) {
        val c = controller ?: return
        var l = list
        var i = index
        if (l.size > 300) {
            val from = (index - 150).coerceAtLeast(0)
            val to = (from + 300).coerceAtMost(l.size)
            l = l.subList(from, to)
            i = index - from
        }
        c.setMediaItems(l.map { it.toMediaItem() }, i, 0L)
        c.prepare()
        c.play()
        Store.markPlayed(l[i].key)
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }
}
