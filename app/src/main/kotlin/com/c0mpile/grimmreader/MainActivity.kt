package com.c0mpile.grimmreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.designsystem.theme.GrimmTheme
import com.c0mpile.grimmreader.core.model.Appearance
import com.c0mpile.grimmreader.navigation.GrimmApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val appearance by prefs.appearance.collectAsStateWithLifecycle(initialValue = Appearance())
            GrimmTheme(appearance) { GrimmApp(prefs, versionName = BuildConfig.VERSION_NAME, devServerUrl = DevDefaults.SERVER_URL) }
        }
    }
}
