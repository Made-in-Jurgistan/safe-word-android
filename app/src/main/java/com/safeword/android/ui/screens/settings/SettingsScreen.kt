package com.safeword.android.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeword.android.BuildConfig
import com.safeword.android.R
import com.safeword.android.ui.components.GlassCard
import com.safeword.android.ui.components.GlassDivider
import com.safeword.android.ui.components.GlassInfoRow
import com.safeword.android.ui.components.GlassListItem
import com.safeword.android.ui.components.GlassSurface
import com.safeword.android.ui.theme.CobaltBright
import com.safeword.android.ui.theme.CobaltGlow
import com.safeword.android.ui.theme.DoneGreen
import com.safeword.android.ui.theme.GlassDimText
import com.safeword.android.ui.theme.GlassWhite

/**
 * SettingsScreen — single scrollable screen for Safe Word settings.
 *
 * Sections (Overlay, Appearance, About) are displayed inline with
 * cobalt section headers and generous touch targets (WCAG AA compliant).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    GlassSurface(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = GlassWhite,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = GlassWhite,
                    ),
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // App icon
                Image(
                    painter = painterResource(id = R.drawable.safeword_icon),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.size(120.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_made_in_jurgistan),
                    style = MaterialTheme.typography.bodyMedium,
                    color = CobaltBright,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(32.dp))

                // === VOICE ===
                SectionHeader(stringResource(R.string.settings_section_voice))
                Spacer(Modifier.height(8.dp))
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = 0.dp,
                ) {
                    GlassListItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.settings_haptic_enabled),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        supportingContent = {
                            Text(
                                if (settings.hapticFeedbackEnabled) {
                                    stringResource(R.string.settings_haptic_state_on)
                                } else {
                                    stringResource(R.string.settings_haptic_state_off)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (settings.hapticFeedbackEnabled) DoneGreen else GlassDimText,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.hapticFeedbackEnabled,
                                onCheckedChange = viewModel::updateHapticFeedbackEnabled,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DoneGreen,
                                    checkedTrackColor = DoneGreen.copy(alpha = 0.3f),
                                ),
                            )
                        },
                        modifier = run {
                            val hapticState = if (settings.hapticFeedbackEnabled) {
                                stringResource(R.string.settings_haptic_state_on)
                            } else {
                                stringResource(R.string.settings_haptic_state_off)
                            }
                            Modifier
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                                .defaultMinSize(minHeight = 64.dp)
                                .semantics(mergeDescendants = true) {
                                    stateDescription = hapticState
                                    toggleableState = ToggleableState(settings.hapticFeedbackEnabled)
                                }
                        },
                        onClick = { viewModel.updateHapticFeedbackEnabled(!settings.hapticFeedbackEnabled) },
                    )
                }

                Spacer(Modifier.height(24.dp))

                // === OVERLAY ===
                SectionHeader(stringResource(R.string.settings_section_overlay))
                Spacer(Modifier.height(8.dp))
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = 0.dp,
                ) {
                    GlassListItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.settings_overlay_enabled),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        supportingContent = {
                            Text(
                                if (settings.overlayEnabled) {
                                    stringResource(R.string.settings_overlay_state_on)
                                } else {
                                    stringResource(R.string.settings_overlay_state_off)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (settings.overlayEnabled) DoneGreen else GlassDimText,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.overlayEnabled,
                                onCheckedChange = viewModel::updateOverlayEnabled,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DoneGreen,
                                    checkedTrackColor = DoneGreen.copy(alpha = 0.3f),
                                ),
                            )
                        },
                        modifier = run {
                            val overlayState = if (settings.overlayEnabled) {
                                stringResource(R.string.settings_overlay_state_on)
                            } else {
                                stringResource(R.string.settings_overlay_state_off)
                            }
                            Modifier
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                                .defaultMinSize(minHeight = 64.dp)
                                .semantics(mergeDescendants = true) {
                                    stateDescription = overlayState
                                    toggleableState = ToggleableState(settings.overlayEnabled)
                                }
                        },
                        onClick = { viewModel.updateOverlayEnabled(!settings.overlayEnabled) },
                    )
                }

                Spacer(Modifier.height(24.dp))

                // === APPEARANCE ===
                SectionHeader(stringResource(R.string.settings_section_appearance))
                Spacer(Modifier.height(8.dp))
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = 0.dp,
                ) {
                    DarkModePicker(
                        selected = settings.darkMode,
                        onModeSelected = viewModel::updateDarkMode,
                    )
                }

                Spacer(Modifier.height(24.dp))

                // === ABOUT ===
                SectionHeader(stringResource(R.string.settings_section_about))
                Spacer(Modifier.height(8.dp))
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = 16.dp,
                ) {
                    GlassInfoRow(
                        label = stringResource(R.string.settings_label_version),
                        value = BuildConfig.VERSION_NAME,
                    )
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ---------- Section header ----------

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = CobaltBright,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    )
}

// ---------- Dark mode picker ----------

@Composable
private fun DarkModePicker(
    selected: String,
    onModeSelected: (String) -> Unit,
) {
    GlassOptionPicker(
        options = listOf(
            "system" to stringResource(R.string.settings_dark_mode_system),
            "light" to stringResource(R.string.settings_dark_mode_light),
            "dark" to stringResource(R.string.settings_dark_mode_dark),
        ),
        selected = selected,
        onOptionSelected = onModeSelected,
    )
}

// ---------- Generic option picker ----------

@Composable
private fun <T> GlassOptionPicker(
    options: List<Pair<T, String>>,
    selected: T,
    onOptionSelected: (T) -> Unit,
) {
    Column(modifier = Modifier.selectableGroup()) {
        options.forEachIndexed { index, (value, label) ->
            if (index > 0) GlassDivider(modifier = Modifier.padding(horizontal = 16.dp))
            GlassListItem(
                headlineContent = {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                leadingContent = { RadioButton(selected = selected == value, onClick = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .selectable(
                        selected = selected == value,
                        onClick = { onOptionSelected(value) },
                        role = Role.RadioButton,
                    ),
            )
        }
    }
}
