// Does a window-capable EGL config exist whose native visual matches one AWT
// will hand us? Every spike so far used EGL_NO_SURFACE + an FBO; a real skiko
// Redrawer needs eglCreateWindowSurface on the AWT component's X11 window.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <X11/Xlib.h>
#include <stdio.h>
int main(void) {
    Display *xd = XOpenDisplay(NULL);
    if (!xd) { puts("no display"); return 1; }
    int scr = DefaultScreen(xd);
    Visual *dv = DefaultVisual(xd, scr);
    printf("X default visual id=0x%lx depth=%d\n", XVisualIDFromVisual(dv), DefaultDepth(xd, scr));

    PFNEGLGETPLATFORMDISPLAYEXTPROC gpd =
        (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
    EGLDisplay d = gpd(EGL_PLATFORM_X11_KHR, xd, NULL);
    eglInitialize(d, NULL, NULL);
    eglBindAPI(EGL_OPENGL_API);

    EGLint attr[] = {EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                     EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
                     EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
                     EGL_NONE};
    EGLConfig cfgs[64]; EGLint n = 0;
    if (!eglChooseConfig(d, attr, cfgs, 64, &n) || n < 1) { puts("RESULT egl-window-config ok=false (none)"); return 1; }

    int match_default = 0, with_alpha = 0;
    printf("window-capable OpenGL configs: %d\n", n);
    for (int i = 0; i < n; i++) {
        int quiet = i >= 8;
        EGLint vid = 0, a = 0, dep = 0, st = 0;
        eglGetConfigAttrib(d, cfgs[i], EGL_NATIVE_VISUAL_ID, &vid);
        eglGetConfigAttrib(d, cfgs[i], EGL_ALPHA_SIZE, &a);
        eglGetConfigAttrib(d, cfgs[i], EGL_DEPTH_SIZE, &dep);
        eglGetConfigAttrib(d, cfgs[i], EGL_STENCIL_SIZE, &st);
        if (!quiet) printf("  cfg[%d] visual=0x%x alpha=%d depth=%d stencil=%d\n", i, vid, a, dep, st);
        if ((unsigned long)vid == XVisualIDFromVisual(dv)) match_default = 1;
        if (a == 8) with_alpha = 1;
    }
    printf("RESULT egl-window-config ok=true count=%d matches-x-default-visual=%d has-alpha-config=%d\n",
           n, match_default, with_alpha);
    return 0;
}
