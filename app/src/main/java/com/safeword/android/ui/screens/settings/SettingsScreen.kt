package com.safeword.android.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeword.android.R
import com.safeword.android.BuildConfig
import com.safeword.android.ui.components.GlassCard
import com.safeword.android.ui.components.GlassDivider
import com.safeword.android.ui.components.GlassInfoRow
import com.safeword.android.ui.components.GlassListItem
import com.safeword.android.ui.components.GlassSectionHeader
import com.safeword.android.ui.components.GlassSurface
import com.safeword.android.ui.theme.CobaltBright
import com.safeword.android.ui.theme.DoneGreen
import com.safeword.android.ui.theme.GlassDimText
import com.safeword.android.ui.theme.GlassWhite

/**
 * SettingsScreen — Material 3 Expressive-styled settings for Safe Word.
 *
 * Sections: Overlay, Appearance, Personalization, About.
 *
 * 2026 UI/UX patterns applied:
 * - Compact 96dp identity header so primary settings stay above the fold.
 * - `SingleChoiceSegmentedButtonRow` for the 3-way Dark Mode picker instead of
 *   a vertical radio list.
 * - Haptic `LongPress` feedback on every toggle/segment change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToDictionary: () -> Unit = {},
    onNavigateToCustomCommands: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    GlassSurface(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.settings_title),
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
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                // === COMPACT HEADER ===
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.safeword_icon),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(96.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_made_in_jurgistan),
                        style = MaterialTheme.typography.labelSmall,
                        color = CobaltBright,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                // === OVERLAY ===
                GlassSectionHeader(stringResource(R.string.settings_section_overlay))
                SettingsCard {
                    HapticSwitchRow(
                        title = stringResource(R.string.settings_overlay_enabled),
                        description = stringResource(R.string.settings_overlay_enabled_desc),
                        checked = settings.overlayEnabled,
                        onCheckedChange = viewModel::updateOverlayEnabled,
                        haptics = haptics,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // === APPEARANCE ===
                GlassSectionHeader(stringResource(R.string.settings_section_appearance))
                SettingsCard {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        DarkModeSegmented(
                            selected = settings.darkMode,
                            onModeSelected = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.updateDarkMode(it)
                            },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // === PERSONALIZATION ===
                GlassSectionHeader(stringResource(R.string.settings_section_dictionary))
                SettingsCard {
                    HapticSwitchRow(
                        title = stringResource(R.string.settings_hotword_boost_title),
                        description = stringResource(R.string.settings_hotword_boost_desc),
                        checked = settings.hotwordBoostEnabled,
                        onCheckedChange = viewModel::updateHotwordBoostEnabled,
                        haptics = haptics,
                    )
                    GlassDivider(modifier = Modifier.padding(vertical = 2.dp))
                    HapticSwitchRow(
                        title = stringResource(R.string.settings_auto_learn_dictionary_title),
                        description = stringResource(R.string.settings_auto_learn_dictionary_desc),
                        checked = settings.autoLearnDictionaryEnabled,
                        onCheckedChange = viewModel::updateAutoLearnDictionaryEnabled,
                        haptics = haptics,
                    )
                    GlassDivider(modifier = Modifier.padding(vertical = 2.dp))
                    NavRow(
                        title = stringResource(R.string.settings_personal_dictionary),
                        description = stringResource(R.string.settings_personal_dictionary_desc),
                        onClick = onNavigateToDictionary,
                    )
                    GlassDivider(modifier = Modifier.padding(vertical = 2.dp))
                    NavRow(
                        title = stringResource(R.string.settings_custom_commands),
                        description = stringResource(R.string.settings_custom_commands_desc),
                        onClick = onNavigateToCustomCommands,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // === ABOUT ===
                GlassSectionHeader(stringResource(R.string.settings_section_about))
                SettingsCard {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        GlassInfoRow(stringResource(R.string.settings_label_version), BuildConfig.VERSION_NAME)
                        GlassDivider(modifier = Modifier.padding(vertical = 2.dp))
                        GlassInfoRow(
                            stringResource(R.string.settings_label_speech_engine),
                            stringResource(R.string.settings_speech_engine_value),
                        )
                        GlassDivider(modifier = Modifier.padding(vertical = 2.dp))
                        GlassInfoRow(
                            stringResource(R.string.settings_label_based_on),
                            stringResource(R.string.settings_based_on_value),
                        )
                    }
                }

                Spacer(Modifier.height(80.dp))
            }
        }
    } // end GlassSurface
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 0.dp) {
        content()
    }
}

@Composable
private fun HapticSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
) {
    GlassListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCheckedChange(it)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = DoneGreen,
                    checkedTrackColor = DoneGreen.copy(alpha = 0.3f),
                ),
            )
        },
    )
}

@Composable
private fun NavRow(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    GlassListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = GlassDimText,
            )
        },
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DarkModeSegmented(
    selected: String,
    onModeSelected: (String) -> Unit,
) {
    data class Mode(val key: String, val label: String, val icon: ImageVector)
    val modes = listOf(
        Mode("system", stringResource(R.string.settings_dark_mode_system), Icons.Filled.Contrast),
        Mode("light", stringResource(R.string.settings_dark_mode_light), Icons.Filled.LightMode),
        Mode("dark", stringResource(R.string.settings_dark_mode_dark), Icons.Filled.DarkMode),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode.key,
                onClick = { onModeSelected(mode.key) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                icon = {
                    Icon(
                        imageVector = mode.icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = { Text(mode.label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}
