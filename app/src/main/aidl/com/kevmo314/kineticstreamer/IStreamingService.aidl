// IStreamingService.aidl
package com.kevmo314.kineticstreamer;

interface IStreamingService {
    void setPreviewSurface(in android.view.Surface surface);

    void startStreaming();

    void stopStreaming();

    boolean isStreaming();

    @nullable String getActiveCameraId();

    void setActiveCameraId(String cameraId);
}