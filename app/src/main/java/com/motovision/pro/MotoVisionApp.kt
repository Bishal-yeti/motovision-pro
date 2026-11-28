package com.motovision.pro

import android.app.Application
import android.util.Log

class MotoVisionApp : Application() {

    override fun onCreate() {
        super.onCreate()
        com.motovision.pro.system.DeviceHealthMonitor.start(this)
        // Pre-warm CameraX: enumerating cameras and loading the provider off the critical path
        // of the first frame cuts camera start-up time.
        try {
            androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this)
        } catch (t: Throwable) {
            Log.w(TAG, "Camera provider pre-warm skipped: ${t.message}")
        }
        Log.i(TAG, "MotoVision Pro initialized on target platform")
    }

    companion object {
        const val TAG = "MotoVisionPro"
    }
}
