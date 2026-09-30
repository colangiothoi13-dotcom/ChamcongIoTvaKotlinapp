package vn.chamcong.iot

import android.app.Application
import androidx.work.Configuration

class ChamCongApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
