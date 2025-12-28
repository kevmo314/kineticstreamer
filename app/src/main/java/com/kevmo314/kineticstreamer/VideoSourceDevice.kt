package com.kevmo314.kineticstreamer

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Wrapper class that represents a video source device which can be either
 * a camera ID or a USB device.
 */
sealed class VideoSourceDevice : Parcelable {
    data class Camera(val cameraId: String) : VideoSourceDevice() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(0) // Type identifier for Camera
            parcel.writeString(cameraId)
        }

        override fun describeContents(): Int = 0

        /**
         * Check if camera permission is granted
         * Note: This doesn't request permission, just checks if it's granted.
         * Permission requests for dangerous permissions like CAMERA must be done from an Activity.
         * @return true if permission is granted, false otherwise
         */
        fun hasPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * Request camera permission using a transparent helper Activity
         * @return true if permission was granted, false otherwise
         */
        suspend fun requestPermission(context: Context): Boolean = suspendCancellableCoroutine { continuation ->
            // Check if already granted
            if (hasPermission(context)) {
                Log.i("VideoSourceDevice", "Camera permission already granted")
                continuation.resume(true)
                return@suspendCancellableCoroutine
            }

            // Generate unique request ID
            val requestId = "camera_${System.currentTimeMillis()}"

            // Register receiver for permission result
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == PermissionRequestActivity.ACTION_PERMISSION_RESULT) {
                        val receivedId = intent.getStringExtra(PermissionRequestActivity.EXTRA_REQUEST_ID)
                        if (receivedId == requestId) {
                            val granted = intent.getBooleanExtra(PermissionRequestActivity.EXTRA_PERMISSION_GRANTED, false)
                            Log.i("VideoSourceDevice", "Camera permission ${if (granted) "granted" else "denied"}")

                            // Unregister receiver
                            context.unregisterReceiver(this)

                            // Resume coroutine
                            if (continuation.isActive) {
                                continuation.resume(granted)
                            }
                        }
                    }
                }
            }

            // Register receiver
            val filter = IntentFilter(PermissionRequestActivity.ACTION_PERMISSION_RESULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            // Handle cancellation
            continuation.invokeOnCancellation {
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: Exception) {
                    // Receiver might not be registered
                }
            }

            // Launch permission request activity
            val intent = Intent(context, PermissionRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(PermissionRequestActivity.EXTRA_PERMISSION, Manifest.permission.CAMERA)
                putExtra(PermissionRequestActivity.EXTRA_REQUEST_ID, requestId)
            }
            context.startActivity(intent)

            Log.i("VideoSourceDevice", "Launching permission request for camera")
        }
    }

    data class UsbCamera(val usbDevice: UsbDevice) : VideoSourceDevice() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(1) // Type identifier for UsbCamera
            parcel.writeParcelable(usbDevice, flags)
        }

        override fun describeContents(): Int = 0

        /**
         * Request USB permission for this specific device
         * @return true if permission was granted, false otherwise
         */
        suspend fun requestPermission(context: Context): Boolean = suspendCancellableCoroutine { continuation ->
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager

            // Check if we already have permission
            if (manager.hasPermission(usbDevice)) {
                Log.i("VideoSourceDevice", "Already have permission for ${usbDevice.productName ?: usbDevice.deviceName}")
                continuation.resume(true)
                return@suspendCancellableCoroutine
            }

            // Create a unique action for this request
            val action = "${StreamingService.UsbReceiver.ACTION_USB_PERMISSION}_${System.currentTimeMillis()}"

            // Register a receiver for the permission response
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (action == intent.action) {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        if (device?.deviceId == usbDevice.deviceId) {
                            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            Log.i("VideoSourceDevice", "Permission ${if (granted) "granted" else "denied"} for ${usbDevice.productName ?: usbDevice.deviceName}")

                            // Unregister this receiver
                            context.unregisterReceiver(this)

                            // Resume the coroutine with the result
                            if (continuation.isActive) {
                                continuation.resume(granted)
                            }
                        }
                    }
                }
            }

            // Register the receiver
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            // Handle cancellation
            continuation.invokeOnCancellation {
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: Exception) {
                    // Receiver might not be registered
                }
            }

            // Request permission
            val intent = Intent(action)
            manager.requestPermission(
                usbDevice,
                PendingIntent.getBroadcast(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

            Log.i("VideoSourceDevice", "Requesting permission for ${usbDevice.productName ?: usbDevice.deviceName}")
        }
    }

    /**
     * Returns a unique identifier for this device for use in preferences/settings
     */
    val identifier: String
        get() {
            return when (this) {
                is Camera -> "camera:$cameraId"
                is UsbCamera -> "usb:${usbDevice.deviceId}"
            }
        }

    /**
     * Returns a display name for this device
     */
    val displayName: String
        get() {
            return when (this) {
                is Camera -> "Camera $cameraId"
                is UsbCamera -> usbDevice.productName ?: "USB Camera ${usbDevice.deviceId}"
            }
        }

    companion object {
        /**
         * Creates a VideoSourceDevice from a stored identifier string
         * Returns null if the identifier is invalid or the USB device is not found
         */
        fun fromIdentifier(identifier: String, availableUsbDevices: List<UsbDevice> = emptyList()): VideoSourceDevice? {
            return when {
                identifier.startsWith("camera:") -> {
                    val cameraId = identifier.removePrefix("camera:")
                    Camera(cameraId)
                }
                identifier.startsWith("usb:") -> {
                    val deviceIdStr = identifier.removePrefix("usb:")
                    val deviceId = deviceIdStr.toIntOrNull() ?: return null
                    val usbDevice = availableUsbDevices.find { it.deviceId == deviceId }
                    usbDevice?.let { UsbCamera(it) }
                }
                else -> null
            }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<VideoSourceDevice> = object : Parcelable.Creator<VideoSourceDevice> {
            override fun createFromParcel(parcel: Parcel): VideoSourceDevice {
                return when (parcel.readInt()) {
                    0 -> Camera(parcel.readString()!!)
                    1 -> UsbCamera(parcel.readParcelable(UsbDevice::class.java.classLoader)!!)
                    else -> throw IllegalArgumentException("Unknown VideoSourceDevice type")
                }
            }

            override fun newArray(size: Int): Array<VideoSourceDevice?> {
                return arrayOfNulls(size)
            }
        }
    }
}
