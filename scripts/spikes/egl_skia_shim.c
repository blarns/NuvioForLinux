// EGL plumbing for EglSkiaSpike.kt -- see that file for what is being proven.
//
// Skia's GrGLMakeNativeInterface() on Linux is the GLX implementation: it starts
// with `if (!glXGetCurrentContext()) return nullptr;`, which is exactly why an
// EGL context normally can't back a DirectContext and why the known workaround
// is a forked skiko built with skia_use_egl=true.
//
// But skiko also ships GLAssembledInterface.createFromNativePointers(ctx, getProc)
// + DirectContext.makeGLWithInterface(), which assembles the interface from a
// caller-supplied proc-address function and never consults GLX. This library
// provides that function (backed by eglGetProcAddress) plus enough EGL setup to
// have a current context, so the theory can be tested against the *shipped*
// skiko rather than a fork.
//
// The EGL display is deliberately the X11 platform one -- surfaceless gives
// hwdec-current=no, which is the trap documented in MPV_SWAP_SCOPING.md.
//
// build: gcc -O2 -shared -fPIC egl_skia_shim.c -o libeglskiashim.so \
//          $(pkg-config --cflags --libs egl x11)

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <X11/Xlib.h>
#include <stdio.h>
#include <unistd.h>
#include <string.h>

typedef unsigned int GLuint;
typedef unsigned int GLenum;
typedef int GLsizei;
typedef int GLint;

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

static Display *g_xdpy;
static EGLDisplay g_dpy = EGL_NO_DISPLAY;
static EGLContext g_ctx = EGL_NO_CONTEXT;
static char g_err[256];

// Skia's assembled-interface getter is GrGLFuncPtr(*)(void* ctx, const char* name).
// eglGetProcAddress takes only the name, so it needs this adapter -- passing
// eglGetProcAddress directly would read `ctx` as the symbol name.
static void *egl_get_proc(void *ctx, const char *name) {
    (void)ctx;
    return (void *)eglGetProcAddress(name);
}

void *egl_shim_get_proc_fn(void) { return (void *)egl_get_proc; }
void *egl_shim_x11_display(void) { return (void *)g_xdpy; }
const char *egl_shim_error(void) { return g_err; }

int egl_shim_setup(void) {
    PFNEGLGETPLATFORMDISPLAYEXTPROC getPlatformDisplay =
        (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
    g_xdpy = XOpenDisplay(NULL);
    if (!g_xdpy) { snprintf(g_err, sizeof g_err, "XOpenDisplay failed"); return 1; }
    if (!getPlatformDisplay) { snprintf(g_err, sizeof g_err, "no eglGetPlatformDisplayEXT"); return 2; }

    g_dpy = getPlatformDisplay(EGL_PLATFORM_X11_KHR, g_xdpy, NULL);
    if (g_dpy == EGL_NO_DISPLAY) { snprintf(g_err, sizeof g_err, "eglGetPlatformDisplay failed"); return 3; }
    if (!eglInitialize(g_dpy, NULL, NULL)) { snprintf(g_err, sizeof g_err, "eglInitialize failed"); return 4; }
    // Desktop GL, not GLES -- matches what GLX would have given Skia.
    if (!eglBindAPI(EGL_OPENGL_API)) { snprintf(g_err, sizeof g_err, "eglBindAPI failed"); return 5; }

    EGLint cfg_attr[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                         EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_NONE};
    EGLConfig cfg; EGLint n = 0;
    if (!eglChooseConfig(g_dpy, cfg_attr, &cfg, 1, &n) || n < 1) {
        snprintf(g_err, sizeof g_err, "eglChooseConfig failed"); return 6;
    }
    EGLint ctx_attr[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3, EGL_NONE};
    g_ctx = eglCreateContext(g_dpy, cfg, EGL_NO_CONTEXT, ctx_attr);
    if (g_ctx == EGL_NO_CONTEXT) { snprintf(g_err, sizeof g_err, "eglCreateContext failed"); return 7; }
    if (!eglMakeCurrent(g_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, g_ctx)) {
        snprintf(g_err, sizeof g_err, "eglMakeCurrent failed"); return 8;
    }
    return 0;
}

// Proof that the current context really is EGL's and not a GLX one that
// happened to be lying around: this is what Skia's native interface would check.
int egl_shim_has_egl_context(void) { return eglGetCurrentContext() != EGL_NO_CONTEXT; }

const char *egl_shim_gl_version(void) {
    const char *(*p_glGetString)(GLenum) = (void *)eglGetProcAddress("glGetString");
    return p_glGetString ? p_glGetString(0x1F02 /* GL_VERSION */) : "(no glGetString)";
}

// Render target for Skia to draw into, so the test needs no window.
GLuint egl_shim_make_fbo(int w, int h) {
    void (*p_glGenTextures)(GLsizei, GLuint *) = (void *)eglGetProcAddress("glGenTextures");
    void (*p_glBindTexture)(GLenum, GLuint) = (void *)eglGetProcAddress("glBindTexture");
    void (*p_glTexImage2D)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void *) =
        (void *)eglGetProcAddress("glTexImage2D");
    void (*p_glTexParameteri)(GLenum, GLenum, GLint) = (void *)eglGetProcAddress("glTexParameteri");
    void (*p_glGenFramebuffers)(GLsizei, GLuint *) = (void *)eglGetProcAddress("glGenFramebuffers");
    void (*p_glBindFramebuffer)(GLenum, GLuint) = (void *)eglGetProcAddress("glBindFramebuffer");
    void (*p_glFramebufferTexture2D)(GLenum, GLenum, GLenum, GLuint, GLint) =
        (void *)eglGetProcAddress("glFramebufferTexture2D");
    GLenum (*p_glCheckFramebufferStatus)(GLenum) = (void *)eglGetProcAddress("glCheckFramebufferStatus");

    GLuint tex = 0, fbo = 0;
    p_glGenTextures(1, &tex);
    p_glBindTexture(GL_TEXTURE_2D, tex);
    p_glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    p_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    p_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    p_glGenFramebuffers(1, &fbo);
    p_glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    p_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    if (p_glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        snprintf(g_err, sizeof g_err, "incomplete FBO");
        return 0;
    }
    return fbo;
}

// glGetError, so a soak can tell "Skia still draws the right colour" apart from
// "the GL context is quietly accumulating errors". A single correct frame can hide
// a context that is already degrading.
unsigned int egl_shim_gl_error(void) {
    GLenum (*p_glGetError)(void) = (void *)eglGetProcAddress("glGetError");
    return p_glGetError ? p_glGetError() : 0xFFFFFFFFu;
}

// Packed 0xAARRGGBB of one pixel, so the Kotlin side can prove Skia's draw
// actually landed rather than trusting that no call threw.
unsigned int egl_shim_read_pixel(GLuint fbo, int x, int y) {
    void (*p_glBindFramebuffer)(GLenum, GLuint) = (void *)eglGetProcAddress("glBindFramebuffer");
    void (*p_glReadPixels)(GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, void *) =
        (void *)eglGetProcAddress("glReadPixels");
    void (*p_glFinish)(void) = (void *)eglGetProcAddress("glFinish");
    unsigned char px[4] = {0, 0, 0, 0};
    p_glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    p_glFinish();
    p_glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
    return ((unsigned)px[3] << 24) | ((unsigned)px[0] << 16) | ((unsigned)px[1] << 8) | px[2];
}

// ---------------------------------------------------------------------------
// Stage B: mpv on the SAME EGL context Skia is using.
//
// Stage A proved Skia can rasterise on EGL. That alone doesn't prove the design
// works -- the point of being on EGL is that mpv can then hardware-decode
// zero-copy into a texture Skia adopts, with both APIs sharing one context.
// Sharing is what's unproven: mpv resets a lot of GL state, and Skia caches its
// view of that state.
// ---------------------------------------------------------------------------

#include <locale.h>
#include <stdlib.h>
#include <mpv/client.h>
#include <mpv/render_gl.h>

static mpv_handle *g_mpv;
static mpv_render_context *g_rctx;
static GLuint g_mpv_tex, g_mpv_fbo;
static int g_mpv_w, g_mpv_h;
static char g_hwdec[64];

int mpv_shim_start(const char *file, const char *hwdec, int w, int h, const char *hdrs) {
    g_mpv_w = w; g_mpv_h = h;
    // ⚠ libmpv refuses to start under a non-C LC_NUMERIC, and the JVM sets a
    // locale during startup -- so mpv_create() fails with no useful diagnostic
    // when called from Java. Any JNA binding must do this first.
    setlocale(LC_NUMERIC, "C");
    g_mpv = mpv_create();
    if (!g_mpv) { snprintf(g_err, sizeof g_err, "mpv_create failed"); return 1; }
    mpv_set_option_string(g_mpv, "hwdec", hwdec);
    mpv_set_option_string(g_mpv, "vo", "libmpv");
    mpv_set_option_string(g_mpv, "audio", "no");
    mpv_set_option_string(g_mpv, "untimed", "yes");
    mpv_set_option_string(g_mpv, "terminal", "no");
    // A soak runs far more frames than any test clip contains, so loop rather than
    // hitting EOF and silently rendering nothing for the rest of the run.
    mpv_set_option_string(g_mpv, "loop-file", "inf");
    if (hdrs && *hdrs) mpv_set_option_string(g_mpv, "http-header-fields", hdrs);
    // Films open on black, which reads identically to "decode produced nothing".
    // SPIKE_START seeks past the titles so the pixel check means something.
    const char *start = getenv("SPIKE_START");
    if (start && *start) mpv_set_option_string(g_mpv, "start", start);
    if (mpv_initialize(g_mpv) < 0) { snprintf(g_err, sizeof g_err, "mpv_initialize failed"); return 2; }

    // Same proc-address function handed to Skia, so both resolve GL identically.
    mpv_opengl_init_params gl_init = {.get_proc_address = egl_get_proc, .get_proc_address_ctx = NULL};
    int advanced = 1;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_API_TYPE_OPENGL},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init},
        {MPV_RENDER_PARAM_ADVANCED_CONTROL, &advanced},
        {MPV_RENDER_PARAM_X11_DISPLAY, g_xdpy},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    int err = mpv_render_context_create(&g_rctx, g_mpv, params);
    if (err < 0) { snprintf(g_err, sizeof g_err, "render_context_create: %s", mpv_error_string(err)); return 3; }

    void (*p_glGenTextures)(GLsizei, GLuint *) = (void *)eglGetProcAddress("glGenTextures");
    void (*p_glBindTexture)(GLenum, GLuint) = (void *)eglGetProcAddress("glBindTexture");
    void (*p_glTexImage2D)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void *) =
        (void *)eglGetProcAddress("glTexImage2D");
    void (*p_glTexParameteri)(GLenum, GLenum, GLint) = (void *)eglGetProcAddress("glTexParameteri");
    void (*p_glGenFramebuffers)(GLsizei, GLuint *) = (void *)eglGetProcAddress("glGenFramebuffers");
    void (*p_glBindFramebuffer)(GLenum, GLuint) = (void *)eglGetProcAddress("glBindFramebuffer");
    void (*p_glFramebufferTexture2D)(GLenum, GLenum, GLenum, GLuint, GLint) =
        (void *)eglGetProcAddress("glFramebufferTexture2D");

    p_glGenTextures(1, &g_mpv_tex);
    p_glBindTexture(GL_TEXTURE_2D, g_mpv_tex);
    p_glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    p_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    p_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    p_glGenFramebuffers(1, &g_mpv_fbo);
    p_glBindFramebuffer(GL_FRAMEBUFFER, g_mpv_fbo);
    p_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_mpv_tex, 0);

    const char *cmd[] = {"loadfile", file, NULL};
    if (mpv_command(g_mpv, cmd) < 0) { snprintf(g_err, sizeof g_err, "loadfile failed"); return 4; }
    return 0;
}

int mpv_shim_render(int want) {
    void (*p_glFinish)(void) = (void *)eglGetProcAddress("glFinish");
    int done = 0, n = 0;
    while (n < want && !done) {
        mpv_event *ev = mpv_wait_event(g_mpv, 0);
        if (ev->event_id == MPV_EVENT_SHUTDOWN || ev->event_id == MPV_EVENT_END_FILE) done = 1;
        if (!(mpv_render_context_update(g_rctx) & MPV_RENDER_UPDATE_FRAME)) { usleep(200); continue; }
        mpv_opengl_fbo mfbo = {.fbo = (int)g_mpv_fbo, .w = g_mpv_w, .h = g_mpv_h, .internal_format = 0};
        int flip = 0;
        mpv_render_param rp[] = {
            {MPV_RENDER_PARAM_OPENGL_FBO, &mfbo},
            {MPV_RENDER_PARAM_FLIP_Y, &flip},
            {MPV_RENDER_PARAM_INVALID, NULL},
        };
        if (mpv_render_context_render(g_rctx, rp) < 0) return -1;
        p_glFinish();
        n++;
        if (!g_hwdec[0]) {
            char *h = NULL;
            if (mpv_get_property(g_mpv, "hwdec-current", MPV_FORMAT_STRING, &h) >= 0 && h) {
                snprintf(g_hwdec, sizeof g_hwdec, "%s", h);
                mpv_free(h);
            }
        }
    }
    return n;
}

const char *mpv_shim_hwdec_current(void) { return g_hwdec[0] ? g_hwdec : "(unset)"; }
unsigned int mpv_shim_texture(void) { return g_mpv_tex; }

// Mean luma over a sparse grid of mpv's own FBO. Distinguishes "Skia drew
// something" from "Skia drew actual video" -- a black frame would still adopt
// and draw without error.
int mpv_shim_frame_mean(void) {
    void (*p_glBindFramebuffer)(GLenum, GLuint) = (void *)eglGetProcAddress("glBindFramebuffer");
    void (*p_glReadPixels)(GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, void *) =
        (void *)eglGetProcAddress("glReadPixels");
    p_glBindFramebuffer(GL_FRAMEBUFFER, g_mpv_fbo);
    long sum = 0; int n = 0;
    unsigned char px[4];
    for (int y = 0; y < g_mpv_h; y += 64)
        for (int x = 0; x < g_mpv_w; x += 64) {
            p_glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
            sum += (px[0] + px[1] + px[2]) / 3; n++;
        }
    return n ? (int)(sum / n) : -1;
}

void mpv_shim_stop(void) {
    if (g_rctx) mpv_render_context_free(g_rctx);
    if (g_mpv) mpv_terminate_destroy(g_mpv);
    g_rctx = NULL; g_mpv = NULL;
}
