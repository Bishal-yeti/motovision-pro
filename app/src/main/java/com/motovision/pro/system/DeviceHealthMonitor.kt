package com.motovision.pro.system

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicReference

/**
 * Watches thermal state, free memory and battery saver so the pipeline can scale its work
 * to what the Helio G85 can sustain right now, instead of overheating and throttling hard.
 */
object DeviceHealthMonitor {

    enum class QualityTier { FULL, REDUCED, MINIMAL }

    private val tierRef = AtomicReference(QualityTier.FULL)
    private var appContext: Context? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    @Volatile private var thermalStatus: Int = 0

    val tier: QualityTier get() = tierRef.get()

    /** Pure decision function (unit-testable). thermal = PowerManager.THERMAL_STATUS_* */
    fun tierFor(thermal: Int, lowMemory: Boolean, batterySaver: Boolean): QualityTier = when {
        thermal >= 4 /* CRITICAL */ || lowMemory && thermal >= 2 -> QualityTier.MINIMAL
        thermal >= 2 /* MODERATE */ || lowMemory -> QualityTier.REDUCED
        batterySaver -> QualityTier.REDUCED
        else -> QualityTier.FULL
    }

    fun start(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val l = PowerManager.OnThermalStatusChangedListener { status ->
                thermalStatus = status
                refresh()
            }
            thermalListener = l
            pm.addThermalStatusListener(l)
            thermalStatus = pm.currentThermalStatus
        }
        refresh()
    }

    /** Re-evaluates the tier; cheap, call it right before heavy processing. */
    fun refresh(): QualityTier {
        val ctx = appContext ?: return tier
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val t = tierFor(thermalStatus, mi.lowMemory || am.isLowRamDevice && mi.availMem < 400L * 1024 * 1024, pm.isPowerSaveMode)
        tierRef.set(t)
        return t
    }
}
