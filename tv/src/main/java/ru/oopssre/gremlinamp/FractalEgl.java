package ru.oopssre.gremlinamp;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.view.Surface;

final class FractalEgl {
    private static final int[] CONTEXT_ATTRIBUTES = {
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE
    };
    private static final int[] NO_ATTRIBUTES = {EGL14.EGL_NONE};

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private Surface window;
    private boolean initialized;

    boolean isOpen() { return surface != EGL14.EGL_NO_SURFACE; }

    void open(SurfaceTexture texture) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        check(display != EGL14.EGL_NO_DISPLAY, "eglGetDisplay");
        check(EGL14.eglInitialize(display, null, 0, null, 0), "eglInitialize");
        initialized = true;
        check(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API), "eglBindAPI");
        window = new Surface(texture);
        try {
            openWindow(8, 8, 8);
        } catch (IllegalStateException first) {
            closeWindow();
            try {
                openWindow(5, 6, 5);
            } catch (IllegalStateException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    private void openWindow(int red, int green, int blue) {
        int[] attributes = {
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, red, EGL14.EGL_GREEN_SIZE, green, EGL14.EGL_BLUE_SIZE, blue,
                EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_NONE
        };
        int[] count = new int[1];
        check(EGL14.eglChooseConfig(display, attributes, 0, null, 0, 0, count, 0),
                "eglChooseConfig");
        if (count[0] == 0) throw new IllegalStateException("No OpenGL ES 2.0 window configuration");
        EGLConfig[] configs = new EGLConfig[count[0]];
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.length, count, 0),
                "eglChooseConfig");
        EGLConfig selected = null;
        int[] value = new int[1];
        for (int i = 0; i < count[0]; i++) {
            EGLConfig config = configs[i];
            if (matches(config, EGL14.EGL_RED_SIZE, red, value)
                    && matches(config, EGL14.EGL_GREEN_SIZE, green, value)
                    && matches(config, EGL14.EGL_BLUE_SIZE, blue, value)) {
                selected = config;
                break;
            }
        }
        if (selected == null) throw new IllegalStateException("No EGL configuration for the requested RGB format");
        context = EGL14.eglCreateContext(display, selected, EGL14.EGL_NO_CONTEXT, CONTEXT_ATTRIBUTES, 0);
        check(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext");
        surface = EGL14.eglCreateWindowSurface(display, selected, window, NO_ATTRIBUTES, 0);
        check(surface != EGL14.EGL_NO_SURFACE, "eglCreateWindowSurface");
        check(EGL14.eglMakeCurrent(display, surface, surface, context), "eglMakeCurrent");
    }

    private boolean matches(EGLConfig config, int attribute, int expected, int[] value) {
        return EGL14.eglGetConfigAttrib(display, config, attribute, value, 0) && value[0] == expected;
    }

    int width() { return size(EGL14.EGL_WIDTH); }
    int height() { return size(EGL14.EGL_HEIGHT); }

    private int size(int attribute) {
        int[] value = new int[1];
        check(EGL14.eglQuerySurface(display, surface, attribute, value, 0), "eglQuerySurface");
        if (value[0] <= 0) throw new IllegalStateException("Invalid visualization surface size");
        return value[0];
    }

    int swap() {
        return EGL14.eglSwapBuffers(display, surface) ? EGL14.EGL_SUCCESS : EGL14.eglGetError();
    }

    void close() {
        try {
            if (initialized) {
                closeWindow();
                EGL14.eglTerminate(display);
            }
            if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglReleaseThread();
        } finally {
            initialized = false;
            display = EGL14.EGL_NO_DISPLAY;
            context = EGL14.EGL_NO_CONTEXT;
            surface = EGL14.EGL_NO_SURFACE;
            if (window != null) {
                window.release();
                window = null;
            }
        }
    }

    private void closeWindow() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
        surface = EGL14.EGL_NO_SURFACE;
        context = EGL14.EGL_NO_CONTEXT;
    }

    private static void check(boolean success, String operation) {
        if (!success) throw failure(operation, EGL14.eglGetError());
    }

    static IllegalStateException failure(String operation, int error) {
        return new IllegalStateException(operation + " failed (EGL 0x" + Integer.toHexString(error) + ")");
    }
}
