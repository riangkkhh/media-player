package com.mont.mediaplayer

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
fun Main() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        Pb.connect(ctx)
        Library.set(withContext(Dispatchers.IO) { Library.scan(ctx) })
    }
    val pager = rememberPagerState { 6 }
    val scope = rememberCoroutineScope()
    var showPlayer by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf<Pair<List<Item>, Int>?>(null) }
    var plTarget by remember { mutableStateOf<Item?>(null) }

    val play: (List<Item>, Item) -> Unit = { list, it ->
        val same = list.filter { x -> x.isVideo == it.isVideo }
        val idx = same.indexOf(it).coerceAtLeast(0)
        if (it.isVideo) video = same to idx else Pb.play(same, idx)
    }
    val onMore: (Item) -> Unit = { plTarget = it }

    val tabs: List<Pair<ImageVector, String>> = listOf(
        Icons.Default.MusicNote to "שירים",
        Icons.Default.VideoLibrary to "סרטונים",
        Icons.Default.Favorite to "מועדפים",
        Icons.Default.QueueMusic to "פלייליסטים",
        Icons.Default.History to "אחרונים",
        Icons.Default.TrendingUp to "הכי הרבה"
    )

    Box(Modifier.fillMaxSize().background(BgBrush)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                tabs.forEachIndexed { i, (icon, label) ->
                    val sel = pager.currentPage == i
                    Column(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .clickable { scope.launch { pager.animateScrollToPage(i) } }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(icon, label, tint = if (sel) Accent else Dim, modifier = Modifier.size(26.dp))
                        Text(label, fontSize = 10.sp, color = if (sel) Accent else Dim)
                        Box(
                            Modifier.padding(top = 3.dp).height(3.dp)
                                .width(if (sel) 22.dp else 0.dp)
                                .background(Accent, RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> FoldersPage(false, play, onMore)
                    1 -> FoldersPage(true, play, onMore)
                    2 -> {
                        val all = Store.favorites.mapNotNull { Library.byKey[it] }
                        ItemsPage(
                            listOf("שירים" to all.filter { !it.isVideo }, "סרטונים" to all.filter { it.isVideo }),
                            "עדיין אין מועדפים — לחצו על הלב ♥ ליד שיר או סרטון", play, onMore
                        )
                    }
                    3 -> PlaylistsPage(play, onMore)
                    4 -> {
                        val all = Store.recents.mapNotNull { Library.byKey[it] }
                        ItemsPage(listOf("" to all), "עדיין לא נוגן או נצפה כלום", play, onMore)
                    }
                    else -> {
                        val sorted = Store.counts.entries.sortedByDescending { it.value }
                            .mapNotNull { e -> Library.byKey[e.key]?.let { it to e.value } }
                        ItemsPage(
                            listOf(
                                "השירים המושמעים ביותר" to sorted.filter { !it.first.isVideo }.take(30).map { it.first },
                                "הסרטונים הנצפים ביותר" to sorted.filter { it.first.isVideo }.take(30).map { it.first }
                            ),
                            "הסטטיסטיקות יתמלאו ככל שתשמעו ותצפו", play, onMore,
                            badge = { "×${Store.counts[it.key] ?: 0}" }
                        )
                    }
                }
            }
        }

        val cur = Pb.currentKey?.let { Library.byKey[it] }
        if (cur != null) {
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
                MiniPlayer(cur) { showPlayer = true }
            }
        }
        if (showPlayer && cur != null) {
            BackHandler { showPlayer = false }
            FullPlayer(cur, onClose = { showPlayer = false }, onAdd = { plTarget = it })
        }
        video?.let { (list, idx) -> VideoScreen(list, idx) { video = null } }
        plTarget?.let { AddToPlaylistDialog(it) { plTarget = null } }
    }
}

@Composable
fun ItemsPage(
    sections: List<Pair<String, List<Item>>>,
    emptyText: String,
    play: (List<Item>, Item) -> Unit,
    onMore: (Item) -> Unit,
    badge: (Item) -> String? = { null },
    extra: (@Composable (Item) -> Unit)? = null
) {
    if (sections.all { it.second.isEmpty() }) {
        Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
            Text(emptyText, color = Dim, textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 110.dp)) {
        sections.forEach { (title, list) ->
            if (list.isNotEmpty()) {
                if (title.isNotEmpty()) {
                    item(key = "h_$title") {
                        Text(
                            title, color = Accent, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                        )
                    }
                }
                items(list, key = { it.key }) { row ->
                    ItemRow(
                        row, badge(row), onClick = { play(list, row) }, onMore = { onMore(row) },
                        extra = if (extra != null) ({ extra(row) }) else null
                    )
                }
            }
        }
    }
}

@Composable
fun FoldersPage(video: Boolean, play: (List<Item>, Item) -> Unit, onMore: (Item) -> Unit) {
    val all = Library.items.filter { it.isVideo == video }
    val groups = remember(all) {
        all.groupBy { it.folderPath }.toList().sortedBy { it.second.first().folder.lowercase() }
    }
    var open by remember { mutableStateOf<String?>(null) }
    BackHandler(open != null) { open = null }

    if (all.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
            Text(if (video) "לא נמצאו סרטונים במכשיר" else "לא נמצאו שירים במכשיר", color = Dim)
        }
        return
    }
    val o = open
    if (o == null) {
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 110.dp)) {
            items(groups, key = { it.first }) { (path, list) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
                        .background(Color(0x22FFFFFF)).clickable { open = path }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Folder, null, tint = Accent, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(list.first().folder, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${list.size} " + if (video) "סרטונים" else "שירים", color = Dim, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronLeft, null, tint = Dim)
                }
            }
        }
    } else {
        val list = groups.firstOrNull { it.first == o }?.second ?: emptyList()
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { open = null }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                Text(list.firstOrNull()?.folder ?: "", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!video && list.isNotEmpty()) {
                    IconButton(onClick = { play(list, list.first()) }) { Icon(Icons.Default.PlayArrow, null, tint = Accent) }
                }
            }
            ItemsPage(listOf("" to list), "", play, onMore)
        }
    }
}

@Composable
fun PlaylistsPage(play: (List<Item>, Item) -> Unit, onMore: (Item) -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    var showNew by remember { mutableStateOf(false) }
    BackHandler(open != null) { open = null }
    if (showNew) NewPlaylistDialog({ showNew = false }) { showNew = false }

    val o = open
    if (o == null) {
        Column(Modifier.fillMaxSize()) {
            Button(onClick = { showNew = true }, modifier = Modifier.padding(12.dp)) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("פלייליסט חדש")
            }
            if (Store.playlists.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                    Text("אין פלייליסטים עדיין", color = Dim)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 110.dp)) {
                    items(Store.playlists.keys.sorted(), key = { it }) { name ->
                        val count = Store.playlists[name]?.size ?: 0
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
                                .background(Color(0x22FFFFFF)).clickable { open = name }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.QueueMusic, null, tint = Accent, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("$count פריטים", color = Dim, fontSize = 12.sp)
                            }
                            IconButton(onClick = { Store.deletePlaylist(name) }) {
                                Icon(Icons.Default.Delete, null, tint = Dim)
                            }
                        }
                    }
                }
            }
        }
    } else {
        val list = (Store.playlists[o] ?: emptyList()).mapNotNull { Library.byKey[it] }
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { open = null }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                Text(o, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (list.isNotEmpty()) {
                    IconButton(onClick = { play(list, list.first()) }) { Icon(Icons.Default.PlayArrow, null, tint = Accent) }
                }
            }
            ItemsPage(
                listOf("" to list), "הפלייליסט ריק — הוסיפו שירים עם הכפתור ליד כל שיר", play, onMore,
                extra = { it2 ->
                    IconButton(onClick = { Store.removeFromPlaylist(o, it2.key) }) {
                        Icon(Icons.Default.Close, null, tint = Dim)
                    }
                }
            )
        }
    }
}

@Composable
fun MiniPlayer(item: Item, open: () -> Unit) {
    Row(
        Modifier.padding(10.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Color(0xF01C1A3A)).clickable(onClick = open).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(item, Modifier.size(46.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(item.artist.ifBlank { item.folder }, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { Pb.toggle() }) {
            Icon(if (Pb.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        IconButton(onClick = { Pb.controller?.seekToNextMediaItem() }) {
            Icon(Icons.Default.SkipNext, null, tint = Color.White)
        }
    }
}

@Composable
fun FullPlayer(item: Item, onClose: () -> Unit, onAdd: (Item) -> Unit) {
    val c = Pb.controller
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var showEq by remember { mutableStateOf(false) }
    LaunchedEffect(c, item.key) {
        while (true) {
            if (c != null) { pos = c.currentPosition; dur = maxOf(0L, c.duration) }
            delay(400)
        }
    }
    val isFav = Store.favorites.contains(item.key)

    Column(
        Modifier.fillMaxSize().background(BgBrush).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
            Text("מנגן עכשיו", Modifier.weight(1f), textAlign = TextAlign.Center, color = Dim)
            IconButton(onClick = { Store.toggleFav(item.key) }) {
                Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (isFav) Pink else Color.White)
            }
        }
        Spacer(Modifier.height(12.dp))
        Thumb(item, Modifier.fillMaxWidth().aspectRatio(1f), px = 900, corner = 28.dp)
        Spacer(Modifier.height(20.dp))
        Text(item.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(item.artist.ifBlank { item.folder }, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))

        Slider(
            value = drag ?: pos.toFloat(),
            onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { c?.seekTo(it.toLong()) }; drag = null },
            valueRange = 0f..maxOf(dur, 1L).toFloat()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt((drag ?: pos.toFloat()).toLong()), color = Dim, fontSize = 12.sp)
            Text(fmt(dur), color = Dim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { c?.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
            IconButton(onClick = { c?.seekTo(((c.currentPosition) - 10_000).coerceAtLeast(0)) }) { Icon(Icons.Default.Replay10, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
            FilledIconButton(onClick = { Pb.toggle() }, modifier = Modifier.size(72.dp)) {
                Icon(if (Pb.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(40.dp))
            }
            IconButton(onClick = { c?.seekTo(((c.currentPosition) + 10_000).coerceAtMost(maxOf(0L, c.duration))) }) { Icon(Icons.Default.Forward10, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
            IconButton(onClick = { c?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { c?.shuffleModeEnabled = !Pb.shuffle }) {
                Icon(Icons.Default.Shuffle, null, tint = if (Pb.shuffle) Accent else Dim)
            }
            IconButton(onClick = {
                c?.repeatMode = when (Pb.repeat) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }) {
                Icon(
                    if (Pb.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, null,
                    tint = if (Pb.repeat != Player.REPEAT_MODE_OFF) Accent else Dim
                )
            }
            IconButton(onClick = { onAdd(item) }) { Icon(Icons.Default.PlaylistAdd, null, tint = Color.White) }
            IconButton(onClick = { showEq = !showEq }) {
                Icon(Icons.Default.Equalizer, null, tint = if (showEq) Accent else Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Speed, null, tint = Dim)
            SPEEDS.forEach { s ->
                FilterChip(
                    selected = kotlin.math.abs(Pb.speed - s) < 0.01f,
                    onClick = { c?.setPlaybackSpeed(s) },
                    label = { Text(fmtSpeed(s)) }
                )
            }
        }
        if (showEq) {
            Spacer(Modifier.height(12.dp))
            if (!Eq.available || Eq.levels.isEmpty()) {
                Text("האקוולייזר אינו זמין במכשיר זה", color = Dim)
            } else {
                Eq.levels.forEachIndexed { i, lv ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${Eq.freqs.getOrNull(i) ?: 0}Hz", color = Dim, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                        Slider(
                            value = lv.toFloat(),
                            onValueChange = { Eq.set(i, it.toInt()) },
                            valueRange = Eq.min.toFloat()..Eq.max.toFloat()
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun VideoScreen(list: List<Item>, start: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var overlay by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    var title by remember { mutableStateOf(list[start].title) }

    val exo = remember {
        ExoPlayer.Builder(ctx)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().apply {
                setMediaItems(
                    list.map {
                        MediaItem.Builder().setMediaId(it.key).setUri(it.uri)
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).build()).build()
                    }, start, 0L
                )
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(Unit) {
        Pb.controller?.pause()
        Store.markPlayed(list[start].key)
        val l = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem != null) {
                    title = mediaItem.mediaMetadata.title?.toString() ?: ""
                    if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) Store.markPlayed(mediaItem.mediaId)
                }
            }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l); exo.release() }
    }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    player = exo
                    setShowRewindButton(true)
                    setShowFastForwardButton(true)
                    setShowNextButton(list.size > 1)
                    setShowPreviousButton(list.size > 1)
                    controllerShowTimeoutMs = 3000
                    keepScreenOn = true
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                        overlay = v == View.VISIBLE
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        AnimatedVisibility(overlay, Modifier.align(Alignment.TopStart)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                Text(title, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = {
                    val next = SPEEDS[(SPEEDS.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f } + 1) % SPEEDS.size]
                    speed = next
                    exo.setPlaybackSpeed(next)
                }) { Text(fmtSpeed(speed), color = Accent, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
