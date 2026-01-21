// IStreamingService.aidl
package com.kevmo314.kineticstreamer;

import com.kevmo314.kineticstreamer.IAudioLevelCallback;

parcelable StreamingConfiguration;
parcelable VideoSourceDevice;

interface IStreamingService {
    void setPreviewSurface(in android.view.Surface surface);

    void startStreaming(in StreamingConfiguration configuration);

    void stopStreaming();

    boolean isStreaming();
    
    int getCurrentBitrate();

    float getCurrentFps();
    
    void setAudioLevelCallback(in IAudioLevelCallback callback);
    
    // WebView overlay methods
    void setWebViewOverlay(in String url, int x, int y, int width, int height);

    void updateWebViewOverlay(in String url, int x, int y, int width, int height);

    void removeWebViewOverlay();

    void refreshWebViewOverlay();

    // WHIP connection state methods
    String getWhipIceConnectionState();

    String getWhipPeerConnectionState();
}