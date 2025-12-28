package com.kevmo314.kineticstreamer

import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun DeviceSelector(
    onDismissRequest: () -> Unit,
    selectedVideoDevice: VideoSourceDevice?,
    onVideoDeviceSelected: (VideoSourceDevice) -> Unit,
    selectedAudioDeviceId: Int?,
    onAudioDeviceSelected: (Int?) -> Unit,
) {
    val context = LocalContext.current
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val cameraIds = cameraManager.cameraIdList
    val usbDevices = usbManager.deviceList.values.filter { it.isUvc() || it.isUac() }
    val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

    val scrollState = rememberScrollState()

    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Select Devices",
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentSize(Alignment.Center)
                        .padding(16.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineMedium
                )

                // Scrollable list of devices
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 8.dp)
                ) {
                    // Video Devices Section
                    Text(
                        text = "Video Devices",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    // Built-in cameras
                    for (cameraId in cameraIds) {
                        val device = VideoSourceDevice.Camera(cameraId)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedVideoDevice == device,
                                onClick = {
                                    onVideoDeviceSelected(device)
                                }
                            )
                            Text(
                                text = device.displayName,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // USB cameras
                    for (usbDevice in usbDevices.filter { it.isUvc() }) {
                        val device = VideoSourceDevice.UsbCamera(usbDevice)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedVideoDevice == device,
                                onClick = {
                                    onVideoDeviceSelected(device)
                                }
                            )
                            Text(
                                text = device.displayName,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 16.dp))

                    // Audio Devices Section
                    Text(
                        text = "Audio Devices",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    // Default microphone option
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = selectedAudioDeviceId == null,
                            onClick = {
                                onAudioDeviceSelected(null)
                            }
                        )
                        Text(
                            text = "Default Microphone",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Available audio devices
                    for (audioDevice in audioDevices) {
                        val deviceName = when (audioDevice.type) {
                            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Microphone"
                            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
                            AudioDeviceInfo.TYPE_USB_DEVICE -> audioDevice.productName?.toString() ?: "USB Audio Device"
                            AudioDeviceInfo.TYPE_USB_HEADSET -> audioDevice.productName?.toString() ?: "USB Headset"
                            AudioDeviceInfo.TYPE_USB_ACCESSORY -> audioDevice.productName?.toString() ?: "USB Audio Accessory"
                            AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
                            else -> audioDevice.productName?.toString() ?: "Audio Device ${audioDevice.id}"
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedAudioDeviceId == audioDevice.id,
                                onClick = {
                                    onAudioDeviceSelected(audioDevice.id)
                                }
                            )
                            Text(
                                text = deviceName,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Divider()

                TextButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Text("OK")
                }
            }
        }
    }
}
