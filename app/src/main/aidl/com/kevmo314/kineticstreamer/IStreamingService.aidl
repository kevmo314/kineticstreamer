// IStreamingService.aidl
package com.kevmo314.kineticstreamer;

parcelable StreamingConfiguration;

interface IStreamingService {
    void setPreviewSurface(in android.view.Surface surface);

    void startStreaming(in StreamingConfiguration configuration);

    void stopStreaming();

    boolean isStreaming();

    @nullable String getActiveCameraId();

    void setActiveCameraId(String cameraId);
}