package com.cchharness.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cchharness.app.ui.HarnessTheme
import com.cchharness.core.HarnessClient

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels {
        viewModelFactory {
            initializer {
                val ctx = applicationContext
                val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                AppViewModel(
                    settings = SharedPrefsSettings(ctx),
                    apiFactory = { url -> HarnessClient(url) },
                    resolver = ContentResolverAttachments(ctx.contentResolver),
                    version = AppVersion(info.longVersionCode.toInt(), info.versionName.orEmpty()),
                    updateDir = PackageApkInstaller.updateDir(ctx),
                    installer = PackageApkInstaller(ctx),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 3-button navigation otherwise gets an opaque white scrim that clashes with the cream background
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            HarnessTheme {
                AppRoot(vm)
            }
        }
    }
}
