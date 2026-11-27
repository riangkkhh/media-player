package com.mont.mediaplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val BgBrush = Brush.verticalGradient(listOf(Color(0xFF0F0C29), Color(0xFF302B63), Color(0xFF24243E)))
val Accent = Color(0xFFB388FF)
val Pink = Color(0xFFFF4081)
val Dim = Color.White.copy(alpha = 0.6f)

@Composable
fun MediaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Color.Black,
            secondary = Color(0xFF80D8FF),
            background = Color(0xFF0F0C29), surface = Color(0xFF1C1A3A),
            onBackground = Color.White, onSurface = Color.White
        ),
        content = content
    )
}

fun fmt(ms: Long): String {
    if (ms < 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun fmtSpeed(s: Float): String = (if (s % 1f == 0f) s.toInt().toString() else s.toString()) + "x"

object Art {
    private val cache = object : LruCache<String, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(ctx: Context, item: Item, px: Int): Bitmap? {
        val k = "${item.key}@$px"
        cache.get(k)?.let { return it }
        var bmp: Bitmap? = null
        if (Build.VERSION.SDK_INT >= 29) {
            try { bmp = ctx.contentResolver.loadThumbnail(item.uri, Size(px, px), null) } catch (_: Exception) {}
        }
        if (bmp == null && !item.isVideo) {
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(ctx, item.uri)
                val b = r.embeddedPicture
                r.release()
                if (b != null) bmp = BitmapFactory.decodeByteArray(b, 0, b.size)
            } catch (_: Exception) {}
        }
        if (bmp != null) cache.put(k, bmp)
        return bmp
    }
}

@Composable
fun Thumb(item: Item, modifier: Modifier, px: Int = 200, corner: Dp = 12.dp) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, item.key, px) {
        value = withContext(Dispatchers.IO) { Art.load(ctx, item, px) }
    }
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(Color(0x33FFFFFF)),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(
                if (item.isVideo) Icons.Default.Movie else Icons.Default.MusicNote, null,
                tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.fillMaxSize(0.5f)
            )
        }
    }
}

@Composable
fun ItemRow(
    item: Item,
    badge: String?,
    onClick: () -> Unit,
    onMore: () -> Unit,
    extra: (@Composable () -> Unit)? = null
) {
    val isFav = Store.favorites.contains(item.key)
    val playing = Pb.currentKey == item.key
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(item, Modifier.size(52.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) Accent else Color.White, fontWeight = FontWeight.SemiBold
            )
            Text(
                listOfNotNull(item.artist.ifBlank { null }, fmt(item.durationMs)).joinToString(" • "),
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = Dim, fontSize = 12.sp
            )
        }
        if (badge != null) Text(badge, color = Accent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        IconButton(onClick = { Store.toggleFav(item.key) }) {
            Icon(
                if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null,
                tint = if (isFav) Pink else Dim
            )
        }
        IconButton(onClick = onMore) { Icon(Icons.Default.PlaylistAdd, null, tint = Dim) }
        extra?.invoke()
    }
}

@Composable
fun NewPlaylistDialog(onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("פלייליסט חדש") },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("שם הפלייליסט") })
        },
        confirmButton = {
            TextButton(onClick = {
                if (Store.createPlaylist(name)) { onCreated(name.trim()) }
            }) { Text("צור") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}

@Composable
fun AddToPlaylistDialog(item: Item, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("הוספה לפלייליסט") },
        text = {
            Column {
                Store.playlists.keys.sorted().forEach { pl ->
                    TextButton(
                        onClick = { Store.addToPlaylist(pl, item.key); onDismiss() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(pl) }
                }
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("פלייליסט חדש") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = name.trim()
                if (n.isNotEmpty()) {
                    Store.createPlaylist(n)
                    Store.addToPlaylist(n, item.key)
                    onDismiss()
                }
            }) { Text("צור והוסף") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("סגור") } }
    )
}
