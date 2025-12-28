// IAudioLevelCallback.aidl
package com.kevmo314.kineticstreamer;

interface IAudioLevelCallback {
    void onAudioLevels(in float[] levels);
}