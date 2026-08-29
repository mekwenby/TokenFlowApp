package xyz.mek030399.tokenflow.ui

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.mek030399.tokenflow.BuildConfig
import xyz.mek030399.tokenflow.R
import xyz.mek030399.tokenflow.ui.theme.LocalTokenFlowDarkTheme
import xyz.mek030399.tokenflow.data.AgentProfile
import xyz.mek030399.tokenflow.data.KnowledgeDocumentPreview
import xyz.mek030399.tokenflow.data.KnowledgeImportSource
import xyz.mek030399.tokenflow.data.Note
import xyz.mek030399.tokenflow.data.markdownNoteFileName
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

@Composable
internal fun WorkspaceScreen(state: AppUiState, viewModel: AppViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val persistent = maxWidth >= 600.dp
        val railWidth = if (maxWidth >= 1000.dp) 280.dp else 264.dp
        Row(Modifier.fillMaxSize()) {
            if (persistent) {
                WorkspaceRail(state.screen, viewModel, Modifier.width(railWidth))
                HorizontalDivider(Modifier.fillMaxHeight().width(1.dp))
            }
            Box(Modifier.weight(1f)) {
                when (state.screen) {
                    AppScreen.BOOKMARKS -> BookmarksScreen(state, viewModel, !persistent)
                    AppScreen.NOTES -> NotesScreen(state, viewModel, !persistent)
                    AppScreen.AGENTS -> AgentsScreen(state, viewModel, !persistent)
                    AppScreen.KNOWLEDGE -> KnowledgeScreen(state, viewModel, !persistent)
                    AppScreen.INFINITE_CLOUD -> InfiniteCloudScreen(state, viewModel, !persistent)
                    AppScreen.ABOUT -> AboutScreen(viewModel, !persistent)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
internal fun AdaptiveConfigurationShell(
    state: AppUiState,
    viewModel: AppViewModel,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val railWidth = if (maxWidth >= 1000.dp) 280.dp else 264.dp
        if (maxWidth < 600.dp) content()
        else Row(Modifier.fillMaxSize()) {
            WorkspaceRail(state.screen, viewModel, Modifier.width(railWidth))
            HorizontalDivider(Modifier.fillMaxHeight().width(1.dp))
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun WorkspaceRail(selected: AppScreen, viewModel: AppViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp, 8.dp, 4.dp, 20.dp)) {
            AppLogo(34.dp)
            Text(stringResource(R.string.app_name), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        val destinations = listOf(
            Triple(AppScreen.CHAT, Icons.Outlined.ChatBubbleOutline, R.string.conversations),
            Triple(AppScreen.BOOKMARKS, Icons.Outlined.Bookmarks, R.string.bookmarks),
            Triple(AppScreen.NOTES, Icons.Outlined.NoteAlt, R.string.notes),
            Triple(AppScreen.AGENTS, Icons.Outlined.SmartToy, R.string.agents),
            Triple(AppScreen.KNOWLEDGE, Icons.Outlined.FolderOpen, R.string.knowledge),
            Triple(AppScreen.INFINITE_CLOUD, Icons.Outlined.Cloud, R.string.infinite_cloud),
            Triple(AppScreen.GLOBAL_SETTINGS, Icons.Outlined.Settings, R.string.global_settings),
            Triple(AppScreen.PROVIDERS, Icons.Outlined.Hub, R.string.providers_models),
            Triple(AppScreen.EXA, Icons.Outlined.Search, R.string.exa_search),
            Triple(AppScreen.TRANSFER, Icons.Outlined.ImportExport, R.string.import_export),
            Triple(AppScreen.ABOUT, Icons.Outlined.Info, R.string.about),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag(UiTestTags.WORKSPACE_DESTINATIONS),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            items(destinations, key = { it.first }) { (screen, icon, label) ->
                Surface(
                    color = if (selected == screen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clickable { viewModel.openScreen(screen) }
                        .testTag(UiTestTags.workspaceDestination(screen)),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, Modifier.size(20.dp))
                        Text(stringResource(label), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Text(stringResource(R.string.all_content_local), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceScaffold(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    action: (@Composable () -> Unit)? = null,
    compactTitle: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = if (compactTitle) {
                            MaterialTheme.typography.titleLarge.copy(fontSize = 11.sp, lineHeight = 14.sp)
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (compactTitle) 1 else Int.MAX_VALUE,
                        overflow = if (compactTitle) TextOverflow.Ellipsis else TextOverflow.Clip,
                        modifier = if (compactTitle) Modifier.testTag(UiTestTags.NOTE_READER_TITLE) else Modifier,
                    )
                },
                navigationIcon = { if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
                actions = { action?.invoke() },
            )
        },
        content = content,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarksScreen(state: AppUiState, viewModel: AppViewModel, showBack: Boolean) {
    var search by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val items = state.bookmarks.filter { search.isBlank() || it.content.contains(search, true) || it.conversationTitle.contains(search, true) }
    WorkspaceScaffold(stringResource(R.string.bookmarks), showBack, { viewModel.openScreen(AppScreen.CHAT) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            OutlinedTextField(search, { search = it }, placeholder = { Text(stringResource(R.string.search_saved)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).align(Alignment.CenterHorizontally))
            if (selected.isNotEmpty()) SelectionToolbar(
                selectedCount = selected.size,
                allVisibleSelected = items.isNotEmpty() && items.all { it.messageId in selected },
                onSelectAll = {
                    val visible = items.map { it.messageId }.toSet()
                    selected = if (visible.isNotEmpty() && visible.all { it in selected }) selected - visible else selected + visible
                },
                onDelete = { confirmDelete = true },
                onClear = { selected = emptySet() },
                modifier = Modifier.fillMaxWidth().widthIn(max = 820.dp).align(Alignment.CenterHorizontally),
            )
            if (items.isEmpty()) EmptyWorkspace(Icons.Outlined.Bookmarks, stringResource(R.string.empty_bookmarks), Modifier.weight(1f))
            else LazyColumn(Modifier.weight(1f).fillMaxWidth().widthIn(max = 820.dp).align(Alignment.CenterHorizontally), contentPadding = PaddingValues(vertical = 12.dp)) {
                items(items, key = { it.id }) { item ->
                    val isSelected = item.messageId in selected
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (selected.isEmpty()) viewModel.openBookmark(item)
                                    else selected = if (isSelected) selected - item.messageId else selected + item.messageId
                                },
                                onLongClick = { selected = if (isSelected) selected - item.messageId else selected + item.messageId },
                            )
                            .padding(vertical = 4.dp)
                            .testTag(UiTestTags.bookmarkItem(item.messageId)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item.conversationTitle.ifBlank { stringResource(R.string.new_chat) },
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (selected.isNotEmpty()) {
                            Checkbox(isSelected, { checked -> selected = if (checked) selected + item.messageId else selected - item.messageId })
                        } else {
                            IconButton(
                                onClick = { viewModel.toggleBookmark(item.messageId) },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(Icons.Outlined.BookmarkRemove, stringResource(R.string.remove_bookmark), Modifier.size(20.dp))
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (confirmDelete) DeleteSelectedDialog(selected.size, { confirmDelete = false }) {
        viewModel.deleteBookmarks(selected)
        selected = emptySet()
        confirmDelete = false
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotesScreen(state: AppUiState, viewModel: AppViewModel, showBack: Boolean) {
    var search by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Note?>(null) }
    var reading by remember { mutableStateOf<Note?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var knowledgeImportTarget by remember { mutableStateOf<Note?>(null) }
    var summaryTarget by remember { mutableStateOf<Note?>(null) }
    var rewritePrompt by rememberSaveable { mutableStateOf("") }
    val importMarkdownLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importMarkdownNote(it.toString()) }
    }
    val exportMarkdownLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        viewModel.exportMarkdownNote(uri?.toString())
    }
    val currentReading = reading?.let { selectedNote ->
        state.notes.firstOrNull { it.id == selectedNote.id } ?: selectedNote
    }
    val navigateBack = {
        when {
            editing != null -> editing = null
            reading != null -> reading = null
            else -> viewModel.openScreen(AppScreen.CHAT)
        }
    }
    BackHandler(enabled = showBack || editing != null || currentReading != null) { navigateBack() }
    val title = when {
        editing != null -> stringResource(if (reading == null) R.string.new_note else R.string.edit)
        currentReading != null -> currentReading.title.ifBlank { stringResource(R.string.notes) }
        else -> stringResource(R.string.notes)
    }
    val toolbarAction: (@Composable () -> Unit)? = when {
        editing != null -> null
        currentReading != null -> {{
            val importing = state.noteImportingId == currentReading.id
            val summarizing = state.noteSummarizingId == currentReading.id
            val importBusy = state.noteImportingId != null
            val summaryBusy = state.noteSummarizingId != null
            val noteKnowledgeDocuments = state.knowledgeDocuments.filter { it.sourceNoteId == currentReading.id }
            val savedToKnowledge = noteKnowledgeDocuments.any { it.status == "ready" || it.status == "indexing" }
            val failedKnowledgeImport = noteKnowledgeDocuments.any { it.status == "error" }
            val importDescription = stringResource(
                when {
                    importing -> R.string.importing_note
                    savedToKnowledge -> R.string.note_saved_to_knowledge
                    failedKnowledgeImport -> R.string.retry_note_to_knowledge
                    else -> R.string.import_note_to_knowledge
                },
            )
            val summaryDescription = stringResource(if (summarizing) R.string.summarizing_note else R.string.summarize_note)
            val exportDescription = stringResource(
                if (state.noteFileExporting) R.string.exporting_markdown_note else R.string.export_markdown_note,
            )
            Row {
                IconButton(
                    onClick = { knowledgeImportTarget = currentReading },
                    enabled = !importBusy && !summaryBusy && !savedToKnowledge,
                    modifier = Modifier
                        .testTag("note_reader_import_knowledge")
                        .semantics { contentDescription = importDescription },
                ) {
                    when {
                        importing -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        savedToKnowledge -> Icon(Icons.Outlined.CheckCircle, null)
                        else -> Icon(Icons.Outlined.Hub, null)
                    }
                }
                IconButton(
                    onClick = {
                        rewritePrompt = ""
                        summaryTarget = currentReading
                    },
                    enabled = !summaryBusy && !importBusy,
                    modifier = Modifier
                        .testTag("note_reader_summarize")
                        .semantics { contentDescription = summaryDescription },
                ) {
                    if (summarizing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.AutoAwesome, null)
                    }
                }
                IconButton(
                    onClick = {
                        viewModel.prepareMarkdownNoteExport(currentReading)
                        exportMarkdownLauncher.launch(markdownNoteFileName(currentReading.title))
                    },
                    enabled = !summarizing && !state.noteFileExporting,
                    modifier = Modifier
                        .testTag(UiTestTags.NOTE_EXPORT_MARKDOWN)
                        .semantics { contentDescription = exportDescription },
                ) {
                    if (state.noteFileExporting) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.FileDownload, null)
                    }
                }
                IconButton(
                    onClick = { editing = currentReading },
                    enabled = !summarizing,
                    modifier = Modifier.testTag("note_reader_edit"),
                ) {
                    Icon(Icons.Outlined.Edit, stringResource(R.string.edit))
                }
            }
        }}
        else -> {{
            val importingMarkdown = state.noteFileImporting
            val importDescription = stringResource(
                if (importingMarkdown) R.string.importing_markdown_note else R.string.import_markdown_note,
            )
            Row {
                IconButton(
                    onClick = {
                        importMarkdownLauncher.launch(
                            arrayOf(
                                "text/markdown",
                                "text/x-markdown",
                                "application/x-markdown",
                                "text/plain",
                                "application/octet-stream",
                            ),
                        )
                    },
                    enabled = !importingMarkdown,
                    modifier = Modifier
                        .testTag(UiTestTags.NOTE_IMPORT_MARKDOWN)
                        .semantics { contentDescription = importDescription },
                ) {
                    if (importingMarkdown) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.UploadFile, null)
                    }
                }
                IconButton(
                    onClick = { editing = Note() },
                    modifier = Modifier.testTag("note_create"),
                ) {
                    Icon(Icons.Outlined.Add, stringResource(R.string.new_note))
                }
            }
        }}
    }
    WorkspaceScaffold(
        title = title,
        showBack = showBack || editing != null || currentReading != null,
        onBack = navigateBack,
        action = toolbarAction,
        compactTitle = currentReading != null && editing == null,
    ) { padding ->
        when {
            editing != null -> NoteEditor(
                note = editing!!,
                onSave = { saved ->
                    viewModel.saveNote(saved)
                    reading = saved
                    editing = null
                },
                modifier = Modifier.padding(padding),
            )
            currentReading != null -> NoteReader(currentReading, Modifier.padding(padding))
            else -> Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            OutlinedTextField(search, { search = it }, placeholder = { Text(stringResource(R.string.search_notes)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).align(Alignment.CenterHorizontally).testTag(UiTestTags.NOTES_SEARCH))
            val notes = state.notes.filter { search.isBlank() || it.title.contains(search, true) || it.body.contains(search, true) }
            if (selected.isNotEmpty()) SelectionToolbar(
                selectedCount = selected.size,
                allVisibleSelected = notes.isNotEmpty() && notes.all { it.id in selected },
                onSelectAll = {
                    val visible = notes.map(Note::id).toSet()
                    selected = if (visible.isNotEmpty() && visible.all { it in selected }) selected - visible else selected + visible
                },
                onDelete = { confirmDelete = true },
                onClear = { selected = emptySet() },
                modifier = Modifier.fillMaxWidth().widthIn(max = 820.dp).align(Alignment.CenterHorizontally),
            )
            if (notes.isEmpty()) EmptyWorkspace(Icons.Outlined.NoteAlt, stringResource(R.string.empty_notes), Modifier.weight(1f))
            else LazyColumn(Modifier.weight(1f).fillMaxWidth().widthIn(max = 820.dp).align(Alignment.CenterHorizontally), contentPadding = PaddingValues(vertical = 10.dp)) {
                items(notes, key = Note::id) { note ->
                    val isSelected = note.id in selected
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (selected.isEmpty()) reading = note
                                    else selected = if (isSelected) selected - note.id else selected + note.id
                                },
                                onLongClick = { selected = if (isSelected) selected - note.id else selected + note.id },
                            )
                            .padding(vertical = 12.dp)
                            .testTag(UiTestTags.noteItem(note.id)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            note.title,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (selected.isNotEmpty()) Checkbox(isSelected, { checked -> selected = if (checked) selected + note.id else selected - note.id })
                        else Row {
                            IconButton(onClick = { viewModel.deleteNote(note.id) }) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete)) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        }
    }
    if (confirmDelete) DeleteSelectedDialog(selected.size, { confirmDelete = false }) {
        viewModel.deleteNotes(selected)
        selected = emptySet()
        confirmDelete = false
    }
    knowledgeImportTarget?.let { note ->
        AlertDialog(
            onDismissRequest = { knowledgeImportTarget = null },
            title = { Text(stringResource(R.string.confirm_import_note_to_knowledge)) },
            text = { Text(stringResource(R.string.confirm_import_note_to_knowledge_detail)) },
            confirmButton = {
                Button(
                    onClick = {
                        knowledgeImportTarget = null
                        viewModel.importNoteToKnowledge(note.id)
                    },
                    modifier = Modifier.testTag("note_import_knowledge_confirm"),
                ) {
                    Text(stringResource(R.string.import_note_to_knowledge))
                }
            },
            dismissButton = {
                TextButton(onClick = { knowledgeImportTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            modifier = Modifier.testTag("note_import_knowledge_dialog"),
        )
    }
    summaryTarget?.let { note ->
        AlertDialog(
            onDismissRequest = {
                summaryTarget = null
                rewritePrompt = ""
            },
            title = {
                Text(
                    text = stringResource(R.string.choose_summary_model),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("note_rewrite_dialog_title"),
                )
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    val rewritePromptDescription = stringResource(R.string.note_rewrite_prompt)
                    OutlinedTextField(
                        value = rewritePrompt,
                        onValueChange = { rewritePrompt = it },
                        placeholder = {
                            Text(
                                text = rewritePromptDescription,
                                modifier = Modifier.fillMaxWidth().testTag(UiTestTags.NOTE_REWRITE_PLACEHOLDER),
                                textAlign = TextAlign.Center,
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = rewritePromptDescription }
                            .testTag("note_rewrite_prompt"),
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(state.models, key = { it.id }) { model ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    val submittedPrompt = rewritePrompt
                                    summaryTarget = null
                                    rewritePrompt = ""
                                    viewModel.summarizeNote(note.id, model.id, submittedPrompt)
                                }.padding(vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.SmartToy, null)
                                Text(
                                    model.displayName,
                                    Modifier.padding(start = 12.dp).weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    summaryTarget = null
                    rewritePrompt = ""
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun NoteReader(note: Note, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("note_reader"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MarkdownContent(
            markdown = note.body,
            modifier = Modifier.fillMaxWidth().widthIn(max = 860.dp),
        )
    }
}

@Composable
private fun SelectionToolbar(
    selectedCount: Int,
    allVisibleSelected: Boolean,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.selected_items, selectedCount), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = onSelectAll, modifier = Modifier.testTag(UiTestTags.WORKSPACE_SELECT_ALL)) {
            Icon(Icons.Outlined.SelectAll, stringResource(R.string.select_all), tint = if (allVisibleSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete, modifier = Modifier.testTag(UiTestTags.WORKSPACE_DELETE_SELECTED)) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete)) }
        IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, stringResource(R.string.clear_selection)) }
    }
}

@Composable
private fun DeleteSelectedDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_delete_selected_items)) },
        text = { Text(pluralStringResource(R.plurals.delete_selected_items_detail, count, count)) },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NoteEditor(note: Note, onSave: (Note) -> Unit, modifier: Modifier = Modifier) {
    var title by remember(note.id) { mutableStateOf(note.title) }
    var body by remember(note.id) { mutableStateOf(note.body) }
    var preview by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().padding(16.dp).navigationBarsPadding().testTag("note_editor"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilterChip(preview, { preview = !preview }, label = { Text(stringResource(R.string.preview)) })
        }
        OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.note_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (preview) MarkdownContent(body, Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp))
        else OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.note_body)) }, modifier = Modifier.weight(1f).fillMaxWidth())
        Button(onClick = { onSave(note.copy(title = title, body = body)) }, enabled = body.isNotBlank(), modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.save)) }
    }
}

@Composable
private fun AgentsScreen(state: AppUiState, viewModel: AppViewModel, showBack: Boolean) {
    var editing by remember { mutableStateOf<AgentProfile?>(null) }
    WorkspaceScaffold(
        stringResource(R.string.agents), showBack, { viewModel.openScreen(AppScreen.CHAT) },
        action = { IconButton(onClick = { editing = AgentProfile() }) { Icon(Icons.Outlined.Add, stringResource(R.string.new_agent)) } },
    ) { padding ->
        if (editing != null) AgentEditor(editing!!, state, { editing = null }) { viewModel.saveAgent(it); editing = null }
        else if (state.agents.isEmpty()) EmptyWorkspace(Icons.Outlined.SmartToy, stringResource(R.string.empty_agents), Modifier.padding(padding).fillMaxSize())
        else LazyColumn(Modifier.padding(padding).fillMaxSize().widthIn(max = 860.dp), contentPadding = PaddingValues(16.dp)) {
            items(state.agents, key = AgentProfile::id) { agent ->
                Row(Modifier.fillMaxWidth().clickable { editing = agent }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) { Icon(Icons.Outlined.SmartToy, null, Modifier.padding(10.dp)) }
                    Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                        Text(agent.name, fontWeight = FontWeight.SemiBold)
                        Text(agent.description, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewModel.startAgent(agent.id) }) { Icon(Icons.Outlined.PlayArrow, stringResource(R.string.start_chat)) }
                    IconButton(onClick = { viewModel.deleteAgent(agent.id) }) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete)) }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AgentEditor(agent: AgentProfile, state: AppUiState, onClose: () -> Unit, onSave: (AgentProfile) -> Unit) {
    var draft by remember(agent.id) { mutableStateOf(agent) }
    var modelMenu by remember { mutableStateOf(false) }
    val readyCloudServers = state.cloudServers.filter { it.keyConfigured && it.hostKeyFingerprint != null }
    val cloudSelectionValid = !draft.enableInfiniteCloud || readyCloudServers.any { it.id == draft.cloudServerId }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.agents), style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text(stringResource(R.string.agent_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.description, { draft = draft.copy(description = it) }, label = { Text(stringResource(R.string.agent_description)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Box {
            OutlinedButton(onClick = { modelMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(state.models.firstOrNull { it.id == draft.modelId }?.displayName ?: stringResource(R.string.select_model), Modifier.weight(1f)) }
            DropdownMenu(modelMenu, { modelMenu = false }) { state.models.forEach { model -> DropdownMenuItem({ Text(model.displayName) }, { draft = draft.copy(modelId = model.id); modelMenu = false }) } }
        }
        OutlinedTextField(draft.systemPrompt, { draft = draft.copy(systemPrompt = it) }, label = { Text(stringResource(R.string.system_prompt)) }, minLines = 5, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("off", "low", "medium", "high").forEach { effort -> FilterChip(draft.thinkingEffort == effort, { draft = draft.copy(thinkingEffort = effort) }, label = { Text(effort) }) } }
        Text("${stringResource(R.string.max_tool_calls)}: ${draft.maxToolCalls}")
        Slider(draft.maxToolCalls.toFloat(), { draft = draft.copy(maxToolCalls = it.roundToInt()) }, valueRange = 0f..20f, steps = 19)
        AgentToggle(stringResource(R.string.search_web), draft.enableSearch) { draft = draft.copy(enableSearch = it) }
        AgentToggle(stringResource(R.string.read_web), draft.enableRead) { draft = draft.copy(enableRead = it) }
        AgentToggle(stringResource(R.string.enable_knowledge), draft.enableKnowledge) { draft = draft.copy(enableKnowledge = it) }
        AgentToggle(
            stringResource(R.string.infinite_cloud),
            draft.enableInfiniteCloud,
            enabled = readyCloudServers.isNotEmpty() || draft.enableInfiniteCloud,
        ) {
            draft = draft.copy(
                enableInfiniteCloud = it,
                cloudServerId = if (it) {
                    draft.cloudServerId?.takeIf { configured -> readyCloudServers.any { server -> server.id == configured } }
                        ?: readyCloudServers.firstOrNull()?.id
                } else null,
            )
        }
        if (draft.enableInfiniteCloud) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                readyCloudServers.forEach { server ->
                    FilterChip(
                        selected = draft.cloudServerId == server.id,
                        onClick = { draft = draft.copy(cloudServerId = server.id) },
                        label = { Text(server.name) },
                    )
                }
            }
            if (!cloudSelectionValid) {
                Text(stringResource(R.string.cloud_select_ready_server), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(
            onClick = { onSave(draft) },
            enabled = draft.name.isNotBlank() && draft.modelId != null && cloudSelectionValid,
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(R.string.save)) }
    }
}

@Composable
private fun AgentToggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun KnowledgeScreen(state: AppUiState, viewModel: AppViewModel, showBack: Boolean) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        var name = uri.lastPathSegment ?: "document"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0) ?: name
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        viewModel.importKnowledge(KnowledgeImportSource(uri.toString(), name, context.contentResolver.getType(uri).orEmpty(), size))
    }
    WorkspaceScaffold(
        stringResource(R.string.knowledge), showBack, { viewModel.openScreen(AppScreen.CHAT) },
        action = { IconButton(onClick = { picker.launch(arrayOf("text/plain", "text/markdown", "application/json", "text/csv", "application/pdf")) }) { Icon(Icons.Outlined.UploadFile, stringResource(R.string.import_document)) } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().widthIn(max = 820.dp).align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(R.string.search_knowledge)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { viewModel.searchKnowledge(query) }, enabled = query.isNotBlank()) { Text(stringResource(R.string.search_action)) }
            }
            if (state.pendingKnowledgeChunkIds.isNotEmpty()) Text(stringResource(R.string.attached_count, state.pendingKnowledgeChunkIds.size), color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
            if (state.knowledgeResults.isNotEmpty()) LazyColumn(Modifier.weight(1f).fillMaxWidth().widthIn(max = 820.dp).align(Alignment.CenterHorizontally)) {
                items(state.knowledgeResults, key = { it.chunkId }) { snippet ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                        Checkbox(snippet.chunkId in state.pendingKnowledgeChunkIds, { viewModel.toggleKnowledgeAttachment(snippet.chunkId) })
                        Column(Modifier.weight(1f)) { Text("${snippet.documentName} · #${snippet.position + 1}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge); Text(snippet.text, maxLines = 5, overflow = TextOverflow.Ellipsis) }
                    }
                    HorizontalDivider()
                }
            } else if (state.knowledgeDocuments.isEmpty()) EmptyWorkspace(Icons.Outlined.FolderOpen, stringResource(R.string.empty_knowledge), Modifier.weight(1f))
            else LazyColumn(Modifier.weight(1f).fillMaxWidth().widthIn(max = 820.dp).align(Alignment.CenterHorizontally), contentPadding = PaddingValues(vertical = 12.dp)) {
                items(state.knowledgeDocuments, key = { it.id }) { document ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val previewModifier = if (document.status == "ready") {
                            Modifier.clickable { viewModel.openKnowledgePreview(document.id) }
                        } else {
                            Modifier
                        }
                        Row(
                            previewModifier
                                .weight(1f)
                                .padding(vertical = 12.dp)
                                .testTag(UiTestTags.knowledgeDocument(document.id)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (document.status == "error") Icons.Outlined.ErrorOutline else if (document.status == "ready") Icons.Outlined.CheckCircle else Icons.Outlined.Description, null, tint = if (document.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                                Text(document.name, fontWeight = FontWeight.Medium)
                                Text(if (document.status == "error") document.error else "${document.chunkCount} chunks · ${document.sizeBytes / 1024} KiB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(
                            onClick = { viewModel.deleteKnowledge(document.id) },
                            modifier = Modifier.testTag(UiTestTags.knowledgeDelete(document.id)),
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KnowledgeDocumentPreviewScreen(
    state: KnowledgePreviewState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val title = when (state) {
        KnowledgePreviewState.Closed -> stringResource(R.string.knowledge)
        is KnowledgePreviewState.Loading -> state.document.name
        is KnowledgePreviewState.Ready -> state.preview.documentName
        is KnowledgePreviewState.Error -> state.document.name
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag(UiTestTags.KNOWLEDGE_PREVIEW),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            KnowledgePreviewState.Closed -> Box(Modifier.padding(padding).fillMaxSize())
            is KnowledgePreviewState.Loading -> Box(
                Modifier.padding(padding).fillMaxSize().testTag(UiTestTags.KNOWLEDGE_PREVIEW_LOADING),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            is KnowledgePreviewState.Ready -> KnowledgePreviewContent(
                preview = state.preview,
                modifier = Modifier.padding(padding),
            )
            is KnowledgePreviewState.Error -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(24.dp)
                    .testTag(UiTestTags.KNOWLEDGE_PREVIEW_ERROR),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    text = state.message.resolve(),
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

@Composable
private fun KnowledgePreviewContent(preview: KnowledgeDocumentPreview, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        if (preview.truncated) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().testTag(UiTestTags.KNOWLEDGE_PREVIEW_TRUNCATED),
            ) {
                Text(
                    text = stringResource(R.string.knowledge_preview_truncated),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        if (remember(preview.extension, preview.text) {
                shouldRenderKnowledgePreviewAsMarkdown(preview.extension, preview.text)
            }
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag(UiTestTags.KNOWLEDGE_PREVIEW_MARKDOWN),
            ) {
                MarkdownContent(
                    markdown = preview.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 860.dp)
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        } else {
            val blocks = remember(preview.text) { knowledgePreviewPlainTextBlocks(preview.text) }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(UiTestTags.KNOWLEDGE_PREVIEW_PLAIN),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                itemsIndexed(blocks) { _, block ->
                    SelectionContainer {
                        Text(
                            text = block,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

internal fun shouldRenderKnowledgePreviewAsMarkdown(extension: String, text: String): Boolean {
    val normalizedExtension = extension.trim().removePrefix(".").lowercase(Locale.ROOT)
    return normalizedExtension in setOf("md", "markdown") &&
        text.length <= KNOWLEDGE_MARKDOWN_MAX_CHARS &&
        text.lineSequence().take(KNOWLEDGE_MARKDOWN_MAX_LINES + 1).count() <= KNOWLEDGE_MARKDOWN_MAX_LINES
}

internal fun knowledgePreviewPlainTextBlocks(
    text: String,
    maxCodePoints: Int = KNOWLEDGE_PLAIN_BLOCK_CODE_POINTS,
): List<String> {
    require(maxCodePoints > 0) { "maxCodePoints must be positive" }
    if (text.isEmpty()) return emptyList()

    val blocks = mutableListOf<String>()
    val current = StringBuilder()
    var currentCodePoints = 0

    fun flush() {
        if (current.isNotEmpty()) {
            blocks += current.toString()
            current.clear()
            currentCodePoints = 0
        }
    }

    fun appendAtom(start: Int, end: Int) {
        if (start >= end) return
        val codePoints = text.codePointCount(start, end)
        if (currentCodePoints > 0 && currentCodePoints + codePoints > maxCodePoints) flush()
        current.append(text, start, end)
        currentCodePoints += codePoints
        if (currentCodePoints == maxCodePoints) flush()
    }

    fun appendLongLine(start: Int, end: Int) {
        flush()
        var offset = start
        var remaining = text.codePointCount(start, end)
        while (offset < end) {
            val count = minOf(maxCodePoints, remaining)
            val next = text.offsetByCodePoints(offset, count)
            current.append(text, offset, next)
            currentCodePoints = count
            offset = next
            remaining -= count
            if (currentCodePoints == maxCodePoints) flush()
        }
    }

    var lineStart = 0
    while (lineStart < text.length) {
        var contentEnd = lineStart
        while (contentEnd < text.length && text[contentEnd] != '\n' && text[contentEnd] != '\r') contentEnd++
        val lineEnd = when {
            contentEnd >= text.length -> contentEnd
            text[contentEnd] == '\r' && contentEnd + 1 < text.length && text[contentEnd + 1] == '\n' -> contentEnd + 2
            else -> contentEnd + 1
        }
        val segmentCodePoints = text.codePointCount(lineStart, lineEnd)
        if (segmentCodePoints <= maxCodePoints) {
            appendAtom(lineStart, lineEnd)
        } else {
            appendLongLine(lineStart, contentEnd)
            appendAtom(contentEnd, lineEnd)
        }
        lineStart = lineEnd
    }
    flush()
    return blocks
}

private const val KNOWLEDGE_MARKDOWN_MAX_CHARS = 32_000
private const val KNOWLEDGE_MARKDOWN_MAX_LINES = 1_000
private const val KNOWLEDGE_PLAIN_BLOCK_CODE_POINTS = 8_192

@Composable
private fun AboutScreen(viewModel: AppViewModel, showBack: Boolean) {
    var showAgreement by rememberSaveable { mutableStateOf(false) }
    var showThirdPartyNotices by rememberSaveable { mutableStateOf(false) }
    val showDetail = showAgreement || showThirdPartyNotices
    val navigateBack = {
        if (showDetail) {
            showAgreement = false
            showThirdPartyNotices = false
        } else {
            viewModel.openScreen(AppScreen.CHAT)
        }
    }
    BackHandler(enabled = showDetail || showBack, onBack = navigateBack)
    WorkspaceScaffold(
        title = stringResource(
            when {
                showAgreement -> R.string.user_agreement
                showThirdPartyNotices -> R.string.third_party_notices
                else -> R.string.about
            },
        ),
        showBack = showDetail || showBack,
        onBack = navigateBack,
    ) { padding ->
        when {
            showAgreement -> BundledMarkdownContent(padding, R.raw.user_agreement, UiTestTags.USER_AGREEMENT_SCREEN)
            showThirdPartyNotices -> BundledMarkdownContent(
                padding,
                R.raw.third_party_notices,
                UiTestTags.THIRD_PARTY_NOTICES_SCREEN,
            )
            else -> AboutOverview(
                padding,
                onOpenThirdPartyNotices = { showThirdPartyNotices = true },
                onOpenAgreement = { showAgreement = true },
            )
        }
    }
}

@Composable
private fun AboutOverview(
    padding: PaddingValues,
    onOpenThirdPartyNotices: () -> Unit,
    onOpenAgreement: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val openUri = { url: String ->
        if (isSafeHttpUrl(url)) runCatching { uriHandler.openUri(url) }
        Unit
    }
    Box(Modifier.padding(padding).fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 820.dp)
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .testTag(UiTestTags.ABOUT_SCREEN),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        ) {
            item { AboutSectionTitle(stringResource(R.string.about_features)) }
            item {
                Text(
                    stringResource(R.string.about_intro),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item {
                Text(
                    stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(ABOUT_FEATURES, key = { it.title }) { feature -> AboutFeatureRow(feature) }
            item { AboutSectionTitle(stringResource(R.string.about_model_tools)) }
            items(ABOUT_MODEL_TOOLS, key = AboutModelTool::id) { tool -> AboutModelToolRow(tool) }
            item { AboutSectionTitle(stringResource(R.string.about_service_keys)) }
            item {
                AboutExternalLinkRow(
                    title = stringResource(R.string.about_exa_key),
                    description = stringResource(R.string.about_exa_key_purpose),
                    testTag = UiTestTags.ABOUT_EXA_KEY_LINK,
                    onClick = { openUri(EXA_API_KEY_URL) },
                )
            }
            item {
                AboutExternalLinkRow(
                    title = stringResource(R.string.about_mimo_key),
                    description = stringResource(R.string.about_mimo_key_purpose),
                    testTag = UiTestTags.ABOUT_MIMO_KEY_LINK,
                    onClick = { openUri(MIMO_API_KEY_URL) },
                )
            }
            item {
                Text(
                    stringResource(R.string.about_key_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item { AboutSectionTitle(stringResource(R.string.about_open_source)) }
            item {
                Text(
                    stringResource(R.string.about_open_source_intro),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(OPEN_SOURCE_COMPONENTS, key = { it.name }) { component ->
                OpenSourceComponentRow(component)
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.third_party_notices), fontWeight = FontWeight.Medium) },
                    leadingContent = { Icon(Icons.Outlined.Description, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTestTags.ABOUT_THIRD_PARTY_NOTICES)
                        .clickable(onClick = onOpenThirdPartyNotices),
                )
            }
            item { AboutSectionTitle(stringResource(R.string.user_agreement)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.user_agreement), fontWeight = FontWeight.Medium) },
                    leadingContent = { Icon(Icons.Outlined.Description, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTestTags.ABOUT_USER_AGREEMENT)
                        .clickable(onClick = onOpenAgreement),
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AboutSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun AboutFeatureRow(feature: AboutFeature) {
    ListItem(
        headlineContent = { Text(stringResource(feature.title), fontWeight = FontWeight.Medium) },
        supportingContent = { Text(stringResource(feature.description)) },
        leadingContent = { Icon(feature.icon, null, tint = MaterialTheme.colorScheme.primary) },
    )
}

@Composable
private fun AboutModelToolRow(tool: AboutModelTool) {
    ListItem(
        headlineContent = { Text(stringResource(tool.title), fontWeight = FontWeight.Medium) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = tool.id,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(stringResource(tool.description))
            }
        },
        leadingContent = { Icon(tool.icon, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.fillMaxWidth().testTag(tool.testTag),
    )
}

@Composable
private fun AboutExternalLinkRow(title: String, description: String, testTag: String, onClick: () -> Unit) {
    val linkColor = if (LocalTokenFlowDarkTheme.current) Color(0xFF90CAF9) else Color(0xFF1565C0)
    Column {
        ListItem(
            headlineContent = { Text(title, color = linkColor, fontWeight = FontWeight.Medium) },
            supportingContent = { Text(description) },
            leadingContent = { Icon(Icons.Outlined.Key, null, tint = linkColor) },
            trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.open_in_browser), tint = linkColor) },
            modifier = Modifier.fillMaxWidth().testTag(testTag).clickable(onClick = onClick),
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
    }
}

@Composable
private fun OpenSourceComponentRow(component: OpenSourceComponent) {
    val linkColor = if (LocalTokenFlowDarkTheme.current) Color(0xFF90CAF9) else Color(0xFF1565C0)
    val linkedName = buildAnnotatedString {
        component.links.forEachIndexed { index, link ->
            if (index > 0) append(" / ")
            withLink(
                LinkAnnotation.Url(
                    link.url,
                    TextLinkStyles(style = SpanStyle(color = linkColor, fontWeight = FontWeight.Medium)),
                ),
            ) {
                append(link.label)
            }
        }
    }
    Column {
        ListItem(
            headlineContent = { Text(linkedName) },
            supportingContent = { Text("${stringResource(component.purpose)} · ${component.license}") },
            trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.open_in_browser), tint = linkColor) },
            modifier = Modifier.fillMaxWidth(),
        )
        HorizontalDivider(Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun BundledMarkdownContent(padding: PaddingValues, rawResource: Int, testTag: String) {
    val context = LocalContext.current
    val content = remember(context, rawResource) {
        context.resources.openRawResource(rawResource).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MarkdownContent(
            content,
            Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

private data class AboutFeature(val icon: ImageVector, val title: Int, val description: Int)
private data class AboutModelTool(
    val id: String,
    val icon: ImageVector,
    val title: Int,
    val description: Int,
    val testTag: String,
)
private data class OpenSourceLink(val label: String, val url: String)
private data class OpenSourceComponent(
    val name: String,
    val purpose: Int,
    val license: String,
    val links: List<OpenSourceLink>,
)

internal const val EXA_API_KEY_URL = "https://dashboard.exa.ai/api-keys"
internal const val MIMO_API_KEY_URL = "https://platform.xiaomimimo.com/console/api-keys"

private val ABOUT_FEATURES = listOf(
    AboutFeature(Icons.Outlined.Hub, R.string.about_feature_multi_model_title, R.string.about_feature_multi_model_body),
    AboutFeature(Icons.Outlined.ChatBubbleOutline, R.string.about_feature_chat_title, R.string.about_feature_chat_body),
    AboutFeature(Icons.Outlined.FolderOpen, R.string.about_feature_workspace_title, R.string.about_feature_workspace_body),
    AboutFeature(Icons.Outlined.AutoAwesome, R.string.about_feature_extensions_title, R.string.about_feature_extensions_body),
)

private val ABOUT_MODEL_TOOLS = listOf(
    AboutModelTool(
        id = "web_search",
        icon = Icons.Outlined.Search,
        title = R.string.about_model_tool_web_search_title,
        description = R.string.about_model_tool_web_search_body,
        testTag = UiTestTags.ABOUT_MODEL_TOOL_WEB_SEARCH,
    ),
    AboutModelTool(
        id = "read_url",
        icon = Icons.Outlined.Link,
        title = R.string.about_model_tool_read_url_title,
        description = R.string.about_model_tool_read_url_body,
        testTag = UiTestTags.ABOUT_MODEL_TOOL_READ_URL,
    ),
    AboutModelTool(
        id = "search_knowledge",
        icon = Icons.Outlined.FolderOpen,
        title = R.string.about_model_tool_search_knowledge_title,
        description = R.string.about_model_tool_search_knowledge_body,
        testTag = UiTestTags.ABOUT_MODEL_TOOL_SEARCH_KNOWLEDGE,
    ),
    AboutModelTool(
        id = "calculate",
        icon = Icons.Outlined.Calculate,
        title = R.string.about_model_tool_calculate_title,
        description = R.string.about_model_tool_calculate_body,
        testTag = UiTestTags.ABOUT_MODEL_TOOL_CALCULATE,
    ),
    AboutModelTool(
        id = "convert_units",
        icon = Icons.Outlined.Straighten,
        title = R.string.about_model_tool_convert_units_title,
        description = R.string.about_model_tool_convert_units_body,
        testTag = UiTestTags.ABOUT_MODEL_TOOL_CONVERT_UNITS,
    ),
)

private val OPEN_SOURCE_COMPONENTS = listOf(
    OpenSourceComponent(
        name = "Jetpack Compose / Material 3",
        purpose = R.string.oss_purpose_compose,
        license = "Apache-2.0",
        links = listOf(
            OpenSourceLink("Jetpack Compose", "https://developer.android.com/compose"),
            OpenSourceLink("Material 3", "https://developer.android.com/develop/ui/compose/designsystems/material3"),
        ),
    ),
    OpenSourceComponent(
        name = "AndroidX Activity / Lifecycle",
        purpose = R.string.oss_purpose_activity_lifecycle,
        license = "Apache-2.0",
        links = listOf(
            OpenSourceLink("AndroidX Activity", "https://developer.android.com/jetpack/androidx/releases/activity"),
            OpenSourceLink("Lifecycle", "https://developer.android.com/jetpack/androidx/releases/lifecycle"),
        ),
    ),
    OpenSourceComponent("AndroidX Room", R.string.oss_purpose_room, "Apache-2.0", listOf(OpenSourceLink("AndroidX Room", "https://developer.android.com/jetpack/androidx/releases/room"))),
    OpenSourceComponent("AndroidX Media3 ExoPlayer", R.string.oss_purpose_media3, "Apache-2.0", listOf(OpenSourceLink("AndroidX Media3 ExoPlayer", "https://github.com/androidx/media"))),
    OpenSourceComponent("AndroidX ExifInterface", R.string.oss_purpose_exif, "Apache-2.0", listOf(OpenSourceLink("AndroidX ExifInterface", "https://developer.android.com/jetpack/androidx/releases/exifinterface"))),
    OpenSourceComponent("Kotlin Coroutines", R.string.oss_purpose_coroutines, "Apache-2.0", listOf(OpenSourceLink("Kotlin Coroutines", "https://github.com/Kotlin/kotlinx.coroutines"))),
    OpenSourceComponent("Kotlin Serialization", R.string.oss_purpose_serialization, "Apache-2.0", listOf(OpenSourceLink("Kotlin Serialization", "https://github.com/Kotlin/kotlinx.serialization"))),
    OpenSourceComponent("OkHttp", R.string.oss_purpose_okhttp, "Apache-2.0", listOf(OpenSourceLink("OkHttp", "https://square.github.io/okhttp/"))),
    OpenSourceComponent("jsoup", R.string.oss_purpose_jsoup, "MIT", listOf(OpenSourceLink("jsoup", "https://jsoup.org/"))),
    OpenSourceComponent("PDFBox Android", R.string.oss_purpose_pdfbox, "Apache-2.0", listOf(OpenSourceLink("PDFBox Android", "https://github.com/TomRoush/PdfBox-Android"))),
    OpenSourceComponent("Apache POI", R.string.oss_purpose_poi, "Apache-2.0", listOf(OpenSourceLink("Apache POI", "https://poi.apache.org/"))),
    OpenSourceComponent("commonmark-java", R.string.oss_purpose_commonmark, "BSD-2-Clause", listOf(OpenSourceLink("commonmark-java", "https://github.com/commonmark/commonmark-java"))),
)

@Composable
private fun EmptyWorkspace(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(32.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) { Icon(icon, null, Modifier.padding(14.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary) }
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
