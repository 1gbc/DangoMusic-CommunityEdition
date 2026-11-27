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

/** 多句：当前句、上一句、上上句……的不透明度（越早唱完越淡；比下一句预告淡，眼睛先往后看） */
private val TRAIL_ALPHA = floatArrayOf(1f, 0.42f, 0.26f, 0.16f)
/** 多句：每往前一句缩小多少 */
private const val TRAIL_SHRINK = 0.07f
/** 多句：每往前一句往上浮多少（字的像素） */
private const val TRAIL_LIFT = 4f
/**
 * 多句：下一句预告的不透明度。正在唱的那句还没唱到的字也是这么淡：
 * 预告变成当前句时字不会先消失再逐字冒出来，而是从淡淡的一行按演唱进度逐字点亮
 */
private const val PREVIEW_ALPHA = 0.55f
/** 多句：下一句预告小一点、低一点（字的像素），变成当前句时往上浮到原位、放大到原大小 */
private const val PREVIEW_SHRINK = 0.06f
private const val PREVIEW_DROP = 3f
/** 多句：下一句最早提前多久出现（毫秒）。一般的句子一换句就出现；长句或间奏只在开始前 4 秒出现 */
private const val PREVIEW_LEAD_MS = 4000L
/** 多句：换句后等多久再出下一句预告，先让旧句子变淡、当前句到位，几件事不挤在同一刻 */
private const val PREVIEW_DELAY_NANOS = 350_000_000L
/** 多句：预告淡入用多久 */
private const val PREVIEW_APPEAR_NANOS = 500_000_000L
/** 多句：下一句预告离开视野这么久，就换到眼前空着的地方重新淡入 */
private const val PREVIEW_REPLACE_NANOS = 300_000_000L
/** 多句：玩家的视线比预告出现时转开超过这么多度，才算"转过头了" */
private const val PREVIEW_TURN_DEGREES = 15.0
/** 多句：发光层里没唱到的字（预告、当前句后半）只发这么一点光，唱到的字才亮起来 */
private const val GHOST_GLOW = 0.35f
/** 多句：换句时变淡、缩小用临界阻尼弹簧（起步和停下都是缓的）；10 大约 0.45 秒到位 */
private const val RANK_SPRING = 10.0
/** 多句：换句时每往前一句晚这么久才开始动，像波浪一样一句接一句挪 */
private const val RANK_STAGGER_NANOS = 70_000_000L
/** 淡出用多久：暂停、拖进度、切歌这类打断要快；多句里唱完退场的句子慢慢淡出 */
private const val FADE_NANOS = 400_000_000L
private const val RETIRE_FADE_NANOS = 900_000_000L
/** 多句：退场的句子一边淡出一边再往上浮多少（字的像素） */
private const val RETIRE_RISE = 6f
/** 多句：间奏或空行超过这么久，唱过的句子也淡出 */
private const val TRAIL_GAP_NANOS = 5_000_000_000L
/** 多句：一帧里播放位置往回退超过这么多、或往前跳超过这么多（毫秒），就当成拖了进度 */
private const val SEEK_BACK_MS = 500L
private const val SEEK_AHEAD_MS = 2500L
/** 多句：新的一句在多少个候选位置里挑最空的 */
private const val PLACEMENT_TRIES = 24
/** 多句：候选位置的上下范围（相对视线，度，向下为正），比单句的 -13..-3 宽，好错开；大多还在视线上方 */
private const val PLACEMENT_PITCH_MIN = -20f
private const val PLACEMENT_PITCH_MAX = 2f
/** 多句：两句之间至少隔开多少度才算"不挤"，再远也不多加分 */
private const val PLACEMENT_ROOM = 6.0

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
    internal val sideFont = TextValue("sys:AniMe Vision - MB_EN")

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

    @ModuleValue("Above ground", "Lyrics never appear below your eyes, so they don't sink into the ground")
    private val aboveGround = BooleanValue(false)

    @ModuleValue("Avoid blocks", "Lyrics move in front of walls, leaves and the ground instead of going through them")
    private val avoidBlocks = BooleanValue(true)

    @ModuleValue("Spread", "How far left or right each line can appear (degrees)")
    private val spread = FloatValue(0f, 60f, 30f, 0)

    /**
     * 3D 歌词同时漂着几句：正在唱的一句，加上刚唱过的几句。每句留在自己出现的地方，慢慢变淡、变小、往上浮，
     * 新的一句挑空着的地方出现。只显示唱过的：后面的词不提前露出来，逐字出现和 KTV 高亮只在正在唱的那句上，
     * 一眼就能看出唱到哪里。1 = 只有当前句（原来的样子）
     */
    @ModuleValue("Simultaneous lyric lines", "The current line plus the next line ahead; from 3 lines, the lines just sung stay too. Each floats on its own")
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

    @ModuleValue("Bounce amount", "How much creatures squash and stretch")
    private val danceAmount = FloatValue(0.2f, 2f, 1.3f, 1)

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

    /** 多句：刚唱过的几句（最新的在前），各自留在原来的位置，按 [Floating.rank] 变淡变小 */
    private val trail = mutableListOf<Floating>()
    /**
     * 多句：下一句预告。提前出现在眼前空着的地方，淡淡的一行；唱到这句时它直接变成当前句（[promote]），
     * 不换位置、不重新出现，只是变亮、放大，字按演唱进度逐个点亮
     */
    private var upcoming: Floating? = null
    /** 多句：当前句最近一次换掉的时刻；下一句预告等一会儿再出，免得几件事挤在同一刻 */
    private var changedNanos = 0L
    /** 下一句预告从什么时候起不在视野里（0 = 在） */
    private var upcomingAwaySince = 0L
    /** 多句：从什么时候起没有正在唱的句子（间奏、空行）；0 = 现在有 */
    private var gapSinceNanos = 0L
    private var trailNanos = 0L

    /** 同时漂着几句。文闪模式自己排版，只用一句 */
    private fun lyricLines(): Int = if (textFlash.get() && !ktvMode.get()) 1 else worldLyricLines.value

    /** 多句：除了当前句，还留几句唱过的。2 句 = 当前 + 下一句；3 句 = 上一句 + 当前 + 下一句；4 句再多留一句 */
    private fun pastLines(): Int = (lyricLines() - 2).coerceAtLeast(0)

    /** 多句：要不要提前显示下一句 */
    private fun previewsNext(): Boolean = lyricLines() >= 2

    /** [r] < 0 是下一句预告（-1 = 完全是预告的样子），0 是当前句，往后是唱过的 */
    private fun rankAlpha(r: Float): Float {
        if (r < 0f) return 1f + (PREVIEW_ALPHA - 1f) * (-r).coerceAtMost(1f)
        val c = r.coerceIn(0f, TRAIL_ALPHA.size - 1f)
        val i = c.toInt().coerceAtMost(TRAIL_ALPHA.size - 2)
        return TRAIL_ALPHA[i] + (TRAIL_ALPHA[i + 1] - TRAIL_ALPHA[i]) * (c - i)
    }

    private fun rankScale(r: Float): Float =
        if (r < 0f) 1f - PREVIEW_SHRINK * (-r).coerceAtMost(1f) else 1f - TRAIL_SHRINK * r.coerceAtMost(3f)
    private fun rankLift(r: Float): Float =
        if (r < 0f) PREVIEW_DROP * (-r).coerceAtMost(1f) else -TRAIL_LIFT * r.coerceAtMost(3f)

    private fun smoothstep(t: Float): Float = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

    /** 下一句预告淡入的进度（不是预告出来的句子一直是 1） */
    private fun previewAppear(f: Floating, now: Long = System.nanoTime()): Float =
        if (f.previewSinceNanos == 0L) 1f else smoothstep((now - f.previewSinceNanos).toFloat() / PREVIEW_APPEAR_NANOS)

    /** 一句整体的不透明度（唱到的字就是这么亮） */
    private fun lineAlpha(f: Floating, now: Long = System.nanoTime()): Float =
        rankAlpha(f.rank) * appearOf(f) * previewAppear(f, now)

    /**
     * 还没唱到的字有多亮：多句时预告和当前句没唱到的字淡淡地显示出来（单句照旧不显示，逐字冒出来）。
     * 当前句退成"上一句"时跟着降到 0：唱过的句子都是完整的，不需要它
     */
    private fun ghostOf(f: Floating, now: Long = System.nanoTime()): Float {
        if (!previewsNext() || f.instrumental) return 0f
        return PREVIEW_ALPHA * appearOf(f) * previewAppear(f, now) * (1f - f.rank.coerceIn(0f, 1f))
    }

    /** 唱完的这句：多句时留下来当"上一句"，挤出去的最老一句慢慢淡出；单句（或只留预告的两句）时直接淡出 */
    private fun retire(f: Floating) {
        val keep = pastLines()
        val multi = lyricLines() > 1
        if (keep <= 0 || f.instrumental) {
            startFade(f, slow = multi && !f.instrumental)
            return
        }
        val now = System.nanoTime()
        trail.add(0, f)
        while (trail.size > keep) startFade(trail.removeAt(trail.size - 1), slow = true, delayNanos = keep * RANK_STAGGER_NANOS)
        trail.forEachIndexed { i, t ->
            t.targetRank = i + 1
            t.rankDelayNanos = now + i * RANK_STAGGER_NANOS
        }
    }

    /** 唱到预告的这句：它直接变成当前句，原地变亮、放大 */
    private fun promote(f: Floating) {
        upcoming = null
        upcomingAwaySince = 0L
        f.targetRank = 0
        f.rankDelayNanos = 0L
        active = f
    }

    private fun dropUpcoming() {
        upcoming?.let { startFade(it) }
        upcoming = null
        upcomingAwaySince = 0L
    }

    private fun clearTrail(slow: Boolean = false) {
        trail.forEach { startFade(it, slow = slow) }
        trail.clear()
        gapSinceNanos = 0L
    }

    /**
     * 多句：下一句预告是哪一句——正在唱的这句（没有的话是最近开始的一句）后面第一句有字的；-1 = 没有。
     * KTV 两句之间会提前切到下一句（[Lyrics.ktvIndexAt]），所以取两者里靠后的
     */
    private fun upcomingIndex(lyrics: Lyrics, idx: Int, pos: Long): Int {
        val lines = lyrics.lines
        var j = max(idx, lyrics.indexAt(pos)) + 1
        while (j < lines.size && lines[j].text.isBlank()) j++
        return if (j < lines.size) j else -1
    }

    /** 每帧一次：该预告的句子变了就换掉；到了时间（离开始不到 [PREVIEW_LEAD_MS]、换句后过了一会儿）就让它出现 */
    /**
     * 是不是同一句：按时间和文字比（不比对象），歌词重新加载、补上翻译换了一批新对象时预告照样认得出来。
     * 拆句开关变了文字不一样，认不出来正好
     */
    private fun sameLine(a: LyricLine?, b: LyricLine?): Boolean =
        a != null && b != null && a.timeMs == b.timeMs && a.text == b.text

    private fun updateUpcoming(lyrics: Lyrics, idx: Int, pos: Long, now: Long) {
        val j = upcomingIndex(lyrics, idx, pos)
        val line = lyrics.lines.getOrNull(j)
        if (upcoming != null && !sameLine(upcoming?.lyric, line)) dropUpcoming()
        if (upcoming != null || line == null) return
        if (line.timeMs - pos > PREVIEW_LEAD_MS || now - changedNanos < PREVIEW_DELAY_NANOS) return
        upcoming = spawn(line, lyrics.lines.getOrNull(j + 1)?.timeMs ?: Long.MAX_VALUE, preview = true)
    }

    /**
     * 下一句预告不在视野里（转头了）超过一会儿：它还没开始唱，没必要守在原地，换到眼前空着的地方重新淡入。
     * 当前句和唱过的句子不这么做：它们照旧固定在世界里（当前句由 Stay on screen 拉回）
     */
    private fun keepUpcomingInView(now: Long) {
        val f = upcoming
        val platform = DangoClient.platform
        val player = platform.player
        if (f == null || player == null) {
            upcomingAwaySince = 0L
            return
        }
        // 和 spawn 一样按玩家自己的视线算（不是镜头）：第三人称正面视角时也不会刚换完位置又判成看不见，来回闪
        val rx = f.x - player.eyeX
        val ry = f.y - player.eyeY
        val rz = f.z - player.eyeZ
        val len = sqrt(rx * rx + ry * ry + rz * rz)
        if (len < 1e-4) return
        val vHalf = platform.fov / 2.0
        val aspect = platform.windowWidth.toDouble() / platform.windowHeight.coerceAtLeast(1)
        val hHalf = Math.toDegrees(atan(tan(Math.toRadians(vHalf)) * aspect))
        val dYaw = abs(wrapDegrees(Math.toDegrees(atan2(-rx, rz)) - player.yaw))
        val dPitch = abs(Math.toDegrees(asin((-ry / len).coerceIn(-1.0, 1.0))) - player.pitch)
        // 没转头就看不见（视野很窄、低头时被"离地"抬上去）：换个地方也一样看不见，不重来
        val turned = f.placedYaw.isNaN() ||
            abs(wrapDegrees((player.yaw - f.placedYaw).toDouble())) > PREVIEW_TURN_DEGREES ||
            abs(player.pitch - f.placedPitch) > PREVIEW_TURN_DEGREES
        if ((dYaw <= hHalf && dPitch <= vHalf) || !turned) {
            upcomingAwaySince = 0L
            return
        }
        if (upcomingAwaySince == 0L) {
            upcomingAwaySince = now
            return
        }
        if (now - upcomingAwaySince < PREVIEW_REPLACE_NANOS) return
        dropUpcoming()
        upcoming = spawn(f.lyric, f.nextMs, preview = true)
    }

    /**
     * 每帧一次（发光和正常两遍绘制之前）：句数设置变小时挤掉多的，间奏太久清掉，
     * 每句的名次用临界阻尼弹簧朝目标走（起步、停下都是缓的，按 [Floating.rankDelayNanos] 一句接一句开始）
     */
    private fun updateLines(now: Long) {
        val dt = if (trailNanos == 0L) 0.016 else ((now - trailNanos) / 1e9).coerceIn(0.0, 0.1)
        trailNanos = now
        val keep = pastLines()
        while (trail.size > keep) startFade(trail.removeAt(trail.size - 1), slow = true)
        if (active == null && trail.isNotEmpty()) {
            if (gapSinceNanos == 0L) gapSinceNanos = now
            else if (now - gapSinceNanos > TRAIL_GAP_NANOS) clearTrail(slow = true)
        } else {
            gapSinceNanos = 0L
        }
        trail.forEach { stepRank(it, now, dt) }
        active?.let { stepRank(it, now, dt) }
        upcoming?.let { stepRank(it, now, dt) }
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
        upcoming = null
        upcomingAwaySince = 0L
        sharedPose = null
        fading.clear()
        trail.clear()
        gapSinceNanos = 0L
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
                clearTrail()
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
            clearTrail()
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
        Dance.amount = danceAmount.value
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
            val lyricIndex = if (ktv) lyrics.ktvIndexAt(pos) else lyrics.indexAt(pos)
            val section = if (ktv && ktvInstrumental.get() && lyricIndex < 0) lyrics.instrumentalAt(pos.coerceAtLeast(0L), playbackDurationMs()) else null
            val idx = if (section != null) -2 - section.ordinal else lyricIndex
            val sectionLabel = section?.displayLabel()
            // 播放位置一帧里往回退、或者往前跳了一大截：拖了进度条，跳过去 / 退回去的句子都不算唱过
            val jumped = lastLyricPos >= 0 && (pos < lastLyricPos - SEEK_BACK_MS || pos > lastLyricPos + SEEK_AHEAD_MS)
            lastLyricPos = pos
            if (paused) {
                // 暂停：当前这句（多句时连同唱过的几句和下一句预告）淡出消失，恢复播放时先重新出现当前句
                active?.let { startFade(it) }
                active = null
                clearTrail()
                lastIndex = idx
                wasPaused = true
            } else if (idx != lastIndex || wasPaused || (sectionLabel != null && sectionLabel != active?.lyric?.text)) {
                val resumed = wasPaused && idx == lastIndex
                // 顺着往下唱（不是拖进度、切换拆句、从暂停恢复、进间奏）：唱完的这句留下来当"上一句"。
                // KTV 两句之间空得久时下标先变成 -1（breath），这也是顺着唱，唱完的句子照样留着等下一句
                val forward = !wasPaused && !jumped && idx >= 0 && lastLyricIndex >= 0 &&
                    idx > lastLyricIndex && idx - lastLyricIndex <= 2
                val breath = !wasPaused && !jumped && idx == -1 && lastIndex >= 0
                // 没有被打断（暂停、拖进度）的换句，比如唱着唱着进了间奏：多句时句子照样慢慢飘走，不是一下子收掉
                val calm = !wasPaused && !jumped && lyricLines() > 1
                wasPaused = false
                lastIndex = idx
                if (idx >= 0) lastLyricIndex = idx
                if (forward || breath) {
                    active?.let(::retire)
                } else {
                    active?.let { startFade(it, slow = calm && !it.instrumental) }
                    clearTrail(slow = calm)
                }
                active = null
                changedNanos = frameNanos
                val ahead = upcoming
                if (sectionLabel != null) {
                    active = spawn(LyricLine(pos, sectionLabel), pos + 3500L).also { it.instrumental = true }
                } else if (idx >= 0 && ahead != null && sameLine(ahead.lyric, lyrics.lines[idx])) {
                    // 唱到了预告的这句：原地变成当前句（拖进度刚好拖到这句也一样，免得同一句出现两次）
                    promote(ahead)
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
            if (view.yaw.isFinite() && view.pitch.isFinite()) keepOnScreen(view)
            keepUpcomingInView(frameNanos)
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

    private fun anyFloating(): Boolean = active != null || upcoming != null || trail.isNotEmpty() || fading.isNotEmpty()

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

    /** [preview] = 作为下一句预告提前出现：躲开当前句和唱过的句子，倾向和当前句分在左右两边 */
    private fun spawn(line: LyricLine, nextMs: Long, preview: Boolean = false): Floating {
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
            f.rank = -1f
            f.targetRank = -1
            f.previewSinceNanos = System.nanoTime()
        }
        val player = DangoClient.platform.player ?: return f
        f.placedYaw = player.yaw
        f.placedPitch = player.pitch
        val d = distance.value.toDouble()
        val ex = player.eyeX
        val ey = player.eyeY
        val ez = player.eyeZ
        // 第一个是"上一句"（左右错开时和它分在两边）：预告的上一句是当前句，当前句的上一句是刚唱完的
        // 慢慢飘走的句子还要再显示将近一秒，也躲开
        val drifting = fading.filter { it.fadeNanos == RETIRE_FADE_NANOS }
        val others = when {
            lyricLines() <= 1 -> emptyList()
            preview -> listOfNotNull(active) + trail + drifting
            else -> trail + listOfNotNull(upcoming) + drifting
        }
        var yaw: Float
        var pitch: Float
        if (others.isEmpty()) {
            yaw = player.yaw + (random.nextFloat() * 2f - 1f) * spread.value
            pitch = (player.pitch - 3f - random.nextFloat() * 10f).coerceIn(-60f, 30f)
            if (aboveGround.get()) pitch = min(pitch, maxPitch(d))
        } else {
            val spot = freeSpot(f, others, player.yaw, player.pitch, ex, ey, ez, d)
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

    /** [o] 现在在哪、多大（按它变淡变小后的样子；Keep away 推远时按推远后的距离） */
    private fun boxOf(o: Floating, ex: Double, ey: Double, ez: Double): LyricBox? {
        val rx = o.x - ex
        val ry = o.y - ey
        val rz = o.z - ez
        var len = sqrt(rx * rx + ry * ry + rz * rz)
        if (len < 1e-4) return null
        val centerPitch = Math.toDegrees(asin((-ry / len).coerceIn(-1.0, 1.0)))
        if (keepAway.get()) len = max(len, distance.value * 0.6)
        val s = 0.03 * scale.value * (distance.value / 6.5) * rankScale(o.targetRank.toFloat())
        val lift = rankLift(o.targetRank.toFloat())
        return LyricBox(
            Math.toDegrees(atan2(-rx, rz)),
            Math.toDegrees(atan2(rowWidth(o) * s / 2.0, len)),
            centerPitch + Math.toDegrees(atan2((-halfMain(o) + lift) * s, len)),
            centerPitch + Math.toDegrees(atan2((rowBottom(o) + lift) * s, len)),
        )
    }

    /** 两句之间隔开多少度：左右或上下错开一个就行，取大的；负数 = 叠在一起 */
    private fun separation(a: LyricBox, b: LyricBox): Double {
        val gx = abs(wrapDegrees(a.yaw - b.yaw)) - (a.halfWidth + b.halfWidth)
        val gy = if (a.top < b.top) b.top - a.bottom else a.top - b.bottom
        return max(gx, gy)
    }

    /**
     * 多句：新的一句在视线附近挑一个空着的地方，不压在还漂着的句子上。
     * 在"分布范围"和比单句宽一点的上下范围里试几个候选位置：离别的句子越远越好（够远就不再加分），
     * 其次离视线中间近，和上一句分在左右两边的略占优势，最后加一点随机，免得每次都落在同一处。
     * 句子很长时左右放不下，自然会上下错开。
     */
    private fun freeSpot(
        f: Floating, others: List<Floating>, viewYaw: Float, viewPitch: Float,
        ex: Double, ey: Double, ez: Double, d: Double,
    ): Pair<Float, Float> {
        val boxes = others.mapNotNull { boxOf(it, ex, ey, ez) }
        val s = 0.03 * scale.value * (distance.value / 6.5)
        val halfWidth = Math.toDegrees(atan2(rowWidth(f) * s / 2.0, d))
        val up = Math.toDegrees(atan2(-halfMain(f) * s, d))
        val down = Math.toDegrees(atan2(rowBottom(f) * s, d))
        val lastSide = boxes.firstOrNull()?.let { Math.signum(wrapDegrees(it.yaw - viewYaw)) } ?: 0.0
        val preferredPitch = viewPitch - 8f
        // 左右不超出屏幕：分布范围调得很大时，新的一句（尤其是要提前看的下一句）也落在看得见的地方
        val platform = DangoClient.platform
        val vHalf = platform.fov / 2.0
        val aspect = platform.windowWidth.toDouble() / platform.windowHeight.coerceAtLeast(1)
        val hHalf = Math.toDegrees(atan(tan(Math.toRadians(vHalf)) * aspect))
        val yawRange = min(spread.value.toDouble(), max(0.0, hHalf - halfWidth - 2.0)).toFloat()
        // 上下也不超出屏幕（视野调得很窄、或者开了缩放时）
        val pitchRange = max(0.0, vHalf - max(-up, down) - 2.0).toFloat()
        var bestYaw = viewYaw
        var bestPitch = preferredPitch
        var bestScore = Double.NEGATIVE_INFINITY
        repeat(PLACEMENT_TRIES) {
            val offset = (random.nextFloat() * 2f - 1f) * yawRange
            var pitch = (viewPitch + PLACEMENT_PITCH_MIN + random.nextFloat() * (PLACEMENT_PITCH_MAX - PLACEMENT_PITCH_MIN))
                .coerceIn(viewPitch - pitchRange, viewPitch + pitchRange).coerceIn(-60f, 30f)
            if (aboveGround.get()) pitch = min(pitch, maxPitch(d))
            val yaw = viewYaw + offset
            val box = LyricBox(yaw.toDouble(), halfWidth, pitch + up, pitch + down)
            val room = boxes.minOfOrNull { separation(box, it) } ?: PLACEMENT_ROOM
            var score = min(room, PLACEMENT_ROOM) * 1.0
            score -= 0.03 * abs(offset) + 0.05 * abs(pitch - preferredPitch)
            if (lastSide != 0.0 && Math.signum(offset.toDouble()) == -lastSide) score += 0.5
            score += random.nextDouble() * 0.3
            if (score > bestScore) {
                bestScore = score
                bestYaw = yaw
                bestPitch = pitch
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
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            out = out or ((a + (b - a) * t).roundToInt().coerceIn(0, 255) shl shift)
        }
        return out
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
     * "Stay on screen"：歌词（连同字的宽度）超出视野时，沿着离视线最近的方向滑回屏幕边上。
     * 用视线方向的偏角（左右 / 上下）判断，视野大小按设置里的 FOV 和窗口宽高比算；离镜头的距离不变。
     * 每帧在画之前做一次，发光层和字本身用的是同一个位置。
     *
     * 往回拉用的是"临界阻尼弹簧"：有速度、会慢慢加速和减速，不会一碰到边就突然开始动、一回来就突然停，
     * 离地高度在绘制时根据插值后的玩家眼睛高度单独计算。
     */
    private fun keepOnScreen(view: ViewAngles) {
        val now = System.nanoTime()
        val dt = if (keepNanos == 0L) 0.016 else ((now - keepNanos) / 1e9).coerceIn(0.0, 0.1)
        keepNanos = now
        if (!stayOnScreen.get()) {
            lastCamX = Double.NaN
            return
        }
        val platform = DangoClient.platform
        if (platform.player == null) return
        val world = DangoClient.gfx.world

        // 移动得快（鞘翅、骑马、矿车）时歌词跟着一起走：只靠转角度的弹簧追不上，会被甩出屏幕。
        // 走路和疾跑（每秒 8 格以内）照旧固定在世界里；每秒 8 到 14 格之间逐渐变成完全跟着走。
        val camX = world.cameraX
        val camY = world.cameraY
        val camZ = world.cameraZ
        var carryX = 0.0
        var carryY = 0.0
        var carryZ = 0.0
        if (!lastCamX.isNaN() && dt > 0.0) {
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
        lastCamX = camX
        lastCamY = camY
        lastCamZ = camZ
        val maxDist = distance.value * 2.0

        val vHalf = platform.fov / 2.0
        val aspect = platform.windowWidth.toDouble() / platform.windowHeight.coerceAtLeast(1)
        val hHalf = Math.toDegrees(atan(tan(Math.toRadians(vHalf)) * aspect))
        val s = 0.03 * scale.value * (distance.value / 6.5)

        /**
         * [spring] = false：只跟着快速移动走、不离太远，不往屏幕里拉。多句时唱过的句子这样处理：
         * 它们留在世界里原来的位置，转头时可以留在身后；要是也往屏幕边上拉，几句会挤在边上叠成一团
         */
        fun follow(f: Floating, spring: Boolean) {
            f.x += carryX
            f.y += carryY
            f.z += carryZ
            var rx = f.x - world.cameraX
            var ry = f.y - world.cameraY
            var rz = f.z - world.cameraZ
            var len = sqrt(rx * rx + ry * ry + rz * rz)
            if (len < 1e-4) return
            // 离得太远（被甩下、传送）：沿原方向拉回设定距离的两倍以内
            if (len > maxDist) {
                val k = maxDist / len
                rx *= k; ry *= k; rz *= k
                len = maxDist
                f.x = world.cameraX + rx
                f.y = world.cameraY + ry
                f.z = world.cameraZ + rz
            }
            if (!spring) return

            // 字本身占的角度：留出一半宽 / 高，再多留 2 度边距
            // 翻译在原文下面多占一截：按整体高度算（偏保守，上下都留出来）
            val halfW = Math.toDegrees(atan2(rowWidth(f) * s / 2.0, len))
            val halfH = Math.toDegrees(atan2(rowBottom(f) * s, len))
            val hLim = max(0.0, hHalf - halfW - 2.0)
            val vLim = max(0.0, vHalf - halfH - 2.0)

            val curYaw = Math.toDegrees(atan2(-rx, rz))
            val curPitch = Math.toDegrees(asin((-ry / len).coerceIn(-1.0, 1.0)))
            val dYaw = wrapDegrees(curYaw - view.yaw)
            val dPitch = curPitch - view.pitch

            val tYaw = dYaw.coerceIn(-hLim, hLim)
            val tPitch = dPitch.coerceIn(-vLim, vLim)

            // 偏出去多少（在边界以内就是 0）
            val eYaw = dYaw - tYaw
            val ePitch = dPitch - tPitch
            if (eYaw == 0.0 && ePitch == 0.0 && abs(f.vYaw) < 0.05 && abs(f.vPitch) < 0.05) {
                f.vYaw = 0.0
                f.vPitch = 0.0
                return
            }

            // 临界阻尼弹簧，分成小步算（帧率低时也稳定）
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
            f.x = world.cameraX - sin(yr) * cos(pr) * len
            f.y = world.cameraY - sin(pr) * len
            f.z = world.cameraZ + cos(yr) * cos(pr) * len
            // 不朝向镜头时，字的朝向跟着一起转，免得斜着看
            f.yaw = (f.yaw + wrapDegrees(newYaw - curYaw)).toFloat()
            f.pitch = (f.pitch + (newPitch - curPitch)).toFloat().coerceIn(-90f, 90f)
        }

        val single = lyricLines() <= 1
        active?.let { follow(it, true) }
        // 预告不往屏幕里拉（会和当前句挤在屏幕边上），离开视野久了由 keepUpcomingInView 换个地方重新出现
        upcoming?.let { follow(it, false) }
        trail.forEach { follow(it, false) }
        fading.forEach { follow(it, single) }
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
     * 往近处拉得快，往回退得慢，免得在方块边上来回跳
     */
    private fun avoidBlocks() {
        val dt = avoidanceStep()
        val targets = buildList {
            active?.let(::add)
            upcoming?.let(::add)
            addAll(trail)
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
            // 和 drawWorld 里一样先做 Keep away，射线打到真正要画的位置
            if (keepAway.get() && len < minD) {
                val k = minD / len
                rx *= k; ry *= k; rz *= k
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
            // 中心 + 四个角：歌词很宽，只探中线会漏掉两头插进树叶 / 墙角的情况
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
        if (keepAway.get() && len in 1e-4..minD) {
            val k = minD / len
            rx *= k; ry *= k; rz *= k
        }

        // Pull forward along the same ray and reduce world-space size proportionally.
        val reach = f.reach.takeUnless { it.isNaN() } ?: 1.0
        rx *= reach; ry *= reach; rz *= reach
        val s = 0.03f * scale.value * (distance.value / 6.5f) * reach.toFloat() * scaleMul
        val lift = if (floor == null) 0.0 else
            max(0.0, floor - (world.cameraY + ry) + (rowBottom(f) + slide.coerceAtLeast(0f) + 6f) * s)
        val horizontal = sqrt(rx * rx + rz * rz)
        val yaw = if (facePlayer.get() && horizontal > 1e-4) Math.toDegrees(atan2(-rx, rz)).toFloat() else f.yaw
        val pitch = if (facePlayer.get() && len > 1e-4) Math.toDegrees(atan2(-(ry + lift), horizontal)).toFloat() else f.pitch
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
        // 多句：先画淡出的，再从最早唱完的往新的画，然后是下一句预告，正在唱的最后画，叠在最上面
        if (!anyFloating()) return
        world.begin()
        for (f in fading) {
            // 先缓后快再缓地淡掉，同时继续往上浮、略微缩小：退场的句子是飘走的，不是一下子没了
            val e = smoothstep(fadeOf(f, frameNanos))
            val rise = if (f.fadeNanos == RETIRE_FADE_NANOS) RETIRE_RISE else 4f
            val frozen = if (f.fadeSongMs >= 0L) f.fadeSongMs else songNow
            drawFloating(f, frozen, frameNanos, floor, f.fadeAlpha * (1f - e), f.fadeLift - e * rise,
                f.fadeScale * (1f - 0.04f * e), false, f.fadeGhost * (1f - e))
        }
        for (i in trail.indices.reversed()) drawRanked(trail[i], songNow, frameNanos, floor)
        upcoming?.let { drawRanked(it, songNow, frameNanos, floor) }
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
