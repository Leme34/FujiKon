package com.fujikon.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.fujikon.app.ui.AppNavHost
import com.fujikon.app.ui.theme.FujiKonTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        setContent {
            FujiKonTheme {
                AppNavHost()
            }
        }

        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return
        val app = application as FujiKonApp
        lifecycleScope.launch {
            val result = app.container.presetRepository.importUris(listOf(uri))
            val message = when {
                result.imported.isNotEmpty() -> "已导入预设：${result.imported.first().name}"
                result.duplicates.isNotEmpty() -> "这个预设已经导入过了"
                else -> result.errors.firstOrNull() ?: "无法导入这个文件"
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }
}
