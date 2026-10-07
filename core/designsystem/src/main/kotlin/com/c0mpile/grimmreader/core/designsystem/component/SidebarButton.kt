package com.c0mpile.grimmreader.core.designsystem.component

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons

/** Opens the navigation sidebar; null while the sidebar is already on screen (expanded panel). */
val LocalOpenSidebar = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Menu button for top-level screens' app bars; shows nothing while the sidebar is visible. */
@Composable
fun SidebarButton() {
    val open = LocalOpenSidebar.current ?: return
    IconButton(onClick = open) { Icon(LucideIcons.Menu, contentDescription = "Open navigation") }
}
