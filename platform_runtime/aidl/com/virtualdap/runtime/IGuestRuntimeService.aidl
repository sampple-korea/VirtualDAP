package com.virtualdap.runtime;

import android.os.ParcelFileDescriptor;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import com.virtualdap.runtime.IGuestRuntimeCallback;

/**
 * Versioned boundary implemented by an OEM/platform-signed runtime service. The image descriptor
 * remains owned by the host app; the runtime never receives access to the app's private path.
 */
interface IGuestRuntimeService {
    int getProtocolVersion();
    String getBackendName();
    int getState();
    String getStateDetail();
    void start(in ParcelFileDescriptor guestImage, String manifest, in byte[] bridgeToken, IGuestRuntimeCallback callback);
    void stop();
    void attachDisplay(in Surface surface, int width, int height, int densityDpi);
    void detachDisplay();
    void injectMotionEvent(in MotionEvent event);
    void injectKeyEvent(in KeyEvent event);
}
