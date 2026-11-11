package com.kanpu.app.domain

/** Size is stored relative to the rendered PDF page, so it follows zoom and rotation. */
object FingeringSize {
    const val SMALL = .010f
    const val MEDIUM = .013f
    const val LARGE = .017f

    // Earlier builds stored much larger presets. Keep old annotations readable at the new sizes.
    fun displayRatio(savedRatio: Float): Float = when (savedRatio) {
        .026f -> SMALL
        .035f -> MEDIUM
        .048f -> LARGE
        else -> savedRatio
    }
}
