package dev.zanderp.innerdesk;

import android.view.Surface;
import android.view.SurfaceControl;

interface IMirrorService {
    String startMirror(int displayId, in Surface surface, in SurfaceControl parent, int width, int height, int dpi);
    oneway void injectPointer(int displayId, int action, float x, float y, long downTime, long eventTime);
    oneway void injectScroll(int displayId, float x, float y, float hScroll, float vScroll);
    oneway void injectFingers(int displayId, int action, int pointerCount, in float[] x, in float[] y, long downTime, long eventTime);
    oneway void injectMouse(int displayId, int action, float x, float y, long downTime, long eventTime);
    oneway void injectMouseButtons(int displayId, int action, float x, float y, long downTime, long eventTime, int buttons);
    oneway void injectKey(int displayId, int action, int keyCode);
    oneway void injectText(int displayId, String text);
    String setDisplayImePolicy(int displayId, int policy);
    int ping();
    String exec(String command);
    void stopMirror();
    void destroy();
}
