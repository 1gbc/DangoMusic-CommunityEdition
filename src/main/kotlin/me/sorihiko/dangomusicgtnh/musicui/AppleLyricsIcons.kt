package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.ui.VectorIcon

/** Artwork-page glyphs share the player's 24-unit path renderer. */
internal object AppleLyricsIcons {
    val close = VectorIcon.path(strokeWidth = 2.1f) {
        move(5.0, 5.0); line(19.0, 19.0)
        move(19.0, 5.0); line(5.0, 19.0)
    }
    val more = VectorIcon.path(filled = true) {
        for (x in listOf(5.0, 12.0, 19.0)) circle(x, 12.0, 2.1)
    }
    val pause = VectorIcon.path(filled = true) {
        roundRect(5.5, 3.4, 5.0, 17.2, 1.15)
        roundRect(13.5, 3.4, 5.0, 17.2, 1.15)
    }
    val play = VectorIcon.path(filled = true) {
        move(6.0, 4.0)
        curve(6.0, 3.2, 6.8, 3.0, 7.4, 3.4)
        line(20.5, 11.1)
        curve(21.3, 11.6, 21.3, 12.4, 20.5, 12.9)
        line(7.4, 20.6)
        curve(6.8, 21.0, 6.0, 20.8, 6.0, 20.0)
        close()
    }
    val lyrics = VectorIcon.path(strokeWidth = 2.0f) {
        move(5.0, 4.0); line(19.0, 4.0)
        curve(21.0, 4.0, 22.0, 5.2, 22.0, 7.0)
        line(22.0, 15.5); curve(22.0, 17.6, 21.0, 18.5, 19.0, 18.5)
        line(13.0, 18.5); line(8.5, 22.0); line(8.5, 18.5); line(5.0, 18.5)
        curve(3.0, 18.5, 2.0, 17.3, 2.0, 15.5)
        line(2.0, 7.0); curve(2.0, 5.0, 3.0, 4.0, 5.0, 4.0); close()
        move(8.5, 8.5); curve(11.2, 8.2, 11.2, 11.7, 8.8, 13.0)
        move(14.0, 8.5); curve(16.7, 8.2, 16.7, 11.7, 14.3, 13.0)
    }
}
