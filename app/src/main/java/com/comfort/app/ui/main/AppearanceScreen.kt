package com.comfort.app.ui.main

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfort.app.theme.AppTheme
import com.comfort.app.theme.DarkColorScheme
import com.comfort.app.theme.LightColorScheme
import com.comfort.app.theme.LocalThemeState
import com.comfort.app.theme.ThemeMode
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Check

// Ported from TachiyomiJ2K's "Appearance" settings screen: a big title, a labeled row of light
// theme preview cards and a labeled row of dark theme preview cards (each a tiny mockup of the
// app rendered in that theme's actual colors), plus "Follow system theme" and "Pure black dark
// mode" toggles. Their version drives this off an Android Preference/RecyclerView stack; this is
// the same visual result built natively in Compose against this app's own AppTheme palette.
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val themeState = LocalThemeState.current
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val availableThemes = remember(dynamicAvailable) {
        AppTheme.entries.filter { !it.isDynamic || dynamicAvailable }
    }
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = when (themeState.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
                    }
                },
                // Same top-of-screen gradient as the rest of Settings (and Home/Library) — this
                // screen has its own Scaffold/TopAppBar separate from SettingsSubScaffold, so it
                // was left out when the gradient was added there.
                modifier = Modifier.background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            MaterialTheme.colorScheme.background,
                        )
                    )
                ),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text("Appearance", 
                fontWeight = FontWeight.Bold,
                fontFamily = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(com.comfort.app.R.font.crystal_radio_kit)),
                fontSize = 36.sp
            )
            Spacer(Modifier.height(28.dp))

            Text(
                "APP THEME",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(16.dp))

            Text("Light theme", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                // Bleed past the page's own 20dp side padding (from the parent Column, above) so
                // this row can actually scroll edge-to-edge instead of stopping short at the same
                // margin every other row on the page rests at — same technique as Home's own
                // Recently-downloaded strip (see MainScreen.kt): widen the measured constraint by
                // bleed*2 and place at x=-bleed to escape the parent's padding-constrained slot,
                // then use contentPadding (a LazyRow constructor PARAMETER, not a trailing
                // Modifier.padding()) to bring the *content* back to resting at that same margin.
                // A trailing .padding() here would look identical at rest but silently cap the
                // LazyRow's own scrollable viewport at the pre-bleed width regardless of how much
                // extra the bleed measures into, making the bleed pointless.
                modifier = Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val bleed = 20.dp.roundToPx()
                        val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + bleed * 2))
                        layout(placeable.width - bleed * 2, placeable.height) {
                            placeable.placeRelative(-bleed, 0)
                        }
                    },
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) {
                items(availableThemes, key = { "${it.name}_light" }) { theme ->
                    ThemePreviewCard(
                        theme = theme,
                        isDark = false,
                        selected = themeState.lightTheme == theme,
                        onClick = { 
                            themeState.setLightTheme(theme)
                            if (themeState.mode != ThemeMode.SYSTEM) {
                                themeState.setMode(ThemeMode.LIGHT)
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))

            Text("Dark theme", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                // Same bleed + contentPadding technique as the Light theme row above.
                modifier = Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val bleed = 20.dp.roundToPx()
                        val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + bleed * 2))
                        layout(placeable.width - bleed * 2, placeable.height) {
                            placeable.placeRelative(-bleed, 0)
                        }
                    },
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) {
                items(availableThemes, key = { "${it.name}_dark" }) { theme ->
                    ThemePreviewCard(
                        theme = theme,
                        isDark = true,
                        selected = themeState.darkTheme == theme,
                        onClick = { 
                            themeState.setDarkTheme(theme)
                            if (themeState.mode != ThemeMode.SYSTEM) {
                                themeState.setMode(ThemeMode.DARK)
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(28.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    ThemeToggleRow(
                        title = "Follow system theme",
                        subtitle = "Switch between your light and dark theme automatically.",
                        checked = themeState.mode == ThemeMode.SYSTEM,
                        onCheckedChange = { follow ->
                            themeState.setMode(if (follow) ThemeMode.SYSTEM else if (effectiveDark) ThemeMode.DARK else ThemeMode.LIGHT)
                        },
                    )
                    if (themeState.mode != ThemeMode.LIGHT) {
                        ThemeToggleRow(
                            title = "Pure black dark mode",
                            subtitle = "Use true black backgrounds in dark theme.",
                            checked = themeState.pureBlack,
                            onCheckedChange = { themeState.setPureBlack(it) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(navBarClearance()))
        }
    }
}

@Composable
private fun ThemeToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// The little phone mockup: a top bar, a hero block, a title line with an accent pill, two
// secondary text lines, and a bottom bar with 3 dots (the middle one accented) — rendered using
// the target theme's actual resolved colors, not the app's current MaterialTheme, so every card
// previews correctly regardless of which theme is presently active.
@Composable
private fun ThemePreviewCard(
    theme: AppTheme,
    isDark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val scheme: ColorScheme = remember(theme, isDark) {
        if (theme.isDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            (if (isDark) theme.dark else theme.light) ?: (if (isDark) DarkColorScheme else LightColorScheme)
        }
    }
    val label = if (isDark) theme.darkLabel else theme.lightLabel

    Column(
        modifier = Modifier.width(100.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(100.dp)
                .height(144.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(
                    width = 3.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(20.dp),
                )
                .clickable(onClick = onClick),
        ) {
            Column(
                modifier = Modifier
                    .padding(4.dp)
                    .fillMaxWidth()
                    .height(136.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(scheme.background),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(18.dp).background(scheme.surface),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .width(26.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(scheme.onSurface.copy(alpha = 0.8f)),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(scheme.onBackground.copy(alpha = 0.16f)),
                )
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(30.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(scheme.onBackground.copy(alpha = 0.85f)),
                    )
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .width(12.dp)
                            .height(7.dp)
                            .clip(RoundedCornerShape(50))
                            .background(scheme.primary),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(44.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(scheme.onSurfaceVariant.copy(alpha = 0.7f)),
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(34.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(scheme.onSurfaceVariant.copy(alpha = 0.7f)),
                )
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier.fillMaxWidth().height(22.dp).background(scheme.surfaceContainer),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(3) { index ->
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(if (index == 1) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.4f)),
                        )
                    }
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(FeatherIcons.Check, contentDescription = "Selected", tint = scheme.onPrimary, modifier = Modifier.size(11.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.width(100.dp),
        )
    }
}
