package com.xrdroid;

import android.view.MotionEvent;
import android.view.Surface;

// Implemented by ShellHelper, which Shizuku runs as the shell user
interface IShellHelper {
    // Transaction id Shizuku uses to stop a user service
    void destroy() = 16777114;

    // Creates a trusted virtual display that renders into the given surface; returns its display id
    int createDisplay(String name, int width, int height, int densityDpi, in Surface surface) = 1;

    void releaseDisplay(int displayId) = 2;

    // Starts an activity fullscreen on a display, even one in desktop mode; returns whether it started
    boolean launchFullscreen(String component, int displayId) = 3;

    // Delivers a touch event to a display, as if a finger were on it. One-way calls arrive in order.
    oneway void injectMotion(in MotionEvent event, int displayId) = 4;

    // Presses and releases a key (e.g. Back) on a display
    oneway void injectKey(int keyCode, int displayId) = 5;
}
