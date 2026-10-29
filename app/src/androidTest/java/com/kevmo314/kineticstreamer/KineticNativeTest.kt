package com.kevmo314.kineticstreamer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kevmo314.kineticstreamer.kinetic.Kinetic
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies libkinetic.so and its bundled dependencies load and bind JNI on a real ABI. */
@RunWith(AndroidJUnit4::class)
class KineticNativeTest {
    @Test
    fun nativeLibraryLoadsAndInitializes() {
        // Throws UnsatisfiedLinkError if libkinetic.so (or libsrt/librist/libusb) is missing
        // or JNI_OnLoad/init fail.
        assertNotNull(Kinetic)
    }
}
