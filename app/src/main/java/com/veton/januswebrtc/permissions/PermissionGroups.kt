package com.veton.januswebrtc.permissions

import android.Manifest
import com.veton.januswebrtc.R


const val RC_AUDIO_VIDEO = 1

object PermissionGroups {

    val audioVideo = PermissionGroup(
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        ),
        null,
        RC_AUDIO_VIDEO,
        R.string.explain_permission_audio_camera
    )
}
