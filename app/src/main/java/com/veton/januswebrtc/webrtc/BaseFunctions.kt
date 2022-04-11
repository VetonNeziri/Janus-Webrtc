package com.veton.januswebrtc.webrtc

import android.content.Context
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.VideoCapturer
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordErrorCallback
import org.webrtc.audio.JavaAudioDeviceModule.AudioTrackErrorCallback
import timber.log.Timber

object BaseFunctions {

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        val deviceNames = enumerator.deviceNames

        // First, try to find front facing camera
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val videoCapturer: VideoCapturer? = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }

        // Front facing camera not found, try something else
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                val videoCapturer: VideoCapturer? = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }
        return null
    }

    fun createVideoCapturer(context: Context): VideoCapturer? {
        val videoCapturer: VideoCapturer? = if (useCamera2(context)) {
            createCameraCapturer(Camera2Enumerator(context))
        } else {
            createCameraCapturer(Camera1Enumerator(captureToTexture()))
        }
        if (videoCapturer == null) {
            return null
        }
        return videoCapturer
    }

    private fun useCamera2(context: Context): Boolean {
        return Camera2Enumerator.isSupported(context)
    }

    private fun captureToTexture(): Boolean {
        return true
    }

    fun createJavaAudioDevice(
        context: Context,
        peerConnectionParameters: PeerConnectionParameters,
        errors: Errors
    ): AudioDeviceModule? {
        // Enable/disable OpenSL ES playback.
        if (!peerConnectionParameters.useOpenSLES) {
            Timber.i("External OpenSLES ADM not implemented yet.")
        }

        // Set audio record error callbacks.
        val audioRecordErrorCallback: AudioRecordErrorCallback = object : AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                Timber.e(errorMessage)
                errors.reportError(errorMessage)
            }

            override fun onWebRtcAudioRecordStartError(
                errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode, errorMessage: String
            ) {
                Timber.e("onWebRtcAudioRecordStartError: $errorCode. $errorMessage")
                errors.reportError(errorMessage)
            }

            override fun onWebRtcAudioRecordError(errorMessage: String) {
                Timber.e(errorMessage)
                errors.reportError(errorMessage)
            }
        }
        val audioTrackErrorCallback: AudioTrackErrorCallback = object : AudioTrackErrorCallback {
            override fun onWebRtcAudioTrackInitError(errorMessage: String) {
                Timber.e(errorMessage)
                errors.reportError(errorMessage)
            }

            override fun onWebRtcAudioTrackStartError(
                errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode, errorMessage: String
            ) {
                Timber.e("onWebRtcAudioTrackStartError: $errorCode. $errorMessage")
                errors.reportError(errorMessage)
            }

            override fun onWebRtcAudioTrackError(errorMessage: String) {
                Timber.e(errorMessage)
                errors.reportError(errorMessage)
            }
        }
        return JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(!peerConnectionParameters.disableBuiltInAEC)
            .setUseHardwareNoiseSuppressor(!peerConnectionParameters.disableBuiltInNS)
            .setAudioRecordErrorCallback(audioRecordErrorCallback)
            .setAudioTrackErrorCallback(audioTrackErrorCallback)
            .createAudioDeviceModule()
    }
}