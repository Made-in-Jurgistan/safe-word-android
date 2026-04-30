package com.safeword.android.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeword.android.R
import com.safeword.android.transcription.CustomVoiceCommand
import com.safeword.android.ui.components.GlassCard
import com.safeword.android.ui.components.GlassDivider
import com.safeword.android.ui.components.GlassSurface
import com.safeword.android.ui.theme.CobaltBright
import com.safeword.android.ui.theme.DoneGreen
import com.safeword.android.ui.theme.GlassDimText
import com.safeword.android.ui.theme.GlassWhite

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomCommandsScreen(
    onBack: () -> Unit,
    viewModel: CustomCommandsViewModel = hiltViewModel(),
) {
    val commands by viewModel.commands.collectAsStateWithLifecycle()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    GlassSurface(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.custom_cmd_title),
                            fontWeight = FontWeight.Bold,
                            color = GlassWhite,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Default.ArrowBack,
                                contentDescription = stringResource(R.string.custom_cmd_cd_back),
                                tint = GlassWhite,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = GlassWhite,
                    ),
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = CobaltBright,
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.custom_cmd_cd_add))
                }
            },
        ) { padding ->
            if (commands.isEmpty()) {
                CommandsEmptyState(modifier = Modifier.padding(padding))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { Spacer(Modifier.height(8.dp)) }
                    items(commands, key = { it.id }) { cmd ->
                        CommandCard(
                            command = cmd,
                            onDelete = { viewModel.delete(cmd.id) },
                            onToggleEnabled = { viewModel.toggleEnabled(cmd.id, it) },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showAddDialog) {
        AddCommandDialog(
            onConfirm = { trigger, insertText, actionName ->
                viewModel.add(trigger, insertText, actionName)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun CommandsEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.custom_cmd_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = GlassWhite,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.custom_cmd_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = GlassDimText,
            )
        }
    }
}

@Composable
private fun CommandCard(
    command: CustomVoiceCommand,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 0.dp) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "\"${command.triggerPhrases}\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (command.enabled) GlassWhite else GlassDimText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val actionLabel = when {
                        !command.insertText.isNullOrBlank() ->
                            stringResource(R.string.custom_cmd_inserts, command.insertText)
                        !command.actionName.isNullOrBlank() ->
                            stringResource(R.string.custom_cmd_action, command.actionName)
                        else -> stringResource(R.string.custom_cmd_misconfigured)
                    }
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (command.enabled) DoneGreen else GlassDimText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = command.enabled,
                        onCheckedChange = onToggleEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DoneGreen,
                            checkedTrackColor = DoneGreen.copy(alpha = 0.3f),
                        ),
                    )
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.custom_cmd_cd_delete),
                            tint = GlassDimText,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCommandDialog(
    onConfirm: (trigger: String, insertText: String?, actionName: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var triggerText by remember { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Tab 0: Insert text
    var insertText by remember { mutableStateOf("") }

    // Tab 1: Built-in action
    var selectedAction by remember { mutableStateOf("") }
    var actionDropdownExpanded by remember { mutableStateOf(false) }

    val isValid = triggerText.isNotBlank() && when (selectedTab) {
        0 -> insertText.isNotBlank()
        1 -> selectedAction.isNotBlank()
        else -> false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_cmd_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.custom_cmd_add_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = triggerText,
                    onValueChange = { triggerText = it },
                    label = { Text(stringResource(R.string.custom_cmd_trigger_label)) },
                    placeholder = { Text(stringResource(R.string.custom_cmd_trigger_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = GlassWhite,
                    indicator = { tabPositions ->
                        if (selectedTab < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = CobaltBright,
                            )
                        }
                    },
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(R.string.custom_cmd_tab_text)) },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.custom_cmd_tab_action)) },
                    )
                }

                when (selectedTab) {
                    0 -> {
                        OutlinedTextField(
                            value = insertText,
                            onValueChange = { insertText = it },
                            label = { Text(stringResource(R.string.custom_cmd_insert_label)) },
                            placeholder = { Text(stringResource(R.string.custom_cmd_insert_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    1 -> {
                        ExposedDropdownMenuBox(
                            expanded = actionDropdownExpanded,
                            onExpandedChange = { actionDropdownExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = selectedAction,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.custom_cmd_action_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = actionDropdownExpanded) },
                                modifier = Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                            )
                            ExposedDropdownMenu(
                                expanded = actionDropdownExpanded,
                                onDismissRequest = { actionDropdownExpanded = false },
                            ) {
                                CustomVoiceCommand.AVAILABLE_ACTIONS.forEach { action ->
                                    DropdownMenuItem(
                                        text = { Text(action) },
                                        onClick = {
                                            selectedAction = action
                                            actionDropdownExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (selectedTab) {
                        0 -> onConfirm(triggerText.trim(), insertText.trim(), null)
                        1 -> onConfirm(triggerText.trim(), null, selectedAction)
                    }
                },
                enabled = isValid,
            ) {
                Text(stringResource(R.string.custom_cmd_add_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.custom_cmd_add_cancel))
            }
        },
    )
}
