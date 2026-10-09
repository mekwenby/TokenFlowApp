package xyz.mek030399.tokenflow.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.mek030399.tokenflow.R

@Composable
internal fun QuestionEditDialog(state: AppUiState, viewModel: AppViewModel) {
    val message = state.editingQuestion ?: return
    var text by rememberSaveable(message.id) { mutableStateOf(message.content) }
    AlertDialog(
        onDismissRequest = { if (!state.questionEditBusy) viewModel.closeQuestionEditor() },
        title = { Text(stringResource(R.string.edit_question_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.edit_question_notice), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, minLines = 4, maxLines = 12,
                    enabled = !state.questionEditBusy, modifier = Modifier.fillMaxWidth().testTag("question_edit_input"))
                state.questionEditError?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
                if (state.questionEditBusy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.submitEditedQuestion(text) }, enabled = !state.questionEditBusy &&
                (text.isNotBlank() || state.attachments[message.conversationId].orEmpty().any { it.messageId == message.id })) {
                Text(stringResource(R.string.edit_question_submit))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::closeQuestionEditor, enabled = !state.questionEditBusy) { Text(stringResource(R.string.cancel)) } },
    )
}
