package com.wesrable.positioning.sensors

import android.content.Context

/**
 * Scales the estimated step length to the person actually carrying the phone.
 *
 * Step length is estimated from the vertical acceleration swing by Weinberg's
 * fourth-root rule, whose constant is a figure from the literature rather than
 * anything measured about this walker. It reads high for short steps: walking
 * a 1.5 x 3 m circuit, where a leg is two or three paces and every few steps
 * is a turn, it reported a 69 cm mean stride for someone plainly taking closer
 * to 45 cm.
 *
 * The correction is deliberately made against *counted* steps rather than real
 * ones, which is what makes it worth doing. Missed steps and an overlong
 * stride produce the same inflated distance, so a factor fitted to a walk of
 * known length absorbs both at once — if the detector misses a fifth of the
 * steps, the factor simply comes out a fifth larger and the distance still
 * lands right. It cannot fix the *shape* that missed steps distort, but it
 * removes the scale error underneath it.
 */
class StrideCalibration(context: Context) {

    private val preferences =
        context.getSharedPreferences("stride_calibration", Context.MODE_PRIVATE)

    /** Multiplier on every estimated step length; 1.0 until calibrated. */
    var factor: Float
        get() = preferences.getFloat(KEY_FACTOR, 1f)
        private set(value) {
            preferences.edit().putFloat(KEY_FACTOR, value).apply()
        }

    val isCalibrated: Boolean get() = preferences.contains(KEY_FACTOR)

    /**
     * Folds a walk of known length into the factor. [measuredMeters] is what
     * the app believed it walked *with the current factor already applied*, so
     * the correction multiplies rather than replaces.
     */
    fun record(actualMeters: Double, measuredMeters: Double) {
        if (actualMeters <= 0.0 || measuredMeters <= MIN_USEFUL_WALK_METERS) return
        val corrected = factor * (actualMeters / measuredMeters).toFloat()
        factor = corrected.coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    fun reset() {
        preferences.edit().remove(KEY_FACTOR).apply()
    }

    private companion object {
        const val KEY_FACTOR = "factor"

        /** Too short a walk and the factor fits noise rather than stride. */
        const val MIN_USEFUL_WALK_METERS = 3.0

        /** Beyond this the walk was almost certainly mis-measured. */
        const val MIN_FACTOR = 0.25f
        const val MAX_FACTOR = 4f
    }
}
