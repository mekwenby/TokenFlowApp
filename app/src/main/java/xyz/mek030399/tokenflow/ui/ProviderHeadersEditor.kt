package xyz.mek030399.tokenflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import xyz.mek030399.tokenflow.R
import xyz.mek030399.tokenflow.data.ProviderHeaderTemplate
import xyz.mek030399.tokenflow.data.ProviderRequestHeader

@Composable
internal fun ProviderHeadersEditor(
    rows: List<ProviderRequestHeader>,
    sessionId: String,
    enabled: Boolean,
    onRowsChanged: (List<ProviderRequestHeader>) -> Unit,
    onAdd: () -> Unit,
    onApplyTemplate: (ProviderHeaderTemplate) -> Unit,
) {
    var expanded by rememberSaveable(sessionId) { mutableStateOf(rows.isNotEmpty()) }
    var templatesOpen by remember(sessionId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().testTag("provider_headers_expand"),
        ) {
            Text(stringResource(R.string.provider_headers_title, rows.size), Modifier.weight(1f))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (expanded) {
            Text(stringResource(R.string.provider_headers_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.provider_headers_custom_auth), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onAdd, enabled = enabled, modifier = Modifier.testTag("provider_header_add")) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.provider_header_add))
                }
                Column {
                    OutlinedButton(onClick = { templatesOpen = true }, enabled = enabled,
                        modifier = Modifier.testTag("provider_header_templates")) {
                        Text(stringResource(R.string.provider_header_apply_template))
                    }
                    DropdownMenu(expanded = templatesOpen && enabled, onDismissRequest = { templatesOpen = false }) {
                        ProviderHeaderTemplate.entries.forEach { template ->
                            DropdownMenuItem(
                                text = { Text(template.label()) },
                                onClick = { templatesOpen = false; onApplyTemplate(template) },
                                modifier = Modifier.testTag("provider_header_template_${template.id}"),
                            )
                        }
                    }
                }
            }
            Text(stringResource(R.string.provider_header_template_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows.forEach { row ->
                key(sessionId, row.id) {
                    ProviderHeaderRow(row, enabled,
                        onChange = { changed -> onRowsChanged(rows.map { if (it.id == row.id) changed else it }) },
                        onDelete = { onRowsChanged(rows.filterNot { it.id == row.id }) })
                }
            }
        }
    }
}

@Composable
private fun ProviderHeaderRow(row: ProviderRequestHeader, enabled: Boolean,
    onChange: (ProviderRequestHeader) -> Unit, onDelete: () -> Unit) {
    var visible by remember(row.id) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().testTag("provider_header_row_${row.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.provider_header_enabled), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Switch(row.enabled, { onChange(row.copy(enabled = it)) }, enabled = enabled,
                    modifier = Modifier.testTag("provider_header_enabled_${row.id}"))
                IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.testTag("provider_header_delete_${row.id}")) {
                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete))
                }
            }
            OutlinedTextField(row.name, { onChange(row.copy(name = it, templateId = null)) }, enabled = enabled, singleLine = true,
                label = { Text(stringResource(R.string.provider_header_name)) },
                modifier = Modifier.fillMaxWidth().testTag("provider_header_name_${row.id}"))
            OutlinedTextField(row.value, { onChange(row.copy(value = it, templateId = null)) }, enabled = enabled, singleLine = true,
                label = { Text(stringResource(R.string.provider_header_value)) },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }, enabled = enabled,
                        modifier = Modifier.testTag("provider_header_visibility_${row.id}")) {
                        Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            stringResource(R.string.toggle_key_visibility))
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("provider_header_value_${row.id}"))
        }
    }
}

@Composable
private fun ProviderHeaderTemplate.label(): String = stringResource(when (this) {
    ProviderHeaderTemplate.CLAUDE_CODE -> R.string.provider_header_template_claude_code
    ProviderHeaderTemplate.OPENCODE -> R.string.provider_header_template_opencode
    ProviderHeaderTemplate.PI -> R.string.provider_header_template_pi
})
