package dev.mindmax.v4

import android.app.Application
import dev.mindmax.v4.core.di.ServiceLocator

class MindMaxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
