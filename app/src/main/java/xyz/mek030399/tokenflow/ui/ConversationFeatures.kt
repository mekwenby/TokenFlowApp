package xyz.mek030399.tokenflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.mek030399.tokenflow.R
import xyz.mek030399.tokenflow.data.*
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageSearchScreen(state: AppUiState, viewModel: AppViewModel, showBack: Boolean) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.search_messages)) }, navigationIcon = {
        if (showBack) IconButton(onClick = { viewModel.openScreen(AppScreen.CHAT) }) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
        }
    }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            OutlinedTextField(state.messageSearchQuery, { viewModel.searchMessages(it) },
                placeholder = { Text(stringResource(R.string.search_messages)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("message_search_input"))
            state.messageSearchError?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("message_search_results"),
                contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.messageSearchPage.items, key = { it.messageId }) { hit ->
                    ElevatedCard(Modifier.fillMaxWidth().clickable { viewModel.openSearchResult(hit) }.testTag("search_hit_${hit.messageId}")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(hit.conversationTitle.ifBlank { stringResource(R.string.new_chat) }, style = MaterialTheme.typography.titleSmall)
                            val role = stringResource(if (hit.role == "user") R.string.search_user else R.string.search_assistant)
                            val archived = if (hit.archivedAt != null) " · ${stringResource(R.string.search_archived)}" else ""
                            Text("$role · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(hit.createdAt))}$archived",
                                style = MaterialTheme.typography.labelSmall)
                            val color = MaterialTheme.colorScheme.secondaryContainer
                            val literal = state.messageSearchQuery.trim()
                            val highlighted = buildAnnotatedString {
                                append(hit.snippet)
                                if (literal.isNotEmpty()) {
                                    var start = hit.snippet.indexOf(literal, ignoreCase = true)
                                    while (start >= 0) {
                                        addStyle(SpanStyle(background = color), start, start + literal.length)
                                        start = hit.snippet.indexOf(literal, start + literal.length, ignoreCase = true)
                                    }
                                }
                            }
                            Text(highlighted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (state.messageSearchPage.items.isEmpty() && !state.messageSearchBusy && state.messageSearchQuery.isNotBlank()) item {
                    Text(stringResource(R.string.search_results_empty), Modifier.padding(12.dp))
                }
                if (state.messageSearchBusy) item { CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp)) }
                else if (state.messageSearchPage.nextCursor != null) item {
                    TextButton(onClick = { viewModel.searchMessages(state.messageSearchQuery, true) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.search_more))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConversationFeatureDialogs(state: AppUiState, viewModel: AppViewModel) {
    if (state.editingQuestion != null) QuestionEditDialog(state, viewModel)
    if (state.knowledgeScopeOpen) KnowledgeScopeDialog(state, viewModel)
    if (state.contextOpen) ContextManagementDialog(state, viewModel)
    if (state.incomingShare != null && state.shareVisible && state.phase != AppPhase.LOADING) ShareImportDialog(state, viewModel)
    else if (state.shareBusy && state.incomingShare == null) AlertDialog(
        onDismissRequest = {}, title = { Text(stringResource(R.string.share_capture)) },
        text = { CircularProgressIndicator() }, confirmButton = {},
    )
}

@Composable
private fun ContextManagementDialog(state: AppUiState, viewModel: AppViewModel) {
    val current = state.activeConversation?.contextPolicy ?: ContextPolicy()
    var policy by remember(state.activeConversationId, current) { mutableStateOf(current) }
    var rounds by remember(policy.recentRounds) { mutableStateOf(policy.recentRounds.toString()) }
    val summary = state.contextCandidate ?: state.contextPreview?.summary
    var summaryText by remember(summary) { mutableStateOf(summary?.body.orEmpty()) }
    var content by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = viewModel::closeContextManager, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 760.dp).fillMaxWidth().fillMaxHeight(0.94f).padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.context_management), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = viewModel::closeContextManager) { Text(stringResource(R.string.close)) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("context_management"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ContextMode.entries.forEach { mode ->
                        val label = when (mode) { ContextMode.FULL -> R.string.context_full; ContextMode.RECENT -> R.string.context_recent; ContextMode.SUMMARY -> R.string.context_summary }
                        Row(Modifier.fillMaxWidth().clickable(enabled = !state.contextBusy) {
                            policy = policy.copy(mode = mode, autoCompact = policy.autoCompact && mode == ContextMode.SUMMARY)
                        }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(policy.mode == mode, onClick = { policy = policy.copy(mode = mode, autoCompact = policy.autoCompact && mode == ContextMode.SUMMARY) }, enabled = !state.contextBusy)
                            Text(stringResource(label))
                        }
                    }
                    if (policy.mode != ContextMode.FULL) OutlinedTextField(rounds, { value ->
                        rounds = value.filter(Char::isDigit).take(2)
                        rounds.toIntOrNull()?.takeIf { it in 1..50 }?.let { policy = policy.copy(recentRounds = it) }
                    }, label = { Text(stringResource(R.string.context_rounds)) }, singleLine = true, enabled = !state.contextBusy, modifier = Modifier.fillMaxWidth())
                    if (policy.mode == ContextMode.SUMMARY) Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(policy.autoCompact, { policy = policy.copy(autoCompact = it) }, enabled = !state.contextBusy)
                        Text(stringResource(R.string.auto_compress), Modifier.padding(start = 8.dp))
                    }
                    Text(stringResource(R.string.context_summary_call_notice), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { viewModel.saveContextPolicy(policy) }, enabled = !state.contextBusy && rounds.toIntOrNull() in 1..50) {
                        Text(stringResource(R.string.save))
                    }
                    state.contextError?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
                    if (state.contextBusy) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.loading))
                        TextButton(onClick = viewModel::closeContextManager) { Text(stringResource(R.string.cancel)) }
                    }
                    state.contextPreview?.let { preview ->
                        HorizontalDivider()
                        Text(stringResource(R.string.context_estimate, preview.estimatedInputTokens, preview.outputReserve))
                        val budget = preview.inputBudget
                        if (budget != null && budget > 0) Text(stringResource(R.string.context_capacity_value,
                            preview.contextWindowTokens ?: 0, budget, (100.0 * preview.estimatedInputTokens / budget).toInt()))
                        else Text(stringResource(R.string.context_capacity_unknown))
                        Text(stringResource(R.string.context_estimate_notice), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { viewModel.closeContextManager(); viewModel.openScreen(AppScreen.PROVIDERS) }) {
                            Text(stringResource(R.string.context_capacity))
                        }
                        OutlinedButton(onClick = viewModel::refreshContextPreview, enabled = !state.contextBusy) { Text(stringResource(R.string.context_refresh)) }
                        TextButton(onClick = { content = preview.systemPrompt }) { Text(stringResource(R.string.context_system)) }
                        Text(stringResource(R.string.context_preview_messages, preview.messages.size))
                        preview.messages.forEachIndexed { index, message ->
                            TextButton(onClick = { content = previewText(message) }) {
                                Text("${index + 1} · ${if (message.role == "user") stringResource(R.string.search_user) else stringResource(R.string.search_assistant)} · ${previewText(message).take(80)}")
                            }
                        }
                        if (preview.tools.isNotEmpty()) TextButton(onClick = {
                            content = preview.tools.joinToString("\n\n") { "${it.name}\n${it.description}\n${it.parameters}" }
                        }) { Text(stringResource(R.string.context_tools, preview.tools.size)) }
                    }
                    OutlinedButton(onClick = viewModel::generateContextSummary,
                        enabled = !state.contextBusy && state.contextPreview?.contextWindowTokens != null,
                        modifier = Modifier.testTag("context_generate_summary")) { Text(stringResource(R.string.context_generate_summary)) }
                    summary?.let {
                        Text(stringResource(R.string.context_summary_usage, it.usage.inputTokens, it.usage.outputTokens), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(summaryText, { summaryText = it }, label = { Text(stringResource(R.string.context_summary_editor)) },
                            modifier = Modifier.fillMaxWidth().testTag("context_summary_editor"), minLines = 4, maxLines = 12, enabled = !state.contextBusy)
                        Button(onClick = { viewModel.applyContextSummary(summaryText) }, enabled = !state.contextBusy && summaryText.isNotBlank()) {
                            Text(stringResource(R.string.context_apply_summary))
                        }
                    }
                    OutlinedButton(onClick = viewModel::restoreFullContext, enabled = !state.contextBusy) { Text(stringResource(R.string.context_restore)) }
                }
            }
        }
    }
    content?.let { full ->
        Dialog(onDismissRequest = { content = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.widthIn(max = 900.dp).fillMaxWidth().fillMaxHeight(0.94f).padding(12.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp)) {
                    TextButton(onClick = { content = null }) { Text(stringResource(R.string.back)) }
                    SelectionContainer { LazyColumn { items(full.chunked(4096)) { Text(it, style = MaterialTheme.typography.bodyMedium) } } }
                }
            }
        }
    }
}

private fun previewText(message: CanonicalMessage): String = message.contentParts().joinToString("\n\n") { part ->
    when (part) {
        is CanonicalContentPart.Text -> part.text
        is CanonicalContentPart.Document -> "${part.fileName}\n${part.text}"
        is CanonicalContentPart.Image -> "${part.mimeType} · ${part.width ?: "?"} × ${part.height ?: "?"}"
    }
}

@Composable
private fun ShareImportDialog(state: AppUiState, viewModel: AppViewModel) {
    val share = state.incomingShare ?: return
    var target by remember(share.id) { mutableStateOf("new") }
    var operation by remember(share.id) { mutableIntStateOf(0) }
    val translationLanguage = stringResource(R.string.share_translation_language)
    val instruction = when (operation) {
        1 -> stringResource(R.string.share_summary_prompt)
        2 -> stringResource(R.string.share_translation_prompt, translationLanguage)
        3 -> stringResource(R.string.share_question_prompt)
        else -> ""
    }
    AlertDialog(onDismissRequest = { if (!state.shareBusy) viewModel.cancelShare() },
        modifier = Modifier.testTag("share_import_dialog"), title = { Text(stringResource(R.string.share_import)) },
        text = {
            Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(share.text, viewModel::editSharedText, label = { Text(stringResource(R.string.share_content)) },
                    minLines = 2, maxLines = 6, enabled = !state.shareBusy, modifier = Modifier.fillMaxWidth().testTag("share_content_input"))
                share.files.forEach { file ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.bodySmall)
                            file.error?.let { Text(stringResource(when (it) {
                                "many" -> R.string.share_file_many; "uri" -> R.string.share_file_uri; "type" -> R.string.share_file_type
                                "size" -> R.string.share_file_size; "empty" -> R.string.share_file_empty; else -> R.string.share_file_read
                            }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                        TextButton(onClick = { viewModel.removeSharedFile(file.id) }, enabled = !state.shareBusy) { Text(stringResource(R.string.remove)) }
                    }
                }
                if (!state.hasModels) {
                    Text(stringResource(R.string.share_missing_model))
                    TextButton(onClick = viewModel::configureSharedModel) { Text(stringResource(R.string.configure_model)) }
                } else {
                    Text(stringResource(R.string.share_destination), style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(target == "new", { target = "new" }); Text(stringResource(R.string.share_new))
                    }
                    state.conversations.filter { state.generations[it.id]?.active != true }.forEach { conversation ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !state.shareBusy) { target = "conversation:${conversation.id}" }
                            .testTag("share_target_${conversation.id}"), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(target == "conversation:${conversation.id}", { target = "conversation:${conversation.id}" })
                            Text(conversation.title.ifBlank { stringResource(R.string.new_chat) }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    state.agents.forEach { agent -> Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(target == "agent:${agent.id}", { target = "agent:${agent.id}" })
                        Text("${stringResource(R.string.share_agent)} · ${agent.name}", style = MaterialTheme.typography.bodySmall)
                    } }
                    listOf(R.string.share_original, R.string.share_summarize, R.string.share_translate, R.string.share_ask).forEachIndexed { index, label ->
                        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(operation == index, { operation = index }); Text(stringResource(label)) }
                    }
                    Text(stringResource(R.string.share_draft_notice), style = MaterialTheme.typography.bodySmall)
                }
                if (state.shareBusy) CircularProgressIndicator(Modifier.size(24.dp))
            }
        }, confirmButton = {
            TextButton(onClick = {
                viewModel.applyShare(target.takeIf { it.startsWith("conversation:") }?.removePrefix("conversation:"),
                    target.takeIf { it.startsWith("agent:") }?.removePrefix("agent:"), instruction)
            }, enabled = !state.shareBusy && state.hasModels && share.files.none { it.error != null } &&
                (share.text.isNotBlank() || share.files.isNotEmpty())) { Text(stringResource(R.string.share_import_draft)) }
        }, dismissButton = { TextButton(onClick = viewModel::cancelShare, enabled = !state.shareBusy) { Text(stringResource(R.string.cancel)) } })
}
