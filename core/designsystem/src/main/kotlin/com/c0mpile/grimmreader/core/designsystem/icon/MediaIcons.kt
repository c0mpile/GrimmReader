package com.c0mpile.grimmreader.core.designsystem.icon

import androidx.compose.ui.graphics.vector.ImageVector

/** Transport icons from Lucide (ISC licence), written out by hand like [FilledIcons]; not generated. */
@Suppress("MaxLineLength")
object MediaIcons {
    val Play: ImageVector by lazy {
        lucide("play") {
            stroke("M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z")
        }
    }
    val Pause: ImageVector by lazy {
        lucide("pause") {
            stroke("M6 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z")
            stroke("M15 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z")
        }
    }
    val SkipBack: ImageVector by lazy {
        lucide("skip-back") {
            stroke("M17.971 4.285A2 2 0 0 1 21 6v12a2 2 0 0 1-3.029 1.715l-9.997-5.998a2 2 0 0 1-.003-3.432z")
            stroke("M3 20V4")
        }
    }
    val SkipForward: ImageVector by lazy {
        lucide("skip-forward") {
            stroke("M21 4v16")
            stroke("M6.029 4.285A2 2 0 0 0 3 6v12a2 2 0 0 0 3.029 1.715l9.997-5.998a2 2 0 0 0 .003-3.432z")
        }
    }
    val Square: ImageVector by lazy {
        lucide("square") {
            stroke("M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z")
        }
    }
}
