package xyz.mek030399.tokenflow

import android.app.Application
import xyz.mek030399.tokenflow.data.AppContainer

class TokenFlowApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
