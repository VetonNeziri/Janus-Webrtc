package com.veton.januswebrtc.permissions

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import timber.log.Timber

class PermissionGroup(
    val requestPermissions: Array<String>,
    private val checkPermissions: Array<String>?,
    val requestCode: Int,
    @StringRes val rationale: Int
) {
    /**
     * Check if a permission request is needed
     *
     * @return true if any of the `requestPermissions` array does not have PERMISSION_GRANTED.
     */
    fun isRequestNeeded(context: Context): Boolean {
        requestPermissions.forEach {
            Timber.v("Checking if we need to request permission: %s", it)
            if (ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED) {
                Timber.v("Need to request permission: %s", it)
                return true
            }
        }
        Timber.v("No need to request permissions")
        return false
    }

    /**
     * Check if all permissions are granted
     *
     * @return true if all of the `requestPermissions` array have PERMISSION_GRANTED.
     */
    fun haveAll(context: Context) : Boolean {
        requestPermissions.forEach {
            Timber.v("Checking for permission: %s", it)
            if (ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED) {
                Timber.v("MISSING permission: %s", it)
                return false
            }
        }
        Timber.d("All permissions granted")
        return true
    }

    /**
     * Check if enough permissions are granted to not complain
     *
     * @return true if all of the `checkPermissions` array have PERMISSION_GRANTED.
     */
    fun haveEnough(context: Context) : Boolean {
        val mustHave = (checkPermissions ?: requestPermissions)
        mustHave.forEach {
            Timber.v("Checking for permission: %s", it)
            if (ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED) {
                Timber.v("MISSING permission: %s", it)
                return false
            }
        }
        Timber.d("All permissions granted")
        return true
    }

    /**
     * Check if all permissions were granted by the user at the permissions prompt
     */
    fun checkFromLauncherResult(results: Map<String, Boolean>): Boolean {
        val mustHave = (checkPermissions ?: requestPermissions)
        mustHave.forEach {
            if (results[it] != true) {
                Timber.v("MISSING permission: %s", it)
                return false
            }
        }
        Timber.d("All permissions granted")
        return true
    }

    fun shouldShowRationale(activity: Activity) : Boolean {
        requestPermissions.forEach {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, it)) {
                Timber.v("Need to show permission rationale for: %s", it)
                return true
            }
        }
        Timber.v("No need to show permission rationale")
        return false
    }

    fun shouldShowRationale(fragment: Fragment) : Boolean {
        requestPermissions.forEach {
            if (fragment.shouldShowRequestPermissionRationale(it)) {
                Timber.v("Need to show permission rationale for: %s", it)
                return true
            }
        }
        Timber.v("No need to show permission rationale")
        return false
    }
}
