package com.veton.januswebrtc.permissions

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.veton.januswebrtc.R
import timber.log.Timber

fun ComponentActivity.requestPermissions(
    group: PermissionGroup,
    callback: (PermissionGroup, Boolean) -> Unit
) {
    val request = createActivityResultLauncher(group, callback)

    requestPermissionsForActivityResult(group, request, callback)
}

fun ComponentActivity.createActivityResultLauncher(
    group: PermissionGroup,
    callback: (PermissionGroup, Boolean) -> Unit
): ActivityResultLauncher<Array<String>> {
    return registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = group.checkFromLauncherResult(it)
        callback(group, granted)
    }
}

fun ComponentActivity.requestPermissionsForActivityResult(
    group: PermissionGroup,
    request: ActivityResultLauncher<Array<String>>,
    callback: (PermissionGroup, Boolean) -> Unit
) {
    val permsListString = group.requestPermissions.joinToString(",")
    Timber.i("Checking %s permissions for activity %s", permsListString, this.javaClass.name)

    when {
        group.haveAll(this) -> {
            Timber.i("Already have required permissions")
            callback(group, true)
        }
        group.shouldShowRationale(this) -> {
            Timber.i("Showing permission rationale dialog for %s", permsListString)
            alertDialog(
                title = getString(R.string.permissions),
                msg = getString(group.rationale),
                onClick = { yes ->
                    if (yes) {
                        request.launch(group.requestPermissions)
                    }
                }
            )
        }
        else -> {
            Timber.i("Prompting for permissions %s", permsListString)
            request.launch(group.requestPermissions)
        }
    }
}

/**
 * Request permissions.
 *
 * This can ONLY be called during Fragment initialization, onAttach, or onCreate
 */
fun Fragment.requestPermissions(
    group: PermissionGroup,
    callback: (PermissionGroup, Boolean) -> Unit
) {
    val request = createActivityResultLauncher(group, callback)

    requestPermissionsForActivityResult(group, request, callback)
}

fun Fragment.createActivityResultLauncher(
    group: PermissionGroup,
    callback: (PermissionGroup, Boolean) -> Unit
): ActivityResultLauncher<Array<String>> {
    return registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = group.checkFromLauncherResult(it)
        callback(group, granted)
    }
}

/**
 * Request permissions, if needed, using an already-created ActivityResultLauncher
 */
fun Fragment.requestPermissionsForActivityResult(
    group: PermissionGroup,
    request: ActivityResultLauncher<Array<String>>,
    callback: (PermissionGroup, Boolean) -> Unit
) {
    val permsListString = group.requestPermissions.joinToString(",")
    Timber.i("Checking %s permissions for activity %s", permsListString, this.javaClass.name)

    when {
        group.haveAll(requireContext()) -> {
            Timber.i("Already have required permissions")
            callback(group, true)
        }
        group.shouldShowRationale(this) -> {
            Timber.i("Showing permission rationale dialog for %s", permsListString)
            alertDialog(
                title = getString(R.string.permissions),
                msg = getString(group.rationale),
                onClick = { yes ->
                    if (yes) {
                        request.launch(group.requestPermissions)
                    }
                }
            )
        }
        else -> {
            Timber.i("Prompting for permissions %s", permsListString)
            request.launch(group.requestPermissions)
        }
    }
}

fun Activity.alertDialog(
    title: String? = null,
    msg: String? = null,
    @StringRes okText: Int = R.string.button_ok,
    @StringRes cancelText: Int = R.string.cancel,
    onClick: ((Boolean) -> Unit)? = null
): AlertDialog {
    return buildAlertDialog(this, title, msg, okText, cancelText, onClick)
}

fun Fragment.alertDialog(
    title: String? = null,
    msg: String? = null,
    @StringRes okText: Int = R.string.button_ok,
    @StringRes cancelText: Int = R.string.cancel,
    onClick: ((Boolean) -> Unit)? = null
): AlertDialog {
    return buildAlertDialog(this.requireActivity(), title, msg, okText, cancelText, onClick)
}

private fun buildAlertDialog(
    a: Activity,
    title: String? = null,
    msg: String? = null,
    @StringRes okText: Int = R.string.button_ok,
    @StringRes cancelText: Int = R.string.cancel,
    onClick: ((Boolean) -> Unit)? = null
): AlertDialog {
    return with(AlertDialog.Builder(a)) {
        setCancelable(false)
        setTitle(title)
        setMessage(msg)
        setPositiveButton(okText) { d, _ ->
            onClick?.invoke(true)
            d.cancel()
        }
        // If there is an onClick, then also show the cancel button
        if (onClick != null) {
            setNegativeButton(cancelText) { f, _ ->
                onClick.invoke(false)
                f.cancel()
            }
        }
        show()
    }
}
