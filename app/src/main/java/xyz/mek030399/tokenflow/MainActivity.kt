package xyz.mek030399.tokenflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import xyz.mek030399.tokenflow.ui.AppViewModel
import xyz.mek030399.tokenflow.ui.AppViewModelFactory
import xyz.mek030399.tokenflow.ui.TokenFlowApp

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels {
        val container = (application as TokenFlowApplication).container
        AppViewModelFactory(container.repository, container.noteMarkdownFiles)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TokenFlowApp(viewModel)
        }
    }
}
