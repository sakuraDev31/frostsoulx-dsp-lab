package app.resonance.player

import android.app.Application
import app.resonance.player.engine.EngineManager

class ResonanceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EngineManager.init(this)
    }
}
