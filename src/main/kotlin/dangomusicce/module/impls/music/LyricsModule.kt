package dangomusicce.module.impls.music

import dangomusicce.DangoClient
import dangomusicce.DangoLogger
import dangomusicce.DangoMusic
import dangomusicce.event.EventHandler
import dangomusicce.events.HudRenderEvent
import dangomusicce.events.WorldRenderEndEvent
import dangomusicce.gfx.ViewAngles
import dangomusicce.gfx.CjkStyle
import dangomusicce.lyrics.LyricLine
import dangomusicce.lyrics.LyricSplit
import dangomusicce.lyrics.Lyrics
import dangomusicce.lyrics.LyricsProvider
import dangomusicce.lyrics.TextFlash
import dangomusicce.module.Category
import dangomusicce.module.Module
import dangomusicce.module.ModuleMeta
import dangomusicce.module.values.BooleanValue
import dangomusicce.module.values.TextValue
import dangomusicce.music.LyricFonts
import dangomusicce.module.values.ColorValue
import dangomusicce.module.values.FloatValue
import dangomusicce.module.values.IntValue
import dangomusicce.module.values.ModuleValue
import dangomusicce.music.CoverArt
import dangomusicce.music.Dance
import dangomusicce.music.MusicPlayer
import dangomusicce.music.NeteaseTrack
import dangomusicce.music.PlaybackView
import dangomusicce.multiplayer.LyricsAnchor
import dangomusicce.multiplayer.LyricsStyle
import dangomusicce.multiplayer.SharedLyricLine
import dangomusicce.multiplayer.shareableLyrics
import dangomusicce.network.NearbyClient

import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** "Above ground"：歌词底边最低在眼睛下面多少格 */
private const val GROUND_MARGIN = 0.5
/** 被方块挡住时，歌词停在方块表面前面多远（格） */
private const val BLOCK_MARGIN = 0.4
/** 拉近的下限：设定距离的这个比例。小房间里允许贴墙，不为了避让把歌词缩得太小 */
private const val MIN_REACH = 0.35
private const val LYRIC_ROW_HEIGHT = 11f
private const val ANNOTATION_GAP = 2.5f

/** Stay on screen 弹簧的快慢（越大跟得越紧；12 大约 0.3 秒回到位） */
private const val SPRING = 12.0

/** 天黑到这个程度（原版 sky darken，0 = 白天，11 = 半夜）就算晚上 */
private const val NIGHT_SKY_DARKEN = 4

/** 翻译的字号是原文的多少 */
private const val TRANSLATION_SCALE = 0.72f

/** 翻译按多宽换行（原文是 200；字小了，按画出来差不多宽算） */
private const val TRANSLATION_WRAP = 270

/** 多句：当前句、第 1~3 句预告的不透明度（越往后越淡，当前句始终最亮） */
private val PREVIEW_ALPHAS = floatArrayOf(1f, 0.56f, 0.36f, 0.24f)
/**
 * 多句：下一句预告的基准不透明度。正在唱的那句还没唱到的字也是这么淡：
 * 预告变成当前句时字不会先消失再逐字冒出来，而是从淡淡的一行按演唱进度逐字点亮
 */
private const val PREVIEW_ALPHA = 0.56f
/** 多句：预告小一点、低一点（字的像素），往上晋升时慢慢浮到原位、放大到原大小 */
private const val PREVIEW_SHRINK = 0.06f
private const val PREVIEW_DROP = 3f
/** 多句：第 1 句预告最早提前多久出现（毫秒）；后续槽位每多一槽顺延一段 */
private const val PREVIEW_LEAD_BASE_MS = 4500L
private const val PREVIEW_LEAD_STEP_MS = 4000L
/** 多句：换句后等多久再出下一句预告，以及连续预告之间的错峰间隔 */
private const val PREVIEW_DELAY_NANOS = 350_000_000L
private const val PREVIEW_STAGGER_NANOS = 180_000_000L
/** 多句：预告淡入用多久 */
private const val PREVIEW_APPEAR_NANOS = 500_000_000L
/** 多句：下一句预告离开视野这么久，就换到眼前空着的地方重新淡入 */
private const val PREVIEW_REPLACE_NANOS = 300_000_000L
/** 多句：玩家的视线比预告出现时转开超过这么多度，才算"转过头了" */
private const val PREVIEW_TURN_DEGREES = 15.0
/** 逐字歌词最后一句唱完后，多留这么一点余韵再立刻淡出，不等下一句开始（毫秒） */
private const val WORD_END_GRACE_MS = 200L
/** 多句：发光层里没唱到的字（预告、当前句后半）只发这么一点光，唱到的字才亮起来 */
private const val GHOST_GLOW = 0.35f
/** 多句：晋升时变亮、放大用临界阻尼弹簧（起步和停下都是缓的）；10 大约 0.45 秒到位 */
private const val RANK_SPRING = 10.0
/** 多句：晋升时每多一槽晚这么久开始上移，像队列依次递补 */
private const val RANK_STAGGER_NANOS = 70_000_000L
/** 淡出用多久：打断用 400ms；多句里唱完退场的句子用干脆的 380ms 淡出 */
private const val FADE_NANOS = 400_000_000L
private const val RETIRE_FADE_NANOS = 380_000_000L
/** 多句：退场的句子一边淡出一边往上浮多少（字的像素） */
private const val RETIRE_RISE = 5f
/** 多句：一帧里播放位置往回退超过这么多、或往前跳超过这么多（毫秒），就当成拖了进度 */
private const val SEEK_BACK_MS = 500L
private const val SEEK_AHEAD_MS = 2500L
/** 多句：新的一句在多少个随机候选位置里挑最空的 */
private const val PLACEMENT_TRIES = 28
/** 多句：候选位置的上下范围（相对视线，度，向下为正），大多还在视线上方 */
private const val PLACEMENT_PITCH_MIN = -22f
private const val PLACEMENT_PITCH_MAX = 6f
/** 多句：两句之间至少隔开多少度才算"不挤"，再远也不多加分 */
private const val PLACEMENT_ROOM = 6.0
/** 动态防重叠：安全边距（度）、俯仰弹簧刚度与方向迟滞 */
private const val COLLISION_PAD_X = 1.5
private const val COLLISION_PAD_Y = 1.8
private const val AVOID_SPRING = 14.0
private const val AVOID_HYSTERESIS = 3.5

/**
 * 歌词：内置播放器放歌时，从网易云取（逐字）歌词，一句句以动画漂浮在世界里。
 */
@ModuleMeta(Category.MUSIC, "Lyrics")
class LyricsModule : Module() {

    /**
     * 旧版本的“3D 歌词”开关（快捷键也切它）。现在由 [lyricStyle] 统一管，这个值只为读出旧存档：
     * 关着的话第一次运行时换成“不显示”（或者只留驾驶舱），之后一直是开着的，设置页里也不再显示
     */
    @ModuleValue("3D lyrics", "Show lyrics floating in the world")
    internal val worldMode = BooleanValue(true)

    @ModuleValue("Lyric style", "Where lyrics appear: floating in the world, cockpit-style on the screen edge, both, or nowhere")
    internal val lyricStyle = IntValue(STYLE_WORLD, STYLE_OFF, STYLE_OFF)

    /**
     * F1 隐藏游戏界面时还显示歌词：3D 歌词（连同附近共享的投影）和驾驶舱歌词共用这一个开关。
     * 以前驾驶舱另有一个默认关着的开关，按 F1 驾驶舱歌词会消失；字段名保留旧的，存档里的值照常读出来
     */
    @ModuleValue("Show lyrics when HUD is hidden", "Keep 3D and cockpit lyrics visible when F1 hides the game HUD")
    private val worldWhileHudHidden = BooleanValue(true)

    internal fun allowsWorldLyrics(hudHidden: Boolean): Boolean = !hudHidden || worldWhileHudHidden.get()
    internal fun allowsCockpitLyrics(hudHidden: Boolean): Boolean = allowsWorldLyrics(hudHidden)

    @ModuleValue("Side position", "Where the side lyric list sits")
    internal val sidePosition = IntValue(0, 2, 2)

    @ModuleValue("Horizontal offset", "Moves the list right (positive) or left (negative) from its position")
    internal val sideOffsetX = FloatValue(-300f, 300f, 0f, 0)

    @ModuleValue("Vertical offset", "Moves the list up (positive) or down (negative) from its position")
    internal val sideOffsetY = FloatValue(-300f, 300f, 0f, 0)

    @ModuleValue("Side width", "Width of the side lyric list")
    internal val sideWidth = FloatValue(200f, 480f, 300f, 0)

    @ModuleValue("Side lines", "How many lines the side list shows")
    internal val sideLines = IntValue(3, 11, 7)

    @ModuleValue("Side text size", "Text size in the side list")
    internal val sideTextSize = FloatValue(0.7f, 1.6f, 1f, 2)

    @ModuleValue("Side line spacing", "Space between lines in the side list (it never gets so tight that lines overlap)")
    internal val sideLineSpacing = FloatValue(0.7f, 1.6f, 1f, 2)

    @ModuleValue("Perspective tilt", "Turns the list toward the middle of the screen like a cockpit side display")
    internal val sideTilt = FloatValue(-45f, 45f, 18f, 0)

    @ModuleValue("Pitch", "Leans the list back (positive) or forward (negative)")
    internal val sidePitch = FloatValue(-30f, 30f, 0f, 0)

    @ModuleValue("Italic", "Slant of the letters")
    internal val sideItalic = FloatValue(0f, 20f, 8f, 0)

    @ModuleValue("Glass text", "The letters become see-through glass that bends the game behind them; off is solid glowing text")
    internal val sideGlass = BooleanValue(false)

    @ModuleValue("Text opacity", "Lower it so the lyrics cover less of the game")
    internal val sideOpacity = FloatValue(0.1f, 1f, 0.8f, 2)

    @ModuleValue("Refraction", "How strongly the glass bends the game behind it")
    internal val sideRefraction = FloatValue(0f, 1f, 0.2f, 2)

    @ModuleValue("Text glow", "The letters glow in their own color and brighten on the beat")
    internal val sideGlow = BooleanValue(true)

    @ModuleValue("Glow strength", "How strongly the text and halo glow")
    internal val sideGlowStrength = FloatValue(0f, 1f, 0.5f, 2)

    @ModuleValue("Halo", "A soft light spreading around the text")
    internal val sideTextGlow = BooleanValue(false)

    @ModuleValue("Guide line", "A thin glowing line beside the list with a tick at the current line")
    internal val sideGuide = BooleanValue(true)

    @ModuleValue("Cockpit inertia", "The list lags a little when you turn or jump, then springs back, like a helmet display")
    internal val sideInertia = BooleanValue(false)

    @ModuleValue("Inertia strength", "How far the list swings")
    internal val sideInertiaStrength = FloatValue(0.1f, 1f, 0.5f, 2)

    @ModuleValue("Cockpit font", "The default bold font, a font from config/dangomusicce/fonts, another mod's font folder, or a system font")
    internal val sideFont = TextValue(LyricFonts.DEFAULT_HUD_LYRIC_FONT)

    /** 驾驶舱字体那一行里选“像素字体”时为 false（和 3D 歌词的 smoothFont 一样，设置页里合进字体那一行） */
    @ModuleValue("Cockpit smooth font", "")
    internal val sideSmoothFont = BooleanValue(true)

    @ModuleValue("Cockpit colors", "Cover color follows each song; or use your own color, a cover gradient, a rainbow, or a flowing aurora of the cover colors")
    internal val sideColors = IntValue(SIDE_COVER, SIDE_AURORA, SIDE_COVER)

    @ModuleValue("Cockpit color", "Used when Cockpit colors is set to Custom color")
    internal val sideColor = ColorValue(0x8EC5FF)

    @ModuleValue("Color in letters", "How much of the color goes into the letters themselves; 0 keeps white letters with a colored glow")
    internal val sideTint = FloatValue(0f, 1f, 0.6f, 2)

    @ModuleValue("Text brightness", "Makes the lyrics brighter or darker")
    internal val sideBrightness = FloatValue(0.3f, 1.6f, 0.8f, 2)

    @ModuleValue("Other lines", "How visible the lines before and after the current one are")
    internal val sideOtherLines = FloatValue(0.1f, 1f, 0.45f, 2)

    @ModuleValue("Auto contrast", "Text too close in color to what is behind it is lightened or darkened so it stays readable")
    internal val autoContrast = BooleanValue(false)

    /** HUD 鼠标在驾驶舱歌词上滚轮翻看：翻到了第几行（0 = 歌名，第 n 句是 n），-1 = 不在预览 */
    private var previewRow = -1
    private var previewInputAt = 0L
    /** 翻看的是哪首歌：换歌后旧的行号作废（鼠标一直停在歌词上时不会自动回去，要在这里清掉） */
    private var previewKey: String? = null
    /** HUD 鼠标的位置（窗口像素，y 向下），没调出鼠标时是 -1；HudPointerInput 每次鼠标事件更新 */
    private var pointerX = -1
    private var pointerY = -1

    private val side = SideLyrics()
    /** 快捷键关掉歌词前用的样式（再按一次恢复成它；存盘，重开游戏后也能恢复；设置页里不显示） */
    @ModuleValue("Style before hiding", "")
    internal val styleBeforeOff = IntValue(STYLE_WORLD, STYLE_BOTH, STYLE_WORLD)
    private var migrated = false

    /** 3D 世界歌词这一帧要不要画 */
    internal fun worldVisible(): Boolean {
        migrateWorldMode()
        return lyricStyle.value == STYLE_WORLD || lyricStyle.value == STYLE_BOTH
    }

    /** 驾驶舱（侧边）歌词要不要画 */
    internal fun sideVisible(): Boolean {
        migrateWorldMode()
        return lyricStyle.value == STYLE_SIDE || lyricStyle.value == STYLE_BOTH
    }

    /** 歌词样式设成了“不显示”（别人投影过来的歌词也不画） */
    internal fun lyricsHidden(): Boolean = enabled && lyricStyle.value == STYLE_OFF

    /** 自己在用侧边列表（多人时就不看别人的 3D 投影，改用自己的歌词） */
    internal fun prefersSideLyrics(): Boolean = enabled && sideVisible()

    /** 自己的歌词会以 3D 投影给别人看；只有“只用侧边列表”时不投影（其他情况保持原来的共享行为） */

    /** 快捷键"开关歌词"：在“不显示”和关之前的样式之间切换 */
    fun toggleLyrics() {
        migrateWorldMode()
        if (lyricStyle.value != STYLE_OFF) {
            styleBeforeOff.setValue(lyricStyle.value)
            lyricStyle.setValue(STYLE_OFF)
        } else {
            lyricStyle.setValue(styleBeforeOff.value)
        }
    }

    /**
     * 旧存档里 3D 歌词是关着的：换成新的样式（只做一次，之后 worldMode 一直开着）。
     * 模块开起来之后存档才读完，所以没开之前不做
     */
    private fun migrateWorldMode() {
        if (migrated || !enabled) return
        migrated = true
        if (worldMode.get()) return
        when (lyricStyle.value) {
            STYLE_WORLD -> lyricStyle.setValue(STYLE_OFF)
            STYLE_BOTH -> lyricStyle.setValue(STYLE_SIDE)
        }
        worldMode.set(true)
    }

    @ModuleValue("Scale", "Lyrics size")
    private val scale = FloatValue(0.5f, 3f, 1f, 1)

    @ModuleValue("Distance", "How far away 3D lyrics appear (blocks)")
    private val distance = FloatValue(3f, 40f, 10f, 1)

    @ModuleValue("Glow", "Deep Glow style: white-hot core, wide soft halo")
    private val glow = BooleanValue(true)

    @ModuleValue("Night glow only", "Turn glow off in daylight and back on when it gets dark")
    private val glowNightOnly = BooleanValue(true)

    @ModuleValue("Glow strength", "Glow brightness")
    private val glowStrength = FloatValue(0f, 3f, 0.3f, 2)

    @ModuleValue("Glow radius", "Halo spread: higher is wider and softer")
    private val glowRadius = FloatValue(0f, 1f, 0.25f, 2)

    /** 平滑字体还是原版像素字体：设置页里和"歌词字体"合成一项，不单独显示 */
    @ModuleValue("Smooth font", "Smooth font instead of the Minecraft pixel font")
    private val smoothFont = BooleanValue(false)

    @ModuleValue("Lyric font", "Pixel font, smooth font, or a .ttf / .otf font from config/dangomusic/fonts")
    private val lyricFont = TextValue("")

    @ModuleValue("Wiggle", "Low-fps jitter on every letter, like hand-drawn animation")
    private val wiggle = BooleanValue(true)

    @ModuleValue("Wiggle fps", "Jitter frame rate: lower feels more hand-drawn")
    private val wiggleFps = IntValue(4, 24, 8)

    @ModuleValue("Wiggle amount", "Jitter strength")
    private val wiggleAmount = FloatValue(0f, 2f, 0.8f, 2)

    @ModuleValue("Text flash", "Words slam onto the screen one by one, synced to the beat")
    private val textFlash = BooleanValue(false)

    @ModuleValue("Word pop", "Words pop in one by one as they are sung")
    private val wordPop = BooleanValue(true)

    @ModuleValue("Show instrumental sections", "Label the intro, interlude and outro in KTV mode")
    internal val ktvInstrumental = BooleanValue(true)

    @ModuleValue("Lyric colors", "Choose the color of the KTV highlight")
    internal val ktvLyricColors = IntValue(COLORS_CUSTOM, COLORS_RAINBOW, COLORS_CUSTOM)

    @ModuleValue("KTV mode", "Show the full line and highlight it as the song plays")
    internal val ktvMode = BooleanValue(false)

    @ModuleValue("KTV base color", "")
    internal val ktvBaseColor = ColorValue(0xD8D8D8)

    @ModuleValue("KTV sung color", "")
    internal val ktvSungColor = ColorValue(0xFF345E)

    @ModuleValue("KTV base shadow", "")
    internal val ktvBaseShadow = BooleanValue(true)

    @ModuleValue("KTV sung shadow", "")
    internal val ktvSungShadow = BooleanValue(true)

    @ModuleValue("Flash size", "Text flash size")
    private val flashSize = FloatValue(0.5f, 2f, 1f, 2)

    @ModuleValue("Face player", "Lyrics keep turning to face you")
    private val facePlayer = BooleanValue(true)

    @ModuleValue("Keep away", "Lyrics back off when you walk or sprint into them")
    private val keepAway = BooleanValue(true)

    @ModuleValue("Stay on screen", "Lyrics glide back into view when you turn away, so they never leave the screen")
    private val stayOnScreen = BooleanValue(true)

    internal fun staysOnScreen() = stayOnScreen.get()

    internal fun facesPlayer() = staysOnScreen() || facePlayer.get()

    internal fun keepsDistance() = staysOnScreen() || keepAway.get()

    @ModuleValue("Above ground", "Lyrics never appear below your eyes, so they don't sink into the ground")
    private val aboveGround = BooleanValue(false)

    @ModuleValue("Avoid blocks", "Lyrics move in front of walls, leaves and the ground instead of going through them")
    private val avoidBlocks = BooleanValue(true)

    @ModuleValue("Spread", "How far left or right each line can appear (degrees)")
    private val spread = FloatValue(0f, 60f, 30f, 0)

    /**
     * 3D 歌词同时漂着几句：1 = 仅当前句；2–4 = 当前句加上提前预告的后续 1–3 句。
     * 唱完的句子立即淡出不再滞留，多句围绕镜头同球面漂浮并在视角转动时自动上下避让不重叠
     */
    @ModuleValue("Simultaneous lyric lines", "The current line plus upcoming lines ahead (1 = current only, 2-4 = current + 1-3 upcoming). Finished lines fade out immediately; lines avoid overlapping on screen")
    private val worldLyricLines = IntValue(1, 4, 1)

    @ModuleValue("Split lines", "Split two lines written as one (NetEase often does this). The lyrics page uses it too, so it stays when lyrics are hidden")
    private val splitLines = BooleanValue(true)

    @ModuleValue("Translation", "Show the translation under each lyric line when the song has one")
    private val translation = BooleanValue(true)

    @ModuleValue("Show phonetic lyrics", "Show phonetic lyrics under the original when available")
    private val romanization = BooleanValue(false)

    @ModuleValue("Translation animation", "The translation appears character by character like the lyrics")
    private val translationAnimation = BooleanValue(true)

    @ModuleValue("Translation wiggle", "The translation wiggles like the lyrics (uses the Wiggle speed and amount)")
    private val translationWiggle = BooleanValue(true)

    @ModuleValue("Mob dance", "Every creature in the world squashes and stretches on the beat of the song")
    private val dance = BooleanValue(false)

    @ModuleValue("Auto BPM", "Work out the song's BPM from its drum beats")
    private val danceAuto = BooleanValue(false)

    @ModuleValue("Tap BPM", "Left-click 16 times on the beat to set the BPM yourself; counting starts with your first click (recommended, most accurate)")
    private val danceTap = BooleanValue(false)

    @ModuleValue("Spin", "Creatures also spin around steadily")
    private val danceSpin = BooleanValue(false)

    @ModuleValue("Spin speed", "How fast creatures spin: 1× is one turn every four beats")
    private val danceSpinSpeed = FloatValue(0.25f, 4f, 1f, 2)

    @ModuleValue("Squash width", "How much wider creatures get when the beat slams them flat")
    private val danceSquash = FloatValue(1f, 1.6f, 1.18f, 2)

    @ModuleValue("Stretch length", "How much taller creatures get when they spring back up")
    private val danceStretch = FloatValue(1f, 1.6f, 1.15f, 2)

    @ModuleValue("Beat offset", "Move the bounce earlier or later if it doesn't land on the beat")
    private val danceOffset = IntValue(-300, 300, 0)

    @ModuleValue("Lyric colors", "Where the lyric and translation colors come from")
    internal val lyricColors = IntValue(COLORS_CUSTOM, COLORS_RAINBOW, COLORS_GRADIENT)

    @ModuleValue("Rainbow speed", "How fast the rainbow flows across the lyrics")
    private val rainbowSpeed = FloatValue(0.05f, 1f, 0.25f, 2)

    @ModuleValue("Custom lyric color", "Used when Lyric colors is set to Custom color")
    private val textColor = ColorValue(0xF7E1D7)

    @ModuleValue("Custom translation color", "Used when Lyric colors is set to Custom color")
    // 旧默认 #803D66 在游戏里几乎看不清：默认改成浅粉，仍是旧默认值的老配置第一次渲染时迁移过来
    private val translationColor = ColorValue(0xF2C6DE)
    private var translationColorMigrated = false

    // ---------------------------------------------------------------- 状态

    /** 正在播放的歌（来自内置播放器） */
    private class NowPlaying(val track: NeteaseTrack, val playing: Boolean) {
        val key: String get() = track.key
        val title: String get() = track.name
        val artist: String get() = track.artists.joinToString(" / ")
    }

    private val cover = CoverArt()
    private val fetcher = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-Lyrics").apply { isDaemon = true } }

    private object NotFound
    private val cache = ConcurrentHashMap<String, Any>()     // key -> Lyrics 或 NotFound
    private val pending = ConcurrentHashMap.newKeySet<String>()

    private var current: NowPlaying? = null
    private var currentKey: String? = null
    private var songPos = 0.0                 // 毫秒，本地平滑时钟
    private var lastSeenNanos = 0L
    private var lastIndex = -1
    /** 最近一句真正的歌词（下标 >= 0）；KTV 两句之间的长空当下标是 -1，靠它认出下一句是顺着唱下来的 */
    private var lastLyricIndex = -1
    /** 上一帧算歌词用的播放位置（毫秒），-1 = 没有；位置突然跳了就是拖进度，不算顺着唱 */
    private var lastLyricPos = -1L
    /** [lastIndex] 是按哪种拆句算的：拆句开关变了，下标就对不上了，要重新找 */
    private var indexedSplit = LyricSplit.enabled

    private val random = Random()

    /** 一句漂浮的歌词 */
    private class Floating(
        val lines: List<String>,
        val startMs: Long,
        val revealMs: Long,
        var x: Double, var y: Double, var z: Double,
        var yaw: Float, var pitch: Float,
        /** 每个非空白字符出现的时间（相对这句开始，毫秒），按词闪出时用 */
        val charTimes: LongArray = LongArray(0),
        val lyric: LyricLine,
        val nextMs: Long,
    ) {
        var instrumental = false
        var fadeStartNanos = 0L
        /** 这一句的翻译（已经按宽度换好行；没有就是空的），画在原文下面 */
        var translation: List<String> = emptyList()
        var phonetic: List<String> = emptyList()
        var lastWiggleFrame: Long? = null
        /** Stay on screen 往回拉时的角速度（度 / 秒），用弹簧算，起步和停下都是平滑的 */
        var vYaw = 0.0
        var vPitch = 0.0
        /** 暂停后恢复播放时重新出现的这句：用一个短淡入代替逐字出现 */
        var resumedAt = 0L
        /** Avoid blocks：沿视线拉近到方块前面的比例（1 = 原位），NaN = 还没测过 */
        var reach = Double.NaN
        /**
         * 多句：现在显示成第几句（-1 = 下一句预告，0 = 正在唱，1 = 上一句……），
         * 换句时用弹簧朝 [targetRank] 过渡（[rankVel] 是速度，[rankDelayNanos] 之前先不动）
         */
        var rank = 0f
        var targetRank = 0
        var rankVel = 0f
        var rankDelayNanos = 0L
        /** 多句：作为下一句预告出现的时刻（0 = 不是预告出来的），预告从这一刻起淡入 */
        var previewSinceNanos = 0L
        /** 出现时玩家朝哪看：预告只在玩家转过头以后才换位置，原地看不见（比如低头时被"离地"抬到视野上面）不会反复重来 */
        var placedYaw = Float.NaN
        var placedPitch = Float.NaN
        /** 开始淡出那一刻的样子：淡出从这里接着变，不会先跳回当前句的样子 */
        var fadeAlpha = 1f
        var fadeScale = 1f
        var fadeLift = 0f
        var fadeGhost = 0f
        var fadeNanos = FADE_NANOS
        /** 开始淡出时的播放位置：多句里淡出的句子停在那一刻的样子，拖回进度时不会突然变回没唱到的样子 */
        var fadeSongMs = -1L
        /** 预告脱离视野的时间戳（0 = 在视野内） */
        var awaySinceNanos = 0L
        /** 是否已纳入多行同球面跟随 */
        var sphereSynced = false
        /** 动态碰撞避让带来的俯仰角偏移（度，负 = 向上躲，正 = 向下躲）与阻尼速度 */
        var avoidPitch = 0.0
        var avoidPitchVel = 0.0
        /** 上一帧避让方向（-1 = 向上，+1 = 向下，0 = 无碰撞），用于方向迟滞防抖 */
        var avoidDir = 0
    }

    /** 恢复播放后重新出现的那句歌词，0.3 秒淡入 */
    private fun appearOf(f: Floating): Float =
        if (f.resumedAt == 0L) 1f else ((System.nanoTime() - f.resumedAt) / 3e8f).coerceIn(0f, 1f)

    private var active: Floating? = null

    /** 最近一帧实际渲染的活动歌词世界姿态；附近共享用它复现位置/朝向/缩放 */
    @Volatile private var sharedPose: LyricsAnchor? = null

    private fun phoneticRows(f: Floating) = if (romanization.get()) f.phonetic else emptyList()
    private fun translationRows(f: Floating) = if (translation.get()) f.translation else emptyList()
    private fun halfMain(f: Floating) = f.lines.size * LYRIC_ROW_HEIGHT / 2f

    private fun rowBottom(f: Floating): Float {
        val annotations = phoneticRows(f).size + translationRows(f).size
        return halfMain(f) + if (annotations == 0) 0f else ANNOTATION_GAP + annotations * LYRIC_ROW_HEIGHT * TRANSLATION_SCALE
    }

    /** 暂停期间（歌词先隐藏，恢复播放时再出现） */
    private var wasPaused = false
    private val flash = TextFlash()
    /** 正在往发光画布上画（只画字本身，不画阴影） */
    private var glowPass = false
    private val fading = mutableListOf<Floating>()

    /** 多句：即将演唱的后续预告队列（下标 0 为紧接着的下一句，最多 3 句），全部留在同球面上且自动避让 */
    private val upcoming = mutableListOf<Floating>()
    /** 多句：当前句最近一次换掉的时刻；下一句预告等一会儿再出，免得几件事挤在同一刻 */
    private var changedNanos = 0L
    /** 多句：上一次生成预告的时刻，连续预告之间错开出现 */
    private var lastPreviewSpawnNanos = 0L
    private var trailNanos = 0L
    private var avoidCollisionNanos = 0L

    /** 同时漂着几句。文闪模式自己排版，只用一句 */
    private fun lyricLines(): Int = if (textFlash.get() && !ktvMode.get()) 1 else worldLyricLines.value

    /** 多句：只向前预告后续句子（1 = 0 句预告，2–4 = 后续 1–3 句） */
    private fun previewCount(): Int = (lyricLines() - 1).coerceAtLeast(0)

    /** 唱过的句子立即退场，不再滞留屏幕 */
    private fun pastLines(): Int = 0

    /** 多句：要不要提前显示后续歌词 */
    private fun previewsNext(): Boolean = previewCount() > 0

    /** [r] <= 0 是当前句（0）和后续预告（-1..-3），越大越靠后越淡 */
    private fun rankAlpha(r: Float): Float {
        if (r <= 0f) {
            val c = (-r).coerceIn(0f, (PREVIEW_ALPHAS.size - 1).toFloat())
            val i = c.toInt().coerceAtMost(PREVIEW_ALPHAS.size - 2)
            return PREVIEW_ALPHAS[i] + (PREVIEW_ALPHAS[i + 1] - PREVIEW_ALPHAS[i]) * (c - i)
        }
        return (1f - 0.5f * r).coerceIn(0.16f, 1f)
    }

    private fun rankScale(r: Float): Float = 1f - PREVIEW_SHRINK * abs(r).coerceAtMost(3f)

    private fun rankLift(r: Float): Float =
        if (r <= 0f) PREVIEW_DROP * (-r).coerceAtMost(3f) else -4f * r.coerceAtMost(3f)

    private fun smoothstep(t: Float): Float = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

    /** 下一句预告淡入的进度（不是预告出来的句子一直是 1） */
    private fun previewAppear(f: Floating, now: Long = System.nanoTime()): Float =
        if (f.previewSinceNanos == 0L) 1f else smoothstep((now - f.previewSinceNanos).toFloat() / PREVIEW_APPEAR_NANOS)

    /** 一句整体的不透明度（唱到的字就是这么亮） */
    private fun lineAlpha(f: Floating, now: Long = System.nanoTime()): Float =
        rankAlpha(f.rank) * appearOf(f) * previewAppear(f, now)

    /**
     * 还没唱到的字有多亮：多句时后续预告按槽位淡显，当前句后半按 [PREVIEW_ALPHA] 淡显（单句照旧逐字冒出）
     */
    private fun ghostOf(f: Floating, now: Long = System.nanoTime()): Float {
        if (!previewsNext() || f.instrumental) return 0f
        val base = if (f.rank <= -1f) rankAlpha(f.rank) else PREVIEW_ALPHA * (1f - f.rank.coerceIn(0f, 1f))
        return base * appearOf(f) * previewAppear(f, now)
    }

    /** 唱完的这句立即退场淡出，不再滞留屏幕上 */
    private fun retire(f: Floating) {
        startFade(f, slow = lyricLines() > 1 && !f.instrumental)
    }

    /** 按向量旋转俯仰角，保持偏航方向与距离不变 */
    private fun rotatePitch(rx: Double, ry: Double, rz: Double, deltaDeg: Double, fallbackYawDeg: Float): Triple<Double, Double, Double> {
        if (abs(deltaDeg) <= 1e-4) return Triple(rx, ry, rz)
        val len = sqrt(rx * rx + ry * ry + rz * rz)
        if (len < 1e-4) return Triple(rx, ry, rz)
        val pitch0 = Math.toDegrees(asin((-ry / len).coerceIn(-1.0, 1.0)))
        val pitch1 = Math.toRadians((pitch0 + deltaDeg).coerceIn(-89.0, 89.0))
        val horiz0 = sqrt(rx * rx + rz * rz)
        val horiz1 = len * cos(pitch1)
        val newRx: Double
        val newRz: Double
        if (horiz0 > 1e-6) {
            val s = horiz1 / horiz0
            newRx = rx * s
            newRz = rz * s
        } else {
            val yr = Math.toRadians(fallbackYawDeg.toDouble())
            newRx = -sin(yr) * horiz1
            newRz = cos(yr) * horiz1
        }
        return Triple(newRx, -len * sin(pitch1), newRz)
    }

    /** 晋升前把动态避让偏移烘焙回基础位置，防止变为当前句释放避让偏移时位置瞬间跳回 */
    private fun bakeAvoidPitch(f: Floating, cx: Double, cy: Double, cz: Double) {
        if (abs(f.avoidPitch) > 1e-4) {
            val rx = f.x - cx
            val ry = f.y - cy
            val rz = f.z - cz
            val len = sqrt(rx * rx + ry * ry + rz * rz)
            if (len >= 1e-4) {
                val (nx, ny, nz) = rotatePitch(rx, ry, rz, f.avoidPitch, f.yaw)
                val pitch0 = Math.toDegrees(asin((-ry / len).coerceIn(-1.0, 1.0)))
                val pitch1 = (pitch0 + f.avoidPitch).coerceIn(-89.0, 89.0)
                f.x = cx + nx
                f.y = cy + ny
                f.z = cz + nz
                f.pitch = (f.pitch + (pitch1 - pitch0)).toFloat().coerceIn(-90f, 90f)
            }
        }
        f.avoidPitch = 0.0
        f.avoidPitchVel = 0.0
        f.avoidDir = 0
    }

    /** 唱到预告队列中的第 [index] 句：越过的更早预告淡出，其余依次往前递补一位 */
    private fun promote(index: Int) {
        val f = upcoming.removeAt(index)
        for (i in 0 until index) startFade(upcoming.removeAt(0))
        if (abs(f.avoidPitch) > 1e-4) {
            if (!lastCamX.isNaN()) {
                bakeAvoidPitch(f, lastCamX, lastCamY, lastCamZ)
            } else {
                val player = DangoClient.platform.player
                if (player != null) bakeAvoidPitch(f, player.eyeX, player.eyeY, player.eyeZ)
                else {
                    f.avoidPitch = 0.0
                    f.avoidPitchVel = 0.0
                    f.avoidDir = 0
                }
            }
        } else {
            f.avoidPitch = 0.0
            f.avoidPitchVel = 0.0
            f.avoidDir = 0
        }
        f.awaySinceNanos = 0L
        f.targetRank = 0
        f.rankDelayNanos = 0L
        active = f
        val now = System.nanoTime()
        upcoming.forEachIndexed { i, u ->
            u.targetRank = -(i + 1)
            u.rankDelayNanos = now + i * RANK_STAGGER_NANOS
        }
    }

    private fun promote(f: Floating) {
        val idx = upcoming.indexOf(f)
        if (idx >= 0) promote(idx)
    }

    private fun dropUpcoming() {
        upcoming.forEach { startFade(it) }
        upcoming.clear()
    }

    /**
     * 当前应在世界中显示的歌词下标：有逐字时间戳时，最后一个字唱完 + [WORD_END_GRACE_MS] 后
     * 立即返回 -1 让当前句直接淡出消失，不再干等下一句开始；普通 LRC 没有结束时间，照旧保留到下一句
     */
    private fun worldIndexAt(lyrics: Lyrics, pos: Long, ktv: Boolean): Int {
        val base = if (ktv) lyrics.ktvIndexAt(pos) else lyrics.indexAt(pos)
        if (base < 0) return -1
        val line = lyrics.lines.getOrNull(base) ?: return -1
        if (line.text.isBlank()) return -1
        if (!line.words.isNullOrEmpty()) {
            val nextMs = lyrics.lines.getOrNull(base + 1)?.timeMs ?: Long.MAX_VALUE
            val endMs = line.ktvEndMs(nextMs)
            if (pos >= endMs + WORD_END_GRACE_MS) return -1
        }
        return base
    }

    /**
     * 多句：从当前（或最近开始的一句）往后找最多 [count] 句非空歌词的下标
     */
    private fun upcomingIndices(lyrics: Lyrics, idx: Int, pos: Long, count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val lines = lyrics.lines
        val out = ArrayList<Int>(count)
        var j = max(idx, lyrics.indexAt(pos)) + 1
        while (j < lines.size && out.size < count) {
            if (lines[j].text.isNotBlank()) out += j
            j++
        }
        return out
    }

    private fun upcomingIndex(lyrics: Lyrics, idx: Int, pos: Long): Int =
        upcomingIndices(lyrics, idx, pos, 1).firstOrNull() ?: -1

    /**
     * 是不是同一句：按时间和文字比（不比对象），歌词重新加载、补上翻译换了一批新对象时预告照样认得出来。
     * 拆句开关变了文字不一样，认不出来正好
     */
    private fun sameLine(a: LyricLine?, b: LyricLine?): Boolean =
        a != null && b != null && a.timeMs == b.timeMs && a.text == b.text

    private fun updateUpcoming(lyrics: Lyrics, idx: Int, pos: Long, now: Long) {
        val limit = previewCount()
        if (limit <= 0) {
            dropUpcoming()
            return
        }
        val wanted = upcomingIndices(lyrics, idx, pos, limit)
        val wantedLines = wanted.map { lyrics.lines[it] }
        val matched = arrayOfNulls<Floating>(wanted.size)
        for (f in upcoming) {
            val slot = wantedLines.indexOfFirst { sameLine(f.lyric, it) }
            if (slot >= 0 && matched[slot] == null) {
                f.targetRank = -(slot + 1)
                matched[slot] = f
            } else {
                startFade(f)
            }
        }
        val nextList = ArrayList<Floating>(wanted.size)
        var spawnedThisFrame = false
        for (slot in wanted.indices) {
            val existing = matched[slot]
            if (existing != null) {
                nextList += existing
                continue
            }
            if (spawnedThisFrame) continue
            val lineIdx = wanted[slot]
            val line = wantedLines[slot]
            val leadMs = PREVIEW_LEAD_BASE_MS + slot * PREVIEW_LEAD_STEP_MS
            val readyAfterChange = now - changedNanos >= PREVIEW_DELAY_NANOS
            val readyAfterPrev = slot == 0 || now - lastPreviewSpawnNanos >= PREVIEW_STAGGER_NANOS
            if (line.timeMs - pos > leadMs || !readyAfterChange || !readyAfterPrev) continue
            val nextMs = lyrics.lines.getOrNull(lineIdx + 1)?.timeMs ?: Long.MAX_VALUE
            val spawned = spawn(line, nextMs, preview = true, previewSlot = slot).apply {
                val target = -(slot + 1)
                rank = target.toFloat()
                targetRank = target
            }
            nextList += spawned
            lastPreviewSpawnNanos = now
            spawnedThisFrame = true
        }
        upcoming.clear()
        upcoming.addAll(nextList)
    }

    /**
     * 某句预告不在视野里（转头了）超过一会儿：它还没开始唱，没必要守在原地，换到眼前空着的地方重新淡入。
     */
    private fun keepUpcomingInView(now: Long) {
        val platform = DangoClient.platform
        val player = platform.player
        if (upcoming.isEmpty() || player == null) {
            upcoming.forEach { it.awaySinceNanos = 0L }
            return
        }
        val vHalf = platform.fov / 2.0
        val aspect = platform.windowWidth.toDouble() / platform.windowHeight.coerceAtLeast(1)
        val hHalf = Math.toDegrees(atan(tan(Math.toRadians(vHalf)) * aspect))
        for (i in upcoming.indices) {
            val f = upcoming[i]
            val rx = f.x - player.eyeX
            val ry = f.y - player.eyeY
            val rz = f.z - player.eyeZ
            val len = sqrt(rx * rx + ry * ry + rz * rz)
            if (len < 1e-4) continue
            val dYaw = abs(wrapDegrees(Math.toDegrees(atan2(-rx, rz)) - player.yaw))
            val dPitch = abs(Math.toDegrees(asin((-ry / len).coerceIn(-1.0, 1.0))) - player.pitch)
            val turned = f.placedYaw.isNaN() ||
                abs(wrapDegrees((player.yaw - f.placedYaw).toDouble())) > PREVIEW_TURN_DEGREES ||
                abs(player.pitch - f.placedPitch) > PREVIEW_TURN_DEGREES
            if ((dYaw <= hHalf && dPitch <= vHalf) || !turned) {
                f.awaySinceNanos = 0L
                continue
            }
            if (f.awaySinceNanos == 0L) {
                f.awaySinceNanos = now
                continue
            }
            if (now - f.awaySinceNanos < PREVIEW_REPLACE_NANOS) continue
            startFade(f)
            upcoming[i] = spawn(f.lyric, f.nextMs, preview = true, previewSlot = i).apply {
                targetRank = -(i + 1)
                rank = targetRank.toFloat()
            }
        }
    }

    /**
     * 每帧一次：句数设置变小时裁掉多余的预告，每个句子的名次用临界阻尼弹簧平滑过渡
     */
    private fun updateLines(now: Long) {
        val dt = if (trailNanos == 0L) 0.016 else ((now - trailNanos) / 1e9).coerceIn(0.0, 0.1)
        trailNanos = now
        val keep = previewCount()
        while (upcoming.size > keep) startFade(upcoming.removeAt(upcoming.size - 1), slow = true)
        active?.let { stepRank(it, now, dt) }
        upcoming.forEach { stepRank(it, now, dt) }
    }

    private fun stepRank(f: Floating, now: Long, dt: Double) {
        if (now < f.rankDelayNanos) return
        val target = f.targetRank.toDouble()
        var x = f.rank.toDouble()
        var v = f.rankVel.toDouble()
        var left = dt
        while (left > 1e-6) {
            val h = min(left, 1.0 / 240.0)
            left -= h
            v += (-RANK_SPRING * RANK_SPRING * (x - target) - 2.0 * RANK_SPRING * v) * h
            x += v * h
        }
        if (abs(x - target) < 1e-3 && abs(v) < 1e-2) {
            x = target
            v = 0.0
        }
        f.rank = x.toFloat()
        f.rankVel = v.toFloat()
    }

    override fun onDisable() {
        cover.clear()
        current = null
        currentKey = null
        active = null
        upcoming.clear()
        sharedPose = null
        fading.clear()
        lastPreviewSpawnNanos = 0L
        avoidCollisionNanos = 0L
        lastCamX = Double.NaN
        flash.reset()
        lastIndex = -1
        lastLyricIndex = -1
        lastLyricPos = -1L
    }

    // ---------------------------------------------------------------- 当前歌曲 / 时钟

    private fun nowPlaying(): NowPlaying? {
        val snap = PlaybackView.snapshot()
        val t = snap.track ?: return null
        if (snap.remote && !NearbyClient.shouldUseLocalLyrics()) return null
        return NowPlaying(t, snap.playing)
    }

    private fun playbackPositionMs(): Long = PlaybackView.snapshot().positionMs

    private fun playbackDurationMs(): Long = PlaybackView.snapshot().durationMs

    private fun playbackBeatPulse(positionMs: Long): Float =
        if (PlaybackView.snapshot().remote) 0f else MusicPlayer.beatPulse(positionMs)

    private val notFoundAt = ConcurrentHashMap<String, Long>()

    private fun lyricsFor(key: String): Lyrics? = cache[key] as? Lyrics

    internal fun playerLyrics(track: NeteaseTrack): Lyrics? {
        syncSplit()
        requestLyrics(track)
        return lyricsFor(track.key)
    }

    /** Line-level lyrics for the nearby/group projection; null until the payload is loaded. */
    internal fun shareLines(track: NeteaseTrack): List<SharedLyricLine>? {
        syncSplit()
        return shareableLyrics(lyricsFor(track.key))
    }

    /**
     * 把“自动拆句”开关同步给歌词数据。歌词页、联机分享在主菜单里、或者这个模块关着时也会读歌词，
     * 不能只在世界渲染里同步，所以读歌词的地方都先对一下。只改开关，不碰 [lastIndex]（那个由渲染自己发现变化后重算）
     */
    private fun syncSplit() {
        val split = splitLines.get()
        if (LyricSplit.enabled != split) LyricSplit.enabled = split
    }

    /** Visual settings so a listener sees the same font, colors, scale and glow. */
    internal fun shareStyle(): LyricsStyle = LyricsStyle(
        font = lyricFont.value,
        smooth = smoothFont.get(),
        textColor = textColor.value,
        translationColor = translationColor.value,
        scale = scale.value,
        distance = distance.value,
        maxLines = 1,
        glow = glow.get(),
        glowStrength = glowStrength.value,
        glowRadius = glowRadius.value,
        translation = translation.get(),
        romanization = romanization.get(),
        ktv = ktvMode.get(),
        ktvBaseColor = ktvBaseColor.value,
        ktvSungColor = ktvSungColor.value,
        wiggle = wiggle.get(),
        wiggleFps = wiggleFps.value,
        wiggleAmount = wiggleAmount.value,
    )

    /** World position of the host's currently rendered lyric line, if any. */
    internal fun shareAnchor(): LyricsAnchor? {
        val f = active ?: return null
        sharedPose?.let { return it }
        return LyricsAnchor(
            f.x, f.y, f.z, f.yaw, f.pitch,
            0.03f * scale.value * (distance.value / 6.5f),
            f.lastWiggleFrame ?: -1L,
        )
    }

    internal fun isLoadingLyrics(track: NeteaseTrack): Boolean = track.key in pending

    private fun requestLyrics(track: NeteaseTrack) {
        val key = track.key
        if (cache[key] === NotFound) {
            val failedAt = notFoundAt[key] ?: 0L
            if (System.nanoTime() - failedAt < 30_000_000_000L) return
            cache.remove(key, NotFound)
        }
        if (cache.containsKey(key) || !pending.add(key)) return
        fetcher.execute {
            try {
                val result = dangomusicce.music.MusicSources.api(track.source).lyrics(track)
                if (result == null) notFoundAt[key] = System.nanoTime() else notFoundAt.remove(key)
                cache[key] = result ?: NotFound
            } catch (_: Exception) {
                notFoundAt[key] = System.nanoTime()
                cache[key] = NotFound
            } finally {
                pending.remove(key)
            }
        }
    }

    /** 跟着内置播放器的进度走（它的进度是精确的） */
    private fun updateClock() {
        val now = System.nanoTime()
        val s = nowPlaying()
        if (s == null) {
            // Remote playback may be active while this client deliberately renders
            // the host's projection instead of local lyrics. Clear immediately in
            // that case so the two lyric layers do not overlap for three seconds.
            val remoteOptOut = PlaybackView.snapshot().remote
            if (current != null && (remoteOptOut || (now - lastSeenNanos) / 1e9 > 3.0)) {
                current = null
                currentKey = null
                active?.let { startFade(it) }
                active = null
                dropUpcoming()
                sharedPose = null
                lastIndex = -1
                lastLyricIndex = -1
                lastLyricPos = -1L
            }
            return
        }
        lastSeenNanos = now
        requestLyrics(s.track)
        val p = playbackPositionMs().toDouble()
        if (s.key != currentKey) {
            currentKey = s.key
            songPos = p
            active?.let { startFade(it) }
            active = null
            dropUpcoming()
            sharedPose = null
            lastIndex = -1
            lastLyricIndex = -1
            lastLyricPos = -1L
        } else {
            // Browser telemetry arrives in batches, not every rendered frame.
            // The lyric index below handles real seeks; a clock correction within
            // the same line must not respawn its random world anchor.
            songPos = p
        }
        current = s
    }

    // ---------------------------------------------------------------- 渲染

    @EventHandler
    fun render(e: WorldRenderEndEvent) {
        // 生物跟着节奏摇：每帧把设置同步过去，各版本画生物时读
        Dance.enabled = enabled && dance.get()
        Dance.spin = danceSpin.get()
        Dance.spinSpeed = danceSpinSpeed.value
        Dance.squashWidth = danceSquash.value
        Dance.stretchLength = danceStretch.value
        Dance.offsetMs = danceOffset.value
        if (!translationColorMigrated) {
            translationColorMigrated = true
            if (translationColor.value == 0x803D66) translationColor.setValue(0xF2C6DE)
        }
        // 两种测 BPM 都没开时默认自动测，否则开了 Mob dance 也没有节拍可跟
        Dance.auto = danceAuto.get() || !danceTap.get()
        Dance.tap = danceTap.get()
        val platform = DangoClient.platform
        Dance.tapTick(platform.inGame && platform.isMouseDown(0), platform.inGame)
        if (!enabled) return
        rainbowPhase = ((System.nanoTime() / 1_000_000_000.0 * rainbowSpeed.value) % 1.0).toFloat()
        // 歌词字体：选了就换（同一个文件不会重复加载）
        // 歌词字体：每帧对一下版本标记，字体文件被替换了就重新加载（热重载）
        val (lf, lk) = if (smoothFont.get()) LyricFonts.load(lyricFont.value) else (null to null)
        DangoClient.gfx.text.font(CjkStyle.LYRIC, lf, lk)
        updateClock()
        val s = current
        if (s != null && (sideVisible() || lyricColors.value == COLORS_COVER || lyricColors.value == COLORS_GRADIENT || (ktvMode.get() && ktvLyricColors.value in COLORS_COVER..COLORS_GRADIENT))) {
            s.track.coverUrl.takeIf { it.isNotEmpty() }?.let { cover.requestUrl(s.key, it) }
        }
        cover.upload()
        // 拆句开关变了：歌词行的下标都变了，重新找当前这句。
        // 开关可能已经被歌词页先同步过了，所以这里和上次算下标时用的拆句比，而不是和开关比
        syncSplit()
        if (indexedSplit != LyricSplit.enabled) {
            indexedSplit = LyricSplit.enabled
            lastIndex = -1
            lastLyricIndex = -1
            lastLyricPos = -1L
        }
        val lyrics = currentKey?.let { lyricsFor(it) }
        val paused = s != null && !s.playing
        val frameNanos = System.nanoTime()
        var previewing = false
        if (s != null && lyrics != null) {
            val pos = songPos.toLong()
            val ktv = ktvMode.get()
            val lyricIndex = worldIndexAt(lyrics, pos, ktv)
            val section = if (ktv && ktvInstrumental.get() && lyricIndex < 0) lyrics.instrumentalAt(pos.coerceAtLeast(0L), playbackDurationMs()) else null
            val idx = if (section != null) -2 - section.ordinal else lyricIndex
            val sectionLabel = section?.displayLabel()
            // 播放位置一帧里往回退、或者往前跳了一大截：拖了进度条，跳过去 / 退回去的句子都不算顺着唱
            val jumped = lastLyricPos >= 0 && (pos < lastLyricPos - SEEK_BACK_MS || pos > lastLyricPos + SEEK_AHEAD_MS)
            lastLyricPos = pos
            if (paused) {
                // 暂停：当前这句（多句时连同后续预告）淡出消失，恢复播放时再出现当前句
                active?.let { startFade(it) }
                active = null
                lastIndex = idx
                wasPaused = true
            } else if (idx != lastIndex || wasPaused || (sectionLabel != null && sectionLabel != active?.lyric?.text)) {
                val resumed = wasPaused && idx == lastIndex
                // 顺着往下唱（或当前句最后一个字已唱完退场）：立即淡出旧句
                val forward = !wasPaused && !jumped && (
                    (idx >= 0 && lastLyricIndex >= 0 && idx > lastLyricIndex && idx - lastLyricIndex <= 2) ||
                    (idx == -1 && lastIndex >= 0)
                )
                val calm = !wasPaused && !jumped && lyricLines() > 1
                wasPaused = false
                lastIndex = idx
                if (idx >= 0) lastLyricIndex = idx
                if (forward) {
                    active?.let(::retire)
                } else {
                    active?.let { startFade(it, slow = calm && !it.instrumental) }
                }
                active = null
                changedNanos = frameNanos
                val aheadIdx = if (idx >= 0) upcoming.indexOfFirst { sameLine(it.lyric, lyrics.lines[idx]) } else -1
                if (sectionLabel != null) {
                    active = spawn(LyricLine(pos, sectionLabel), pos + 3500L).also { it.instrumental = true }
                } else if (aheadIdx >= 0) {
                    // 唱到了预告队列中的这句：原地晋升成当前句（烘焙避让偏移以免跳动）
                    promote(aheadIdx)
                } else if (idx >= 0) {
                    val line = lyrics.lines[idx]
                    if (line.text.isNotBlank()) {
                        val next = lyrics.lines.getOrNull(idx + 1)?.timeMs ?: Long.MAX_VALUE
                        active = spawn(line, next).also { nextLine ->
                            if (resumed) nextLine.resumedAt = System.nanoTime()
                        }
                    }
                }
            }
            if (!paused && previewsNext()) {
                previewing = true
                updateUpcoming(lyrics, idx, pos, frameNanos)
            }
        }
        // 暂停、没歌词、只显示一句：不要预告
        if (!previewing) dropUpcoming()

        // 单句：新的一句一出来，淡出中的旧句马上消失（只在没有当前句时画淡出）。多句：淡出的和当前句一起画到淡完
        fading.removeAll { f -> (active != null && lyricLines() <= 1) || frameNanos - f.fadeStartNanos >= f.fadeNanos }
        updateLines(frameNanos)
        if (!worldVisible() || !allowsWorldLyrics(platform.hudHidden)) return

        val useFlash = textFlash.get() && !ktvMode.get()
        val gfx = DangoClient.gfx
        val floor = e.playerEyeY?.minus(GROUND_MARGIN)?.takeIf { aboveGround.get() }
        if (!useFlash) {
            val view = gfx.world.cameraAngles()
            val validView = view.yaw.isFinite() && view.pitch.isFinite()
            updateCameraAndScreen(view, validView)
            keepUpcomingInView(frameNanos)
            if (validView) resolveOverlaps(view, floor)
            avoidBlocks()
        }

        val deepGlow = gfx.glow
        gfx.guarded {
            val songNow = songPos.toLong()
            val beat = if (s != null) playbackBeatPulse(playbackPositionMs()) else 0f
            if (useFlash) {
                flash.draw(if (s != null && !paused) lyrics else null, songNow, beat, flashSize.value, 1)
            } else {
                // 先把歌词画到发光画布上做出光晕，再正常画一遍清晰的字
                val glowAmount = glowStrength.value * nightGlow()
                if (glow.get() && glowAmount > 0.01f && deepGlow.available && anyFloating()) {
                    try {
                        if (deepGlow.begin()) {
                            glowPass = true
                            try {
                                drawWorld(songNow, frameNanos, floor)
                            } finally {
                                glowPass = false
                            }
                            // 光晕是字本身的颜色扩散出去，不另外染色
                            deepGlow.end(1f, 1f, 1f, glowAmount * 4f, glowRadius.value)
                        }
                    } catch (t: Throwable) {
                        // 发光出任何错都不影响歌词本身：关掉发光，下面照常画字
                        glowPass = false
                        deepGlow.disable(t)
                    }
                    // 发光画完，把文字需要的渲染状态重新设好
                    gfx.world.resync()
                }
                // 近似色反色：字先画进离屏画布，合成时和后面的景色比较；做不了就照常直接画
                val contrast = autoContrast.get() && anyFloating() && gfx.contrast.begin()
                // 离屏画布上 alpha 要叠加：重新设一次文字的渲染状态（混合方式）
                if (contrast) gfx.world.resync()
                try {
                    drawWorld(songNow, frameNanos, floor)
                } finally {
                    if (contrast) {
                        gfx.contrast.end()
                        gfx.world.resync()
                    }
                }
            }
        }
    }

    /** 屏幕上的东西（原版暗角之后画，不会被压暗）：驾驶舱歌词和对拍提示 */
    @EventHandler
    fun renderHud(e: HudRenderEvent) {
        if (!enabled) return
        renderCockpit(e)
        Dance.tapStatus()?.let { status -> DangoClient.gfx.guarded { drawTapStatus(status) } }
    }

    internal fun renderCockpit(e: HudRenderEvent? = null) {
        if (!enabled || !allowsCockpitLyrics(DangoClient.platform.hudHidden)) return
        // 切换歌词样式时让旧列表淡出。
        if (sideVisible() || !side.idle) {
            DangoClient.gfx.guarded { drawSide(e, current) }
        }
    }

    /** 侧边歌词列表：当前歌词、进度和设置交给 [SideLyrics] 画（玻璃字、透视倾斜、发光、逐字点亮） */
    private fun drawSide(e: HudRenderEvent?, s: NowPlaying?) {
        val platform = DangoClient.platform
        // 驾驶舱字体：每帧对一下版本标记，字体文件被替换了就重新加载（热重载）
        val (sf, sk) = LyricFonts.load(sideFont.value)
        DangoClient.gfx.text.font(CjkStyle.SIDE, sf, sk)
        updatePreview()
        val frame = s?.let {
            val lyrics = currentKey?.let { key -> lyricsFor(key) }
            val pos = songPos.toLong()
            val shown = cover.shownKey == it.key
            SideLyrics.Frame(
                key = it.key,
                title = it.title,
                artist = it.artist,
                lines = lyrics?.lines.orEmpty(),
                index = lyrics?.indexAt(pos) ?: -1,
                positionMs = pos,
                accent = if (shown) cover.accent else CoverArt.DEFAULT_ACCENT,
                palette = if (shown) cover.palette else emptyList(),
                beat = playbackBeatPulse(playbackPositionMs()),
                visible = sideVisible() && !platform.chatOpen,
                previewRow = previewRow,
                pointerX = pointerX,
                pointerY = pointerY,
            )
        }
        side.draw(e, frame, SideLyrics.Settings(
            position = sidePosition.value,
            offsetX = sideOffsetX.value,
            offsetY = sideOffsetY.value,
            width = sideWidth.value,
            lines = sideLines.value,
            textSize = sideTextSize.value,
            lineSpacing = sideLineSpacing.value,
            glass = sideGlass.get(),
            opacity = sideOpacity.value,
            glow = sideGlow.get(),
            glowStrength = sideGlowStrength.value,
            halo = sideTextGlow.get(),
            translation = translation.get(),
            romanization = romanization.get(),
            tilt = sideTilt.value,
            pitch = sidePitch.value,
            italic = sideItalic.value,
            refraction = sideRefraction.value,
            guide = sideGuide.get(),
            inertia = if (sideInertia.get()) sideInertiaStrength.value else 0f,
            colors = sideColors.value,
            color = sideColor.value,
            tint = sideTint.value,
            brightness = sideBrightness.value,
            otherLines = sideOtherLines.value,
            contrast = autoContrast.get(),
            fontKey = sk,
            pixel = !sideSmoothFont.get(),
        ))
    }

    // ---------------------------------------------------------------- 驾驶舱歌词：HUD 鼠标翻看、单击跳转

    /** 不操作 3 秒自动回到正在唱的那一行；鼠标还停在歌词上时不回去 */
    private fun updatePreview() {
        if (previewRow < 0) return
        if (previewKey != currentKey) {
            previewRow = -1
            return
        }
        val now = System.currentTimeMillis()
        if (pointerX >= 0 && side.covers(pointerX, pointerY)) previewInputAt = now
        else if (now - previewInputAt > PREVIEW_RETURN_MS) previewRow = -1
    }

    /** HUD 鼠标（按住交互键）移到了 (x, y)，窗口像素、y 向下；鼠标收起时传 (-1, -1) */
    fun cockpitPointerMove(x: Int, y: Int) {
        pointerX = x
        pointerY = y
    }

    /**
     * HUD 鼠标在驾驶舱歌词上滚动滚轮：往上看前面的歌词，往下看后面的（不切换快捷栏）。
     * 返回 true = 这次滚轮归歌词；鼠标不在歌词上时返回 false，交给点歌台等别的 HUD
     */
    fun cockpitPointerScroll(x: Int, y: Int, wheel: Int): Boolean {
        if (wheel == 0 || !allowsCockpitLyrics(DangoClient.platform.hudHidden) || !side.covers(x, y)) return false
        if (!enabled || !sideVisible() || current == null) return true
        val lyrics = currentKey?.let { lyricsFor(it) } ?: return true
        val rows = lyrics.lines.size
        if (rows == 0) return true
        val currentRow = (lyrics.indexAt(songPos.toLong()) + 1).coerceIn(0, rows)
        // 记的是固定的行号：唱到下一句时，正在看的那一行不会跟着挪
        previewRow = ((if (previewRow >= 0 && previewKey == currentKey) previewRow else currentRow) - Integer.signum(wheel)).coerceIn(0, rows)
        previewKey = currentKey
        previewInputAt = System.currentTimeMillis()
        return true
    }

    /**
     * HUD 鼠标在驾驶舱歌词上按下左键：从指着的那一行开始播放（歌名那一行 = 从头放）。
     * 返回 true = 这次点击归歌词（点在行与行的空隙上也算，免得穿透给别的 HUD）
     */
    fun cockpitPointerPress(x: Int, y: Int): Boolean {
        if (!allowsCockpitLyrics(DangoClient.platform.hudHidden) || !side.covers(x, y)) return false
        val s = current
        if (!enabled || !sideVisible() || s == null) return true
        val lyrics = currentKey?.let { lyricsFor(it) } ?: return true
        val rows = lyrics.lines.size
        val row = side.rowAt(x, y)
        if (row < 0 || row > rows) return true
        val target = if (row == 0) 0L else lyrics.lines[row - 1].timeMs.coerceAtLeast(0L)
        PlaybackView.seek(target)
        previewRow = -1
        DangoLogger.info("Cockpit lyrics: jump to line {} ({} ms) of {}", row, target, s.key)
        return true
    }

    /** 对拍时准星下面显示点到第几下 / 点出来的 BPM */
    private fun drawTapStatus(text: String) {
        val platform = DangoClient.platform
        val scale = platform.guiScale.coerceAtLeast(1)
        val w = DangoClient.gfx.text.pixelWidth(text)
        val hud = DangoClient.gfx.hud
        hud.begin()
        hud.text(text, platform.windowWidth / scale / 2f - w / 2f, platform.windowHeight / scale / 2f + 10f, 0xFFFFFFFF.toInt(), true)
        hud.end()
    }

    private fun anyFloating(): Boolean = active != null || upcoming.isNotEmpty() || fading.isNotEmpty()

    /**
     * 开始淡出：[slow] = 多句里唱完退场的句子（挤出去的最老一句、间奏太久清掉的），慢慢淡出、边淡边往上浮；
     * 暂停、拖进度、切歌这类打断用短的。[delayNanos] 之后才开始变（换句时排在别的句子后面动）
     */
    private fun startFade(f: Floating, slow: Boolean = false, delayNanos: Long = 0L) {
        val now = System.nanoTime()
        f.fadeAlpha = lineAlpha(f, now)
        f.fadeScale = rankScale(f.rank)
        f.fadeLift = rankLift(f.rank)
        f.fadeGhost = ghostOf(f, now)
        f.fadeNanos = if (slow) RETIRE_FADE_NANOS else FADE_NANOS
        f.fadeSongMs = songPos.toLong()
        f.fadeStartNanos = now + delayNanos
        fading.add(f)
    }

    /** 拆成"词"：英文按空格；中日韩每两个字一组。返回每个词（去掉空白） */
    private fun splitWords(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var cjk = 0
        fun flush() {
            if (sb.isNotEmpty()) out += sb.toString()
            sb.setLength(0)
            cjk = 0
        }
        for (c in text) {
            when {
                c.isWhitespace() -> flush()
                c.code >= 0x2E80 -> {
                    if (sb.isNotEmpty() && cjk == 0) flush()   // 英文后面跟中文：分开
                    sb.append(c)
                    cjk++
                    if (cjk >= 2) flush()
                }
                else -> {
                    if (cjk > 0) flush()
                    sb.append(c)
                }
            }
        }
        flush()
        return out
    }

    /** 每个非空白字符的出现时间：有逐字歌词用真实时间，没有就把一句平均分给每个词 */
    private fun charTimes(line: LyricLine, nextMs: Long): LongArray {
        val times = ArrayList<Long>()
        val words = line.words
        if (words != null) {
            for (w in words) {
                val at = (w.startMs - line.timeMs).coerceAtLeast(0L)
                for (c in w.text) if (!c.isWhitespace()) times += at
            }
        } else {
            val toks = splitWords(line.text)
            val dur = (nextMs - line.timeMs).coerceAtLeast(300L)
            val span = min(dur * 0.75, toks.size * 330.0)
            toks.forEachIndexed { i, t ->
                val at = (span * i / max(1, toks.size)).toLong()
                repeat(t.length) { times += at }
            }
        }
        return times.toLongArray()
    }

    /**
     * "Above ground"：歌词中心最低只到眼睛下面 [GROUND_MARGIN] 格（站在平地上时离地还有一格多），
     * 换算成距离 [d] 处最多能往下偏多少度（原版 pitch 向下为正）
     */
    private fun maxPitch(d: Double): Float =
        Math.toDegrees(asin((GROUND_MARGIN / d).coerceIn(0.0, 1.0))).toFloat()

    /** [preview] = 作为后续预告提前出现，在 [previewSlot] 处躲开当前句和其他漂浮歌词 */
    private fun spawn(line: LyricLine, nextMs: Long, preview: Boolean = false, previewSlot: Int = -1): Floating {
        val text = line.text
        val startMs = line.timeMs
        val animationEnd = if (nextMs == Long.MAX_VALUE) {
            val boundary = playbackDurationMs().takeIf { it > startMs } ?: Long.MAX_VALUE
            line.ktvEndMs(boundary).takeUnless { it == Long.MAX_VALUE } ?: (startMs + 3500L)
        } else nextMs
        val ct = charTimes(line, animationEnd)
        val lines = wrap(text, 200)
        val chars = lines.sumOf { it.length }
        val lineDur = (animationEnd - startMs).coerceAtLeast(400L)
        val reveal = min((chars * 55L).coerceAtLeast(250L), (lineDur * 0.6).toLong())

        val phonetic = line.romanization?.takeIf { it.isNotBlank() }?.let { wrap(it, TRANSLATION_WRAP) }.orEmpty()
        val tr = line.translation?.takeIf { it.isNotBlank() }?.let { wrap(it, TRANSLATION_WRAP) }.orEmpty()
        val f = Floating(lines, startMs, reveal, 0.0, 0.0, 0.0, 0f, 0f, ct, line, nextMs).also { it.translation = tr; it.phonetic = phonetic }
        if (preview) {
            val r = -(previewSlot.coerceAtLeast(0) + 1)
            f.rank = r.toFloat()
            f.targetRank = r
            f.previewSinceNanos = System.nanoTime()
        }
        val player = DangoClient.platform.player ?: return f
        f.placedYaw = player.yaw
        f.placedPitch = player.pitch
        val d = distance.value.toDouble()
        val ex = player.eyeX
        val ey = player.eyeY
        val ez = player.eyeZ
        val drifting = fading.filter { it.fadeNanos == RETIRE_FADE_NANOS }
        val others = when {
            lyricLines() <= 1 -> emptyList()
            preview -> listOfNotNull(active) + upcoming + drifting
            else -> upcoming + drifting
        }
        var yaw: Float
        var pitch: Float
        if (others.isEmpty()) {
            yaw = player.yaw + (random.nextFloat() * 2f - 1f) * spread.value
            pitch = (player.pitch - 3f - random.nextFloat() * 10f).coerceIn(-60f, 30f)
            if (aboveGround.get()) pitch = min(pitch, maxPitch(d))
        } else {
            val spot = freeSpot(f, others, player.yaw, player.pitch, ex, ey, ez, d, if (preview) previewSlot.coerceAtLeast(0) else -1)
            yaw = spot.first
            pitch = spot.second
        }
        val yr = Math.toRadians(yaw.toDouble())
        val pr = Math.toRadians(pitch.toDouble())
        f.x = ex - sin(yr) * cos(pr) * d
        f.y = ey - sin(pr) * d
        f.z = ez + cos(yr) * cos(pr) * d
        f.yaw = yaw
        f.pitch = pitch
        return f
    }

    /** 一句歌词在镜头里占的角度范围（度）：中心的偏角、左右半宽、上下边（向下为正） */
    private class LyricBox(val yaw: Double, val halfWidth: Double, val top: Double, val bottom: Double)

    /** [o] 在镜头视角里占据的包围盒（含原文/音译/翻译、Keep away 距离、可选离地抬升与动态避让） */
    private fun boxOf(
        o: Floating, ex: Double, ey: Double, ez: Double,
        includeAvoid: Boolean = true, floor: Double? = null,
    ): LyricBox? {
        var rx = o.x - ex
        var ry = o.y - ey
        var rz = o.z - ez
        var len = sqrt(rx * rx + ry * ry + rz * rz)
        if (len < 1e-4) return null
        val minD = distance.value * 0.6
        if (keepsDistance() && len < minD) {
            val k = minD / len
            rx *= k; ry *= k; rz *= k
            len = minD
        }
        if (includeAvoid && abs(o.avoidPitch) > 1e-4) {
            val (nx, ny, nz) = rotatePitch(rx, ry, rz, o.avoidPitch, o.yaw)
            rx = nx; ry = ny; rz = nz
        }
        val rankVal = o.rank
        val s = 0.03 * scale.value * (distance.value / 6.5) * rankScale(rankVal)
        val lift = rankLift(rankVal)
        var effectiveRy = ry
        if (floor != null) {
            val extraFloor = max(0.0, floor - (ey + ry) + (rowBottom(o) + lift.coerceAtLeast(0f) + 6f) * s)
            effectiveRy += extraFloor
        }
        val effectiveLen = sqrt(rx * rx + effectiveRy * effectiveRy + rz * rz)
        if (effectiveLen < 1e-4) return null
        val centerPitch = Math.toDegrees(asin((-effectiveRy / effectiveLen).coerceIn(-1.0, 1.0)))
        return LyricBox(
            Math.toDegrees(atan2(-rx, rz)),
            Math.toDegrees(atan2(rowWidth(o) * s / 2.0, effectiveLen)),
            centerPitch + Math.toDegrees(atan2((-halfMain(o) + lift) * s, effectiveLen)),
            centerPitch + Math.toDegrees(atan2((rowBottom(o) + lift) * s, effectiveLen)),
        )
    }

    /** 两句之间隔开多少度：横向偏角按平均俯仰角余弦修正，与纵向间隔取较大者；负数 = 叠在一起 */
    private fun separation(a: LyricBox, b: LyricBox): Double {
        val avgPitch = ((a.top + a.bottom) * 0.5 + (b.top + b.bottom) * 0.5) * 0.5
        val cosPitch = cos(Math.toRadians(avgPitch)).coerceAtLeast(0.2)
        val gx = abs(wrapDegrees(a.yaw - b.yaw)) * cosPitch - (a.halfWidth + b.halfWidth)
        val gy = if (a.top < b.top) b.top - a.bottom else a.top - b.bottom
        return max(gx, gy)
    }

    /**
     * 多句：新的一句在视线附近挑一个空着的地方，结合随机候选与确定性 3×5 兜底网格，尽量不压在还漂着的句子上
     */
    private fun freeSpot(
        f: Floating, others: List<Floating>, viewYaw: Float, viewPitch: Float,
        ex: Double, ey: Double, ez: Double, d: Double, previewSlot: Int = -1,
    ): Pair<Float, Float> {
        val boxes = others.mapNotNull { boxOf(it, ex, ey, ez) }
        val s = 0.03 * scale.value * (distance.value / 6.5)
        val halfWidth = Math.toDegrees(atan2(rowWidth(f) * s / 2.0, d))
        val up = Math.toDegrees(atan2(-halfMain(f) * s, d))
        val down = Math.toDegrees(atan2(rowBottom(f) * s, d))
        val lastSide = boxes.firstOrNull()?.let { Math.signum(wrapDegrees(it.yaw - viewYaw)) } ?: 0.0
        val roleOffset = when (previewSlot) {
            -1 -> -8f
            0 -> -2f
            1 -> 3f
            else -> 6f
        }
        val preferredPitch = viewPitch + roleOffset
        val platform = DangoClient.platform
        val vHalf = platform.fov / 2.0
        val aspect = platform.windowWidth.toDouble() / platform.windowHeight.coerceAtLeast(1)
        val hHalf = Math.toDegrees(atan(tan(Math.toRadians(vHalf)) * aspect))
        val yawRange = min(spread.value.toDouble(), max(0.0, hHalf - halfWidth - 2.0)).toFloat()
        val pitchRange = max(0.0, vHalf - max(-up, down) - 2.0).toFloat()
        val minP = max((viewPitch - pitchRange).toDouble(), -60.0).toFloat()
        val maxP = min((viewPitch + pitchRange).toDouble(), if (aboveGround.get()) min(30.0, maxPitch(d).toDouble()) else 30.0).toFloat()
        val clampedPreferred = preferredPitch.coerceIn(min(minP, maxP), max(minP, maxP))

        var bestYaw = viewYaw
        var bestPitch = clampedPreferred
        var bestScore = Double.NEGATIVE_INFINITY

        fun eval(offset: Float, rawPitch: Float, jitter: Double) {
            var pitch = rawPitch.coerceIn(viewPitch - pitchRange, viewPitch + pitchRange).coerceIn(-60f, 30f)
            if (aboveGround.get()) pitch = min(pitch, maxPitch(d))
            val yaw = viewYaw + offset
            val box = LyricBox(yaw.toDouble(), halfWidth, pitch + up, pitch + down)
            val room = boxes.minOfOrNull { separation(box, it) } ?: PLACEMENT_ROOM
            var score = min(room, PLACEMENT_ROOM) * 1.0
            score -= 0.03 * abs(offset) + 0.05 * abs(pitch - clampedPreferred)
            if (lastSide != 0.0 && Math.signum(offset.toDouble()) == -lastSide) score += 0.5
            score += jitter
            if (score > bestScore) {
                bestScore = score
                bestYaw = yaw
                bestPitch = pitch
            }
        }

        repeat(PLACEMENT_TRIES) {
            val offset = (random.nextFloat() * 2f - 1f) * yawRange
            val pitch = viewPitch + PLACEMENT_PITCH_MIN + random.nextFloat() * (PLACEMENT_PITCH_MAX - PLACEMENT_PITCH_MIN)
            eval(offset, pitch, random.nextDouble() * 0.3)
        }
        // 确定性 3×5 兜底网格：避免高密度多行时全靠随机采样错过空隙
        val pLow = (viewPitch + PLACEMENT_PITCH_MIN).coerceIn(-60f, 30f)
        val pHigh = (viewPitch + PLACEMENT_PITCH_MAX).coerceIn(-60f, 30f)
        for (gx in -1..1) {
            val offset = gx * 0.65f * yawRange
            for (gy in 0..4) {
                val pitch = pLow + (pHigh - pLow) * (gy / 4f)
                eval(offset, pitch, 0.0)
            }
        }
        return bestYaw to bestPitch
    }


    /** 按像素宽度换行：英文在空格处断，中日韩可以在任意字之间断 */
    private fun wrap(text: String, maxWidth: Int): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var lastSpace = -1
        for (c in text) {
            sb.append(c)
            if (c == ' ') lastSpace = sb.length - 1
            if (strW(sb.toString()) > maxWidth && sb.length > 1) {
                if (lastSpace > 0) {
                    out += sb.substring(0, lastSpace).trim()
                    val rest = sb.substring(lastSpace + 1)
                    sb.setLength(0)
                    sb.append(rest)
                } else {
                    out += sb.substring(0, sb.length - 1).trim()
                    sb.setLength(0)
                    sb.append(c)
                }
                lastSpace = -1
            }
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    // 默认暖白偏粉；翻译默认比原文稍灰一点（设置里可以改）
    /** 封面的代表色（选了封面主色 / 封面渐变、并且这首歌的封面已经出来时才有） */
    private fun coverColors(mode: Int = lyricColors.value): List<Int>? {
        if (mode != COLORS_COVER && mode != COLORS_GRADIENT) return null
        if (currentKey == null || cover.shownKey != currentKey) return null
        return cover.palette.takeIf { it.isNotEmpty() }
    }

    /** 彩虹的相位：每帧算一次，发光层和本体用同一个值，颜色才对得上 */
    private var rainbowPhase = 0f

    /** 整句一个颜色时用的颜色（封面主色或自定义颜色） */
    private val textRgb: Int get() = coverColors()?.let { it[0] and 0xFFFFFF } ?: textColor.value
    private val translationRgb: Int get() = coverColors()?.let { it[0] and 0xFFFFFF } ?: translationColor.value

    /**
     * 一个字的颜色：[center] 是这个字的中心离行首多远，[width] 是整行宽。
     * 彩虹按位置铺开色相再随时间流动；封面渐变在几种封面颜色之间从左到右过渡；其他情况整句一个颜色 [base]。
     */
    private fun charRgb(center: Float, width: Float, base: Int, mode: Int = lyricColors.value): Int {
        val u = if (width > 0f) (center / width).coerceIn(0f, 1f) else 0.5f
        if (mode == COLORS_RAINBOW) {
            val hue = ((u * RAINBOW_SPAN + rainbowPhase) % 1f + 1f) % 1f
            return java.awt.Color.HSBtoRGB(hue, 0.55f, 1f) and 0xFFFFFF
        }
        val colors = if (mode == COLORS_GRADIENT) coverColors(mode) else null
        if (colors == null || colors.size < 2) return base
        val at = u * (colors.size - 1)
        val i = at.toInt().coerceAtMost(colors.size - 2)
        return mixRgb(colors[i], colors[i + 1], at - i)
    }

    internal fun ktvHighlight(center: Float, width: Float): Int {
        val mode = ktvLyricColors.value
        val base = coverColors(mode)?.firstOrNull() ?: ktvSungColor.value
        return charRgb(center, width, base, mode)
    }

    /** 两个颜色按 [t]（0..1）混合，分通道线性插值 */
    private fun mixRgb(from: Int, to: Int, t: Float): Int {
        val r0 = (from shr 16) and 0xFF
        val g0 = (from shr 8) and 0xFF
        val b0 = from and 0xFF
        val r = (r0 + (((to shr 16) and 0xFF) - r0) * t).roundToInt().coerceIn(0, 255)
        val g = (g0 + (((to shr 8) and 0xFF) - g0) * t).roundToInt().coerceIn(0, 255)
        val b = (b0 + ((to and 0xFF) - b0) * t).roundToInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    // ---------------------------------------------------------------- 歌词文字（平滑字体 / MC 字体 + 发光）

    /** 平滑字体的字号：和 MC 字体的大写字高（7 像素）差不多 */
    private val lyricSize = 10f

    private fun strW(s: String): Float =
        if (smoothFont.get()) DangoClient.gfx.text.smoothWidth(s, lyricSize, CjkStyle.LYRIC) else DangoClient.gfx.text.pixelWidth(s)

    private fun charW(c: Char): Float =
        if (smoothFont.get()) DangoClient.gfx.text.smoothWidth(c.toString(), lyricSize, CjkStyle.LYRIC) else DangoClient.gfx.text.pixelCharWidth(c)

    /** 只在晚上发光时：天黑（含下雨天变暗）慢慢亮起来，天亮慢慢灭掉。返回 0..1 */
    private var nightLevel = -1f
    private var nightNanos = 0L

    private fun nightGlow(): Float {
        val target = if (!glowNightOnly.get()) 1f else {
            val dark = DangoClient.platform.skyDarken
            if (dark == null || dark >= NIGHT_SKY_DARKEN) 1f else 0f
        }
        val now = System.nanoTime()
        if (nightLevel < 0f) nightLevel = target
        val dt = if (nightNanos == 0L) 0f else ((now - nightNanos) / 1e9f).coerceIn(0f, 0.1f)
        nightNanos = now
        nightLevel += (target - nightLevel) * (1f - kotlin.math.exp(-dt / 0.8f))
        return nightLevel
    }

    /** 一个要画的字：[rgb] 在排版时就按它在句子里的位置定好了，抖动和动画不会让同一个字变色 */
    private class Glyph(val c: Char, val x: Float, val y: Float, val a: Float, val k: Int, val rgb: Int, val pop: Float = 1f)

    /** 由 (字序号, 第几帧, 种类) 得到一个固定的 -1..1 随机数：同一帧里不管画几遍（发光层、本体）结果都一样 */
    private fun hashRand(k: Int, frame: Long, salt: Int, seed: Int): Float {
        var h = k * 73856093 xor (frame * 19349663L).toInt() xor (salt * 83492791) xor seed
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
        return (h and 0xFFFF) / 32767.5f - 1f
    }

    /**
     * 低帧率 wiggle：每个字每 1/fps 秒换一个随机的小角度、小错位、小缩放，中间保持不动，
     * 就是 AE 里 posterizeTime(8) + wiggle 的一顿一顿手绘感。
     */
    private inline fun withWiggle(g: Glyph, seed: Int, frame: Long, draw: () -> Unit) = withWiggle(g, seed, frame, wiggle.get(), draw)

    private inline fun withWiggle(g: Glyph, seed: Int, frame: Long, on: Boolean, draw: () -> Unit) {
        val amt = wiggleAmount.value
        val wig = on && amt > 0.001f
        if (!wig && g.pop == 1f) {
            draw()
            return
        }
        val rot = if (wig) hashRand(g.k, frame, 1, seed) * 9f * amt else 0f
        val dx = if (wig) hashRand(g.k, frame, 2, seed) * 0.8f * amt else 0f
        val dy = if (wig) hashRand(g.k, frame, 3, seed) * 1.6f * amt else 0f
        val sc = (if (wig) 1f + hashRand(g.k, frame, 4, seed) * 0.12f * amt else 1f) * g.pop
        val cx = g.x + charW(g.c) / 2f
        val cy = g.y + 3.5f
        val pen = DangoClient.gfx.text
        pen.push()
        pen.translate(cx + dx, cy + dy)
        pen.rotate(rot)
        pen.scale(sc, sc)
        pen.translate(-cx, -cy)
        draw()
        pen.pop()
    }

    /** 画一个字：y 是字的顶部（和 MC 字体一样） */
    private fun drawGlyph(c: Char, x: Float, y: Float, rgb: Int, a: Float, smooth: Boolean, shadow: Boolean) {
        val pen = DangoClient.gfx.text
        if (smooth) {
            val r = (rgb shr 16 and 255) / 255f
            val g = (rgb shr 8 and 255) / 255f
            val b = (rgb and 255) / 255f
            val baseline = y + 7.6f
            if (shadow) {
                pen.color(0f, 0f, 0f, 0.45f * a)
                pen.smoothText(c.toString(), x + 0.6f, baseline + 0.6f, lyricSize, CjkStyle.LYRIC)
            }
            pen.color(r, g, b, a)
            pen.smoothText(c.toString(), x, baseline, lyricSize, CjkStyle.LYRIC)
        } else {
            val alpha = (a * 255f).toInt()
            if (alpha < 5) return                          // FontRenderer 会把 alpha<4 当成不透明
            pen.pixelText(c.toString(), x, y, (alpha shl 24) or rgb, shadow)
        }
    }

    /**
     * 逐字出现：每个字按顺序在 revealMs 内依次淡入并从下往上浮起一点；
     * 发光由 [DeepGlow] 另外做（先把字画到发光画布上），这里只画字本身。
     */
    private fun drawKtv(f: Floating, positionMs: Long, alpha: Float, preview: Boolean, wiggleFrame: Long?) {
        val boundary = if (f.nextMs == Long.MAX_VALUE) {
            playbackDurationMs().takeIf { it > f.startMs } ?: Long.MAX_VALUE
        } else f.nextMs
        val sung = if (preview) 0f else f.lyric.sungCharacters(positionMs, boundary)
        drawKtvLines(f.lines, sung, alpha, -halfMain(f), wiggleFrame = wiggleFrame)
        val phonetic = phoneticRows(f)
        val translated = translationRows(f)
        if (phonetic.isNotEmpty() || translated.isNotEmpty()) {
            val pen = DangoClient.gfx.text
            val progress = if (preview) 0f else f.lyric.sungFraction(positionMs, boundary)
            pen.push()
            try {
                pen.translate(0f, halfMain(f) + ANNOTATION_GAP)
                pen.scale(TRANSLATION_SCALE, TRANSLATION_SCALE)
                val phoneticCount = phonetic.sumOf { line -> line.count { !it.isWhitespace() } }
                val translatedCount = translated.sumOf { line -> line.count { !it.isWhitespace() } }
                val annotationFrame = wiggleFrame?.takeIf { translationWiggle.get() }
                drawKtvLines(phonetic, progress * phoneticCount, alpha * 0.85f, 0f, wiggleFrame = annotationFrame)
                drawKtvLines(translated, progress * translatedCount, alpha * 0.85f, phonetic.size * 11f, wiggleFrame = annotationFrame)
            } finally {
                pen.pop()
            }
        }
    }

    private fun drawKtvLines(
        lines: List<String>, sung: Float, alpha: Float, top: Float,
        useKtvColors: Boolean = true, wiggleFrame: Long? = null,
    ) {
        val seed = if (wiggleFrame != null) lines.hashCode() else 0
        val smooth = smoothFont.get()
        var index = 0
        var y = top
        if (smooth) DangoClient.gfx.text.beginSmooth()
        for (line in lines) {
            val width = strW(line)
            var x = -width / 2f
            for (c in line) {
                val w = charW(c)
                if (!c.isWhitespace()) {
                    val progress = (sung - index++).coerceIn(0f, 1f)
                    val center = x + width / 2f + w / 2f
                    val base = if (useKtvColors) ktvBaseColor.value else charRgb(center, width, textRgb)
                    val draw = {
                        if (progress < 1f) {
                            val shadow = !glowPass && (!useKtvColors || ktvBaseShadow.get())
                            drawGlyph(c, x, y, base, alpha, smooth, shadow)
                        }
                        if (progress > 0f) drawGlyph(c, x, y, ktvHighlight(center, width), alpha * progress, smooth, !glowPass && ktvSungShadow.get())
                    }
                    if (wiggleFrame == null) draw()
                    else withWiggle(Glyph(c, x, y, alpha, index, base), seed, wiggleFrame, draw)
                }
                x += w
            }
            y += 11f
        }
        if (smooth) DangoClient.gfx.text.endSmooth()
    }

    /**
     * [ghost] > 0（多句）：还没出现的字不是不画，而是画成这么淡；到了时间从淡变亮，轻轻跳一下（按词闪出时鼓一下）。
     * 这样预告变成当前句时不会先消失，唱到哪里就亮到哪里
     */
    private fun drawAnimated(lines: List<String>, elapsedMs: Long, revealMs: Long, alphaMul: Float, charTimes: LongArray?, wiggleFrame: Long?, ghost: Float = 0f) {
        val smooth = smoothFont.get()
        val flashMode = charTimes != null && charTimes.isNotEmpty()
        var n = 0   // 非空白字符序号
        val total = lines.sumOf { it.length }.coerceAtLeast(1)
        val lineH = 11f
        var y = -(lines.size * lineH) / 2f
        var k = 0
        val glyphs = ArrayList<Glyph>()
        for (line in lines) {
            val lineW = strW(line)
            var x = -lineW / 2f
            for (c in line) {
                val rgb = charRgb(x + lineW / 2f + charW(c) / 2f, lineW, textRgb)
                if (flashMode && !c.isWhitespace()) {
                    // 按词闪出：到了这个词的时间，整个词一下子出现，先放大再弹回原大小
                    val at = charTimes!![min(n, charTimes.size - 1)]
                    n++
                    val t = (elapsedMs - at) / 160f
                    if (ghost > 0f) {
                        // 从淡淡的样子亮起来：大小鼓一下再回去（两头都是原大小，不会一下子跳大）
                        val a = max((t * 3f).coerceIn(0f, 1f) * alphaMul, ghost)
                        val pop = if (t > 0f && t < 1f) 1f + 0.3f * sin(Math.PI * t).toFloat() else 1f
                        if (a > 0.02f) glyphs += Glyph(c, x, y, a, k, rgb, pop)
                    } else if (t >= 0f) {
                        val a = (t * 3f).coerceIn(0f, 1f) * alphaMul
                        val back = (1f - t).coerceIn(0f, 1f)
                        val pop = 1f + 0.55f * back * back
                        if (a > 0.02f) glyphs += Glyph(c, x, y, a, k, rgb, pop)
                    }
                } else if (!flashMode) {
                    val appearAt = k.toFloat() / total * revealMs
                    var a = ((elapsedMs - appearAt) / 180f).coerceIn(0f, 1f)
                    a = a * a * (3f - 2f * a)                 // smoothstep
                    if (ghost > 0f) {
                        // 从淡淡的样子亮起来，顺便往上轻轻跳一下（起点终点都在原位）
                        val alpha = max(a * alphaMul, ghost)
                        val hop = -sin(Math.PI * a).toFloat() * 1.5f
                        if (alpha > 0.02f && !c.isWhitespace()) glyphs += Glyph(c, x, y + hop, alpha, k, rgb)
                    } else {
                        val alpha = a * alphaMul
                        if (alpha > 0.02f && !c.isWhitespace()) glyphs += Glyph(c, x, y + (1f - a) * 5f, alpha, k, rgb)
                    }
                }
                x += charW(c)
                k++
            }
            y += lineH
        }
        if (glyphs.isEmpty()) return

        if (smooth) DangoClient.gfx.text.beginSmooth()

        val seed = lines.hashCode()
        for (g in glyphs) withWiggle(g, seed, wiggleFrame ?: 0L, wiggleFrame != null && wiggle.get()) {
            drawGlyph(g.c, g.x, g.y, g.rgb, g.a, smooth, !glowPass)
        }

        if (smooth) DangoClient.gfx.text.endSmooth()
    }

    /**
     * 翻译：画在原文下面，字小一点、淡一点；原文开始出现后整行淡入（不逐字）。
     * 和原文用同一个位置和朝向，发光层也会画到。
     */
    private fun drawTranslation(lines: List<String>, elapsedMs: Long, revealMs: Long, alphaMul: Float, top: Float, scale: Float, settled: Boolean, wiggleFrame: Long?) {
        if (lines.isEmpty()) return
        val smooth = smoothFont.get()
        val pen = DangoClient.gfx.text
        val lineH = 11f
        val animate = translationAnimation.get()
        val wig = wiggleFrame != null && translationWiggle.get()
        pen.push()
        pen.translate(0f, top)
        pen.scale(scale, scale)
        if (smooth) pen.beginSmooth()
        if (!wig && (settled || !animate)) {
            // 默认：整行一起淡入
            var a = if (settled) 1f else ((elapsedMs - 120L) / 260f).coerceIn(0f, 1f)
            a = a * a * (3f - 2f * a)
            val alpha = a * alphaMul * 0.85f
            if (alpha > 0.02f) {
                var y = 0f
                for (line in lines) {
                    if (lyricColors.value == COLORS_RAINBOW || (lyricColors.value == COLORS_GRADIENT && coverColors() != null)) {
                        // 每个字颜色不一样时只能一个个画
                        val lineW = strW(line)
                        var x = -lineW / 2f
                        for (c in line) {
                            val w = charW(c)
                            if (!c.isWhitespace()) drawGlyph(c, x, y, charRgb(x + lineW / 2f + w / 2f, lineW, translationRgb), alpha, smooth, !glowPass)
                            x += w
                        }
                    } else {
                        drawLine(line, -strW(line) / 2f, y, translationRgb, alpha, smooth, !glowPass)
                    }
                    y += lineH
                }
            }
        } else {
            // 逐字：出场动画和原文一样（按顺序淡入、从下往上浮起一点），抖动也用同一套参数
            val total = lines.sumOf { it.length }.coerceAtLeast(1)
            val glyphs = ArrayList<Glyph>()
            var k = 0
            var y = 0f
            for (line in lines) {
                val lineW = strW(line)
                var x = -lineW / 2f
                for (c in line) {
                    val rgb = charRgb(x + lineW / 2f + charW(c) / 2f, lineW, translationRgb)
                    val a: Float
                    val lift: Float
                    if (settled) {
                        a = 1f
                        lift = 0f
                    } else if (animate) {
                        val appearAt = 120f + k.toFloat() / total * revealMs
                        var t = ((elapsedMs - appearAt) / 180f).coerceIn(0f, 1f)
                        t = t * t * (3f - 2f * t)
                        a = t
                        lift = (1f - t) * 5f
                    } else {
                        var t = ((elapsedMs - 120L) / 260f).coerceIn(0f, 1f)
                        t = t * t * (3f - 2f * t)
                        a = t
                        lift = 0f
                    }
                    val alpha = a * alphaMul * 0.85f
                    if (alpha > 0.02f && !c.isWhitespace()) glyphs += Glyph(c, x, y + lift, alpha, 1000 + k, rgb)
                    x += charW(c)
                    k++
                }
                y += lineH
            }
            val seed = lines.hashCode()
            for (g in glyphs) withWiggle(g, seed, wiggleFrame ?: 0L, wig) { drawGlyph(g.c, g.x, g.y, g.rgb, g.a, smooth, !glowPass) }
        }
        if (smooth) pen.endSmooth()
        pen.pop()
    }

    /** 整行画（翻译用，不需要逐字动画） */
    private fun drawLine(text: String, x: Float, y: Float, rgb: Int, a: Float, smooth: Boolean, shadow: Boolean) {
        val pen = DangoClient.gfx.text
        if (smooth) {
            val r = (rgb shr 16 and 255) / 255f
            val g = (rgb shr 8 and 255) / 255f
            val b = (rgb and 255) / 255f
            val baseline = y + 7.6f
            if (shadow) {
                pen.color(0f, 0f, 0f, 0.45f * a)
                pen.smoothText(text, x + 0.6f, baseline + 0.6f, lyricSize, CjkStyle.LYRIC)
            }
            pen.color(r, g, b, a)
            pen.smoothText(text, x, baseline, lyricSize, CjkStyle.LYRIC)
        } else {
            val alpha = (a * 255f).toInt()
            if (alpha < 5) return
            pen.pixelText(text, x, y, (alpha shl 24) or rgb, shadow)
        }
    }

    // ---------------------------------------------------------------- 不跑出屏幕

    private var keepNanos = 0L
    /** 上一帧镜头的位置（Stay on screen 跟着走用），NaN = 还没有 */
    private var lastCamX = Double.NaN
    private var lastCamY = 0.0
    private var lastCamZ = 0.0

    private fun wrapDegrees(d: Double): Double {
        var v = d % 360.0
        if (v >= 180.0) v -= 360.0
        if (v < -180.0) v += 360.0
        return v
    }

    /**
     * 统一同步镜头位置：多行模式先把所有漂浮歌词保持在同距球面上，再对当前句做视口拉回，避免相机位移被重复累加。
     */
    private fun updateCameraAndScreen(view: ViewAngles, validView: Boolean) {
        val platform = DangoClient.platform
        if (platform.player == null) return
        val multi = lyricLines() > 1
        if (!multi && !staysOnScreen()) {
            lastCamX = Double.NaN
            return
        }
        val world = DangoClient.gfx.world
        val camX = world.cameraX
        val camY = world.cameraY
        val camZ = world.cameraZ
        if (multi) syncMultiLineSphere(camX, camY, camZ)
        if (validView && staysOnScreen()) keepOnScreen(view, camX, camY, camZ, trackCarry = !multi)
        lastCamX = camX
        lastCamY = camY
        lastCamZ = camZ
    }

    /** 多行同球面共距：围绕当前镜头维持统一半径，消除走路/飞行时的"一前一后"景深错位，同时保留转头时的 3D 方向分布 */
    private fun syncMultiLineSphere(camX: Double, camY: Double, camZ: Double) {
        val d = distance.value.toDouble()
        val hasPrev = !lastCamX.isNaN()
        fun sync(f: Floating) {
            val refX = if (f.sphereSynced && hasPrev) lastCamX else camX
            val refY = if (f.sphereSynced && hasPrev) lastCamY else camY
            val refZ = if (f.sphereSynced && hasPrev) lastCamZ else camZ
            f.sphereSynced = true
            val rx = f.x - refX
            val ry = f.y - refY
            val rz = f.z - refZ
            val len = sqrt(rx * rx + ry * ry + rz * rz)
            if (len < 1e-4) return
            val k = d / len
            f.x = camX + rx * k
            f.y = camY + ry * k
            f.z = camZ + rz * k
        }
        active?.let(::sync)
        upcoming.forEach(::sync)
        fading.forEach(::sync)
    }

    /**
     * "Stay on screen"：歌词（连同字的宽度）超出视野时，沿着离视线最近的方向滑回屏幕边上。
     */
    private fun keepOnScreen(view: ViewAngles, camX: Double, camY: Double, camZ: Double, trackCarry: Boolean) {
        val now = System.nanoTime()
        val dt = if (keepNanos == 0L) 0.016 else ((now - keepNanos) / 1e9).coerceIn(0.0, 0.1)
        keepNanos = now
        val platform = DangoClient.platform

        var carryX = 0.0
        var carryY = 0.0
        var carryZ = 0.0
        if (trackCarry && !lastCamX.isNaN() && dt > 0.0) {
            val mx = camX - lastCamX
            val my = camY - lastCamY
            val mz = camZ - lastCamZ
            val moved = sqrt(mx * mx + my * my + mz * mz)
            // 一帧走了 50 格以上是传送 / 换维度：不跟，下面的距离限制会把歌词拉回来
            if (moved < 50.0) {
                val carry = ((moved / dt - 8.0) / 6.0).coerceIn(0.0, 1.0)
                carryX = mx * carry
                carryY = my * carry
                carryZ = mz * carry
            }
        }
        val maxDist = distance.value * 2.0
        val minD = distance.value * 0.6

        val vHalf = platform.fov / 2.0
        val aspect = platform.windowWidth.toDouble() / platform.windowHeight.coerceAtLeast(1)
        val hHalf = Math.toDegrees(atan(tan(Math.toRadians(vHalf)) * aspect))
        val s = 0.03 * scale.value * (distance.value / 6.5)

        fun follow(f: Floating, spring: Boolean) {
            f.x += carryX
            f.y += carryY
            f.z += carryZ
            var rx = f.x - camX
            var ry = f.y - camY
            var rz = f.z - camZ
            var len = sqrt(rx * rx + ry * ry + rz * rz)
            if (len < 1e-4) return
            if (len > maxDist) {
                val k = maxDist / len
                rx *= k; ry *= k; rz *= k
                len = maxDist
                f.x = camX + rx
                f.y = camY + ry
                f.z = camZ + rz
            }
            if (!spring) return

            val drawnLen = max(len, minD)
            if (drawnLen > len) {
                val k = drawnLen / len
                val px = rx * k
                val py = ry * k
                val pz = rz * k
                if (platform.clearFraction(camX, camY, camZ, camX + px, camY + py, camZ + pz) >= 1.0) {
                    rx = px; ry = py; rz = pz
                    len = drawnLen
                    f.x = camX + rx
                    f.y = camY + ry
                    f.z = camZ + rz
                }
            }

            val halfW = Math.toDegrees(atan2(rowWidth(f) * s / 2.0, drawnLen))
            val halfH = Math.toDegrees(atan2(rowBottom(f) * s, drawnLen))
            val hLim = max(hHalf * 0.2, hHalf - halfW - 2.0)
            val vLim = max(vHalf * 0.2, vHalf - halfH - 2.0)

            val curYaw = Math.toDegrees(atan2(-rx, rz))
            val curPitch = Math.toDegrees(asin((-ry / len).coerceIn(-1.0, 1.0)))
            val dYaw = wrapDegrees(curYaw - view.yaw)
            val dPitch = curPitch - view.pitch

            val tYaw = dYaw.coerceIn(-hLim, hLim)
            val tPitch = dPitch.coerceIn(-vLim, vLim)

            val eYaw = dYaw - tYaw
            val ePitch = dPitch - tPitch
            if (eYaw == 0.0 && ePitch == 0.0 && abs(f.vYaw) < 0.05 && abs(f.vPitch) < 0.05) {
                f.vYaw = 0.0
                f.vPitch = 0.0
                return
            }

            var moveYaw = 0.0
            var movePitch = 0.0
            var left = dt
            while (left > 1e-6) {
                val h = min(left, 1.0 / 240.0)
                left -= h
                f.vYaw += (-SPRING * SPRING * (eYaw - moveYaw) - 2.0 * SPRING * f.vYaw) * h
                f.vPitch += (-SPRING * SPRING * (ePitch - movePitch) - 2.0 * SPRING * f.vPitch) * h
                moveYaw += f.vYaw * h
                movePitch += f.vPitch * h
            }

            val newYaw = curYaw + moveYaw
            val newPitch = (curPitch + movePitch).coerceIn(-89.0, 89.0)
            val yr = Math.toRadians(newYaw)
            val pr = Math.toRadians(newPitch)
            f.x = camX - sin(yr) * cos(pr) * len
            f.y = camY - sin(pr) * len
            f.z = camZ + cos(yr) * cos(pr) * len
            f.yaw = (f.yaw + wrapDegrees(newYaw - curYaw)).toFloat()
            f.pitch = (f.pitch + (newPitch - curPitch)).toFloat().coerceIn(-90f, 90f)
        }

        val single = lyricLines() <= 1
        active?.let { follow(it, true) }
        if (single) fading.forEach { follow(it, true) }
    }

    /** 计算使 [[baseTop], [baseBottom]] 避开所有重叠阻塞区间的最小俯仰偏移（带视口上限与方向迟滞防抖） */
    internal fun solveAvoidPitch(
        baseTop: Double,
        baseBottom: Double,
        blockers: List<Pair<Double, Double>>,
        minPitch: Double,
        maxPitch: Double,
        preferUp: Boolean,
        prevDir: Int,
    ): Pair<Double, Int> {
        if (blockers.isEmpty()) return 0.0 to 0
        val sorted = blockers.sortedBy { it.first }
        val merged = ArrayList<Pair<Double, Double>>(sorted.size)
        for (b in sorted) {
            val last = merged.lastOrNull()
            if (last == null || b.first > last.second) merged += b
            else merged[merged.lastIndex] = last.first to max(last.second, b.second)
        }
        if (merged.none { baseBottom > it.first && baseTop < it.second }) return 0.0 to 0

        val height = (baseBottom - baseTop).coerceAtLeast(0.1)
        var curTop = baseTop
        var curBottom = baseBottom
        for (i in merged.indices.reversed()) {
            val iv = merged[i]
            if (curBottom > iv.first && curTop < iv.second) {
                curBottom = iv.first
                curTop = curBottom - height
            }
        }
        val shiftUp = curTop - baseTop

        curTop = baseTop
        curBottom = baseBottom
        for (iv in merged) {
            if (curBottom > iv.first && curTop < iv.second) {
                curTop = iv.second
                curBottom = curTop + height
            }
        }
        val shiftDown = curTop - baseTop

        val upFits = baseTop + shiftUp >= minPitch
        val downFits = baseBottom + shiftDown <= maxPitch
        val chooseUp = when {
            upFits && !downFits -> true
            !upFits && downFits -> false
            else -> {
                val upCost = abs(shiftUp) - (if (preferUp) 2.0 else 0.0) - (if (prevDir < 0) AVOID_HYSTERESIS else 0.0)
                val downCost = abs(shiftDown) - (if (!preferUp) 1.5 else 0.0) - (if (prevDir > 0) AVOID_HYSTERESIS else 0.0)
                upCost <= downCost
            }
        }
        val rawShift = if (chooseUp) shiftUp else shiftDown
        val lo = -82.0 - baseTop
        val hi = 82.0 - baseBottom
        val clampedShift = if (lo <= hi) rawShift.coerceIn(lo, hi) else 0.0
        val dir = when {
            clampedShift < -1e-4 -> -1
            clampedShift > 1e-4 -> 1
            else -> 0
        }
        return clampedShift to dir
    }

    private fun stepAvoidPitch(f: Floating, target: Double, dt: Double) {
        if (abs(f.avoidPitch - target) < 1e-3 && abs(f.avoidPitchVel) < 1e-2) {
            f.avoidPitch = target
            f.avoidPitchVel = 0.0
            return
        }
        var x = f.avoidPitch
        var v = f.avoidPitchVel
        var left = dt
        while (left > 1e-6) {
            val h = min(left, 1.0 / 240.0)
            left -= h
            v += (-AVOID_SPRING * AVOID_SPRING * (x - target) - 2.0 * AVOID_SPRING * v) * h
            x += v * h
        }
        if (abs(x - target) < 1e-3 && abs(v) < 1e-2) {
            x = target
            v = 0.0
        }
        f.avoidPitch = x
        f.avoidPitchVel = v
    }

    /** 按优先级（当前句 > 后续预告 > 淡出句）动态检测视角碰撞并用阻尼弹簧平滑上下避让 */
    private fun resolveOverlaps(view: ViewAngles, floor: Double?) {
        val now = System.nanoTime()
        val dt = if (avoidCollisionNanos == 0L) 0.016 else ((now - avoidCollisionNanos) / 1e9).coerceIn(0.0, 0.1)
        avoidCollisionNanos = now
        if (lyricLines() <= 1) {
            active?.let { stepAvoidPitch(it, 0.0, dt); it.avoidDir = 0 }
            fading.forEach { stepAvoidPitch(it, 0.0, dt); it.avoidDir = 0 }
            return
        }
        val platform = DangoClient.platform
        if (platform.player == null) return
        val world = DangoClient.gfx.world
        val cx = world.cameraX
        val cy = world.cameraY
        val cz = world.cameraZ
        val vHalf = platform.fov / 2.0
        val d = distance.value.toDouble()
        val minPitch = max(-75.0, view.pitch - vHalf + 2.0)
        val maxPitch = min(if (aboveGround.get()) maxPitch(d).toDouble() else 65.0, view.pitch + vHalf - 2.0)

        val placed = ArrayList<LyricBox>(8)
        active?.let { act ->
            stepAvoidPitch(act, 0.0, dt)
            act.avoidDir = 0
            boxOf(act, cx, cy, cz, includeAvoid = true, floor = floor)?.let { placed += it }
        }

        val others = ArrayList<Pair<Floating, Boolean>>(upcoming.size + fading.size)
        for (u in upcoming) others += (u to false)
        for (i in fading.indices.reversed()) others += (fading[i] to true)

        for ((f, preferUp) in others) {
            val base = boxOf(f, cx, cy, cz, includeAvoid = false, floor = floor) ?: continue
            val blockers = ArrayList<Pair<Double, Double>>(placed.size)
            for (a in placed) {
                val avgPitch = ((a.top + a.bottom) * 0.5 + (base.top + base.bottom) * 0.5) * 0.5
                val cosPitch = cos(Math.toRadians(avgPitch)).coerceAtLeast(0.2)
                val horizGap = abs(wrapDegrees(a.yaw - base.yaw)) * cosPitch - (a.halfWidth + base.halfWidth)
                if (horizGap < COLLISION_PAD_X) {
                    blockers += (a.top - COLLISION_PAD_Y) to (a.bottom + COLLISION_PAD_Y)
                }
            }
            val (targetAvoid, dir) = solveAvoidPitch(
                base.top, base.bottom, blockers, minPitch, maxPitch, preferUp, f.avoidDir,
            )
            f.avoidDir = dir
            stepAvoidPitch(f, targetAvoid, dt)
            placed += LyricBox(base.yaw, base.halfWidth, base.top + f.avoidPitch, base.bottom + f.avoidPitch)
        }
    }

    private var avoidNanos = 0L

    private fun avoidanceStep(): Double {
        val now = System.nanoTime()
        val dt = if (avoidNanos == 0L) 0.016 else ((now - avoidNanos) / 1e9).coerceIn(0.0, 0.1)
        avoidNanos = now
        return dt
    }

    private fun easeBlockReach(f: Floating, fraction: Double, dt: Double): Double {
        val target = fraction.coerceIn(MIN_REACH, 1.0)
        val cur = f.reach
        val rate = if (target < cur) 18.0 else 3.0
        return (if (cur.isNaN()) target else cur + (target - cur) * (1.0 - exp(-rate * dt))).also { f.reach = it }
    }

    /**
     * Avoid blocks：从镜头往歌词的中心、左右两端、顶部、底部各打一条射线；被方块挡住，
     * 就算出整组歌词要沿视线拉近多少才能停在方块前面（拉近时按比例缩小，屏幕上看起来一样大）。
     */
    private fun avoidBlocks() {
        val dt = avoidanceStep()
        val targets = buildList {
            active?.let(::add)
            addAll(upcoming)
            addAll(fading)
        }
        if (!avoidBlocks.get()) {
            targets.forEach { it.reach = 1.0 }
            return
        }
        if (DangoClient.platform.player == null) return
        val platform = DangoClient.platform
        val world = DangoClient.gfx.world
        val cx = world.cameraX
        val cy = world.cameraY
        val cz = world.cameraZ
        val s = 0.03 * scale.value * (distance.value / 6.5)
        val minD = distance.value * 0.6
        for (f in targets) {
            var rx = f.x - cx
            var ry = f.y - cy
            var rz = f.z - cz
            val len = sqrt(rx * rx + ry * ry + rz * rz)
            if (len < 1e-4) continue
            if (keepsDistance() && len < minD) {
                val k = minD / len
                rx *= k; ry *= k; rz *= k
            }
            if (abs(f.avoidPitch) > 1e-4) {
                val (nx, ny, nz) = rotatePitch(rx, ry, rz, f.avoidPitch, f.yaw)
                rx = nx; ry = ny; rz = nz
            }
            val halfW = rowWidth(f) / 2.0 * s
            val top = halfMain(f) * s
            val bottom = -rowBottom(f) * s
            val mid = (top + bottom) / 2.0
            val h = sqrt(rx * rx + rz * rz)
            val px = if (h > 1e-4) -rz / h else 1.0
            val pz = if (h > 1e-4) rx / h else 0.0
            var t = 1.0
            fun probe(ox: Double, oy: Double, oz: Double) {
                val tx = rx + ox
                val ty = ry + oy
                val tz = rz + oz
                val pl = sqrt(tx * tx + ty * ty + tz * tz)
                if (pl < 1e-4) return
                val hit = platform.clearFraction(cx, cy, cz, cx + tx, cy + ty, cz + tz)
                if (hit < 1.0) t = min(t, (hit * pl - BLOCK_MARGIN) / pl)
            }
            probe(0.0, mid, 0.0)
            probe(px * halfW, top, pz * halfW)
            probe(-px * halfW, top, -pz * halfW)
            probe(px * halfW, bottom, pz * halfW)
            probe(-px * halfW, bottom, -pz * halfW)
            easeBlockReach(f, t, dt)
        }
    }

    private fun rowWidth(f: Floating): Float = max(
        f.lines.maxOfOrNull(::strW) ?: 0f,
        (phoneticRows(f) + translationRows(f)).maxOfOrNull { strW(it) * TRANSLATION_SCALE } ?: 0f,
    )

    private fun pushFloating(f: Floating, floor: Double?, slide: Float, scaleMul: Float = 1f) {
        val world = DangoClient.gfx.world
        var rx = f.x - world.cameraX
        var ry = f.y - world.cameraY
        var rz = f.z - world.cameraZ
        val len = sqrt(rx * rx + ry * ry + rz * rz)
        val minD = distance.value * 0.6
        if (keepsDistance() && len in 1e-4..minD) {
            val k = minD / len
            rx *= k; ry *= k; rz *= k
        }
        if (abs(f.avoidPitch) > 1e-4) {
            val (nx, ny, nz) = rotatePitch(rx, ry, rz, f.avoidPitch, f.yaw)
            rx = nx; ry = ny; rz = nz
        }

        // Pull forward along the same ray and reduce world-space size proportionally.
        val reach = f.reach.takeUnless { it.isNaN() } ?: 1.0
        rx *= reach; ry *= reach; rz *= reach
        val s = 0.03f * scale.value * (distance.value / 6.5f) * reach.toFloat() * scaleMul
        val lift = if (floor == null) 0.0 else
            max(0.0, floor - (world.cameraY + ry) + (rowBottom(f) + slide.coerceAtLeast(0f) + 6f) * s)
        val horizontal = sqrt(rx * rx + rz * rz)
        val yaw = if (facesPlayer() && horizontal > 1e-4) Math.toDegrees(atan2(-rx, rz)).toFloat() else f.yaw
        val pitch = if (facesPlayer() && len > 1e-4) Math.toDegrees(atan2(-(ry + lift), horizontal)).toFloat() else f.pitch
        if (f === active) {
            // Broadcast the anchor before viewer-local block avoidance. 多句时预告刚变成当前句那一下的缩放只是本地的过渡，不广播出去
            sharedPose = LyricsAnchor(world.cameraX + rx / reach, world.cameraY + (ry + lift) / reach,
                world.cameraZ + rz / reach, yaw, pitch, (s / reach / scaleMul).toFloat(), f.lastWiggleFrame ?: -1L)
        }
        world.push(rx, ry + lift, rz, yaw, pitch, s)
    }

    private fun fadeOf(f: Floating, frameNanos: Long): Float =
        ((frameNanos - f.fadeStartNanos).coerceAtLeast(0L).toFloat() / f.fadeNanos).coerceIn(0f, 1f)

    private fun drawWorld(songNow: Long, frameNanos: Long, floor: Double?) {
        val current = active
        val world = DangoClient.gfx.world
        if (lyricLines() <= 1) {
            // 单句：只画当前句；没有当前句时画最后一句淡出的
            val f = current ?: fading.lastOrNull() ?: return
            val fade = if (current != null) 0f else fadeOf(f, frameNanos)
            world.begin()
            // 淡出从开始淡出那一刻的亮度接着变（平常就是 1；刚从多句切回一句时，唱过的淡句不会先闪亮一下）
            drawFloating(f, songNow, frameNanos, floor, if (current != null) appearOf(f) else f.fadeAlpha * (1f - fade), -fade * 4f, 1f, current != null)
            return
        }
        // 多句：先画淡出的，再从最远的后续预告往最近的画，正在唱的最后画，叠在最上面
        if (!anyFloating()) return
        world.begin()
        for (f in fading) {
            val e = smoothstep(fadeOf(f, frameNanos))
            val rise = if (f.fadeNanos == RETIRE_FADE_NANOS) RETIRE_RISE else 4f
            val frozen = if (f.fadeSongMs >= 0L) f.fadeSongMs else songNow
            drawFloating(f, frozen, frameNanos, floor, f.fadeAlpha * (1f - e), f.fadeLift - e * rise,
                f.fadeScale * (1f - 0.04f * e), false, f.fadeGhost * (1f - e))
        }
        for (i in upcoming.indices.reversed()) drawRanked(upcoming[i], songNow, frameNanos, floor)
        current?.let { drawRanked(it, songNow, frameNanos, floor) }
    }

    /** 多句：按这句现在的名次画（预告、当前、唱过的都用这一个，名次在中间过渡时样子也是连续的） */
    private fun drawRanked(f: Floating, songNow: Long, frameNanos: Long, floor: Double?) {
        drawFloating(f, songNow, frameNanos, floor, lineAlpha(f, frameNanos), rankLift(f.rank), rankScale(f.rank), true, ghostOf(f, frameNanos))
    }

    /**
     * 画一句。[live] = 还在动的句子（当前句、预告和唱过还留着的句子）：抖动继续走；淡出中的句子停在最后一帧的抖动上。
     * 唱过的句子用同一套画法：逐字出现、KTV 高亮按播放位置算出来都是唱完的样子，所以整句完整显示。
     * [ghost] = 还没唱到的字画多亮（0 = 不画，单句的逐字冒出）；预告整句都没唱到，所以整句是这么亮
     */
    private fun drawFloating(
        f: Floating, songNow: Long, frameNanos: Long, floor: Double?,
        alpha: Float, slide: Float, scaleMul: Float, live: Boolean, ghost: Float = 0f,
    ) {
        // 当前句照常走完（附近共享的位置在 pushFloating 里更新，恢复播放淡入的第一帧也要更新）
        if (alpha <= 0.01f && ghost <= 0.01f && f !== active) return
        // 发光层：没唱到的字（预告、当前句后半）只发一点光，唱到的字才亮起来
        val dim = if (glowPass) 1f + (GHOST_GLOW - 1f) * (-f.rank).coerceIn(0f, 1f) else 1f
        val shownAlpha = alpha * dim
        val shownGhost = if (glowPass) ghost * GHOST_GLOW else ghost
        val world = DangoClient.gfx.world
        val pen = DangoClient.gfx.text
        if (live) f.lastWiggleFrame = frameNanos / 1_000_000L * wiggleFps.value / 1000L
        val wiggleFrame = f.lastWiggleFrame
        pushFloating(f, floor, slide, scaleMul)
        try {
            pen.push()
            try {
                pen.translate(0f, slide)
                if (f.instrumental) {
                    drawKtvLines(f.lines, 0f, shownAlpha, -halfMain(f), wiggleFrame = wiggleFrame)
                } else if (ktvMode.get()) {
                    // KTV 本来就整句显示、按进度高亮：预告就是还没开始唱的一句，只是淡一点
                    drawKtv(f, songNow, shownAlpha, false, wiggleFrame)
                } else {
                    val elapsed = songNow - f.startMs
                    drawAnimated(f.lines, elapsed, f.revealMs, shownAlpha, if (wordPop.get()) f.charTimes else null, wiggleFrame, shownGhost)
                    drawTranslation(phoneticRows(f) + translationRows(f), elapsed, f.revealMs, shownAlpha,
                        halfMain(f) + ANNOTATION_GAP, TRANSLATION_SCALE, false, wiggleFrame)
                }
            } finally {
                pen.pop()
            }
        } finally {
            world.pop()
        }
    }

}

/** 歌词配色：自定义颜色 / 封面主色 / 封面渐变 / 彩虹 */
private const val COLORS_CUSTOM = 0
private const val COLORS_COVER = 1
private const val COLORS_GRADIENT = 2
private const val COLORS_RAINBOW = 3
/** 一句歌词从头到尾跨过多少色相（1 = 整个色环） */
private const val RAINBOW_SPAN = 0.6f

/** 歌词样式：3D 世界歌词 / 侧边列表 / 两者都显示 */
private const val STYLE_WORLD = 0
private const val STYLE_SIDE = 1
private const val STYLE_BOTH = 2
private const val STYLE_OFF = 3

/** 驾驶舱歌词的配色 */
internal const val SIDE_COVER = 0
internal const val SIDE_CUSTOM = 1
internal const val SIDE_GRADIENT = 2
internal const val SIDE_RAINBOW = 3
internal const val SIDE_AURORA = 4

private const val PREVIEW_RETURN_MS = 3000L
