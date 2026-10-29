package com.kevmo314.kineticstreamer

import android.os.Parcel
import androidx.core.os.ParcelCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoSourceDeviceParcelTest {
    @Test
    fun videoSourceDeviceSurvivesParcelRoundTrip() {
        for (device in listOf(VideoSourceDevice.Camera("0"), VideoSourceDevice.RtmpServer(1936))) {
            val parcel = Parcel.obtain()
            try {
                parcel.writeParcelable(device, 0)
                parcel.setDataPosition(0)
                assertEquals(device, ParcelCompat.readParcelable(parcel, VideoSourceDevice::class.java.classLoader, VideoSourceDevice::class.java))
            } finally {
                parcel.recycle()
            }
        }
    }
}
