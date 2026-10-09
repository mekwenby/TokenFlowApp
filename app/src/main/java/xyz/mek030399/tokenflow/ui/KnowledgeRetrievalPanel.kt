package xyz.mek030399.tokenflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.mek030399.tokenflow.R
import xyz.mek030399.tokenflow.data.KnowledgeScope
import xyz.mek030399.tokenflow.data.KnowledgeScopeMode

@Composable
internal fun KnowledgeRetrievalPanel(state: AppUiState, viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val scope = state.config.knowledgeScope
    val selectedCount = state.knowledgeDocuments.count { it.status == "ready" && scope.includes(it.id) }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.knowledge_scope_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.knowledge_scope_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = viewModel::openKnowledgeScope, modifier = Modifier.fillMaxWidth().testTag("knowledge_scope_open")) {
                Text(if (scope.mode == KnowledgeScopeMode.ALL) stringResource(R.string.knowledge_scope_all_short)
                else stringResource(R.string.knowledge_scope_selected_short, selectedCount), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun KnowledgeScopeDialog(state: AppUiState, viewModel: AppViewModel) {
    val current = state.config.knowledgeScope
    val documents = state.knowledgeDocuments.filter { it.status == "ready" }
    var mode by rememberSaveable(state.activeConversationId, current.mode) { mutableStateOf(current.mode) }
    var ids by rememberSaveable(state.activeConversationId, current, stateSaver = listSaver<Set<String>, String>(
        save = { it.toList() }, restore = { it.toSet() },
    )) { mutableStateOf(current.documentIds.toSet()) }
    val blocked = state.activeGeneration?.active == true || state.contextBusy || state.activeConversation?.activeOperation?.isNotBlank() == true
    Dialog(onDismissRequest = viewModel::closeKnowledgeScope, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(.85f).padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).testTag("knowledge_scope_dialog"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.knowledge_scope_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.knowledge_scope_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                KnowledgeScopeMode.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !blocked) { mode = option }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(mode == option, onClick = { mode = option }, enabled = !blocked,
                            modifier = Modifier.testTag(if (option == KnowledgeScopeMode.ALL) "knowledge_scope_all" else "knowledge_scope_selected"))
                        Text(stringResource(if (option == KnowledgeScopeMode.ALL) R.string.knowledge_scope_all else R.string.knowledge_scope_selected))
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (documents.isEmpty()) item {
                        Text(stringResource(R.string.knowledge_scope_no_documents), Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(documents, key = { it.id }) { document ->
                        val checked = mode == KnowledgeScopeMode.ALL || document.id in ids
                        val selectable = mode == KnowledgeScopeMode.SELECTED && !blocked
                        Row(Modifier.fillMaxWidth().clickable(enabled = selectable) {
                            ids = if (document.id in ids) ids - document.id else ids + document.id
                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, { selected -> ids = if (selected) ids + document.id else ids - document.id }, enabled = selectable,
                                modifier = Modifier.testTag("knowledge_scope_document_${document.id}"))
                            Text(document.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (mode == KnowledgeScopeMode.SELECTED && documents.none { it.id in ids }) {
                    Text(stringResource(R.string.knowledge_scope_empty_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (blocked) Text(stringResource(R.string.knowledge_scope_busy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = viewModel::closeKnowledgeScope) { Text(stringResource(R.string.cancel)) }
                    FilledTonalButton(onClick = {
                        viewModel.saveKnowledgeScope(KnowledgeScope(mode, ids.filter { id -> documents.any { it.id == id } }))
                    }, enabled = !blocked, modifier = Modifier.testTag("knowledge_scope_save")) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
}
