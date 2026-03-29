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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.safeword.android.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 * SettingsScreen — simplified settings for Safe Word.
 * Sections: Overlay, Appearance (dark mode), About.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isRealWhisper by viewModel.isRealWhisper.collectAsStateWithLifecycle()

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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.safeword_icon),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(144.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.settings_made_in_jurgistan),
                        style = MaterialTheme.typography.labelSmall,
                        color = CobaltBright,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // === OVERLAY ===
                GlassSectionHeader(stringResource(R.string.settings_section_overlay))
                SettingsCard {
                    GlassListItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.settings_overlay_enabled),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(R.string.settings_overlay_enabled_desc),
                                style = MaterialTheme.typography.bodySmall,
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
                    )
                }

                Spacer(Modifier.height(16.dp))

                // === APPEARANCE ===
                GlassSectionHeader(stringResource(R.string.settings_section_appearance))
                SettingsCard {
                    DarkModePicker(
                        selected = settings.darkMode,
                        onModeSelected = viewModel::updateDarkMode,
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
                            stringResource(R.string.settings_label_whisper_backend),
                            if (isRealWhisper) stringResource(R.string.settings_whisper_native)
                            else stringResource(R.string.settings_whisper_not_loaded),
                        )
                        GlassDivider(modifier = Modifier.padding(vertical = 2.dp))
                        GlassInfoRow(stringResource(R.string.settings_label_based_on), stringResource(R.string.settings_based_on_value))
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
private fun DarkModePicker(
    selected: String,
    onModeSelected: (String) -> Unit,
) {
    val modes = listOf(
        "system" to stringResource(R.string.settings_dark_mode_system),
        "light" to stringResource(R.string.settings_dark_mode_light),
        "dark" to stringResource(R.string.settings_dark_mode_dark),
    )
    Column(modifier = Modifier.selectableGroup()) {
        modes.forEachIndexed { index, (mode, label) ->
            if (index > 0) GlassDivider(modifier = Modifier.padding(vertical = 2.dp))
            GlassListItem(
                headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                leadingContent = { RadioButton(selected = selected == mode, onClick = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == mode,
                        onClick = { onModeSelected(mode) },
                        role = Role.RadioButton,
                    ),
            )
        }
    }
}
