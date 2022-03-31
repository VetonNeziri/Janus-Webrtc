package com.veton.januswebrtc

import android.content.Context
import android.widget.Toast
import kotlin.math.roundToInt


private const val UNKNOWN_ERROR = "Unknown error"

fun Context.toastMsg(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
fun Context.toastError(msg: String? = UNKNOWN_ERROR) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

infix fun Float.convertDpToPixels(context: Context): Int {
    val dm = context.resources.displayMetrics
    return (this * (dm.densityDpi / 160f)).roundToInt()
}