package xyz.mek030399.tokenflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.mek030399.tokenflow.R

@Composable
internal fun SystemPromptEditorEntry(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.system_prompt)
    val summary = remember(value) {
        val text = value.trimStart()
        if (text.length > 240) text.take(240).trimEnd { it.isHighSurrogate() } + "\u2026" else text
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = summary.ifBlank { stringResource(R.string.system_prompt_empty) },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
        modifier = modifier.fillMaxWidth().clickable(onClick = { open = true }),
    )
    if (open) {
        SystemPromptEditorDialog(
            value = value,
            onValueChange = onValueChange,
            editable = editable,
            onClose = { open = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemPromptEditorDialog(
    value: String,
    onValueChange: (String) -> Unit,
    editable: Boolean,
    onClose: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val showEditor = editable && editing
    val previewScrollState = rememberLazyListState()
    val title = stringResource(R.string.system_prompt)

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val focusManager = LocalFocusManager.current
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding().testTag("system_prompt_fullscreen"),
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onClose, modifier = Modifier.testTag("system_prompt_back")) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (editable) {
                    TabRow(selectedTabIndex = if (showEditor) 1 else 0) {
                        Tab(
                            selected = !showEditor,
                            onClick = {
                                focusManager.clearFocus()
                                editing = false
                            },
                            text = { Text(stringResource(R.string.preview)) },
                            modifier = Modifier.testTag("system_prompt_preview_tab"),
                        )
                        Tab(
                            selected = showEditor,
                            onClick = { editing = true },
                            text = { Text(stringResource(R.string.edit)) },
                            modifier = Modifier.testTag("system_prompt_edit_tab"),
                        )
                    }
                }
                if (showEditor) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        label = { Text(title) },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("system_prompt_text_editor"),
                        textStyle = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    val blocks = remember(value) { knowledgePreviewPlainTextBlocks(value) }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("system_prompt_text_preview"),
                        state = previewScrollState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        if (blocks.isEmpty()) {
                            item {
                                Text(stringResource(R.string.system_prompt_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(blocks) { block ->
                            SelectionContainer {
                                Text(block, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
