// Does hwdec=auto-copy survive MPV_RENDER_API_TYPE_SW?
//
// The MPV_SWAP_SCOPING.md design renders through mpv_render_context with the SW
// API into a caller-owned BGRA buffer -- the same shape VLCJ's RenderCallback
// hands to the Skia path. The CPU measurement that justifies the swap was taken
// with --vo=null, which is NOT that path. This exercises the real one and reports
// hwdec-current, so a silent fallback to software decoding cannot pass unnoticed.
//
// build: gcc -O2 mpv_sw_hwdec.c -o mpv_sw_hwdec $(pkg-config --cflags --libs mpv)
// usage: ./mpv_sw_hwdec <file> <hwdec-value> <frames>

#include <mpv/client.h>
#include <mpv/render.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void die(const char *what, int err) {
    fprintf(stderr, "FAIL %s: %s\n", what, mpv_error_string(err));
    exit(1);
}

int main(int argc, char **argv) {
    const char *file = argv[1];
    const char *hwdec = argv[2];
    int want_frames = atoi(argv[3]);

    int w = argc > 4 ? atoi(argv[4]) : 1920;
    int h = argc > 5 ? atoi(argv[5]) : 1080;
    const char *hdrs = argc > 6 ? argv[6] : NULL;   // e.g. "User-Agent: X,Referer: Y"
    size_t stride = (size_t)w * 4;
    void *pixels = malloc(stride * h);

    mpv_handle *mpv = mpv_create();
    if (!mpv) die("mpv_create", 0);

    mpv_set_option_string(mpv, "hwdec", hwdec);
    mpv_set_option_string(mpv, "vo", "libmpv");
    mpv_set_option_string(mpv, "audio", "no");
    mpv_set_option_string(mpv, "untimed", "yes");
    mpv_set_option_string(mpv, "terminal", "no");
    if (hdrs) mpv_set_option_string(mpv, "http-header-fields", hdrs);

    int err = mpv_initialize(mpv);
    if (err < 0) die("mpv_initialize", err);

    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_API_TYPE_SW},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    mpv_render_context *ctx = NULL;
    err = mpv_render_context_create(&ctx, mpv, params);
    if (err < 0) die("mpv_render_context_create(SW)", err);

    const char *cmd[] = {"loadfile", file, NULL};
    err = mpv_command(mpv, cmd);
    if (err < 0) die("loadfile", err);

    int rendered = 0, nonblank = 0;
    char *hwdec_current = NULL;

    int done = 0;
    while (rendered < want_frames && !done) {
        // Non-blocking event pump; the frame pump is mpv_render_context_update.
        // The 200us sleep is identical in both hwdec legs, so it cancels out of
        // the comparison rather than favouring either.
        mpv_event *ev = mpv_wait_event(mpv, 0);
        if (ev->event_id == MPV_EVENT_SHUTDOWN || ev->event_id == MPV_EVENT_END_FILE)
            done = 1;

        uint64_t flags = mpv_render_context_update(ctx);
        if (!(flags & MPV_RENDER_UPDATE_FRAME)) {
            usleep(200);
        } else {
            int sw_size[2] = {w, h};
            mpv_render_param rp[] = {
                {MPV_RENDER_PARAM_SW_SIZE, sw_size},
                {MPV_RENDER_PARAM_SW_FORMAT, (void *)"bgra"},
                {MPV_RENDER_PARAM_SW_STRIDE, &stride},
                {MPV_RENDER_PARAM_SW_POINTER, pixels},
                {MPV_RENDER_PARAM_INVALID, NULL},
            };
            err = mpv_render_context_render(ctx, rp);
            if (err < 0) die("mpv_render_context_render", err);
            rendered++;

            // Confirm real pixels came back, not an empty buffer.
            unsigned char *p = pixels;
            long sum = 0;
            for (int i = 0; i < 4096; i++) sum += p[(size_t)i * 977 % (stride * h)];
            if (sum > 0) nonblank++;

            if (!hwdec_current)
                mpv_get_property(mpv, "hwdec-current", MPV_FORMAT_STRING, &hwdec_current);
        }
    }

    char *vfmt = NULL, *dw = NULL, *dh = NULL;
    mpv_get_property(mpv, "video-format", MPV_FORMAT_STRING, &vfmt);
    mpv_get_property(mpv, "width", MPV_FORMAT_STRING, &dw);
    mpv_get_property(mpv, "height", MPV_FORMAT_STRING, &dh);
    printf("RESULT hwdec-requested=%s hwdec-current=%s frames=%d nonblank=%d src=%sx%s fmt=%s\n",
           hwdec, hwdec_current ? hwdec_current : "(null)", rendered, nonblank,
           dw ? dw : "?", dh ? dh : "?", vfmt ? vfmt : "?");
    if (vfmt) mpv_free(vfmt);
    if (dw) mpv_free(dw);
    if (dh) mpv_free(dh);

    if (hwdec_current) mpv_free(hwdec_current);
    mpv_render_context_free(ctx);
    mpv_terminate_destroy(mpv);
    free(pixels);
    return 0;
}
