package ai.venice.aimdrag

import android.app.Service
import android.content.Intent
import android.hardware.input.InputManager
import android.os.IBinder
import android.view.InputDevice
import android.view.MotionEvent

class InjectionService : Service() {

    private val input by lazy { getSystemService(InputManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun send(ev: MotionEvent) {
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN)
        input.injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        ev.recycle()
    }

    companion object {
        @Volatile
        var instance: InjectionService? = null
        val alive: Boolean get() = instance != null
    }
}
