// IStreamingService.aidl
package com.kevmo314.kineticstreamer;

parcelable StreamingConfiguration;

interface IStreamingService {
    void setPreviewSurface(in android.view.Surface surface);

    @nullable String startStreaming(in StreamingConfiguration configuration, String outputConfigurationsJson);

    void stopStreaming();

    boolean isStreaming();

    @nullable String getActiveCameraId();

    void setActiveCameraId(String cameraId);
}