package com.opencode.android.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opencode.android.R
import com.opencode.android.ui.LocalBackendSession
import com.opencode.android.ui.theme.spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Load state of one media reference.
 *
 * A nullable file was not enough: a failed download then looked exactly like one still
 * running, and the tile spun forever.
 */
internal sealed interface MediaFileState {
    data object Loading : MediaFileState

    data class Ready(
        val file: File,
    ) : MediaFileState

    data class Failed(
        val reason: String?,
    ) : MediaFileState
}

/**
 * The media strip under a chat message.
 *
 * Every generated artifact the message carries or produced is shown as a tile:
 * images and videos as thumbnails, audio as a labelled card. A tap opens the
 * full-screen viewer, where images zoom and videos/audio play.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MediaStrip(
    refs: List<MediaRef>,
    modifier: Modifier = Modifier,
    /** Right-align the tiles (user bubbles), like the bubble itself. */
    alignEnd: Boolean = false,
) {
    if (refs.isEmpty()) return
    var openIndex by remember(refs) { mutableIntStateOf(-1) }

    FlowRow(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = MaterialTheme.spacing.extraSmall),
        horizontalArrangement =
            Arrangement.spacedBy(
                MaterialTheme.spacing.small,
                if (alignEnd) Alignment.End else Alignment.Start,
            ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        refs.forEachIndexed { index, ref ->
            MediaTile(ref = ref, onClick = { openIndex = index })
        }
    }

    if (openIndex >= 0) {
        MediaViewer(items = refs, startIndex = openIndex, onDismiss = { openIndex = -1 })
    }
}

/**
 * Resolves a reference to a local file, sharing one download per key.
 *
 * The state survives recomposition (a streaming message recomposes constantly) and is
 * re-armed only when the reference itself changes.
 */
@Composable
internal fun rememberMediaFileState(ref: MediaRef): MediaFileState {
    val session = LocalBackendSession.current
    val cached = remember(ref.key) { MediaCache.cached(ref) }
    var state by remember(ref.key) {
        mutableStateOf<MediaFileState>(cached?.let { MediaFileState.Ready(it) } ?: MediaFileState.Loading)
    }
    LaunchedEffect(ref.key) {
        if (state is MediaFileState.Loading) {
            state =
                withContext(Dispatchers.IO) {
                    runCatching {
                        MediaCache.ensure(ref, session.api, session.currentBaseUrl())
                    }.fold(
                        onSuccess = { MediaFileState.Ready(it) },
                        onFailure = { MediaFileState.Failed(it.message) },
                    )
                }
        }
    }
    return state
}

@Composable
private fun MediaTile(
    ref: MediaRef,
    onClick: () -> Unit,
) {
    if (ref.kind == MediaKind.AUDIO) {
        AudioTile(name = ref.displayName, onClick = onClick)
        return
    }

    val state = rememberMediaFileState(ref)
    val readyFile = (state as? MediaFileState.Ready)?.file
    val bitmap =
        produceState<android.graphics.Bitmap?>(initialValue = null, readyFile, ref.key) {
            value = readyFile?.let { MediaThumbnails.load(ref, it) }
        }.value
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier =
            Modifier
                .size(width = 116.dp, height = 88.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
                .clickable(onClick = onClick),
    ) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = ref.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            readyFile != null -> {
                // Loaded, but not decodable (an exotic codec or a truncated file):
                // say so instead of showing an empty rectangle.
                Text(
                    text = stringResource(R.string.media_preview_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(MaterialTheme.spacing.small),
                )
            }

            state is MediaFileState.Failed -> {
                // A real failure must look different from "still loading".
                Text(
                    text = stringResource(R.string.media_preview_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(MaterialTheme.spacing.small),
                )
            }

            else -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        if (ref.kind == MediaKind.VIDEO) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.media_play),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun AudioTile(
    name: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .size(width = 208.dp, height = 62.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        ),
                    ),
                ).border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                    RoundedCornerShape(12.dp),
                ).clickable(onClick = onClick)
                .padding(horizontal = MaterialTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.media_play),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name ?: stringResource(R.string.media_audio),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.media_audio_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * Full-screen viewer: pinch/pan for images, an embedded player for videos, transport
 * controls for audio. Media with a previous/next sibling can be stepped through.
 */
@Composable
internal fun MediaViewer(
    items: List<MediaRef>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) }
    val ref = items.getOrNull(index) ?: return
    val state = rememberMediaFileState(ref)

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(MaterialTheme.spacing.small),
        ) {
            when (state) {
                is MediaFileState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                    )
                }

                is MediaFileState.Failed -> {
                    Text(
                        text = state.reason ?: stringResource(R.string.media_preview_failed),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.Center).padding(MaterialTheme.spacing.medium),
                    )
                }

                is MediaFileState.Ready -> {
                    when (ref.kind) {
                        MediaKind.IMAGE -> ZoomableImage(state.file)
                        MediaKind.VIDEO -> VideoPlayer(state.file)
                        MediaKind.AUDIO -> AudioPlayer(state.file, ref.displayName)
                    }
                }
            }

            // Header: name plus close. Kept over the media so it is reachable even
            // for a full-bleed image; the scrim keeps white glyphs visible on a white
            // screenshot.
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .systemBarsPadding()
                        .padding(start = MaterialTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ref.displayName ?: stringResource(R.string.media_preview),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White,
                    )
                }
            }

            if (items.size > 1) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .systemBarsPadding(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    IconButton(
                        onClick = { index = (index - 1 + items.size) % items.size },
                        enabled = index > 0,
                    ) {
                        Text("‹", color = if (index > 0) Color.White else Color.White.copy(alpha = 0.3f))
                    }
                    Text(
                        text = "${index + 1} / ${items.size}",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    IconButton(
                        onClick = { index = (index + 1) % items.size },
                        enabled = index < items.size - 1,
                    ) {
                        Text(
                            "›",
                            color = if (index < items.size - 1) Color.White else Color.White.copy(alpha = 0.3f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(file: File) {
    val bitmap =
        produceState<android.graphics.Bitmap?>(initialValue = null, file) {
            // Bounded "full" decode: detail far beyond 4096 px is not visible on a
            // phone but its allocation is what kills the process.
            value = MediaThumbnails.fullImage(file)
        }.value

    if (bitmap == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    // Keyed on the file: stepping to the next image must start unzoomed.
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale <= 1f) Offset.Zero else offset + pan
                    }
                }.graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
    )
}

@Composable
private fun VideoPlayer(file: File) {
    var videoView by remember { mutableStateOf<android.widget.VideoView?>(null) }
    DisposableEffect(file) {
        onDispose { videoView?.stopPlayback() }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            android.widget.VideoView(ctx).apply {
                val controller = android.widget.MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                setVideoPath(file.absolutePath)
                setOnCompletionListener { controller.show() }
                videoView = this
                start()
            }
        },
        update = { view ->
            videoView = view
            // A switched item reuses the view: only reload when the path differs.
            if (view.tag != file.absolutePath) {
                view.tag = file.absolutePath
                view.setVideoPath(file.absolutePath)
                view.start()
            }
        },
    )
}

@Composable
private fun AudioPlayer(
    file: File,
    title: String?,
) {
    var player by remember(file) { mutableStateOf<android.media.MediaPlayer?>(null) }
    var playing by remember(file) { mutableStateOf(false) }
    var duration by remember(file) { mutableIntStateOf(0) }
    var position by remember(file) { mutableFloatStateOf(0f) }

    DisposableEffect(file) {
        val created =
            android.media.MediaPlayer().apply {
                runCatching {
                    setDataSource(file.absolutePath)
                    prepare()
                }
                setOnCompletionListener {
                    playing = false
                    position = 0f
                }
            }
        player = created
        duration = runCatching { created.duration }.getOrDefault(0).coerceAtLeast(0)
        onDispose {
            runCatching { created.release() }
            player = null
        }
    }

    LaunchedEffect(playing, player) {
        while (playing) {
            player?.let { position = it.currentPosition.toFloat() }
            kotlinx.coroutines.delay(250)
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(36.dp))
                        .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(36.dp),
                )
            }
            Text(
                text = title ?: stringResource(R.string.media_audio),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Slider(
                value = position.coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                onValueChange = { value ->
                    position = value
                    runCatching { player?.seekTo(value.toInt()) }
                },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatClock(position.toInt()),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = formatClock(duration),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(
                onClick = {
                    val current = player ?: return@IconButton
                    if (playing) {
                        runCatching { current.pause() }
                        playing = false
                    } else {
                        runCatching {
                            if (current.currentPosition >= current.duration - 200) current.seekTo(0)
                            current.start()
                        }
                        playing = true
                    }
                },
            ) {
                Icon(
                    imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.media_pause else R.string.media_play),
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}

private fun formatClock(milliseconds: Int): String {
    val total = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
