package xyz.mek030399.tokenflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import xyz.mek030399.tokenflow.R
import xyz.mek030399.tokenflow.data.ConfigurationException
import xyz.mek030399.tokenflow.data.ProviderHeaderPolicy
import xyz.mek030399.tokenflow.data.ProviderHeaderTemplate
import xyz.mek030399.tokenflow.data.ProviderHeaderTemplates
import xyz.mek030399.tokenflow.data.ProviderRequestHeader

@Composable
internal fun ProviderHeadersEditor(
    rows: List<ProviderRequestHeader>,
    sessionId: String,
    enabled: Boolean,
    onRowsChanged: (List<ProviderRequestHeader>) -> Unit,
) {
    var open by remember(sessionId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("provider_headers_edit"),
        ) {
            Text(stringResource(R.string.provider_headers_title, rows.size), Modifier.weight(1f))
            Text(stringResource(R.string.provider_headers_edit_json))
        }
    }
    if (open && enabled) {
        ProviderHeaderJsonDialog(rows, onDismiss = { open = false }, onApply = {
            onRowsChanged(it)
            open = false
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderHeaderJsonDialog(
    rows: List<ProviderRequestHeader>,
    onDismiss: () -> Unit,
    onApply: (List<ProviderRequestHeader>) -> Unit,
) {
    var referenceRows by remember { mutableStateOf(rows) }
    var source by remember { mutableStateOf(ProviderHeaderJson.encode(rows)) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var templatesOpen by remember { mutableStateOf(false) }

    fun editRows(transform: (List<ProviderRequestHeader>) -> Unit) {
        try {
            transform(ProviderHeaderPolicy.normalize(ProviderHeaderJson.decode(source, referenceRows)))
            error = null
        } catch (failure: ConfigurationException) {
            error = providerHeaderFailureText(failure.message) ?: UiText.Resource(R.string.provider_header_json_invalid)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.provider_headers_json_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, modifier = Modifier.testTag("provider_headers_json_close")) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { editRows(onApply) },
                            modifier = Modifier.testTag("provider_headers_json_apply"),
                        ) { Text(stringResource(R.string.provider_headers_apply_json)) }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        TextButton(
                            onClick = { templatesOpen = true },
                            modifier = Modifier.testTag("provider_header_templates"),
                        ) { Text(stringResource(R.string.provider_header_apply_template)) }
                        DropdownMenu(expanded = templatesOpen, onDismissRequest = { templatesOpen = false }) {
                            ProviderHeaderTemplate.entries.forEach { template ->
                                DropdownMenuItem(
                                    text = { Text(template.label()) },
                                    onClick = {
                                        templatesOpen = false
                                        editRows {
                                            referenceRows = ProviderHeaderTemplates.apply(it, template)
                                            source = ProviderHeaderJson.encode(referenceRows)
                                        }
                                    },
                                    modifier = Modifier.testTag("provider_header_template_${template.id}"),
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = { editRows { source = ProviderHeaderJson.encode(it) } },
                        modifier = Modifier.testTag("provider_headers_json_format"),
                    ) { Text(stringResource(R.string.provider_headers_format_json)) }
                }
                Text(
                    stringResource(R.string.provider_headers_json_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it; error = null },
                    label = { Text(stringResource(R.string.provider_headers_json_title)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message.resolve(), Modifier.testTag("provider_headers_json_error")) } },
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("provider_headers_json_input"),
                )
            }
        }
    }
}

@Composable
private fun ProviderHeaderTemplate.label(): String = stringResource(when (this) {
    ProviderHeaderTemplate.CLAUDE_CODE -> R.string.provider_header_template_claude_code
    ProviderHeaderTemplate.OPENCODE -> R.string.provider_header_template_opencode
    ProviderHeaderTemplate.PI -> R.string.provider_header_template_pi
})
