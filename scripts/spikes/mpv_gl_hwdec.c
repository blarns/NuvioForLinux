// Does GPU rendering remove the 4K readback bottleneck?
//
// mpv_sw_hwdec.c showed hwdec works but only buys ~23% at 2160p, because the
// GPU->CPU download of a 33MB frame replaces decode as the dominant cost. This
// exercises the other path: MPV_RENDER_API_TYPE_OPENGL rendering straight into
// an FBO, so the frame never leaves the GPU. Offscreen EGL, no window, so it can
// run headless and be compared against the SW numbers directly.
//
// build: gcc -O2 mpv_gl_hwdec.c -o mpv_gl_hwdec $(pkg-config --cflags --libs mpv egl gl)
// usage: ./mpv_gl_hwdec <file> <hwdec> <frames> [w] [h] [headers]

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <X11/Xlib.h>
#include <mpv/client.h>
#include <mpv/render_gl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef unsigned int GLuint;
typedef unsigned int GLenum;
typedef int GLsizei;
typedef int GLint;

static void (*p_glGenFramebuffers)(GLsizei, GLuint *);
static void (*p_glBindFramebuffer)(GLenum, GLuint);
static void (*p_glFramebufferTexture2D)(GLenum, GLenum, GLenum, GLuint, GLint);
static void (*p_glGenTextures)(GLsizei, GLuint *);
static void (*p_glBindTexture)(GLenum, GLuint);
static void (*p_glTexImage2D)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void *);
static void (*p_glTexParameteri)(GLenum, GLenum, GLint);
static GLenum (*p_glCheckFramebufferStatus)(GLenum);
static void (*p_glFinish)(void);

#define GL_TEXTURE_2D 0x0DE1
#define GL_FRAMEBUFFER 0x8D40
#define GL_COLOR_ATTACHMENT0 0x8CE0
#define GL_FRAMEBUFFER_COMPLETE 0x8CD5
#define GL_RGBA8 0x8058
#define GL_RGBA 0x1908
#define GL_UNSIGNED_BYTE 0x1401
#define GL_TEXTURE_MIN_FILTER 0x2801
#define GL_TEXTURE_MAG_FILTER 0x2800
#define GL_LINEAR 0x2601

static void *get_proc(void *ctx, const char *name) {
    (void)ctx;
    return (void *)eglGetProcAddress(name);
}

static void die(const char *what) { fprintf(stderr, "FAIL %s\n", what); exit(1); }

int main(int argc, char **argv) {
    const char *file = argv[1];
    const char *hwdec = argv[2];
    int want_frames = atoi(argv[3]);
    int w = argc > 4 ? atoi(argv[4]) : 1920;
    int h = argc > 5 ? atoi(argv[5]) : 1080;
    const char *hdrs = argc > 6 ? argv[6] : NULL;

    // ---- offscreen EGL context (no window, no X surface) ----
    // Zero-copy VA-API needs an EGL display the driver can tie to a real device.
    // Surfaceless gives hwdec-current=no; the X11 platform display works, and it
    // also matches what Compose Desktop/Skiko runs on.
    PFNEGLGETPLATFORMDISPLAYEXTPROC getPlatformDisplay =
        (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
    Display *xdpy = XOpenDisplay(NULL);
    EGLDisplay dpy;
    if (xdpy && getPlatformDisplay)
        dpy = getPlatformDisplay(EGL_PLATFORM_X11_KHR, xdpy, NULL);
    else if (getPlatformDisplay)
        dpy = getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
    else
        dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (dpy == EGL_NO_DISPLAY) die("eglGetDisplay");
    if (!eglInitialize(dpy, NULL, NULL)) die("eglInitialize");
    if (!eglBindAPI(EGL_OPENGL_API)) die("eglBindAPI");

    EGLint cfg_attr[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE,
                         EGL_OPENGL_BIT, EGL_NONE};
    EGLConfig cfg; EGLint n = 0;
    if (!eglChooseConfig(dpy, cfg_attr, &cfg, 1, &n) || n < 1) die("eglChooseConfig");
    EGLint ctx_attr[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctx_attr);
    if (ctx == EGL_NO_CONTEXT) die("eglCreateContext");
    if (!eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)) die("eglMakeCurrent");

    p_glGenFramebuffers = get_proc(0, "glGenFramebuffers");
    p_glBindFramebuffer = get_proc(0, "glBindFramebuffer");
    p_glFramebufferTexture2D = get_proc(0, "glFramebufferTexture2D");
    p_glGenTextures = get_proc(0, "glGenTextures");
    p_glBindTexture = get_proc(0, "glBindTexture");
    p_glTexImage2D = get_proc(0, "glTexImage2D");
    p_glTexParameteri = get_proc(0, "glTexParameteri");
    p_glCheckFramebufferStatus = get_proc(0, "glCheckFramebufferStatus");
    p_glFinish = get_proc(0, "glFinish");
    if (!p_glGenFramebuffers || !p_glFinish) die("glGetProcAddress");

    GLuint tex = 0, fbo = 0;
    p_glGenTextures(1, &tex);
    p_glBindTexture(GL_TEXTURE_2D, tex);
    p_glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    p_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    p_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    p_glGenFramebuffers(1, &fbo);
    p_glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    p_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    if (p_glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        die("incomplete FBO");

    // ---- mpv ----
    mpv_handle *mpv = mpv_create();
    if (!mpv) die("mpv_create");
    mpv_set_option_string(mpv, "hwdec", hwdec);
    mpv_set_option_string(mpv, "vo", "libmpv");
    mpv_set_option_string(mpv, "audio", "no");
    mpv_set_option_string(mpv, "untimed", "yes");
    mpv_set_option_string(mpv, "terminal", "no");
    if (hdrs) mpv_set_option_string(mpv, "http-header-fields", hdrs);
    if (mpv_initialize(mpv) < 0) die("mpv_initialize");

    mpv_opengl_init_params gl_init = {.get_proc_address = get_proc, .get_proc_address_ctx = NULL};
    int advanced = 1;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_API_TYPE_OPENGL},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init},
        {MPV_RENDER_PARAM_ADVANCED_CONTROL, &advanced},
        {MPV_RENDER_PARAM_X11_DISPLAY, xdpy},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    mpv_render_context *rctx = NULL;
    int err = mpv_render_context_create(&rctx, mpv, params);
    if (err < 0) { fprintf(stderr, "FAIL render_context_create(GL): %s\n", mpv_error_string(err)); exit(1); }

    const char *cmd[] = {"loadfile", file, NULL};
    if (mpv_command(mpv, cmd) < 0) die("loadfile");

    int rendered = 0, done = 0;
    char *hwdec_current = NULL;
    while (rendered < want_frames && !done) {
        mpv_event *ev = mpv_wait_event(mpv, 0);
        if (ev->event_id == MPV_EVENT_SHUTDOWN || ev->event_id == MPV_EVENT_END_FILE) done = 1;

        uint64_t flags = mpv_render_context_update(rctx);
        if (!(flags & MPV_RENDER_UPDATE_FRAME)) { usleep(200); continue; }

        mpv_opengl_fbo mfbo = {.fbo = (int)fbo, .w = w, .h = h, .internal_format = 0};
        int flip = 0;
        mpv_render_param rp[] = {
            {MPV_RENDER_PARAM_OPENGL_FBO, &mfbo},
            {MPV_RENDER_PARAM_FLIP_Y, &flip},
            {MPV_RENDER_PARAM_INVALID, NULL},
        };
        err = mpv_render_context_render(rctx, rp);
        if (err < 0) { fprintf(stderr, "FAIL render: %s\n", mpv_error_string(err)); exit(1); }
        p_glFinish();   // don't let GPU work pile up outside the timed window
        rendered++;
        if (!hwdec_current)
            mpv_get_property(mpv, "hwdec-current", MPV_FORMAT_STRING, &hwdec_current);
    }

    char *dw = NULL, *dh = NULL, *vfmt = NULL;
    mpv_get_property(mpv, "width", MPV_FORMAT_STRING, &dw);
    mpv_get_property(mpv, "height", MPV_FORMAT_STRING, &dh);
    mpv_get_property(mpv, "video-format", MPV_FORMAT_STRING, &vfmt);
    printf("RESULT gl hwdec-requested=%s hwdec-current=%s frames=%d src=%sx%s fmt=%s\n",
           hwdec, hwdec_current ? hwdec_current : "(null)", rendered,
           dw ? dw : "?", dh ? dh : "?", vfmt ? vfmt : "?");

    mpv_render_context_free(rctx);
    mpv_terminate_destroy(mpv);
    return 0;
}
