package com.kevmo314.kineticstreamer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Transparent activity used to request dangerous permissions like CAMERA
 * This is needed because Services cannot show permission dialogs
 */
class PermissionRequestActivity : Activity() {
    companion object {
        const val EXTRA_PERMISSION = "permission"
        const val ACTION_PERMISSION_RESULT = "com.kevmo314.kineticstreamer.PERMISSION_RESULT"
        const val EXTRA_PERMISSION_GRANTED = "permission_granted"
        const val EXTRA_REQUEST_ID = "request_id"

        private const val REQUEST_CODE = 1001
    }

    private var requestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make the activity transparent
        setTheme(android.R.style.Theme_Translucent_NoTitleBar)

        val permission = intent.getStringExtra(EXTRA_PERMISSION)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID)

        if (permission == null || requestId == null) {
            Log.e("PermissionRequestActivity", "Missing permission or request ID")
            finish()
            return
        }

        // Check if permission is already granted
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            sendResult(true)
            finish()
            return
        }

        // Request the permission
        ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() &&
                          grantResults[0] == PackageManager.PERMISSION_GRANTED
            sendResult(granted)
        }

        finish()
    }

    private fun sendResult(granted: Boolean) {
        // Send broadcast with result
        val intent = Intent(ACTION_PERMISSION_RESULT).apply {
            putExtra(EXTRA_PERMISSION_GRANTED, granted)
            putExtra(EXTRA_REQUEST_ID, requestId)
            setPackage(packageName) // Make it explicit to this app
        }
        sendBroadcast(intent)

        Log.i("PermissionRequestActivity", "Permission result sent: granted=$granted, requestId=$requestId")
    }
}
