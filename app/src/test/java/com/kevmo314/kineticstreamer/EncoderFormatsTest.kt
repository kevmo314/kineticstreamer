package com.kevmo314.kineticstreamer

import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderFormatsTest {
    @Test
    fun aacDelay_fallbackIs2048SamplesAt48k() {
        assertEquals(42_666L, AAC_FALLBACK_ENCODER_DELAY_US)
    }

    @Test
    fun aacDelay_usesReportedSamples() {
        assertEquals(21_333L, aacEncoderDelayUs(1024))
        assertEquals(0L, aacEncoderDelayUs(0, fallbackUs = 42_666L))
    }

    @Test
    fun aacDelay_fallsBackWhenUnreportedOrInvalid() {
        assertEquals(42_666L, aacEncoderDelayUs(null))
        assertEquals(0L, aacEncoderDelayUs(null, fallbackUs = 0L))
        assertEquals(42_666L, aacEncoderDelayUs(-1))
    }
}
