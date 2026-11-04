package com.megu.phone

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.megu.phone.data.MyList
import com.megu.phone.data.Progress
import com.megu.phone.data.Sync
import com.megu.phone.data.SearchHistory
import com.megu.phone.ui.MeguApp
import com.megu.phone.ui.MeguTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Progress.init(this)
        MyList.init(this)
        SearchHistory.init(this)
        Sync.pullInBackground()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            MeguTheme {
                MeguApp()
            }
        }
    }
}
