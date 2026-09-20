package com.facefusion.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Settings
// icons-core only. The extended icon pack is a multi-megabyte dependency for
// one glyph, and PlayArrow reads as a running feed well enough.
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.facefusion.mobile.R

enum class Screen { Swap, Live, Settings }

/**
 * The frame around both screens: brand band above, two destinations below.
 *
 * Two or three destinations is not enough to justify a navigation library -- and adding one
 * would mean resolving a dependency this build cannot be relied on to fetch. A plain enum
 * plus Material3's own NavigationBar is the whole navigation system.
 *
 * Live appears only when [showLive] does, which the caller derives from BuildConfig rather
 * than a flag of its own -- same signal as the app id and the launcher label.
 */
@Composable
fun AppScaffold(
    screen: Screen,
    onScreen: (Screen) -> Unit,
    showLive: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // LiveFusion owns this build. Keep a quiet text header, but do not retain the
            // upstream FaceFusion face mark or FACEFUSION wordmark anywhere in the UI.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                Column {
                    Text(
                        "LIVEFUSION",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Light,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 18.dp),
                    )
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    // ⚠ The gap below the RULE, not above it. Before the band existed the
                    // top bar carried `bottom = 18.dp` and that was the whole separation
                    // between the wordmark and the first control. The band kept 16.dp but
                    // spends it ABOVE the divider, so every screen's first row -- the
                    // Processors chips, the Live title -- ended up flush against the line
                    // with nothing under it. Part of the top bar rather than of each
                    // screen, so it measures into the Scaffold's own top inset and no
                    // screen has to know the band is there.
                    Spacer(Modifier.height(14.dp))
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = screen == Screen.Swap,
                    onClick = { onScreen(Screen.Swap) },
                    icon = { Icon(Icons.Default.Face, stringResource(R.string.nav_swap)) },
                    label = { Text(stringResource(R.string.nav_swap)) },
                )
                if (showLive) NavigationBarItem(
                    selected = screen == Screen.Live,
                    onClick = { onScreen(Screen.Live) },
                    icon = { Icon(Icons.Default.PlayArrow, stringResource(R.string.nav_live)) },
                    label = { Text(stringResource(R.string.nav_live)) },
                )
                NavigationBarItem(
                    selected = screen == Screen.Settings,
                    onClick = { onScreen(Screen.Settings) },
                    icon = { Icon(Icons.Default.Settings, stringResource(R.string.nav_settings)) },
                    label = { Text(stringResource(R.string.nav_settings)) },
                )
            }
        },
        content = content,
    )
}
