@file:OptIn(ExperimentalMaterial3Api::class)

package com.c0mpile.grimmreader.core.designsystem.theme

import android.provider.Settings
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.c0mpile.grimmreader.core.designsystem.R
import com.c0mpile.grimmreader.core.model.Appearance
import com.c0mpile.grimmreader.core.model.ThemeMode

private fun inter(weight: Int) =
    Font(
        R.font.inter,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

val InterFamily = FontFamily(inter(400), inter(500), inter(550), inter(600), inter(700))

private fun grimmTypography(): Typography {
    val base = Typography()

    fun TextStyle.inter() = copy(fontFamily = InterFamily)
    return Typography(
        displayLarge = base.displayLarge.inter(),
        displayMedium = base.displayMedium.inter(),
        displaySmall = base.displaySmall.inter(),
        headlineLarge = base.headlineLarge.inter(),
        headlineMedium = base.headlineMedium.inter(),
        headlineSmall = base.headlineSmall.inter(),
        titleLarge = base.titleLarge.inter(),
        titleMedium = base.titleMedium.inter(),
        titleSmall = base.titleSmall.inter(),
        bodyLarge = base.bodyLarge.inter(),
        bodyMedium = base.bodyMedium.inter(),
        bodySmall = base.bodySmall.inter(),
        labelLarge = base.labelLarge.inter(),
        labelMedium = base.labelMedium.inter(),
        labelSmall = base.labelSmall.inter(),
    )
}

/** Card title / author styles of the web book card (13/17 w550, 12/15). */
object GrimmTextStyles {
    val CardTitle = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight(550))
    val CardAuthor = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, lineHeight = 15.sp)
}

private val GrimmShapes =
    Shapes(
        small =
            androidx.compose.foundation.shape
                .RoundedCornerShape(4.dp),
        medium =
            androidx.compose.foundation.shape
                .RoundedCornerShape(8.dp),
        large =
            androidx.compose.foundation.shape
                .RoundedCornerShape(12.dp),
    )

@Composable
fun GrimmTheme(
    appearance: Appearance = Appearance(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val eink = appearance.mode == ThemeMode.EINK
    val scheme =
        when (appearance.mode) {
            ThemeMode.LIGHT -> lightGrimmScheme()
            ThemeMode.DARK -> darkGrimmScheme(amoled = false)
            ThemeMode.AMOLED -> darkGrimmScheme(amoled = true)
            ThemeMode.EINK -> einkScheme(appearance.einkTint)
            ThemeMode.SYSTEM -> if (systemDark) darkGrimmScheme(amoled = false) else lightGrimmScheme()
        }
    val context = LocalContext.current
    val systemAnimations =
        remember(context) {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }
    val theme: @Composable () -> Unit = {
        MaterialTheme(colorScheme = scheme, typography = grimmTypography(), shapes = GrimmShapes, content = content)
    }
    if (!eink) {
        CompositionLocalProvider(LocalEinkLook provides false, LocalMotionEnabled provides systemAnimations, content = theme)
        return
    }
    val viewConfiguration = LocalViewConfiguration.current
    val einkViewConfiguration =
        remember(viewConfiguration) {
            viewConfiguration.withMinimumTouchTarget(DpSize(EinkMinTouchTarget, EinkMinTouchTarget))
        }
    CompositionLocalProvider(
        LocalEinkLook provides true,
        LocalMotionEnabled provides false,
        LocalIndication provides NoIndication,
        LocalRippleConfiguration provides null,
        LocalMinimumInteractiveComponentSize provides EinkMinTouchTarget,
        LocalViewConfiguration provides einkViewConfiguration,
        content = theme,
    )
}
