package com.motovision.pro

import android.app.Application
import android.util.Log

class MotoVisionApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MotoVision Pro initialized on target platform")
    }

    companion object {
        const val TAG = "MotoVisionPro"
    }
}
