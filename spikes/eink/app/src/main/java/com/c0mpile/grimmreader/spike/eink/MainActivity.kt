@file:OptIn(ExperimentalMaterial3Api::class)

package com.c0mpile.grimmreader.spike.eink

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

/**
 * Spike c: "E-ink look" display theme, emulator only (see PLAN.md §9).
 * Driven with `adb shell am start -n <pkg>/.MainActivity [--es look light|warm|cool] [--ei flash N]
 * [--ez grain true] [--es screen library|text|comic]`. Logs to tag EINK.
 */
class MainActivity : ComponentActivity() {
    private val config = mutableStateOf(SpikeConfig())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config.value = SpikeConfig.from(intent)
        setContent { SpikeApp(config.value) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        config.value = SpikeConfig.from(intent)
    }
}

enum class Look { Light, Warm, Cool }

enum class Screen { Library, Text, Comic }

data class SpikeConfig(
    val look: Look = Look.Light,
    val flashEvery: Int = 0,
    val grain: Boolean = false,
    val screen: Screen = Screen.Library,
) {
    companion object {
        fun from(intent: Intent) = SpikeConfig(
            look = when (intent.getStringExtra("look")) {
                "warm" -> Look.Warm
                "cool" -> Look.Cool
                else -> Look.Light
            },
            flashEvery = intent.getIntExtra("flash", 0),
            grain = intent.getBooleanExtra("grain", false),
            screen = when (intent.getStringExtra("screen")) {
                "text" -> Screen.Text
                "comic" -> Screen.Comic
                else -> Screen.Library
            },
        ).also { Log.i(TAG, "config $it") }
    }
}

private const val TAG = "EINK"

val LocalEinkLook = staticCompositionLocalOf { false }

private val WarmPaper = Color(0xFFF4F1EA)
private val WarmInk = Color(0xFF1A1A1A)
private val CoolPaper = Color(0xFFEEF0EF)
private val CoolInk = Color(0xFF161819)

/** Saturation 0, contrast ×1.15, as specified for covers and comic pages. */
private val EinkFilter: ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix().apply {
        setToSaturation(0f)
        val c = 1.15f
        val t = (1f - c) * 128f
        timesAssign(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    },
)

/** Indication that draws nothing: no ripple, no pressed overlay. */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}

    override fun equals(other: Any?) = other === this

    override fun hashCode() = -1
}

@Composable
fun SpikeTheme(look: Look, content: @Composable () -> Unit) {
    if (look == Look.Light) {
        val scheme = lightColorScheme(primary = Color(0xFFF54900), background = Color(0xFFFAFAF9), surface = Color.White)
        MaterialTheme(colorScheme = scheme, content = content)
        return
    }
    val paper = if (look == Look.Warm) WarmPaper else CoolPaper
    val ink = if (look == Look.Warm) WarmInk else CoolInk
    val scheme = lightColorScheme(
        primary = ink, onPrimary = paper, primaryContainer = paper, onPrimaryContainer = ink,
        secondary = ink, onSecondary = paper, secondaryContainer = paper, onSecondaryContainer = ink,
        background = paper, onBackground = ink, surface = paper, onSurface = ink,
        surfaceVariant = paper, onSurfaceVariant = ink.copy(alpha = 0.8f), surfaceTint = Color.Transparent,
        surfaceBright = paper, surfaceDim = paper, surfaceContainerLowest = paper, surfaceContainerLow = paper,
        surfaceContainer = paper, surfaceContainerHigh = paper, surfaceContainerHighest = paper,
        outline = ink, outlineVariant = ink,
    )
    val viewConfig = LocalViewConfiguration.current
    val einkViewConfig = remember(viewConfig) {
        object : ViewConfiguration by viewConfig {
            override val minimumTouchTargetSize = DpSize(56.dp, 56.dp)
        }
    }
    CompositionLocalProvider(
        LocalEinkLook provides true,
        LocalViewConfiguration provides einkViewConfig,
        LocalIndication provides NoIndication,
        LocalRippleConfiguration provides null,
        LocalMinimumInteractiveComponentSize provides 56.dp,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
fun SpikeApp(config: SpikeConfig) {
    var look by remember(config) { mutableStateOf(config.look) }
    var screen by remember(config) { mutableStateOf(config.screen) }
    SpikeTheme(look) {
        Surface(Modifier.fillMaxSize().paperGrain(config.grain && look != Look.Light), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.safeDrawingPadding()) {
                when (screen) {
                    Screen.Library -> Library(look, { look = it }, { screen = it })
                    Screen.Text -> PagedReader(config.flashEvery, { screen = Screen.Library }) { TextPage(it) }
                    Screen.Comic -> PagedReader(config.flashEvery, { screen = Screen.Library }) { ComicPage(it) }
                }
            }
        }
    }
}

@Composable
private fun Library(look: Look, onLook: (Look) -> Unit, onOpen: (Screen) -> Unit) {
    val eink = LocalEinkLook.current
    Column(Modifier.padding(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Look.entries.forEach { l ->
                if (l == look) Button({ onLook(l) }) { Text(l.name) } else OutlinedButton({ onLook(l) }) { Text(l.name) }
            }
            OutlinedButton({ onOpen(Screen.Text) }) { Text("Text") }
            OutlinedButton({ onOpen(Screen.Comic) }) { Text("Comic") }
        }
        val covers = remember { List(12) { syntheticCover(it) } }
        LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(covers.indices.toList()) { i ->
                Card(
                    onClick = { onOpen(Screen.Text) },
                    border = if (eink) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
                    elevation = if (eink) CardDefaults.cardElevation(0.dp) else CardDefaults.elevatedCardElevation(),
                ) {
                    Image(covers[i], null, Modifier.fillMaxWidth().aspectRatio(5f / 7f), contentScale = ContentScale.Crop, colorFilter = if (eink) EinkFilter else null)
                    Text("Sample book ${i + 1}", Modifier.padding(6.dp), fontSize = 13.sp, fontWeight = if (eink) FontWeight.SemiBold else FontWeight.Medium)
                    Text("Author ${'A' + i}", Modifier.padding(start = 6.dp, bottom = 6.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Tap zones 30/40/30: left = previous, right = next, centre = back to library. */
@Composable
private fun PagedReader(flashEvery: Int, onBack: () -> Unit, page: @Composable (Int) -> Unit) {
    val eink = LocalEinkLook.current
    val ink = MaterialTheme.colorScheme.onBackground
    val paper = MaterialTheme.colorScheme.background
    var index by remember { mutableIntStateOf(0) }
    var turns by remember { mutableIntStateOf(0) }
    var flash by remember { mutableStateOf<Color?>(null) }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { o ->
                when {
                    o.x < size.width * 0.3f -> if (index > 0) index--
                    o.x > size.width * 0.7f -> index++
                    else -> return@detectTapGestures onBack()
                }
                turns++
                Log.i(TAG, "turn $turns page $index")
            }
        },
    ) {
        if (eink) {
            page(index)
        } else {
            AnimatedContent(index, transitionSpec = { slideInHorizontally { it } togetherWith slideOutHorizontally { -it } }, label = "page") { page(it) }
        }
        flash?.let { Box(Modifier.fillMaxSize().background(it)) }
    }
    LaunchedEffect(turns) {
        if (!eink || flashEvery <= 0 || turns == 0 || turns % flashEvery != 0) return@LaunchedEffect
        val t0 = withFrameNanos { it }
        flash = ink
        withFrameNanos {}
        val t1 = withFrameNanos { it }
        flash = paper
        withFrameNanos {}
        val t2 = withFrameNanos { it }
        flash = null
        Log.i(TAG, "flash ink ${(t1 - t0) / 1_000_000} ms, paper ${(t2 - t1) / 1_000_000} ms")
    }
}

@Composable
private fun TextPage(index: Int) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Page ${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        repeat(5) { p ->
            Text(filler(index * 5 + p), Modifier.padding(top = 12.dp), fontSize = 18.sp, lineHeight = 27.sp)
        }
    }
}

@Composable
private fun ComicPage(index: Int) {
    val bmp = remember(index) { syntheticComicPage(index) }
    Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = if (LocalEinkLook.current) EinkFilter else null)
}

/** Static paper grain: one pre-baked 128 px noise tile, drawn as a repeated shader over the content. */
private fun Modifier.paperGrain(enabled: Boolean): Modifier {
    if (!enabled) return this
    val brush = ShaderBrush(ImageShader(grainTile, TileMode.Repeated, TileMode.Repeated))
    return drawWithContent {
        drawContent()
        drawRect(brush, blendMode = BlendMode.Multiply)
    }
}

private val grainTile: ImageBitmap by lazy {
    val r = Random(7)
    val px = IntArray(128 * 128) {
        val v = 255 - r.nextInt(18)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    Bitmap.createBitmap(px, 128, 128, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun syntheticCover(i: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(250, 350, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val hue = (i * 37f) % 360f
    val a = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.7f, 0.9f))
    val b = android.graphics.Color.HSVToColor(floatArrayOf((hue + 60f) % 360f, 0.8f, 0.4f))
    c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 250f, 350f, a, b, Shader.TileMode.CLAMP) })
    c.drawCircle(125f, 150f, 60f, Paint().apply { color = android.graphics.Color.argb(160, 255, 255, 255) })
    return bmp.asImageBitmap()
}

private fun syntheticComicPage(i: Int): ImageBitmap {
    val w = 800
    val h = 1200
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.drawColor(android.graphics.Color.WHITE)
    val r = Random(i)
    val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = android.graphics.Color.BLACK }
    val rows = 3
    for (row in 0 until rows) {
        val cols = 1 + r.nextInt(3)
        for (col in 0 until cols) {
            val left = 30f + col * (w - 60f) / cols
            val top = 30f + row * (h - 60f) / rows
            val rect = android.graphics.RectF(left + 10f, top + 10f, left + (w - 60f) / cols - 10f, top + (h - 60f) / rows - 10f)
            c.drawRect(rect, Paint().apply { color = android.graphics.Color.HSVToColor(floatArrayOf(r.nextFloat() * 360f, 0.6f, 0.95f)) })
            c.drawRect(rect, border)
        }
    }
    return bmp.asImageBitmap()
}

private val words = "the of reader page light paper ink quiet river morning window letter garden distant voice slow evening".split(' ')

private fun filler(seed: Int): String {
    val r = Random(seed)
    return List(40 + r.nextInt(20)) { words[r.nextInt(words.size)] }.joinToString(" ").replaceFirstChar { it.uppercase() } + "."
}
