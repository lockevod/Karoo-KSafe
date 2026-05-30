package com.enderthor.kSafe.extension.util

import kotlin.math.roundToInt

/** Carbs (g) contained in [ml] of a drink mix at [concentrationPer500ml] g per 500 ml.
 *  Rounded to the nearest gram. Used by the Fueling screen to auto-fill a combined button's
 *  carbs from its volume; the result is editable, not a hard binding. */
fun carbsFromVolume(ml: Int, concentrationPer500ml: Int): Int =
    if (ml <= 0 || concentrationPer500ml <= 0) 0
    else (ml.toDouble() * concentrationPer500ml / 500.0).roundToInt()
