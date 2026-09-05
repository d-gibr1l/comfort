package com.comfort.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val PillShape = RoundedCornerShape(50)

// Used only when AppTheme.EXPRESSIVE is the active theme (see Theme.kt's GalleryDLTheme) — M3
// Expressive pushes corners noticeably larger than the app's default scale: medium/large cover
// the spec's "32dp cards", extraLarge covers "about 40dp dialogs and sheets" (both AlertDialog
// and ModalBottomSheet read shapes.extraLarge by default).
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(20.dp),
    medium = RoundedCornerShape(32.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(40.dp),
)
