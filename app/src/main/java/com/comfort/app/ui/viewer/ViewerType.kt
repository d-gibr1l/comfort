/*
 * Ported from Photon Gallery (https://github.com/Bn5prS/Photon_Gallery), GPL-3.0, like Comfort.
 * Its info sheet's two technical-parameter styles, without Photon's variable Google Sans Flex
 * (Comfort doesn't bundle it): same sizes, weights and spacing on the app's own font.
 */
package com.comfort.app.ui.viewer

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val TechnicalParamValueStyle = TextStyle(
    fontWeight = FontWeight.Bold,
    fontSize = 14.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.3.sp
)

val TechnicalParamLabelStyle = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.8.sp
)
