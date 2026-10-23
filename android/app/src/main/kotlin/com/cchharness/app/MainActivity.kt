package com.cchharness.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cchharness.app.ui.HarnessTheme
import com.cchharness.core.HarnessClient

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels {
        viewModelFactory {
            initializer { AppViewModel(SharedPrefsSettings(applicationContext), { url -> HarnessClient(url) }) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HarnessTheme {
                AppRoot(vm)
            }
        }
    }
}
