package xyz.mek030399.tokenflow.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.mek030399.tokenflow.data.CloudMcpServer
import xyz.mek030399.tokenflow.data.CloudMcpTransport
import xyz.mek030399.tokenflow.data.CloudArtifactDelivery
import xyz.mek030399.tokenflow.data.CloudArtifactDeliveryStatus
import xyz.mek030399.tokenflow.data.CloudServerDraft
import xyz.mek030399.tokenflow.data.CloudServerProfile
import xyz.mek030399.tokenflow.data.CloudTaskStatus
import xyz.mek030399.tokenflow.data.CloudTask

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InfiniteCloudScreen(state: AppUiState, viewModel: AppViewModel, showBack: Boolean) {
    var editingServer by remember { mutableStateOf<CloudServerProfile?>(null) }
    var editingMcp by remember { mutableStateOf<CloudMcpServer?>(null) }
    var deleteServer by remember { mutableStateOf<CloudServerProfile?>(null) }
    var expandedServerId by remember { mutableStateOf<String?>(null) }
    var taskSelectionMode by remember { mutableStateOf(false) }
    var selectedTaskIds by remember { mutableStateOf(emptySet<String>()) }
    var confirmDeleteTasks by remember { mutableStateOf(false) }
    val visibleDeletableTaskIds = state.cloudTasks
        .filter { state.cloud.taskServerFilterId == null || it.cloudServerId == state.cloud.taskServerFilterId }
        .filter(CloudTask::canDeleteLocally)
        .map(CloudTask::id)
        .toSet()
    LaunchedEffect(visibleDeletableTaskIds, state.cloud.section) {
        selectedTaskIds = selectedTaskIds.intersect(visibleDeletableTaskIds)
        if (state.cloud.section != CloudSection.TASKS) taskSelectionMode = false
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (taskSelectionMode && state.cloud.section == CloudSection.TASKS) {
                            stringResource(xyz.mek030399.tokenflow.R.string.selected_items, selectedTaskIds.size)
                        } else stringResource(xyz.mek030399.tokenflow.R.string.infinite_cloud),
                    )
                },
                navigationIcon = {
                    if (taskSelectionMode && state.cloud.section == CloudSection.TASKS) {
                        IconButton(onClick = { taskSelectionMode = false; selectedTaskIds = emptySet() }) {
                            Icon(Icons.Outlined.Close, stringResource(xyz.mek030399.tokenflow.R.string.clear_selection))
                        }
                    } else if (showBack) IconButton(onClick = { viewModel.openScreen(AppScreen.CHAT) }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(xyz.mek030399.tokenflow.R.string.back))
                    }
                },
                actions = {
                    when (state.cloud.section) {
                        CloudSection.SERVERS -> IconButton(onClick = { editingServer = CloudServerProfile() }) { Icon(Icons.Outlined.Add, stringResource(xyz.mek030399.tokenflow.R.string.cloud_add_server)) }
                        CloudSection.MCP -> IconButton(
                            onClick = {
                                state.cloud.selectedServerId?.let { editingMcp = CloudMcpServer(cloudServerId = it) }
                            },
                        ) { Icon(Icons.Outlined.Add, stringResource(xyz.mek030399.tokenflow.R.string.cloud_add_mcp)) }
                        CloudSection.FILES -> IconButton(onClick = { viewModel.loadCloudFiles() }) { Icon(Icons.Outlined.Refresh, stringResource(xyz.mek030399.tokenflow.R.string.cloud_refresh)) }
                        CloudSection.TASKS -> if (taskSelectionMode) {
                            IconButton(onClick = {
                                selectedTaskIds = if (visibleDeletableTaskIds.isNotEmpty() && visibleDeletableTaskIds.all { it in selectedTaskIds }) {
                                    selectedTaskIds - visibleDeletableTaskIds
                                } else selectedTaskIds + visibleDeletableTaskIds
                            }) {
                                Icon(Icons.Outlined.SelectAll, stringResource(xyz.mek030399.tokenflow.R.string.select_all))
                            }
                            IconButton(onClick = { confirmDeleteTasks = true }, enabled = selectedTaskIds.isNotEmpty()) {
                                Icon(Icons.Outlined.DeleteOutline, stringResource(xyz.mek030399.tokenflow.R.string.delete))
                            }
                        } else if (visibleDeletableTaskIds.isNotEmpty()) {
                            IconButton(onClick = { taskSelectionMode = true }) {
                                Icon(Icons.Outlined.Checklist, stringResource(xyz.mek030399.tokenflow.R.string.cloud_select_tasks))
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(state.cloud.section.ordinal) {
                CloudSection.entries.forEach { section ->
                    Tab(
                        selected = state.cloud.section == section,
                        onClick = {
                            if (section != CloudSection.TASKS) {
                                taskSelectionMode = false
                                selectedTaskIds = emptySet()
                            }
                            viewModel.selectCloudSection(section)
                        },
                        text = { Text(section.displayName()) },
                    )
                }
            }
            if (state.cloud.section != CloudSection.SERVERS && state.cloudServers.isNotEmpty()) {
                ServerSelector(state, viewModel)
            }
            if (state.cloud.busy || state.cloud.testingMcpServerId != null) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
            }
            when (state.cloud.section) {
                CloudSection.SERVERS -> ServerList(
                    state = state,
                    viewModel = viewModel,
                    expandedServerId = expandedServerId,
                    onExpandedChange = { expandedServerId = if (expandedServerId == it) null else it },
                    edit = { editingServer = it },
                    delete = { deleteServer = it },
                )
                CloudSection.TASKS -> TaskList(
                    state = state,
                    viewModel = viewModel,
                    selectionMode = taskSelectionMode,
                    selectedTaskIds = selectedTaskIds,
                    onToggleSelection = { id ->
                        selectedTaskIds = if (id in selectedTaskIds) selectedTaskIds - id else selectedTaskIds + id
                    },
                    onStartSelection = { id ->
                        taskSelectionMode = true
                        selectedTaskIds += id
                    },
                )
                CloudSection.FILES -> FileBrowser(state, viewModel)
                CloudSection.MCP -> McpList(state, viewModel, { editingMcp = it })
            }
        }
    }

    editingServer?.let { profile ->
        ServerEditor(profile, onDismiss = { editingServer = null }) { draft ->
            viewModel.saveCloudServer(draft)
            editingServer = null
        }
    }
    editingMcp?.let { mcp ->
        McpEditor(mcp, onDismiss = { editingMcp = null }) { value, env, headers ->
            viewModel.saveCloudMcpServer(value, env, headers)
            editingMcp = null
        }
    }
    deleteServer?.let { server ->
        AlertDialog(
            onDismissRequest = { deleteServer = null },
            title = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_delete_server_title, server.name)) },
            text = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_delete_server_message)) },
            confirmButton = { Button(onClick = { viewModel.deleteCloudServer(server.id); deleteServer = null }) { Text(stringResource(xyz.mek030399.tokenflow.R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleteServer = null }) { Text(stringResource(xyz.mek030399.tokenflow.R.string.cancel)) } },
        )
    }
    if (confirmDeleteTasks) {
        AlertDialog(
            onDismissRequest = { confirmDeleteTasks = false },
            title = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_delete_tasks_title)) },
            text = {
                Text(pluralStringResource(
                    xyz.mek030399.tokenflow.R.plurals.cloud_delete_tasks_detail,
                    selectedTaskIds.size,
                    selectedTaskIds.size,
                ))
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteCloudTasks(selectedTaskIds)
                    selectedTaskIds = emptySet()
                    taskSelectionMode = false
                    confirmDeleteTasks = false
                }) { Text(stringResource(xyz.mek030399.tokenflow.R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteTasks = false }) { Text(stringResource(xyz.mek030399.tokenflow.R.string.cancel)) } },
        )
    }
    state.cloud.pendingProbe?.let { probe ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCloudHostTrust,
            icon = { Icon(Icons.Outlined.Key, null) },
            title = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_trust_host_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_trust_host_message))
                Text(probe.fingerprint, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Text(probe.hostKeyAlgorithm)
            } },
            confirmButton = {
                Button(onClick = viewModel::trustPendingCloudHost, enabled = state.cloud.probingServerId == null) {
                    Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_trust))
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissCloudHostTrust) { Text(stringResource(xyz.mek030399.tokenflow.R.string.cancel)) } },
        )
    }
    state.cloud.pendingHostKeyReplacement?.let { replacement ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCloudHostKeyReplacement,
            icon = { Icon(Icons.Outlined.Key, null) },
            title = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_replace_host_key_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_replace_host_key_message))
                    Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_old_fingerprint), style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(replacement.oldFingerprint, fontFamily = FontFamily.Monospace)
                    }
                    Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_new_fingerprint), style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(replacement.probe.fingerprint, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    }
                    Text(replacement.probe.hostKeyAlgorithm)
                }
            },
            confirmButton = {
                Button(onClick = viewModel::replacePendingCloudHostKey, enabled = state.cloud.probingServerId == null) {
                    Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_replace_host_key))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCloudHostKeyReplacement) {
                    Text(stringResource(xyz.mek030399.tokenflow.R.string.cancel))
                }
            },
        )
    }
    state.cloud.taskLogId?.let {
        AlertDialog(
            onDismissRequest = viewModel::closeCloudTaskLog,
            title = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_task_output)) },
            text = { Text(state.cloud.taskLog.ifBlank { stringResource(xyz.mek030399.tokenflow.R.string.cloud_no_output) }, fontFamily = FontFamily.Monospace, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = viewModel::closeCloudTaskLog) { Text(stringResource(xyz.mek030399.tokenflow.R.string.close)) } },
        )
    }
    state.cloud.mcpTestName?.let { name ->
        AlertDialog(
            onDismissRequest = viewModel::closeCloudMcpTest,
            title = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_mcp_tools_title, name)) },
            text = { Text(state.cloud.mcpTestTools.joinToString("\n").ifBlank { stringResource(xyz.mek030399.tokenflow.R.string.cloud_mcp_no_tools) }) },
            confirmButton = { TextButton(onClick = viewModel::closeCloudMcpTest) { Text(stringResource(xyz.mek030399.tokenflow.R.string.close)) } },
        )
    }
}

@Composable
private fun ServerSelector(state: AppUiState, viewModel: AppViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.cloud.section == CloudSection.TASKS) {
            FilterChip(
                selected = state.cloud.taskServerFilterId == null,
                onClick = { viewModel.selectCloudTaskServerFilter(null) },
                label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_all_servers)) },
            )
        }
        state.cloudServers.forEach { server ->
            FilterChip(
                selected = if (state.cloud.section == CloudSection.TASKS) {
                    state.cloud.taskServerFilterId == server.id
                } else state.cloud.selectedServerId == server.id,
                onClick = {
                    if (state.cloud.section == CloudSection.TASKS) viewModel.selectCloudTaskServerFilter(server.id)
                    else viewModel.selectCloudServer(server.id)
                },
                label = { Text(server.name) },
            )
        }
    }
}

@Composable
private fun ServerList(
    state: AppUiState,
    viewModel: AppViewModel,
    expandedServerId: String?,
    onExpandedChange: (String) -> Unit,
    edit: (CloudServerProfile) -> Unit,
    delete: (CloudServerProfile) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.cloudServers.isEmpty()) Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_empty_servers))
        state.cloudServers.forEach { server ->
            ServerCard(
                server = server,
                diagnostic = state.cloud.serverDiagnostics[server.id],
                expanded = expandedServerId == server.id,
                probing = state.cloud.probingServerId == server.id,
                probeEnabled = state.cloud.probingServerId == null && server.id !in state.cloud.mutatingServerIds,
                actionsEnabled = state.cloud.probingServerId == null && server.id !in state.cloud.mutatingServerIds,
                toggle = { onExpandedChange(server.id) },
                probe = { viewModel.probeCloudServer(server.id) },
                replaceHostKey = { viewModel.probeCloudHostReplacement(server.id) },
                edit = { edit(server) },
                delete = { delete(server) },
            )
        }
    }
}

@Composable
private fun ServerCard(
    server: CloudServerProfile,
    diagnostic: CloudServerDiagnostic?,
    expanded: Boolean,
    probing: Boolean,
    probeEnabled: Boolean,
    actionsEnabled: Boolean,
    toggle: () -> Unit,
    probe: () -> Unit,
    replaceHostKey: () -> Unit,
    edit: () -> Unit,
    delete: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val status = when {
        !server.keyConfigured -> xyz.mek030399.tokenflow.R.string.cloud_server_needs_key
        server.hostKeyFingerprint == null -> xyz.mek030399.tokenflow.R.string.cloud_server_needs_verification
        else -> xyz.mek030399.tokenflow.R.string.cloud_server_ready
    }
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = toggle).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Cloud, null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(server.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${server.username}@${server.host}:${server.port}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = toggle) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        stringResource(xyz.mek030399.tokenflow.R.string.cloud_server_details),
                    )
                }
            }
            if (expanded) {
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ServerDetail(stringResource(xyz.mek030399.tokenflow.R.string.cloud_start_directory), server.startDirectory)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.weight(1f)) { ServerDetail(stringResource(xyz.mek030399.tokenflow.R.string.cloud_concurrency), server.maxConcurrentTasks.toString()) }
                        Box(Modifier.weight(1f)) { ServerDetail(stringResource(xyz.mek030399.tokenflow.R.string.cloud_timeout_minutes), server.defaultTimeoutMinutes.toString()) }
                    }
                    Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_host_fingerprint), style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(
                                server.hostKeyFingerprint ?: stringResource(xyz.mek030399.tokenflow.R.string.cloud_host_untrusted),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        server.hostKeyFingerprint?.let { fingerprint ->
                            IconButton(onClick = { clipboard.setText(AnnotatedString(fingerprint)) }) {
                                Icon(Icons.Outlined.ContentCopy, stringResource(xyz.mek030399.tokenflow.R.string.cloud_copy_fingerprint))
                            }
                        }
                    }
                    when {
                        probing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_testing_connection), style = MaterialTheme.typography.bodySmall)
                        }
                        diagnostic?.probe != null -> {
                            Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_connection_succeeded), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(
                                    xyz.mek030399.tokenflow.R.string.cloud_runtime_diagnostic,
                                    diagnostic.probe.helperVersion?.toString() ?: "-",
                                    diagnostic.probe.pythonVersion ?: "-",
                                    stringResource(if (diagnostic.probe.nodeAvailable == true) xyz.mek030399.tokenflow.R.string.cloud_node_available else xyz.mek030399.tokenflow.R.string.cloud_node_unavailable),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        diagnostic?.error != null -> Text(diagnostic.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = probe, enabled = probeEnabled, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                            Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_test_connection), Modifier.padding(start = 8.dp))
                        }
                        IconButton(onClick = edit, enabled = actionsEnabled) {
                            Icon(Icons.Outlined.Edit, stringResource(xyz.mek030399.tokenflow.R.string.edit))
                        }
                        IconButton(onClick = delete, enabled = actionsEnabled) {
                            Icon(Icons.Outlined.DeleteOutline, stringResource(xyz.mek030399.tokenflow.R.string.delete))
                        }
                    }
                    if (server.hostKeyFingerprint != null) {
                        TextButton(onClick = replaceHostKey, enabled = probeEnabled, modifier = Modifier.align(Alignment.End)) {
                            Icon(Icons.Outlined.Key, null, Modifier.size(18.dp))
                            Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_replace_host_key), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ServerEditor(profile: CloudServerProfile, onDismiss: () -> Unit, save: (CloudServerDraft) -> Unit) {
    var value by remember(profile.id) { mutableStateOf(profile) }
    var key by remember(profile.id) { mutableStateOf("") }
    var passphrase by remember(profile.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (profile.name.isBlank()) xyz.mek030399.tokenflow.R.string.cloud_new_server else xyz.mek030399.tokenflow.R.string.cloud_edit_server)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value.name, { value = value.copy(name = it) }, label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_name)) }, singleLine = true)
            OutlinedTextField(value.host, { value = value.copy(host = it) }, label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_host)) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value.username, { value = value.copy(username = it) }, label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_username)) }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value.port.toString(), { value = value.copy(port = it.toIntOrNull() ?: value.port) }, label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_port)) }, modifier = Modifier.weight(.55f), singleLine = true)
            }
            OutlinedTextField(value.startDirectory, { value = value.copy(startDirectory = it) }, label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_start_directory)) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value.maxConcurrentTasks.toString(), { value = value.copy(maxConcurrentTasks = it.toIntOrNull() ?: value.maxConcurrentTasks) }, label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_concurrency)) }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value.defaultTimeoutMinutes.toString(), { value = value.copy(defaultTimeoutMinutes = it.toIntOrNull() ?: value.defaultTimeoutMinutes) }, label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_timeout_minutes)) }, modifier = Modifier.weight(1f), singleLine = true)
            }
            OutlinedTextField(key, { key = it }, label = { Text(stringResource(if (profile.keyConfigured) xyz.mek030399.tokenflow.R.string.cloud_replace_private_key else xyz.mek030399.tokenflow.R.string.cloud_private_key)) }, minLines = 4)
            OutlinedTextField(passphrase, { passphrase = it }, label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_private_key_passphrase)) }, singleLine = true)
            Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_key_policy), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Button(onClick = { save(CloudServerDraft(value, key, passphrase)); key = ""; passphrase = "" }) { Text(stringResource(xyz.mek030399.tokenflow.R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(xyz.mek030399.tokenflow.R.string.cancel)) } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskList(
    state: AppUiState,
    viewModel: AppViewModel,
    selectionMode: Boolean,
    selectedTaskIds: Set<String>,
    onToggleSelection: (String) -> Unit,
    onStartSelection: (String) -> Unit,
) {
    val tasks = state.cloudTasks.filter { state.cloud.taskServerFilterId == null || it.cloudServerId == state.cloud.taskServerFilterId }
    val knownTaskIds = state.cloudTasks.mapTo(mutableSetOf(), CloudTask::id)
    val unboundFailedDeliveries = state.cloudArtifactDeliveries.filter { delivery ->
        delivery.status == CloudArtifactDeliveryStatus.FAILED &&
            (delivery.taskId == null || delivery.taskId !in knownTaskIds) &&
            (state.cloud.taskServerFilterId == null || delivery.cloudServerId == state.cloud.taskServerFilterId)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tasks.isEmpty()) Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_no_tasks))
        if (unboundFailedDeliveries.isNotEmpty()) {
            Text(
                stringResource(xyz.mek030399.tokenflow.R.string.cloud_other_failed_artifacts),
                style = MaterialTheme.typography.titleSmall,
            )
            unboundFailedDeliveries.forEach { delivery ->
                ArtifactDeliveryFailureRow(delivery, state, viewModel)
            }
            HorizontalDivider()
        }
        tasks.forEach { task ->
            val deletable = task.canDeleteLocally()
            val selected = task.id in selectedTaskIds
            val failedDeliveries = state.cloudArtifactDeliveries.filter {
                it.taskId == task.id && it.status == CloudArtifactDeliveryStatus.FAILED
            }
            ListItem(
                modifier = Modifier.fillMaxWidth().testTag("cloud_task_${task.id}").combinedClickable(
                    onClick = { if (selectionMode && deletable) onToggleSelection(task.id) },
                    onLongClick = { if (deletable) onStartSelection(task.id) },
                ),
                headlineContent = { Text(task.summary.ifBlank { task.kind }, maxLines = 2) },
                supportingContent = { Text("${task.serverName} · ${task.status.displayName()}${task.exitCode?.let { " · ${stringResource(xyz.mek030399.tokenflow.R.string.cloud_task_exit, it)}" }.orEmpty()}") },
                leadingContent = {
                    if (selectionMode) Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelection(task.id) },
                        enabled = deletable,
                    ) else Icon(Icons.Outlined.Terminal, null)
                },
                trailingContent = {
                    if (!selectionMode) Row {
                        IconButton(onClick = { viewModel.loadCloudTaskLog(task.id) }) { Icon(Icons.Outlined.InsertDriveFile, stringResource(xyz.mek030399.tokenflow.R.string.cloud_task_output)) }
                        IconButton(onClick = { viewModel.refreshCloudTask(task.id) }) { Icon(Icons.Outlined.Refresh, stringResource(xyz.mek030399.tokenflow.R.string.cloud_refresh)) }
                        if (!deletable) {
                            IconButton(onClick = { viewModel.cancelCloudTask(task.id) }) { Icon(Icons.Outlined.Cancel, stringResource(xyz.mek030399.tokenflow.R.string.cancel)) }
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                ),
            )
            failedDeliveries.forEach { delivery ->
                ArtifactDeliveryFailureRow(
                    delivery,
                    state,
                    viewModel,
                    Modifier.padding(start = 56.dp, end = 8.dp, bottom = 6.dp),
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ArtifactDeliveryFailureRow(
    delivery: CloudArtifactDelivery,
    state: AppUiState,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(delivery.displayName, style = MaterialTheme.typography.labelMedium)
            Text(
                delivery.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = { viewModel.retryCloudArtifactDelivery(delivery.id) },
            enabled = delivery.id !in state.cloud.retryingArtifactDeliveryIds,
        ) {
            if (delivery.id in state.cloud.retryingArtifactDeliveryIds) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
            }
            Text(stringResource(xyz.mek030399.tokenflow.R.string.retry), Modifier.padding(start = 6.dp))
        }
    }
}

private fun CloudTask.canDeleteLocally(): Boolean =
    status != CloudTaskStatus.UNKNOWN && status != CloudTaskStatus.QUEUED && status != CloudTaskStatus.RUNNING

@Composable
private fun FileBrowser(state: AppUiState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pathInput by remember(state.cloud.selectedServerId, state.cloud.currentPath) { mutableStateOf(state.cloud.currentPath) }
    var newFolder by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<CloudPathTarget?>(null) }
    var pendingDownloadTarget by remember { mutableStateOf<CloudPathTarget?>(null) }
    var pendingUploadTarget by remember { mutableStateOf<CloudDirectoryTarget?>(null) }
    LaunchedEffect(state.cloud.selectedServerId) {
        newFolder = null
        deleteTarget = null
        pendingDownloadTarget = null
        pendingUploadTarget = null
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pendingUploadTarget
        pendingUploadTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            var pendingInput: java.io.InputStream? = null
            try {
                val pair = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "upload"
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Unable to open the selected file")
                    name to input
                }
                pendingInput = pair.second
                viewModel.uploadCloudFile(
                    fileName = pair.first,
                    input = pair.second,
                    expectedServerId = target.serverId,
                    expectedDirectory = target.directory,
                )
                pendingInput = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                viewModel.reportCloudFileError(error)
            } finally {
                pendingInput?.let { input -> runCatching { input.close() } }
            }
        }
    }
    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val target = pendingDownloadTarget
        pendingDownloadTarget = null
        if (uri != null && target != null) {
            scope.launch {
                var pendingOutput: java.io.OutputStream? = null
                try {
                    pendingOutput = withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)
                            ?: throw IOException("Unable to open the selected destination")
                    }
                    viewModel.downloadCloudFile(
                        target.path,
                        requireNotNull(pendingOutput),
                        expectedServerId = target.serverId,
                    )
                    pendingOutput = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    viewModel.reportCloudFileError(error)
                } finally {
                    pendingOutput?.let { output -> runCatching { output.close() } }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(pathInput, { pathInput = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_remote_path)) })
            IconButton(onClick = { viewModel.loadCloudFiles(pathInput) }) { Icon(Icons.Outlined.Refresh, stringResource(xyz.mek030399.tokenflow.R.string.cloud_open_path)) }
            IconButton(onClick = {
                val serverId = state.cloud.selectedServerId ?: return@IconButton
                pendingUploadTarget = CloudDirectoryTarget(serverId, state.cloud.currentPath)
                upload.launch(arrayOf("*/*"))
            }) { Icon(Icons.Outlined.Upload, stringResource(xyz.mek030399.tokenflow.R.string.cloud_upload)) }
            IconButton(onClick = { newFolder = "" }) { Icon(Icons.Outlined.CreateNewFolder, stringResource(xyz.mek030399.tokenflow.R.string.cloud_new_folder)) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            state.cloud.files.forEach { file ->
                ListItem(
                    headlineContent = { Text(file.name) },
                    supportingContent = { Text(if (file.directory) stringResource(xyz.mek030399.tokenflow.R.string.cloud_directory) else stringResource(xyz.mek030399.tokenflow.R.string.cloud_file_bytes, file.size)) },
                    leadingContent = { Icon(if (file.directory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile, null) },
                    modifier = Modifier.clickable { if (file.directory) viewModel.loadCloudFiles(file.path) else viewModel.readCloudText(file.path) },
                    trailingContent = { Row {
                        if (!file.directory) IconButton(onClick = {
                            val serverId = state.cloud.selectedServerId ?: return@IconButton
                            pendingDownloadTarget = CloudPathTarget(serverId, file.path)
                            download.launch(file.name)
                        }) { Icon(Icons.Outlined.Download, stringResource(xyz.mek030399.tokenflow.R.string.cloud_download)) }
                        IconButton(onClick = {
                            state.cloud.selectedServerId?.let { deleteTarget = CloudPathTarget(it, file.path) }
                        }) { Icon(Icons.Outlined.DeleteOutline, stringResource(xyz.mek030399.tokenflow.R.string.delete)) }
                    } },
                )
            }
        }
    }
    state.cloud.textPath?.let { textPath ->
        AlertDialog(
            onDismissRequest = viewModel::closeCloudText,
            title = { Text(textPath.substringAfterLast('/')) },
            text = { OutlinedTextField(state.cloud.textContent, viewModel::updateCloudText, minLines = 12, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                Button(onClick = viewModel::saveCloudText, enabled = !state.cloud.savingText) {
                    if (state.cloud.savingText) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(xyz.mek030399.tokenflow.R.string.save))
                    }
                }
            },
            dismissButton = { TextButton(onClick = viewModel::closeCloudText) { Text(stringResource(xyz.mek030399.tokenflow.R.string.close)) } },
        )
    }
    newFolder?.let { value ->
        AlertDialog(
            onDismissRequest = { newFolder = null },
            title = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_new_directory)) },
            text = { OutlinedTextField(value, { newFolder = it }, label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_name)) }, singleLine = true) },
            confirmButton = { Button(onClick = {
                state.cloud.selectedServerId?.let { serverId ->
                    viewModel.cloudFileOperation(
                        "mkdir",
                        mapOf("path" to state.cloud.currentPath.trimEnd('/') + "/" + value.replace('/', '_')),
                        expectedServerId = serverId,
                    )
                }
                newFolder = null
            }) { Text(stringResource(xyz.mek030399.tokenflow.R.string.create)) } },
            dismissButton = { TextButton(onClick = { newFolder = null }) { Text(stringResource(xyz.mek030399.tokenflow.R.string.cancel)) } },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_permanent_delete)) }, text = { Text(target.path) },
            confirmButton = { Button(onClick = {
                viewModel.cloudFileOperation("delete", mapOf("path" to target.path), expectedServerId = target.serverId)
                deleteTarget = null
            }) { Text(stringResource(xyz.mek030399.tokenflow.R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(xyz.mek030399.tokenflow.R.string.cancel)) } },
        )
    }
}

private data class CloudPathTarget(val serverId: String, val path: String)
private data class CloudDirectoryTarget(val serverId: String, val directory: String)

@Composable
private fun McpList(state: AppUiState, viewModel: AppViewModel, edit: (CloudMcpServer) -> Unit) {
    val items = state.cloudMcpServers.filter { it.cloudServerId == state.cloud.selectedServerId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_mcp_notice), style = MaterialTheme.typography.bodySmall)
        items.forEach { mcp ->
            val mutating = mcp.id in state.cloud.mutatingMcpServerIds
            ListItem(
                headlineContent = { Text(mcp.name) },
                supportingContent = { Text(if (mcp.transport == CloudMcpTransport.STDIO) mcp.command else mcp.url) },
                leadingContent = { Icon(Icons.Outlined.Terminal, null) },
                trailingContent = { Row {
                    if (state.cloud.testingMcpServerId == mcp.id) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(3.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { viewModel.testCloudMcpServer(mcp) }, enabled = !mutating) {
                            Icon(Icons.Outlined.Refresh, stringResource(xyz.mek030399.tokenflow.R.string.cloud_mcp_test))
                        }
                    }
                    IconButton(onClick = { edit(mcp) }, enabled = !mutating && state.cloud.testingMcpServerId != mcp.id) {
                        Icon(Icons.Outlined.Edit, stringResource(xyz.mek030399.tokenflow.R.string.edit))
                    }
                    IconButton(onClick = { viewModel.deleteCloudMcpServer(mcp.id) }, enabled = !mutating) {
                        Icon(Icons.Outlined.DeleteOutline, stringResource(xyz.mek030399.tokenflow.R.string.delete))
                    }
                } },
            )
        }
    }
}

@Composable
private fun McpEditor(
    initial: CloudMcpServer,
    onDismiss: () -> Unit,
    save: (CloudMcpServer, Map<String, String>, Map<String, String>) -> Unit,
) {
    var value by remember(initial.id) { mutableStateOf(initial) }
    var arguments by remember(initial.id) { mutableStateOf(initial.arguments.joinToString("\n")) }
    var environment by remember(initial.id) { mutableStateOf("") }
    var headers by remember(initial.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_mcp_server)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value.name, { value = value.copy(name = it) }, label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_name)) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudMcpTransport.entries.forEach { transport ->
                    FilterChip(selected = value.transport == transport, onClick = { value = value.copy(transport = transport) }, label = { Text(transport.name) })
                }
            }
            if (value.transport == CloudMcpTransport.STDIO) {
                OutlinedTextField(value.command, { value = value.copy(command = it) }, label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_executable)) }, singleLine = true)
                OutlinedTextField(arguments, { arguments = it }, label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_arguments_lines)) }, minLines = 3)
                OutlinedTextField(value.workingDirectory, { value = value.copy(workingDirectory = it) }, label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_working_directory)) }, singleLine = true)
                OutlinedTextField(environment, { environment = it }, label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_secret_environment)) }, minLines = 3)
            } else {
                OutlinedTextField(value.url, { value = value.copy(url = it) }, label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_streamable_http_url)) }, singleLine = true)
                OutlinedTextField(headers, { headers = it }, label = { Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_secret_headers)) }, minLines = 3)
            }
            Text(stringResource(xyz.mek030399.tokenflow.R.string.cloud_secrets_notice), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Button(onClick = {
            val env = parseSecretLines(environment)
            val headerMap = parseSecretLines(headers)
            save(value.copy(
                arguments = arguments.lines().filter(String::isNotBlank),
                environmentNames = (value.environmentNames + env.keys).distinct(),
                headerNames = (value.headerNames + headerMap.keys).distinct(),
            ), env, headerMap)
        }) { Text(stringResource(xyz.mek030399.tokenflow.R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(xyz.mek030399.tokenflow.R.string.cancel)) } },
    )
}

private fun parseSecretLines(value: String): Map<String, String> = value.lines().mapNotNull { line ->
    val name = line.substringBefore('=', "").trim()
    val secret = line.substringAfter('=', "")
    if (name.isBlank()) null else name to secret
}.toMap()

@Composable
private fun CloudSection.displayName() = stringResource(when (this) {
    CloudSection.SERVERS -> xyz.mek030399.tokenflow.R.string.cloud_servers_tab
    CloudSection.TASKS -> xyz.mek030399.tokenflow.R.string.cloud_tasks_tab
    CloudSection.FILES -> xyz.mek030399.tokenflow.R.string.cloud_files_tab
    CloudSection.MCP -> xyz.mek030399.tokenflow.R.string.cloud_mcp_server
})

@Composable
private fun CloudTaskStatus.displayName() = stringResource(when (this) {
    CloudTaskStatus.QUEUED -> xyz.mek030399.tokenflow.R.string.cloud_task_queued
    CloudTaskStatus.RUNNING -> xyz.mek030399.tokenflow.R.string.cloud_task_running
    CloudTaskStatus.SUCCEEDED -> xyz.mek030399.tokenflow.R.string.cloud_task_succeeded
    CloudTaskStatus.FAILED -> xyz.mek030399.tokenflow.R.string.cloud_task_failed
    CloudTaskStatus.CANCELLED -> xyz.mek030399.tokenflow.R.string.cloud_task_cancelled
    CloudTaskStatus.TIMED_OUT -> xyz.mek030399.tokenflow.R.string.cloud_task_timed_out
    CloudTaskStatus.UNKNOWN -> xyz.mek030399.tokenflow.R.string.cloud_task_unknown
})
