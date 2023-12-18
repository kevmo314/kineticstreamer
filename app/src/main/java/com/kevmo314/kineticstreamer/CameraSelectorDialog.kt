package com.kevmo314.kineticstreamer

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun CameraSelector(
    onDismissRequest: () -> Unit,
    selectedCameraId: String,
    onCameraSelected: (String) -> Unit,
) {
    val cameraManager =
        LocalContext.current.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val cameraIds = cameraManager.cameraIdList

    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = "Select a camera",
            )
            Divider()
            // scrollable list of cameras
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(cameraIds.size) { index ->
//                    val characteristics = cameraManager.getCameraCharacteristics(cameraIds[index])

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedCameraId == cameraIds[index],
                            onClick = {
                                onCameraSelected(cameraIds[index])
                            }
                        )
                        Text(
                            text = "Camera ${index + 1}",
                            modifier = Modifier
                                .fillMaxWidth()
                        )
                    }
                }
            }
            Divider()
            TextButton(onClick = onDismissRequest) {
                Text("Ok")
            }
        }
    }
}