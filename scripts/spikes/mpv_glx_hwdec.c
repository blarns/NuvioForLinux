// Does zero-copy VA-API survive on a GLX context?
//
// mpv_gl_hwdec.c proved GPU render + hwdec=vaapi costs 1.85s where the shipping
// path costs 41.2s -- but it built its own EGL context (EGL_PLATFORM_X11_KHR).
// Skiko, which is what Compose Desktop actually renders with, is GLX: libskiko
// links libGLX/libGL/libX11 and exports no EGL symbols at all. mpv's zero-copy
// VA-API interop goes through EGL_EXT_image_dma_buf_import, so on GLX it may
// silently fall back to vaapi-copy and give the 1.85s number away.
//
// This is the same program as mpv_gl_hwdec.c with GLX substituted for EGL, so
// the two can be compared directly.
//
// build: gcc -O2 mpv_glx_hwdec.c -o mpv_glx_hwdec $(pkg-config --cflags --libs mpv gl x11)
// usage: ./mpv_glx_hwdec <file> <hwdec> <frames> [w] [h] [headers]

#include <GL/glx.h>
#include <X11/Xlib.h>
#include <mpv/client.h>
#include <mpv/render_gl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void (*p_glGenFramebuffers)(GLsizei, GLuint *);
static void (*p_glBindFramebuffer)(GLenum, GLuint);
static void (*p_glFramebufferTexture2D)(GLenum, GLenum, GLenum, GLuint, GLint);
static void (*p_glGenTextures)(GLsizei, GLuint *);
static void (*p_glBindTexture)(GLenum, GLuint);
static void (*p_glTexImage2D)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void *);
static void (*p_glTexParameteri)(GLenum, GLenum, GLint);
static GLenum (*p_glCheckFramebufferStatus)(GLenum);
static void (*p_glFinish)(void);

#define FB 0x8D40
#define COLOR0 0x8CE0
#define FB_COMPLETE 0x8CD5
#define RGBA8 0x8058

static void *get_proc(void *ctx, const char *name) {
    (void)ctx;
    return (void *)glXGetProcAddress((const GLubyte *)name);
}

static void die(const char *what) { fprintf(stderr, "FAIL %s\n", what); exit(1); }

int main(int argc, char **argv) {
    const char *file = argv[1];
    const char *hwdec = argv[2];
    int want_frames = atoi(argv[3]);
    int w = argc > 4 ? atoi(argv[4]) : 1920;
    int h = argc > 5 ? atoi(argv[5]) : 1080;
    const char *hdrs = argc > 6 ? argv[6] : NULL;

    // ---- offscreen GLX context, matching how Skiko sets itself up ----
    Display *xdpy = XOpenDisplay(NULL);
    if (!xdpy) die("XOpenDisplay (need a real X display; this test cannot run headless)");

    int fb_attr[] = {GLX_DRAWABLE_TYPE, GLX_PBUFFER_BIT,
                     GLX_RENDER_TYPE,   GLX_RGBA_BIT,
                     GLX_RED_SIZE,      8,
                     GLX_GREEN_SIZE,    8,
                     GLX_BLUE_SIZE,     8,
                     GLX_ALPHA_SIZE,    8,
                     None};
    int nfb = 0;
    GLXFBConfig *fbc = glXChooseFBConfig(xdpy, DefaultScreen(xdpy), fb_attr, &nfb);
    if (!fbc || nfb < 1) die("glXChooseFBConfig");

    int pb_attr[] = {GLX_PBUFFER_WIDTH, 16, GLX_PBUFFER_HEIGHT, 16, None};
    GLXPbuffer pbuf = glXCreatePbuffer(xdpy, fbc[0], pb_attr);
    GLXContext gctx = glXCreateNewContext(xdpy, fbc[0], GLX_RGBA_TYPE, NULL, True);
    if (!gctx) die("glXCreateNewContext");
    if (!glXMakeCurrent(xdpy, pbuf, gctx)) die("glXMakeCurrent");

    p_glGenFramebuffers = get_proc(0, "glGenFramebuffers");
    p_glBindFramebuffer = get_proc(0, "glBindFramebuffer");
    p_glFramebufferTexture2D = get_proc(0, "glFramebufferTexture2D");
    p_glGenTextures = get_proc(0, "glGenTextures");
    p_glBindTexture = get_proc(0, "glBindTexture");
    p_glTexImage2D = get_proc(0, "glTexImage2D");
    p_glTexParameteri = get_proc(0, "glTexParameteri");
    p_glCheckFramebufferStatus = get_proc(0, "glCheckFramebufferStatus");
    p_glFinish = get_proc(0, "glFinish");
    if (!p_glGenFramebuffers || !p_glFinish) die("glXGetProcAddress");

    printf("INFO glx vendor=%s renderer=%s\n",
           glXGetClientString(xdpy, GLX_VENDOR),
           glXQueryServerString(xdpy, DefaultScreen(xdpy), GLX_VENDOR));

    GLuint tex = 0, fbo = 0;
    p_glGenTextures(1, &tex);
    p_glBindTexture(GL_TEXTURE_2D, tex);
    p_glTexImage2D(GL_TEXTURE_2D, 0, RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    p_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    p_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    p_glGenFramebuffers(1, &fbo);
    p_glBindFramebuffer(FB, fbo);
    p_glFramebufferTexture2D(FB, COLOR0, GL_TEXTURE_2D, tex, 0);
    if (p_glCheckFramebufferStatus(FB) != FB_COMPLETE) die("incomplete FBO");

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
    if (err < 0) { fprintf(stderr, "FAIL render_context_create(GLX): %s\n", mpv_error_string(err)); exit(1); }

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
    printf("RESULT glx hwdec-requested=%s hwdec-current=%s frames=%d src=%sx%s fmt=%s\n",
           hwdec, hwdec_current ? hwdec_current : "(null)", rendered,
           dw ? dw : "?", dh ? dh : "?", vfmt ? vfmt : "?");

    mpv_render_context_free(rctx);
    mpv_terminate_destroy(mpv);
    return 0;
}
