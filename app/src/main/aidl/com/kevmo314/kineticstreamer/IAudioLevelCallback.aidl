// IAudioLevelCallback.aidl
package com.kevmo314.kineticstreamer;

oneway interface IAudioLevelCallback {
    void onAudioLevels(in float[] levels);
}