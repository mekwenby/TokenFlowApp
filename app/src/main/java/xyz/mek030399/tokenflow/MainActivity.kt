package xyz.mek030399.tokenflow

import android.os.Bundle
import android.content.Intent
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import xyz.mek030399.tokenflow.ui.AppViewModel
import xyz.mek030399.tokenflow.ui.AppViewModelFactory
import xyz.mek030399.tokenflow.ui.TokenFlowApp
import xyz.mek030399.tokenflow.background.GenerationService

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels {
        val container = (application as TokenFlowApplication).container
        AppViewModelFactory(container.repository, container.noteMarkdownFiles, container.shareDrafts, container.generationCoordinator)
    }
    private var shareReceipt = UUID.randomUUID().toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shareReceipt = savedInstanceState?.getString("shareReceipt") ?: shareReceipt
        handleShare(intent)
        handleGenerationNotification(intent)
        enableEdgeToEdge()
        setContent {
            TokenFlowApp(viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shareReceipt = UUID.randomUUID().toString()
        handleShare(intent)
        handleGenerationNotification(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("shareReceipt", shareReceipt)
        super.onSaveInstanceState(outState)
    }

    private fun handleShare(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            viewModel.receiveShare(intent, shareReceipt)
        }
    }

    private fun handleGenerationNotification(intent: Intent) {
        intent.getStringExtra(GenerationService.EXTRA_CONVERSATION_ID)?.let { id ->
            viewModel.openNotifiedConversation(id)
            intent.removeExtra(GenerationService.EXTRA_CONVERSATION_ID)
        }
    }
}
