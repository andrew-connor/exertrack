package com.exertrack.app.ui

import android.media.AudioManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.exertrack.app.data.MediaFiles
import com.exertrack.app.data.MediaKind
import com.exertrack.app.data.RowMedia
import com.exertrack.app.ui.theme.ExColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Zoom range of the image viewer; double-tap jumps between 1x and [DOUBLE_TAP_ZOOM]. */
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Decoded previews, most recently used last, so a list scrolling past the same rows does not
 * decode the same JPEG again. Keyed by the media file name, which changes whenever the file does.
 */
private object ThumbnailCache {
    private const val MAX_ENTRIES = 24
    private val entries = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    fun get(key: String): ImageBitmap? = entries[key]

    @Synchronized
    fun put(key: String, bitmap: ImageBitmap) {
        entries[key] = bitmap
    }
}

/**
 * A preview of an exercise's photo or video: the stored thumbnail (loaded off the main thread
 * through MediaFiles.loadThumbnail, cached per media file), cropped to fill [modifier]'s bounds,
 * with a play badge over video previews. Shows a neutral placeholder while loading or when the
 * preview is missing. Tapping calls [onClick] when given.
 */
@Composable
fun MediaThumbnail(
    media: RowMedia,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
): Unit {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = media.file) {
        // The producer restarts when the file changes; clear the previous file's picture first.
        value = ThumbnailCache.get(media.file)
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                ThumbnailCache.get(media.file)
                    ?: MediaFiles.loadThumbnail(context, media)?.asImageBitmap()?.also { ThumbnailCache.put(media.file, it) }
            }
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val label = if (media.kind == MediaKind.VIDEO) "Video" else "Photo"

    Box(
        modifier = modifier
            .clip(shape)
            .background(ExColors.surfaceHigh, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "Exercise ${label.lowercase()}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = ExColors.textDim,
            )
        }
        if (media.kind == MediaKind.VIDEO) {
            PlayBadge(size = 40.dp, iconSize = 28.dp)
        }
    }
}

/** A translucent circle with a play arrow; tappable when [onClick] is given. */
@Composable
private fun PlayBadge(
    size: Dp,
    iconSize: Dp,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(ExColors.background.copy(alpha = 0.6f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "Play",
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Full-screen viewer. Images: the full-size image (MediaFiles.loadImage) on a black background
 * with pinch-to-zoom and drag; tap the close control or the system back to dismiss. Videos: a
 * player that shows the first frame and a large play control, and only starts when the user
 * taps play (never automatically); playback stops when the viewer closes. Sound goes through
 * the media stream like everything else in the app.
 */
@Composable
fun MediaViewer(
    media: RowMedia,
    onDismiss: () -> Unit,
): Unit {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler(onBack = onDismiss)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            when (media.kind) {
                MediaKind.IMAGE -> ImageViewer(media)
                MediaKind.VIDEO -> VideoPlayer(media)
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

// ---- images ----

private sealed class ImageLoad {
    object Loading : ImageLoad()
    object Failed : ImageLoad()
    class Ready(val image: ImageBitmap) : ImageLoad()
}

@Composable
private fun ImageViewer(media: RowMedia) {
    val context = LocalContext.current.applicationContext
    val load by produceState<ImageLoad>(initialValue = ImageLoad.Loading, key1 = media.file) {
        value = ImageLoad.Loading
        val image = withContext(Dispatchers.IO) { MediaFiles.loadImage(context, media, 2048)?.asImageBitmap() }
        value = if (image == null) ImageLoad.Failed else ImageLoad.Ready(image)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = load) {
            ImageLoad.Loading -> CircularProgressIndicator(color = ExColors.textDim)
            ImageLoad.Failed -> Text(
                text = "Could not load image",
                style = MaterialTheme.typography.bodyLarge,
                color = ExColors.textDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
            is ImageLoad.Ready -> ZoomableImage(current.image)
        }
    }
}

/** The image fitted to the screen, with pinch-to-zoom, drag while zoomed, and double-tap zoom. */
@Composable
private fun ZoomableImage(image: ImageBitmap) {
    var scale by remember { mutableFloatStateOf(MIN_ZOOM) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    // The layer scales around its centre, so the picture may be dragged by at most half of the
    // extra size in each direction before its edge would leave the screen.
    fun clampOffset(candidate: Offset, atScale: Float): Offset {
        if (atScale <= MIN_ZOOM) return Offset.Zero
        val maxX = size.width * (atScale - 1f) / 2f
        val maxY = size.height * (atScale - 1f) / 2f
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        scale = newScale
        offset = clampOffset(offset + panChange, newScale)
    }

    Image(
        bitmap = image,
        contentDescription = "Exercise photo",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > MIN_ZOOM) {
                            scale = MIN_ZOOM
                            offset = Offset.Zero
                        } else {
                            // Keep the tapped spot under the finger: it moves away from the
                            // centre by the zoom factor, so shift the picture back by the same amount.
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            scale = DOUBLE_TAP_ZOOM
                            offset = clampOffset((centre - tap) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM)
                        }
                    },
                )
            }
            .transformable(transformState)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

// ---- videos ----

@Composable
private fun VideoPlayer(media: RowMedia) {
    val context = LocalContext.current
    val path = remember(media.file) { MediaFiles.file(context, media).path }
    var playing by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<VideoView?>(null) }
    var controller by remember { mutableStateOf<MediaController?>(null) }

    // The transport controls can pause and resume too, so mirror the real playback state
    // rather than trusting our own taps.
    LaunchedEffect(player) {
        val view = player ?: return@LaunchedEffect
        while (true) {
            playing = view.isPlaying
            delay(250)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            controller?.hide()
            player?.stopPlayback()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                val frame = FrameLayout(ctx)
                val view = VideoView(ctx)
                frame.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
                val transport = MediaController(ctx).also { it.setAnchorView(frame) }
                view.setMediaController(transport)
                // Duck other audio instead of stopping it, like the app's chimes and speech.
                view.setAudioFocusRequest(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                view.setOnPreparedListener { mp ->
                    mp.isLooping = false
                    // Show the first frame; playback waits for the play control.
                    view.seekTo(1)
                    prepared = true
                }
                view.setOnCompletionListener { playing = false }
                view.setOnErrorListener { _, _, _ ->
                    error = "Could not play this video"
                    playing = false
                    true
                }
                view.setVideoPath(path)
                player = view
                controller = transport
                frame
            },
            modifier = Modifier.fillMaxSize(),
        )
        val message = error
        when {
            message != null -> Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = ExColors.textDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
            !prepared -> CircularProgressIndicator(color = ExColors.textDim)
            !playing -> PlayBadge(
                size = 88.dp,
                iconSize = 60.dp,
                onClick = {
                    player?.start()
                    playing = true
                },
            )
        }
    }
}
