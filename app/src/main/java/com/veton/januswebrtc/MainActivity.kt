package com.veton.januswebrtc

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.veton.januswebrtc.databinding.ActivityMainBinding
import com.veton.januswebrtc.permissions.PermissionGroup
import com.veton.januswebrtc.permissions.PermissionGroups
import com.veton.januswebrtc.permissions.createActivityResultLauncher
import com.veton.januswebrtc.permissions.requestPermissionsForActivityResult
import timber.log.Timber

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val permissionRequestVideoCall = createActivityResultLauncher(PermissionGroups.audioVideo, ::onPermissionResult)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnVideo.setOnClickListener {
            makeVideoCall()
        }
    }

    private fun makeVideoCall() {
        requestPermissionsForActivityResult(PermissionGroups.audioVideo, permissionRequestVideoCall, ::onPermissionResult)
    }

    private fun onPermissionResult(group: PermissionGroup, granted: Boolean) {
        if (granted) {
            when (group) {
                PermissionGroups.audioVideo -> {
                    Timber.i("Video call initiated")
                    val intent = Intent(this, VideoActivity::class.java)
                    startActivity(intent)
                }
            }
        } else {
            Timber.i("Prompted for permission and it was denied.")
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
        }
    }
}