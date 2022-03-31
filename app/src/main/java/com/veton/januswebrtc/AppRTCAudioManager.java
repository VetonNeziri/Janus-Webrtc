/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package com.veton.januswebrtc;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import androidx.annotation.Nullable;

import org.webrtc.ThreadUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import timber.log.Timber;

/**
 * AppRTCAudioManager manages all audio related parts of the AppRTC demo.
 */
@SuppressWarnings("WeakerAccess")
public class AppRTCAudioManager {
	private static final String SPEAKERPHONE_AUTO = "auto";
	private static final String SPEAKERPHONE_TRUE = "true";
	private static final String SPEAKERPHONE_FALSE = "false";

	/**
	 * AudioDevice is the names of possible audio devices that we currently
	 * support.
	 * <p>
	 * TODO Support all additional audio devices
	 */
	public enum AudioDevice {
		SPEAKER_PHONE, WIRED_HEADSET, EARPIECE, /*BLUETOOTH,*/ NONE
	}

	/**
	 * AudioManager state.
	 */
	public enum AudioManagerState {
		UNINITIALIZED,
		PREINITIALIZED,
		RUNNING,
	}

	/** Selected audio device change event. */
	public interface AudioManagerEvents {
		// Callback fired once audio device is changed or list of available audio devices changed.
		void onAudioDeviceChanged(
			AudioDevice selectedAudioDevice, Set<AudioDevice> availableAudioDevices);
	}

	private final Context apprtcContext;
	@Nullable
	private final AudioManager audioManager;
	@Nullable
	private AudioManagerEvents audioManagerEvents;
	private AudioManagerState amState;
	private int savedAudioMode = AudioManager.MODE_INVALID;
	private boolean savedIsSpeakerPhoneOn = false;
	private boolean savedIsMicrophoneMute = false;
	private boolean hasWiredHeadset = false;
	// Default audio device; speaker phone for video calls or earpiece for audio
	// only calls.
	private final AudioDevice defaultAudioDevice;
	// Contains the currently selected audio device.
	// This device is changed automatically using a certain scheme where e.g.
	// a wired headset "wins" over speaker phone. It is also possible for a
	// user to explicitly select a device (and overrid any predefined scheme).
	// See |userSelectedAudioDevice| for details.
	private AudioDevice selectedAudioDevice;
	// Contains the user-selected audio device which overrides the predefined
	// selection scheme.
	private AudioDevice userSelectedAudioDevice;
	// Contains a list of available audio devices. A Set collection is used to
	// avoid duplicate elements.
	private Set<AudioDevice> audioDevices = new HashSet<>();
	// Broadcast receiver for wired headset intent broadcasts.
	private final BroadcastReceiver wiredHeadsetReceiver;
	// Callback method for changes in audio focus.
	@Nullable
	private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;

	/* Receiver which handles changes in wired headset availability. */
	private class WiredHeadsetReceiver extends BroadcastReceiver {
		private static final int STATE_UNPLUGGED = 0;
		private static final int STATE_PLUGGED = 1;
		private static final int HAS_NO_MIC = 0;
		private static final int HAS_MIC = 1;
		@Override
		public void onReceive(Context context, Intent intent) {
			int state = intent.getIntExtra("state", STATE_UNPLUGGED);
			int microphone = intent.getIntExtra("microphone", HAS_NO_MIC);
			String name = intent.getStringExtra("name");
			Timber.d("WiredHeadsetReceiver.onReceive: "
				+ "a=" + intent.getAction() + ", s="
				+ (state == STATE_UNPLUGGED ? "unplugged" : "plugged") + ", m="
				+ (microphone == HAS_MIC ? "mic" : "no mic") + ", n=" + name + ", sb="
				+ isInitialStickyBroadcast());
			hasWiredHeadset = (state == STATE_PLUGGED);
			updateAudioDeviceState();
		}
	}

	/**
	 * Construction
	 */
	public static AppRTCAudioManager create(Context context) {
		return new AppRTCAudioManager(context);
	}

	private AppRTCAudioManager(Context context) {
		Timber.d("AppRTCAudioManager");
		ThreadUtils.checkIsOnMainThread();
		apprtcContext = context;
		audioManager = ((AudioManager) context.getSystemService(Context.AUDIO_SERVICE));
		wiredHeadsetReceiver = new WiredHeadsetReceiver();
		amState = AudioManagerState.UNINITIALIZED;
		// TODO Get this from a setting instead of hard-coding to true
		// Contains speakerphone setting: auto, true or false
		String useSpeakerphone = SPEAKERPHONE_TRUE;
		Timber.d("useSpeakerphone: %s", useSpeakerphone);
		if (useSpeakerphone.equals(SPEAKERPHONE_FALSE)) {
			defaultAudioDevice = AudioDevice.EARPIECE;
		} else {
			defaultAudioDevice = AudioDevice.SPEAKER_PHONE;
		}
		Timber.d("defaultAudioDevice: %s", defaultAudioDevice);
	}

	/**
	 * 
	 * @param audioManagerEvents
	 */
	@SuppressLint("WrongConstant")
	public void start(AudioManagerEvents audioManagerEvents) {
		Timber.d("start");
		ThreadUtils.checkIsOnMainThread();
		if (amState == AudioManagerState.RUNNING) {
			Timber.e("AudioManager is already active");
			return;
		}
		Timber.d("AudioManager starts...");
		this.audioManagerEvents = audioManagerEvents;
		amState = AudioManagerState.RUNNING;
		// Store current audio state so we can restore it when close() is called.
		assert audioManager != null;
		savedAudioMode = audioManager.getMode();
		savedIsSpeakerPhoneOn = audioManager.isSpeakerphoneOn();
		savedIsMicrophoneMute = audioManager.isMicrophoneMute();
		hasWiredHeadset = hasWiredHeadset();
		// Create an AudioManager.OnAudioFocusChangeListener instance.
		// Called on the listener to notify if the audio focus for this listener has been changed.
		// The |focusChange| value indicates whether the focus was gained, whether the focus was lost,
		// and whether that loss is transient, or whether the new focus holder will hold it for an
		// unknown amount of time.
		// TODO(henrika): possibly extend support of handling audio-focus changes. Only contains
		// logging for now.
		audioFocusChangeListener = focusChange -> {
			final String typeOfChange;
			switch (focusChange) {
				case AudioManager.AUDIOFOCUS_GAIN:
					typeOfChange = "AUDIOFOCUS_GAIN";
					break;
				case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT:
					typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT";
					break;
				case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE:
					typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE";
					break;
				case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:
					typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK";
					break;
				case AudioManager.AUDIOFOCUS_LOSS:
					typeOfChange = "AUDIOFOCUS_LOSS";
					break;
				case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
					typeOfChange = "AUDIOFOCUS_LOSS_TRANSIENT";
					break;
				case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
					typeOfChange = "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK";
					break;
				default:
					typeOfChange = "AUDIOFOCUS_INVALID";
					break;
			}
			Timber.d("onAudioFocusChange: %s", typeOfChange);
		};
		// Request audio playout focus (without ducking) and install listener for changes in focus.
		int result = audioManager.requestAudioFocus(audioFocusChangeListener,
			AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
		if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
			Timber.d("Audio focus request granted for VOICE_CALL streams");
		} else {
			Timber.e("Audio focus request failed");
			retryAudioFocus();
		}
		// Start by setting MODE_IN_COMMUNICATION as default audio mode. It is
		// required to be in this mode when playout and/or recording starts for
		// best possible VoIP performance.
		audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
		// Always disable microphone mute during a WebRTC call.
		setMicrophoneMute(false);
		// Set initial device states.
		userSelectedAudioDevice = AudioDevice.NONE;
		selectedAudioDevice = AudioDevice.NONE;
		audioDevices.clear();
		// Do initial selection of audio device.
		// TODO This setting can later be changed
		// either by adding/removing a BT or wired headset or by covering/uncovering
		// the proximity sensor.
		updateAudioDeviceState();
		registerReceiver(wiredHeadsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
		Timber.d("AudioManager started");
	}

	private void retryAudioFocus(){
		if (amState == AudioManagerState.RUNNING){
			audioManager.requestAudioFocus(audioFocusChangeListener,
				AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
		}
	}

	@SuppressLint("WrongConstant")
	public void stop() {
		Timber.d("stop");
		ThreadUtils.checkIsOnMainThread();
		if (amState != AudioManagerState.RUNNING) {
			Timber.e("Trying to stop AudioManager in incorrect state: %s", amState);
			return;
		}
		amState = AudioManagerState.UNINITIALIZED;
		unregisterReceiver(wiredHeadsetReceiver);
		// Restore previously stored audio states.
		setSpeakerphoneOn(savedIsSpeakerPhoneOn);
		setMicrophoneMute(savedIsMicrophoneMute);
		if (audioManager != null) {
			audioManager.setMode(savedAudioMode);
			// Abandon audio focus. Gives the previous focus owner, if any, focus.
			audioManager.abandonAudioFocus(audioFocusChangeListener);
		}
		audioFocusChangeListener = null;
		Timber.d("Abandoned audio focus for VOICE_CALL streams");
		audioManagerEvents = null;
		Timber.d("AudioManager stopped");
	}

	/** Changes selection of the currently active audio device. */
	private void setAudioDeviceInternal(AudioDevice device) {
		Timber.d("setAudioDeviceInternal(device=" + device + ")");
		if (!audioDevices.contains(device)) {
			throw new AssertionError("Invalid Audio Device Selected");
		}
		switch (device) {
			case SPEAKER_PHONE:
				setSpeakerphoneOn(true);
				break;
			case EARPIECE:
				setSpeakerphoneOn(false);
				break;
			case WIRED_HEADSET:
				setSpeakerphoneOn(false);
				break;
			default:
				Timber.e("Invalid audio device selection");
				break;
		}
		selectedAudioDevice = device;
	}

	/** Changes selection of the currently active audio device. */
	public void selectAudioDevice(AudioDevice device) {
		ThreadUtils.checkIsOnMainThread();
		if (!audioDevices.contains(device)) {
			Timber.e("Can not select " + device + " from available " + audioDevices);
		}
		userSelectedAudioDevice = device;
		updateAudioDeviceState();
	}

	/** Returns current set of available/selectable audio devices. */
	public Set<AudioDevice> getAudioDevices() {
		ThreadUtils.checkIsOnMainThread();
		return Collections.unmodifiableSet(new HashSet<>(audioDevices));
	}
	/** Returns the currently selected audio device. */
	public AudioDevice getSelectedAudioDevice() {
		ThreadUtils.checkIsOnMainThread();
		return selectedAudioDevice;
	}

	/** Helper method for receiver registration. */
	private void registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
		apprtcContext.registerReceiver(receiver, filter);
	}
	/** Helper method for unregistration of an existing receiver. */
	private void unregisterReceiver(BroadcastReceiver receiver) {
		apprtcContext.unregisterReceiver(receiver);
	}

	/**
	 * Sets the speaker phone mode.
	 */
	public void setSpeakerphoneOn(boolean on) {
		if (audioManager == null) {
			return;
		}
		boolean wasOn = audioManager.isSpeakerphoneOn();
		if (wasOn == on) {
			return;
		}
		audioManager.setSpeakerphoneOn(on);
	}

	/**
	 * Sets the microphone mute state.
	 */
	public void setMicrophoneMute(boolean on) {
		if (audioManager == null) {
			return;
		}
		boolean wasMuted = audioManager.isMicrophoneMute();
		if (wasMuted == on) {
			return;
		}
		audioManager.setMicrophoneMute(on);
	}

	/** Gets the current earpiece state. */
	private boolean hasEarpiece() {
		return apprtcContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
	}

	/**
	 * Checks whether a wired headset is connected or not.
	 * This is not a valid indication that audio playback is actually over
	 * the wired headset as audio routing depends on other conditions. We
	 * only use it as an early indicator (during initialization) of an attached
	 * wired headset.
	 */
	@Deprecated
	private boolean hasWiredHeadset() {
		if (audioManager != null) {
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
				return audioManager.isWiredHeadsetOn();
			} else {
				@SuppressLint("WrongConstant") final AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL);
				for (AudioDeviceInfo device : devices) {
					final int type = device.getType();
					if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
						Timber.d("hasWiredHeadset: found wired headset");
						return true;
					} else if (type == AudioDeviceInfo.TYPE_USB_DEVICE) {
						Timber.d("hasWiredHeadset: found USB audio device");
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Updates list of possible audio devices and make new device selection.
	 */
	private void updateAudioDeviceState() {
		ThreadUtils.checkIsOnMainThread();
		Timber.d("--- updateAudioDeviceState: "
			+ "wired headset=" + hasWiredHeadset);
		Timber.d("Device status: "
			+ "available=" + audioDevices + ", "
			+ "selected=" + selectedAudioDevice + ", "
			+ "user selected=" + userSelectedAudioDevice);
		// Update the set of available audio devices.
		Set<AudioDevice> newAudioDevices = new HashSet<>();
		if (hasWiredHeadset) {
			// If a wired headset is connected, then it is the only possible option.
			newAudioDevices.add(AudioDevice.WIRED_HEADSET);
		} else {
			// No wired headset, hence the audio-device list can contain speaker
			// phone (on a tablet), or speaker phone and earpiece (on mobile phone).
			newAudioDevices.add(AudioDevice.SPEAKER_PHONE);
			if (hasEarpiece()) {
				newAudioDevices.add(AudioDevice.EARPIECE);
			}
		}
		// Store state which is set to true if the device list has changed.
		boolean audioDeviceSetUpdated = !audioDevices.equals(newAudioDevices);
		// Update the existing audio device set.
		audioDevices = newAudioDevices;
		if (hasWiredHeadset && userSelectedAudioDevice == AudioDevice.SPEAKER_PHONE) {
			// If user selected speaker phone, but then plugged wired headset then make
			// wired headset as user selected device.
			userSelectedAudioDevice = AudioDevice.WIRED_HEADSET;
		}
		if (!hasWiredHeadset && userSelectedAudioDevice == AudioDevice.WIRED_HEADSET) {
			// If user selected wired headset, but then unplugged wired headset then make
			// speaker phone as user selected device.
			userSelectedAudioDevice = AudioDevice.SPEAKER_PHONE;
		}
		// Update selected audio device.
		final AudioDevice newAudioDevice;
		if (hasWiredHeadset) {
			// If a wired headset is connected, but Bluetooth is not, then wired headset is used as
			// audio device.
			newAudioDevice = AudioDevice.WIRED_HEADSET;
		} else {
			// No wired headset and no Bluetooth, hence the audio-device list can contain speaker
			// phone (on a tablet), or speaker phone and earpiece (on mobile phone).
			// |defaultAudioDevice| contains either AudioDevice.SPEAKER_PHONE or AudioDevice.EARPIECE
			// depending on the user's selection.
			newAudioDevice = defaultAudioDevice;
		}
		// Switch to new device but only if there has been any changes.
		if (newAudioDevice != selectedAudioDevice || audioDeviceSetUpdated) {
			// Do the required device switch.
			setAudioDeviceInternal(newAudioDevice);
			Timber.d("New device status: "
				+ "available=" + audioDevices + ", "
				+ "selected=" + newAudioDevice);
			if (audioManagerEvents != null) {
				// Notify a listening client that audio device has been changed.
				audioManagerEvents.onAudioDeviceChanged(selectedAudioDevice, audioDevices);
			}
		}
		Timber.d("--- updateAudioDeviceState done");
	}
}
