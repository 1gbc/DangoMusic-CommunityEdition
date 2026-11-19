package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.client.ClientMusic
import me.sorihiko.dangomusicgtnh.i18n.I18n
import me.sorihiko.dangomusicgtnh.lyrics.LyricLine
import me.sorihiko.dangomusicgtnh.module.impls.music.NeteaseMusicModule
import me.sorihiko.dangomusicgtnh.music.CoverCache
import me.sorihiko.dangomusicgtnh.music.MusicPlayer
import me.sorihiko.dangomusicgtnh.music.MusicSource
import me.sorihiko.dangomusicgtnh.music.NeteaseTrack
import me.sorihiko.dangomusicgtnh.music.PlaybackView
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.ShapeStyle
import me.sorihiko.dangomusicgtnh.ui.Theme
import me.sorihiko.dangomusicgtnh.ui.UiStyle
import me.sorihiko.dangomusicgtnh.ui.VectorIcon
import me.sorihiko.dangomusicgtnh.utils.Color
import kotlin.math.abs
import kotlin.math.roundToInt

private const val ANNOTATION_RATIO = 0.74f

/** 网易云唱片与歌词页。保留原来浏览页的状态，也不改动游戏内歌词的设置。 */
internal class LyricsPage(
    private val ui: Ui,
    private val module: NeteaseMusicModule,
    private val comments: SongComments,
    private val backdrop: FlowingCoverBackdrop,
) {
    private enum class Menu { SIZE, MORE }

    private data class Row(val top: Float, val text: List<String>, val phonetic: List<String>, val translation: List<String>, val height: Float) {
        val center get() = top + height / 2f
    }

    private val white = Color(1f, 1f, 1f, 1f)
    private var menu: Menu? = null
    private var track: NeteaseTrack? = null
    private var lines = emptyList<LyricLine>()
    private var rows = emptyList<Row>()
    private var lyricArea = LayoutBounds()
    private var layoutWidth = 0f
    private var layoutSize = 0f
    private var layoutAnnotationSize = 0f

    private var lyricOffsetMs = 0L
    private var offsetTrack: String? = null

    private fun lyricOffset(song: NeteaseTrack): Long = if (offsetTrack == song.key) lyricOffsetMs else 0L

    private fun adjustLyricOffset(song: NeteaseTrack, deltaMs: Long) {
        lyricOffsetMs = (lyricOffset(song) + deltaMs).coerceIn(-30_000L, 30_000L)
        offsetTrack = song.key
    }

    private var showTranslation = false
    private var showRomanization = false
    private var hasTranslation = false
    private var hasRomanization = false
    private var manualPosition: Float? = null
    private var appleFollowAfter = 0L
    private var scrollPosition = 0f
    private var fontSliderOpen = false
    private var fontSliderDragging = false
    private var fontSliderTrack = LayoutBounds()
    private val railHover = List(5) { Spring(420f, 23f, 0f) }

    fun dismissMenu(): Boolean {
        val wasOpen = menu != null || fontSliderOpen
        menu = null
        fontSliderOpen = false
        stopFontSliderDrag()
        return wasOpen
    }

    fun dragFontSlider(y: Int): Boolean {
        if (!fontSliderDragging || fontSliderTrack.height <= 0f) return false
        val setting = module.lyricsPageSize
        val fraction = ((fontSliderTrack.bottom - y) / fontSliderTrack.height).coerceIn(0f, 1f)
        setting.setValue((setting.minRange + fraction * (setting.maxRange - setting.minRange)).roundToInt())
        return true
    }

    fun stopFontSliderDrag() { fontSliderDragging = false }

    private var commentsOpen = false
    private var commentsProgress = 0f
    private val commentsVisible get() = commentsProgress >= 0.5f
    val commentFade get() = abs(commentsProgress * 2f - 1f)
    private var pageArea = LayoutBounds()
    private var shortcutArea = LayoutBounds()
    private var popupArea: LayoutBounds? = null
    private var shortcutOpacity = 0f

    fun openComments() {
        if (!module.songCommentsEnabled.get()) return
        dismissMenu()
        commentsOpen = true
    }

    fun reset() {
        commentsOpen = false
        commentsProgress = 0f
        shortcutOpacity = 0f
        popupArea = null
        dismissMenu()
        manualPosition = null
        appleFollowAfter = 0L
        resumeAppleFollow = false
    }

    fun scroll(wheel: Int, x: Int, y: Int) {
        if (!pageArea.contains(x, y)) return
        if (commentsOpen && module.songCommentsEnabled.get()) {
            comments.scroll(wheel) { commentsOpen = false }
            return
        }
        if (fontSliderDragging) return
        if (inLyricsModule(x, y)) {
            val end = rows.lastOrNull()?.center ?: return
            val position = manualPosition ?: scrollPosition
            manualPosition = (position + if (wheel > 0) -80f else 80f).coerceIn(0f, end)
            if (track?.source == MusicSource.APPLE) {
                appleFollowAfter = System.nanoTime() + APPLE_LYRIC_IDLE_NANOS
            }
        } else if (wheel < 0 && MusicPlayer.current?.source == MusicSource.NETEASE) {
            openComments()
        }
    }

    private fun inLyricsModule(x: Int, y: Int): Boolean =
        lyricArea.contains(x, y) || shortcutArea.contains(x, y) || popupArea?.contains(x, y) == true

    fun draw(area: LayoutBounds, backdrop: LayoutBounds, close: () -> Unit) {
        pageArea = backdrop.copy(height = area.bottom - backdrop.y)
        val song = MusicPlayer.current?.takeIf { it.source == MusicSource.NETEASE }
        this.backdrop.drawLyrics(backdrop, song, Theme.GLASS_LYRICS_EDGE.radius)
        ui.ctx.drawGlass(backdrop, Theme.GLASS_LYRICS_EDGE)
        drawCommentTransition(song, commentArea = { area }) { drawLyricsContent(area, close) }
    }

    private fun drawCommentTransition(song: NeteaseTrack?, appleLayout: Boolean = false,
                                      commentArea: () -> LayoutBounds, drawLyrics: () -> Unit) {
        if (song == null || !module.songCommentsEnabled.get()) {
            commentsOpen = false
            commentsProgress = 0f
        }
        val destination = if (commentsOpen) 1f else 0f
        commentsProgress = ui.approach(commentsProgress, destination, 65f)
        if (abs(commentsProgress - destination) < 0.02f) commentsProgress = destination
        val commentsSong = song?.takeIf { commentsVisible }
        val alpha = commentFade
        val before = ui.hits.size
        ui.shifted(0f, if (commentsSong != null) (1f - alpha) * 16f else (alpha - 1f) * 16f, alpha) {
            if (commentsSong != null) {
                if (appleLayout) ui.ctx.recaptureBackdrop()
                comments.draw(commentArea(), commentsSong, appleLayout) { commentsOpen = false }
            } else drawLyrics()
        }
        if (alpha < 0.99f) while (ui.hits.size > before) ui.hits.removeAt(ui.hits.lastIndex)
    }

    private var resumeAppleFollow = false

    fun resumeAppleLyrics() {
        manualPosition = null
        appleFollowAfter = 0L
        resumeAppleFollow = true
    }

    fun drawApple(frame: AppleLyricsLayout, playback: PlaybackView.Snapshot, close: () -> Unit, toggleLyrics: () -> Unit) {
        if (frame.scale <= 0f) return
        pageArea = frame.viewport
        val song = playback.track?.takeIf { it.source == MusicSource.APPLE }
        backdrop.drawLyrics(frame.viewport, song)
        drawAppleContent(frame, playback, song, close, toggleLyrics, netease = false)
    }

    fun drawNeteaseApple(frame: AppleLyricsLayout, playback: PlaybackView.Snapshot, close: () -> Unit, toggleLyrics: () -> Unit): AppleLyricsLayout {
        if (frame.scale <= 0f) return frame
        val outer = frame.viewport
        val song = playback.track?.takeIf { it.source == MusicSource.NETEASE }
        pageArea = outer
        backdrop.drawLyrics(outer, song)
        if (!frame.showLyrics) dismissMenu()
        val commentControls = frame.commentControls()
        drawCommentTransition(song, appleLayout = true, commentArea = {
            val top = outer.y + 10f
            LayoutBounds(outer.x + 10f, top, (outer.width - 20f).coerceAtLeast(0f),
                (commentControls.y(858f) - top).coerceAtLeast(0f))
        }) {
            drawAppleContent(frame, playback, song, close, toggleLyrics, netease = true)
            if (frame.showLyrics) {
                drawNeteaseAppleRail(frame, song)
                if (manualPosition != null) {
                    val width = minOf(140f, lyricArea.width)
                    val follow = LayoutBounds(lyricArea.x + (lyricArea.width - width) / 2f, lyricArea.bottom + 6f, width, 26f)
                    ui.pill(follow, Theme.CARD_HOVER, I18n.text("Follow playback")) { resumeAppleLyrics() }
                }
            }
        }
        return if (commentsVisible) commentControls else frame
    }

    private fun drawAppleContent(frame: AppleLyricsLayout, playback: PlaybackView.Snapshot, song: NeteaseTrack?,
                                 close: () -> Unit, toggleLyrics: () -> Unit, netease: Boolean) {
        lyricArea = if (netease) frame.lyrics.copy(height = (frame.lyrics.height - 32f).coerceAtLeast(0f)) else frame.lyrics
        shortcutArea = LayoutBounds()
        popupArea = null
        val closeHit = frame.close
        PlayerIcons.draw(ui.ctx, if (netease) PlayerIcons.chevronDown else AppleLyricsIcons.close,
            closeHit.x + closeHit.width / 2f, closeHit.y + closeHit.height / 2f,
            (if (ui.hover(closeHit)) 20f else 18f) * frame.scale, if (netease) white else white.copy(a = 0.72f))
        ui.hits += closeHit to close
        val toggleHit = frame.lyricsToggle
        PlayerIcons.draw(ui.ctx, AppleLyricsIcons.lyrics, toggleHit.x + toggleHit.width / 2f,
            toggleHit.y + toggleHit.height / 2f, (if (ui.hover(toggleHit)) 22f else 19.5f) * frame.scale,
            white.copy(a = if (ui.hover(toggleHit)) 1f else 0.72f))
        ui.hits += toggleHit to toggleLyrics

        if (song?.key != track?.key) {
            manualPosition = null
            appleFollowAfter = 0L
            scrollPosition = 0f
            hasRomanization = false
            hasTranslation = false
        }
        track = song
        // The reference cover turns into the straight edge at about 18 px out of 600.
        // The continuous-corner shader extends the nominal radius by 1.5.
        val cover = song?.let(CoverCache::lyricsArtwork)
        val radius = frame.artworkRadius
        if (cover == null) ui.card(frame.artwork, Theme.TRACK, radius)
        else ui.ctx.drawRoundedImage(frame.artwork, cover, radius)
        if (song == null) SidebarIcons.draw(ui.ctx, "♫", frame.artwork.x + frame.artwork.width / 2f,
            frame.artwork.y + frame.artwork.height / 2f, Theme.TEXT_TERTIARY, 70f * frame.scale)
        val rim = 0.75f * frame.scale
        ui.ctx.drawShape(frame.artwork.expand(-rim, -rim), ShapeStyle(
            radius = (radius - rim).coerceAtLeast(0f), borderWidth = rim,
            borderColor = white.copy(a = 0.20f), clipBounds = frame.artwork, clipRadius = radius
        ))
        if (!frame.showLyrics) return
        val lyricModule = module.lyrics()
        val lyrics = song?.let { lyricModule?.playerLyrics(it) }
        val baseSize = (if (netease) module.lyricsPageSize.value else module.appleLyricsPageSize).toFloat()
        val size = (baseSize + 3f) * frame.scale
        val annotationSize = (baseSize * ANNOTATION_RATIO + 3f) * frame.scale
        val keptScroll = layout(lyrics?.lines.orEmpty(), lyricArea.width, size,
            preserveAppleScroll = true, annotationSize = annotationSize)
        if (rows.isEmpty()) {
            val loading = song != null && lyricModule?.isLoadingLyrics(song) == true
            val title = I18n.text(if (loading) "Loading lyrics..." else "No lyrics available")
            val detail = I18n.text("There aren't any lyrics available for this song.")
            val fontSize = 13f * frame.scale
            val center = lyricArea.x + lyricArea.width / 2f
            val font = Theme.FONT_SEMIBOLD
            ui.text(title, center - ui.width(title, fontSize, font) / 2f, frame.y(630f), fontSize, white, font)
            if (!loading) {
                ui.text(detail, center - ui.width(detail, fontSize) / 2f, frame.y(669f), fontSize, white.copy(a = 0.8f))
            }
            return
        }
        val position = playback.positionMs + (song?.let(::lyricOffset) ?: 0L)
        val active = lyrics?.indexAt(position) ?: -1
        if (appleFollowAfter != 0L && System.nanoTime() - appleFollowAfter >= 0L) {
            manualPosition = null
            appleFollowAfter = 0L
        }
        val target = manualPosition?.coerceAtMost(rows.last().center) ?: (rows.getOrNull(active)?.center ?: 0f)
        if (resumeAppleFollow) scrollPosition = target
        else if (!keptScroll) scrollPosition = ui.approach(scrollPosition, target, 95f)
        resumeAppleFollow = false
        drawLyrics(active, size, null, centered = true, annotationSize = annotationSize)
    }

    private fun drawNeteaseAppleRail(frame: AppleLyricsLayout, song: NeteaseTrack?) {
        val buttonSize = minOf(34f, frame.viewport.height / 9f)
        val step = buttonSize + 6f
        val railX = frame.viewport.right - buttonSize - 14f
        val railY = frame.viewport.y + (frame.viewport.height - (4f * step + buttonSize)) / 2f
        val lastY = railY + 4f * step
        val popupHeight = minOf(154f, frame.viewport.height - 16f)
        val popup = if (fontSliderOpen && popupHeight >= 64f) {
            val y = (lastY + buttonSize - popupHeight).coerceIn(frame.viewport.y + 8f, frame.viewport.bottom - popupHeight - 8f)
            LayoutBounds(railX - 62f, y, 56f, popupHeight)
        } else null
        val top = minOf(lyricArea.y, railY)
        shortcutArea = LayoutBounds(lyricArea.right, top, (railX + buttonSize - lyricArea.right).coerceAtLeast(0f),
            maxOf(lyricArea.bottom, lastY + buttonSize) - top)
        popupArea = popup
        val hovered = fontSliderDragging || inLyricsModule(ui.mx, ui.my)
        shortcutOpacity = ui.approach(shortcutOpacity, if (hovered) 1f else 0f, 75f)
        if (shortcutOpacity < 0.02f) {
            if (!hovered) dismissMenu()
            return
        }
        val firstHit = ui.hits.size
        ui.shifted(0f, 0f, shortcutOpacity) {
            val offset = song?.let { "   " + I18n.format("Lyrics offset · %+.1f s", lyricOffset(it) / 1000.0) }.orEmpty()
            fun button(index: Int, icon: VectorIcon, label: String, enabled: Boolean = true,
                       selected: Boolean = false, action: () -> Unit) {
                val hit = LayoutBounds(railX, railY + step * index, buttonSize, buttonSize)
                val over = hovered && ui.hover(hit)
                val scale = ui.appleHoverScale(railHover[index], over)
                val center = hit.y + hit.height / 2f
                ui.card(hit, white.copy(a = if (selected) 0.17f else 0.06f), hit.height / 2f)
                PlayerIcons.draw(ui.ctx, icon, hit.x + hit.width / 2f, center,
                    minOf(14f, hit.width * 0.45f) * scale, white.copy(a = if (enabled) 0.95f else 0.40f))
                if (over) {
                    val right = if (popup == null) hit.x else popup.x
                    val hint = ui.trim(label, (right - frame.viewport.x - 16f).coerceAtLeast(0f), 12f)
                    ui.text(hint, right - 9f - ui.width(hint, 12f), center - 6f, 12f, white)
                }
                if (enabled) ui.hits += hit to action
            }
            button(0, PlayerIcons.chevronLeft, I18n.text("Show lyrics 0.5 seconds earlier") + offset, song != null) {
                song?.let { adjustLyricOffset(it, 500L) }
            }
            button(1, PlayerIcons.chevronRight, I18n.text("Show lyrics 0.5 seconds later") + offset, song != null) {
                song?.let { adjustLyricOffset(it, -500L) }
            }
            button(2, AppleLyricsIcons.romanization, I18n.text(if (hasRomanization) "Phonetic lyrics" else "No phonetic lyrics"),
                hasRomanization, showRomanization && hasRomanization) { module.lyricsPageRomanization.toggle() }
            button(3, AppleLyricsIcons.translation, I18n.text(if (hasTranslation) "Lyric translation" else "No lyric translation"),
                hasTranslation, showTranslation && hasTranslation) { module.lyricsPageTranslation.toggle() }
            button(4, LyricsIcons.fontSize, I18n.text("Lyrics font size"), selected = fontSliderOpen) {
                if (fontSliderOpen) dismissMenu() else fontSliderOpen = true
            }
            if (popup != null) drawFontSlider(popup)
        }
        if (!hovered) while (ui.hits.size > firstHit) ui.hits.removeAt(ui.hits.lastIndex)
    }

    private fun drawFontSlider(panel: LayoutBounds) {
        val setting = module.lyricsPageSize
        ui.ctx.drawGlass(panel, (if (UiStyle.continuous) PopupLook.CONTINUOUS_GLASS else PopupLook.CLASSIC_GLASS)
            .copy(radius = 8f, bevel = 6f, refraction = 4f))
        val x = panel.x + 16f
        val rail = LayoutBounds(x - 2f, panel.y + 32f, 4f, panel.height - 64f)
        fontSliderTrack = rail
        val knob = rail.bottom - rail.height * setting.normalize()
        ui.card(rail, white.copy(a = 0.26f), 2f)
        if (knob < rail.bottom) ui.card(rail.copy(y = knob, height = rail.bottom - knob), RED, 2f)
        ui.card(LayoutBounds(x - 5f, knob - 5f, 10f, 10f), white, 5f)
        ui.text(setting.value.toString(), panel.x + 29f, knob - 6f, 11f, white, Theme.FONT_SEMIBOLD)

        fun step(y: Float, glyph: VectorIcon, delta: Int) {
            val hit = LayoutBounds(panel.x, y, panel.width, 28f)
            PlayerIcons.draw(ui.ctx, glyph, x, y + 14f, 12f, white)
            ui.hits += hit to { setting.setValue(setting.value + delta) }
        }
        step(panel.y, LyricsIcons.plus, 2)
        step(panel.bottom - 28f, LyricsIcons.minus, -2)
        ui.hits += LayoutBounds(panel.x, panel.y + 28f, panel.width, panel.height - 56f) to {
            fontSliderDragging = true
            dragFontSlider(ui.my)
        }
    }

    private fun drawLyricsContent(area: LayoutBounds, close: () -> Unit) {
        val song = MusicPlayer.current?.takeIf { it.source == MusicSource.NETEASE }
        if (song?.key != track?.key) {
            manualPosition = null
            scrollPosition = 0f
        }
        track = song
        val lyricModule = module.lyrics()
        val lyrics = song?.let { lyricModule?.playerLyrics(it) }
        val loading = song != null && lyricModule?.isLoadingLyrics(song) == true
        val size = module.lyricsPageSize.value.toFloat()

        val collapse = LayoutBounds(area.x + 18f, area.y + 16f, 36f, 36f)
        PlayerIcons.draw(ui.ctx, PlayerIcons.chevronDown, collapse.x + collapse.width / 2f,
            collapse.y + collapse.height / 2f, 20f, white)
        ui.hits += collapse to close
        if (ui.hover(collapse)) drawTooltip(collapse, I18n.text("Collapse lyrics"), left = false)

        val leftWidth = area.width * 0.46f
        val textX = area.x + leftWidth
        val railX = area.right - 66f
        val textWidth = railX - textX - 26f
        val title = song?.name ?: I18n.text("Not playing")
        val titleFont = Theme.FONT_SEMIBOLD
        ui.text(ui.trim(title, textWidth, 32f, titleFont), textX, area.y + 28f, 32f, white, titleFont)
        val info = listOfNotNull(
            song?.artists?.takeIf { it.isNotEmpty() }?.let { I18n.format("Artist: %s", it.joinToString(" / ")) },
            song?.albumName?.takeIf { it.isNotBlank() }?.let { I18n.format("Album: %s", it) },
        ).joinToString("   ·   ")
        ui.text(ui.trim(info, textWidth, 15f), textX, area.y + 74f, 15f, white.copy(a = 0.63f))

        val label = LayoutBounds(textX, area.y + 104f, 78f, 28f)
        ui.card(label, white.copy(a = 0.13f), 14f)
        ui.icon(I18n.text("Lyrics"), label.x + 39f, label.y + 14f, 14f, white)
        if (manualPosition != null) {
            val follow = LayoutBounds(label.right + 10f, label.y, 140f, label.height)
            ui.pill(follow, white.copy(a = 0.16f), I18n.text("Follow playback")) { manualPosition = null }
        }

        drawRecord(area.x + leftWidth / 2f, area.y + area.height * 0.55f, minOf(leftWidth * 0.74f, area.height * 0.65f), song)
        lyricArea = LayoutBounds(textX, label.bottom + 12f, textWidth, area.bottom - label.bottom - 29f)
        layout(lyrics?.lines.orEmpty(), textWidth, size)
        val position = MusicPlayer.positionMs + (song?.let(::lyricOffset) ?: 0L)
        val active = lyrics?.indexAt(position) ?: -1
        val railY = area.y + (area.height - 6 * 40f - 24f) / 2f
        val popup = neteaseLyricsPopup(railX, railY)
        if (rows.isEmpty()) {
            val message = when {
                song == null -> "Play a NetEase song to view lyrics"
                loading -> "Loading lyrics..."
                else -> "No lyrics available"
            }
            ui.text(I18n.text(message), textX, lyricArea.y + lyricArea.height / 2f - 8f, 17f, white.copy(a = 0.66f))
        } else {
            val target = manualPosition?.coerceAtMost(rows.last().center) ?: (rows.getOrNull(active)?.center ?: 0f)
            scrollPosition = ui.approach(scrollPosition, target, 95f)
            drawLyrics(active, size, popup)
        }
        drawNeteaseLyricsControls(railX, railY, song)
    }

    private fun neteaseLyricsPopup(railX: Float, railY: Float) = menu?.let {
        val height = if (it == Menu.SIZE) 82f else 216f
        LayoutBounds(railX - 246f, railY + 260f - height, 232f, height)
    }

    private fun drawNeteaseLyricsControls(railX: Float, railY: Float, song: NeteaseTrack?) {
        val popup = neteaseLyricsPopup(railX, railY)
        shortcutArea = LayoutBounds(lyricArea.right, minOf(lyricArea.y, railY),
            railX + 34f - lyricArea.right, maxOf(lyricArea.bottom, railY + 258f) - minOf(lyricArea.y, railY))
        popupArea = popup
        val hovered = inLyricsModule(ui.mx, ui.my)
        shortcutOpacity = ui.approach(shortcutOpacity, if (hovered) 1f else 0f, 75f)
        if (!hovered && shortcutOpacity < 0.02f) dismissMenu()
        if (shortcutOpacity >= 0.02f) {
            val firstHit = ui.hits.size
            ui.shifted(0f, 0f, shortcutOpacity) {
                drawShortcuts(railX, railY, song)
                if (popup != null) drawMenu(popup)
            }
            if (!hovered) while (ui.hits.size > firstHit) ui.hits.removeAt(ui.hits.lastIndex)
        }
    }

    private fun layout(
        newLines: List<LyricLine>, width: Float, size: Float, preserveAppleScroll: Boolean = false,
        annotationSize: Float = size * ANNOTATION_RATIO,
    ): Boolean {
        val translate = module.lyricsPageTranslation.get()
        val romanize = module.lyricsPageRomanization.get()
        val sameLayout = lines === newLines && layoutWidth == width && layoutSize == size &&
            layoutAnnotationSize == annotationSize
        if (sameLayout && showTranslation == translate && showRomanization == romanize) return false
        val previousRows = rows.takeIf { preserveAppleScroll && sameLayout && it.isNotEmpty() }

        lines = newLines
        layoutWidth = width
        layoutSize = size
        layoutAnnotationSize = annotationSize
        showTranslation = translate
        showRomanization = romanize
        hasTranslation = lines.any { it.translation != null }
        hasRomanization = lines.any { it.romanization != null }
        var top = 0f
        rows = lines.map { line ->
            val original = ui.wrap2(line.text, width, size, Theme.FONT_SEMIBOLD)
            val phonetic = line.annotations(romanize, false).flatMap { ui.wrap2(it, width, annotationSize) }
            val translation = line.annotations(false, translate).flatMap { ui.wrap2(it, width, annotationSize) }
            val height = original.size * size * 1.32f + (phonetic.size + translation.size) * size + 17f
            Row(top, original, phonetic, translation, height).also { top += height }
        }

        if (previousRows == null) return false
        // Keep the same original lyric near the viewport center when annotations reflow.
        fun reanchor(position: Float): Float {
            val index = previousRows.binarySearchBy(position) { it.top }
                .let { if (it < 0) (-it - 2).coerceAtLeast(0) else it }
            val offset = (position - previousRows[index].top).coerceIn(0f, rows[index].height)
            return (rows[index].top + offset).coerceAtMost(rows.last().center)
        }
        scrollPosition = reanchor(scrollPosition)
        manualPosition = manualPosition?.let(::reanchor)
        return true
    }

    private fun drawLyrics(
        active: Int, size: Float, popup: LayoutBounds?, centered: Boolean = false,
        annotationSize: Float = size * ANNOTATION_RATIO,
    ) {
        val a = lyricArea
        val baseline = a.y + a.height / 2f - scrollPosition
        val first = rows.binarySearchBy(a.y - baseline) { it.top + it.height }
            .let { if (it < 0) -it - 1 else it }
        ui.beginClip(a)
        for (i in first until rows.size) {
            val row = rows[i]
            var y = baseline + row.top
            if (y >= a.bottom) break
            val center = baseline + row.center
            val edge = minOf(center - a.y, a.bottom - center).div(48f).coerceIn(0.22f, 1f)
            val color = white.copy(a = (if (i == active) 1f else 0.53f) * edge)
            for (text in row.text) {
                lyricText(text, y, size, color, Theme.FONT_SEMIBOLD, popup, centered)
                y += size * 1.32f
            }
            val annotationColor = color.copy(a = color.a * 0.85f)
            for (text in row.phonetic + row.translation) {
                lyricText(text, y, annotationSize, annotationColor, Theme.FONT_REGULAR, popup, centered)
                y += size
            }
        }
        ui.endClip()
    }

    private fun lyricText(text: String, y: Float, size: Float, color: Color, font: String, popup: LayoutBounds?, centered: Boolean) {
        // 中文走延迟绘制，弹窗背后的文字也需要在提交时截断。
        val visible = if (popup != null && y + size >= popup.y && y <= popup.bottom) {
            ui.trim(text, popup.x - lyricArea.x - 10f, size, font)
        } else text
        val x = if (centered) lyricArea.x + (lyricArea.width - ui.width(visible, size, font)) / 2f else lyricArea.x
        ui.text(visible, x, y, size, color, font)
    }

    private fun drawRecord(cx: Float, cy: Float, diameter: Float, song: NeteaseTrack?) {
        val disc = LayoutBounds(cx - diameter / 2f, cy - diameter / 2f, diameter, diameter)
        ui.card(disc.expand(8f, 8f), white.copy(a = 0.08f), diameter / 2f + 8f)
        ui.card(disc, Color.fromHex("#131318"), diameter / 2f)
        for (i in 0 until 11) {
            val inset = diameter * (0.026f + i * 0.014f)
            val groove = disc.expand(-inset, -inset)
            ui.ctx.drawShape(groove, ShapeStyle(Color.TRANSPARENT, groove.width / 2f, 1f, white.copy(a = 0.06f)))
        }
        val cover = disc.expand(-diameter * 0.165f, -diameter * 0.165f)
        val artwork = song?.let(CoverCache::lyricsArtwork)
        val radius = cover.width / 2f
        if (artwork != null) ui.ctx.drawRoundedImage(cover, artwork, radius)
        else ui.card(cover, Theme.TRACK, radius)
        if (song == null) ui.icon("♫", cx, cy, diameter * 0.19f, white.copy(a = 0.55f))
    }

    private fun drawShortcuts(x: Float, y: Float, song: NeteaseTrack?) {
        val seekable = song != null && MusicPlayer.durationMs > 0 &&
            (MusicPlayer.state == MusicPlayer.State.PLAYING || MusicPlayer.state == MusicPlayer.State.PAUSED)
        fun at(index: Int) = LayoutBounds(x, y + 40f * index + if (index >= 2) 24f else 0f, 34f, 34f)
        button(at(0), LyricsIcons.backFive, I18n.text("Back 5 seconds"), enabled = seekable) { seek(song, -5000L) }
        button(at(1), LyricsIcons.forwardFive, I18n.text("Forward 5 seconds"), enabled = seekable) { seek(song, 5000L) }
        button(at(2), LyricsIcons.phonetic, I18n.text(if (hasRomanization) "Phonetic lyrics" else "No phonetic lyrics"),
            showRomanization && hasRomanization, hasRomanization) { module.lyricsPageRomanization.toggle() }
        button(at(3), LyricsIcons.translation, I18n.text(if (hasTranslation) "Lyric translation" else "No lyric translation"),
            showTranslation && hasTranslation, hasTranslation) { module.lyricsPageTranslation.toggle() }
        button(at(4), LyricsIcons.fontSize, I18n.text("Lyrics font size"), menu == Menu.SIZE) { toggleMenu(Menu.SIZE) }
        button(at(5), AppleLyricsIcons.more, I18n.text("Lyrics settings"), menu == Menu.MORE) { toggleMenu(Menu.MORE) }
    }

    private fun seek(song: NeteaseTrack?, delta: Long) = ClientMusic.control {
        if (song != null && MusicPlayer.current?.key == song.key) MusicPlayer.seek(MusicPlayer.positionMs + delta)
    }

    private fun toggleMenu(next: Menu) {
        menu = if (menu == next) null else next
    }

    private fun drawMenu(b: LayoutBounds) {
        ui.ctx.drawShape(b, ShapeStyle(Color.fromHex("#513332"), 13f, 1f, white.copy(a = 0.18f)))
        val more = menu == Menu.MORE
        ui.text(I18n.text(if (more) "Lyrics settings" else "Lyrics font size"), b.x + 14f, b.y + 12f, 15f, white, Theme.FONT_SEMIBOLD)
        val size = module.lyricsPageSize.value
        val sizeY = b.y + 37f
        ui.text(I18n.format("Font size: %d", size), b.x + 14f, sizeY + 8f, 13f, white)
        button(LayoutBounds(b.right - 84f, sizeY, 28f, 28f), LyricsIcons.minus, null, enabled = size > 14) { module.lyricsPageSize.setValue(size - 2) }
        button(LayoutBounds(b.right - 46f, sizeY, 28f, 28f), LyricsIcons.plus, null, enabled = size < 30) { module.lyricsPageSize.setValue(size + 2) }
        if (more) {
            ui.text(I18n.text("Show phonetic lyrics"), b.x + 14f, b.y + 109f, 13f, white)
            ui.text(I18n.text("Lyric translation"), b.x + 14f, b.y + 78f, 13f, white)
            ui.toggleSwitch(LayoutBounds(b.right - 53f, b.y + 105f, 38f, 19f), showRomanization, b) { module.lyricsPageRomanization.toggle() }
            ui.toggleSwitch(LayoutBounds(b.right - 53f, b.y + 74f, 38f, 19f), showTranslation, b) { module.lyricsPageTranslation.toggle() }
            val song = track
            if (song != null) {
                val offset = lyricOffset(song) / 1000.0
                val label = I18n.format("Lyrics offset · %+.1f s", offset)
                ui.text(label, b.x + 14f, b.y + 143f, 12f, white)
                button(LayoutBounds(b.right - 84f, b.y + 135f, 28f, 28f), LyricsIcons.minus, null, enabled = offset > -30) {
                    adjustLyricOffset(song, -500L)
                }
                button(LayoutBounds(b.right - 46f, b.y + 135f, 28f, 28f), LyricsIcons.plus, null, enabled = offset < 30) {
                    adjustLyricOffset(song, 500L)
                }
            }
            ui.pill(LayoutBounds(b.x + 14f, b.y + 174f, b.width - 28f, 28f), white.copy(a = 0.13f), I18n.text("Reset lyrics settings")) {
                if (song != null) adjustLyricOffset(song, -lyricOffset(song))
                module.lyricsPageSize.setValue(22)
                module.lyricsPageRomanization.set(false)
                module.lyricsPageTranslation.set(true)
            }
        }
        ui.hits += b to {}
    }

    private fun button(b: LayoutBounds, icon: VectorIcon, tooltip: String?, on: Boolean = false, enabled: Boolean = true, action: () -> Unit) {
        val hovered = ui.hover(b)
        val fill = white.copy(a = if (on) 0.21f else if (hovered && enabled) 0.13f else 0.04f)
        ui.ctx.drawShape(b, ShapeStyle(fill, b.height / 2f, 1f, white.copy(a = if (on) 0.74f else 0.20f)))
        PlayerIcons.draw(ui.ctx, icon, b.x + b.width / 2f, b.y + b.height / 2f, 18f,
            white.copy(a = if (enabled) 1f else 0.33f))
        if (enabled) ui.hits += b to action
        if (tooltip != null && hovered && menu == null) drawTooltip(b, tooltip, left = true)
    }

    private fun drawTooltip(anchor: LayoutBounds, text: String, left: Boolean) {
        val width = ui.width(text, 12f) + 18f
        val box = if (left) LayoutBounds(anchor.x - width - 9f, anchor.y - 24f, width, 22f)
            else LayoutBounds(anchor.right + 9f, anchor.y + 7f, width, 22f)
        ui.card(box, Color.fromHex("#513332"), 7f)
        ui.text(text, box.x + 9f, box.y + 5f, 12f, white)
    }
}

private object LyricsIcons {
    private fun seekFive(forward: Boolean) = VectorIcon.path {
        fun mirror(x: Double) = if (forward) 24.0 - x else x
        move(mirror(4.0), 11.0)
        curve(mirror(4.0), 5.7, mirror(7.3), 3.0, mirror(12.0), 3.0)
        curve(mirror(17.0), 3.0, mirror(21.0), 6.6, mirror(21.0), 11.5)
        curve(mirror(21.0), 16.2, mirror(17.4), 20.0, mirror(13.0), 20.5)
        move(mirror(1.0), 7.8); line(mirror(4.0), 11.0); line(mirror(7.7), 8.3)
        move(14.5, 8.1); line(10.3, 8.1); line(9.8, 11.8)
        curve(16.8, 9.8, 16.2, 18.0, 9.5, 15.8)
    }
    val backFive = seekFive(false)
    val forwardFive = seekFive(true)
    val phonetic = VectorIcon.path {
        move(2.5, 12.0); line(5.0, 12.0); line(7.5, 7.0); line(11.0, 17.0)
        line(14.0, 5.5); line(17.0, 15.0); line(19.0, 12.0); line(21.5, 12.0)
    }
    val translation = VectorIcon.path {
        move(2.0, 5.7); line(13.3, 5.7)
        move(7.7, 3.0); line(7.7, 5.7)
        move(11.3, 6.0); curve(10.6, 10.6, 7.5, 14.2, 2.7, 16.2)
        move(4.9, 8.8); curve(6.2, 11.3, 8.1, 13.3, 10.6, 14.6)
        move(12.3, 21.0); line(17.0, 10.9); line(21.7, 21.0)
        move(14.0, 17.4); line(20.0, 17.4)
    }
    val fontSize = VectorIcon.path {
        move(2.0, 5.0); line(14.0, 5.0)
        move(8.0, 5.0); line(8.0, 19.0)
        move(14.0, 12.0); line(22.0, 12.0)
        move(18.0, 9.0); line(18.0, 16.5); curve(18.0, 19.0, 19.5, 19.4, 21.3, 18.8)
    }
    val minus = VectorIcon.path { move(5.0, 12.0); line(19.0, 12.0) }
    val plus = VectorIcon.path {
        move(5.0, 12.0); line(19.0, 12.0)
        move(12.0, 5.0); line(12.0, 19.0)
    }
}

private const val APPLE_LYRIC_IDLE_NANOS = 3_000_000_000L
