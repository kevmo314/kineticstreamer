package com.kevmo314.kineticstreamer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class StreamingConfiguration(val codec: String): Parcelable
