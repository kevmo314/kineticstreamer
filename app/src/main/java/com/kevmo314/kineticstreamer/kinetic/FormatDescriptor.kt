package com.kevmo314.kineticstreamer.kinetic

/**
 * UVC format descriptor
 */
data class FormatDescriptor(
    val format: Long,
    val width: Int,
    val height: Int,
    val fps: Int
)
