package com.kevmo314.kineticstreamer.kinetic

/**
 * Main Kinetic library class that loads the native library
 */
object Kinetic {
    init {
        System.loadLibrary("kinetic")
        init()
    }

    @JvmStatic
    private external fun init()
}
