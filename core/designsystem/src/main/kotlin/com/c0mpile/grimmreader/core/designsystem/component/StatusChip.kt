package com.c0mpile.grimmreader.core.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Small persistent notice ("Offline — changes will sync", "Unencrypted HTTP"). */
@Composable
fun StatusChip(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}
