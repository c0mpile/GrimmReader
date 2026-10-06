package com.c0mpile.grimmreader.core.designsystem.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** True while the E-ink look is active: no animation, no ripples, grayscale images, 56 dp touch targets. */
val LocalEinkLook = staticCompositionLocalOf { false }

/** False when the E-ink look is on or the system animator scale is 0; every animation must check it. */
val LocalMotionEnabled = staticCompositionLocalOf { true }

val EinkMinTouchTarget = 56.dp

/** Saturation 0, contrast ×1.15; applied to covers and comic/PDF pages in the E-ink look. */
val EinkImageFilter: ColorFilter =
    ColorFilter.colorMatrix(
        ColorMatrix().apply {
            setToSaturation(0f)
            val c = 1.15f
            val t = (1f - c) * 128f
            timesAssign(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        },
    )

/** Indication that draws nothing (no ripple, no pressed overlay). */
internal object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}

    override fun equals(other: Any?) = other === this

    override fun hashCode() = -1
}

/**
 * Hit testing and accessibility touch bounds come from [ViewConfiguration.minimumTouchTargetSize];
 * `LocalMinimumInteractiveComponentSize` alone only reserves layout space (Spike c).
 */
internal fun ViewConfiguration.withMinimumTouchTarget(size: DpSize): ViewConfiguration =
    object : ViewConfiguration by this {
        override val minimumTouchTargetSize: DpSize = size
    }
