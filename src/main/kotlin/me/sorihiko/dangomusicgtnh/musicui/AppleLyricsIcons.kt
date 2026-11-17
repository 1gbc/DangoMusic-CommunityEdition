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
    private fun VectorIcon.Builder.latinA(x: Double, y: Double, width: Double, height: Double) {
        move(x, y + height); line(x + width / 2, y); line(x + width, y + height)
        move(x + width * 0.22, y + height * 0.63); line(x + width * 0.78, y + height * 0.63)
    }

    val romanization = VectorIcon.path {
        latinA(2.5, 4.3, 8.5, 10.4)
        move(19.6, 9.4); curve(17.2, 6.6, 13.4, 8.4, 13.4, 11.6)
        curve(13.4, 15.1, 17.1, 16.1, 19.6, 13.6)
        move(19.6, 8.4); line(19.6, 14.9)
        move(3.5, 19.0); line(6.6, 19.0)
        curve(8.0, 19.0, 8.3, 16.9, 10.0, 16.9)
        curve(11.7, 16.9, 12.2, 20.9, 13.8, 20.9)
        curve(15.4, 20.9, 15.8, 19.0, 17.4, 19.0); line(20.5, 19.0)
    }

    val translation = VectorIcon.path {
        move(4.3, 2.8); line(11.4, 2.8); curve(13.1, 2.8, 14.3, 4.0, 14.3, 5.5)
        line(14.3, 10.5); curve(14.3, 12.0, 13.1, 13.0, 11.7, 13.0)
        line(7.1, 13.0); line(4.0, 15.4); line(4.0, 13.0)
        curve(2.5, 12.8, 1.8, 11.9, 1.8, 10.6); line(1.8, 5.5)
        curve(1.8, 4.0, 2.9, 2.8, 4.3, 2.8); close()
        move(17.2, 8.9); line(19.7, 8.9)
        curve(21.2, 8.9, 22.3, 10.0, 22.3, 11.5); line(22.3, 17.3)
        curve(22.3, 18.8, 21.3, 19.8, 20.0, 19.8); line(20.0, 22.0)
        line(16.6, 19.8); line(12.5, 19.8); curve(10.9, 19.8, 9.7, 18.8, 9.7, 17.3)
        line(9.7, 16.0)
        latinA(5.4, 5.3, 5.3, 5.3)
        // The second bubble's 文 is a path, so it has the same stroke as the Latin A.
        move(17.8, 11.6); line(18.3, 12.3)
        move(14.6, 13.2); line(19.9, 13.2)
        move(18.8, 13.8); curve(18.2, 15.3, 16.9, 16.4, 14.7, 17.0)
        move(16.1, 14.4); curve(16.8, 15.7, 18.2, 16.5, 20.0, 17.0)
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
