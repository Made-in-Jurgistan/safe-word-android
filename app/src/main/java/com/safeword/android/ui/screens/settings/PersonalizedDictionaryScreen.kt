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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.safeword.android.data.db.PersonalizedEntryEntity
import com.safeword.android.ui.components.GlassCard
import com.safeword.android.ui.components.GlassDivider
import com.safeword.android.ui.components.GlassSurface
import com.safeword.android.ui.theme.CobaltBright
import com.safeword.android.ui.theme.DoneGreen
import com.safeword.android.ui.theme.GlassDimText
import com.safeword.android.ui.theme.GlassWhite

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalizedDictionaryScreen(
    onBack: () -> Unit,
    viewModel: PersonalizedDictionaryViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    GlassSurface(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.dict_title),
                            fontWeight = FontWeight.Bold,
                            color = GlassWhite,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Default.ArrowBack,
                                contentDescription = stringResource(R.string.dict_cd_back),
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
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.dict_cd_add))
                }
            },
        ) { padding ->
            if (entries.isEmpty()) {
                EmptyState(modifier = Modifier.padding(padding))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { Spacer(Modifier.height(8.dp)) }
                    items(entries, key = { it.id }) { entry ->
                        EntryCard(
                            entry = entry,
                            onDelete = { viewModel.delete(entry) },
                            onToggleEnabled = { viewModel.setEnabled(entry, it) },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showAddDialog) {
        AddEntryDialog(
            onConfirm = { from, to ->
                viewModel.add(from, to)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.dict_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = GlassWhite,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.dict_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = GlassDimText,
            )
        }
    }
}

@Composable
private fun EntryCard(
    entry: PersonalizedEntryEntity,
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
                        text = "\"${entry.fromPhrase}\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (entry.enabled) GlassWhite else GlassDimText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "→  \"${entry.toPhrase}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (entry.enabled) DoneGreen else GlassDimText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = onToggleEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DoneGreen,
                            checkedTrackColor = DoneGreen.copy(alpha = 0.3f),
                        ),
                    )
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.dict_cd_delete),
                            tint = GlassDimText,
                        )
                    }
                }
            }
            if (entry.useCount > 0) {
                GlassDivider(modifier = Modifier.padding(top = 8.dp))
                Text(
                    text = stringResource(R.string.dict_use_count, entry.useCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassDimText,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AddEntryDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    val isValid = fromText.isNotBlank() && toText.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dict_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.dict_add_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = fromText,
                    onValueChange = { fromText = it },
                    label = { Text(stringResource(R.string.dict_add_from_label)) },
                    placeholder = { Text(stringResource(R.string.dict_add_from_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = toText,
                    onValueChange = { toText = it },
                    label = { Text(stringResource(R.string.dict_add_to_label)) },
                    placeholder = { Text(stringResource(R.string.dict_add_to_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(fromText.trim(), toText.trim()) }, enabled = isValid) {
                Text(stringResource(R.string.dict_add_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dict_add_cancel))
            }
        },
    )
}
