package xyz.mek030399.tokenflow

import android.app.Application
import xyz.mek030399.tokenflow.data.AppContainer
import xyz.mek030399.tokenflow.background.GenerationServiceHost

class TokenFlowApplication : Application(), GenerationServiceHost {
    val container: AppContainer by lazy { AppContainer(this) }
    override val generationCoordinator get() = container.generationCoordinator
    override val generationRuntime get() = container.generationRuntime

    override fun onCreate() {
        super.onCreate()
        // Register visibility callbacks before the first Activity starts.
        container.generationRuntime
    }
}
