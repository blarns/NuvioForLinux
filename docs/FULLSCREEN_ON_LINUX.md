# Fullscreen on Linux — what is actually going on

Written while chasing the unresolved half of
[#4](https://github.com/blarns/NuvioForLinux/issues/4) ("fullscreen only half works on sway").
Two plausible explanations were tested and **both turned out to be wrong**. Recorded here so the
next attempt starts from measurements rather than from either of them.

## The call chain, confirmed

`WindowState.placement = Fullscreen`
→ `ComposeWindow.setPlacement` → `setFullscreen(true)`
→ `ComposeWindowPanel` → `ComposeContainer` → `ComposeSceneMediator` → `SkiaLayerComponent`
→ skiko `HardwareLayer.setFullscreen` → `PlatformOperations.setFullscreen`
→ **`java.awt.GraphicsDevice.setFullScreenWindow(window)`**

Read out of the compiled classes (`javap -c`) in skiko 0.144.6 and Compose 1.11.1. Worth knowing:
skiko's Linux branch of `PlatformOperations` is byte-for-byte its Windows branch. macOS is the only
platform with a native path.

## Wrong theory 1: "AWT matches the WM against a hardcoded list that excludes sway"

This is what `docs/V0.3.0_PORT_VERIFICATION.md` claimed. It does not hold up.

`sun.awt.X11GraphicsDevice.isFullScreenSupported()` is, in its entirety,
`isXrandrExtensionSupported()` plus a `SecurityManager` check that is dead on a modern JVM. It
never consults the window manager. The hardcoded list is real — `sun.awt.X11.XWM` enumerates
`NO_WM`, `OTHER_WM`, `KDE2_WM`, `METACITY_WM`, `MUTTER_WM`, `UNITY_COMPIZ_WM` and friends, with no
sway — but it does not gate fullscreen. Anything unrecognised is simply `OTHER_WM`.

## Wrong theory 2: "AWT never sends `_NET_WM_STATE_FULLSCREEN`, so tiling WMs ignore it"

Also wrong, and it is worth being precise about *why*, because the evidence looked convincing.

`sun.awt.X11.XNETProtocol` declares an `XA_NET_WM_STATE_FULLSCREEN` field, and disassembly shows
that field is only ever written — in the constructor — and never read anywhere else. That reads
like an atom that is defined and then forgotten. It is a false negative: the state-setting path is
`requestState(XWindow, XAtom, boolean)` / `setStateHelper(XWindowPeer, XAtom, boolean)`, which take
the atom **as a parameter**. Searching for reads of the field finds nothing because the value
arrives from the caller.

## What was actually measured

Xvfb at 1920x1080 with **i3** — a tiling WM in the same family as sway, which sway is a Wayland
reimplementation of — running two AWT frames so i3 tiles them:

| Step | Window bounds | `_NET_WM_STATE_FULLSCREEN` |
|---|---|---|
| tiled by i3 | 960x1060 | not set |
| after `setFullScreenWindow(w)` | 1924x1100 | **set** |

So on a tiling window manager, AWT fullscreen works, and the EWMH atom does end up set. There is
no bug at this layer to fix, and a hand-rolled EWMH client message would be redundant. One was
written, measured against the table above, and deleted.

## What this means for #4

The sway report is not explained by tiling geometry or by EWMH not being asked for. It needs the
reporter, and the specific things worth having from them are:

1. The startup line — `./Nuvio-*.AppImage 2>&1 | grep DesktopWindowGeometry` — which already
   prints `XDG_SESSION_TYPE`, `XDG_CURRENT_DESKTOP`, `WAYLAND_DISPLAY`, the display scale and
   `fullscreenSupported`.
2. `xprop -name Nuvio _NET_WM_STATE` before and after pressing **F**. If the atom flips, the WM
   was asked correctly and the problem is downstream — most likely the video surface or scaling,
   not the window.
3. `xdotool search --name '^Nuvio' getwindowgeometry`, same before and after.

"Half works" is doing a lot of load-bearing work in the original report and could mean the window
goes fullscreen but the video does not fill it, which would be a completely different defect from
the one everyone has been assuming. Establish which before writing any more code.

## Reproducing the test rig

```sh
apt-get install -y --no-install-recommends xvfb i3-wm x11-utils
Xvfb :99 -screen 0 1920x1080x24 &
printf 'font pango:monospace 8\n' > /tmp/i3.conf
DISPLAY=:99 i3 -c /tmp/i3.conf &
DISPLAY=:99 xprop -root _NET_SUPPORTED | tr ',' '\n' | grep FULLSCREEN
```

i3 under Xvfb is a cheap stand-in for sway for anything EWMH-shaped. It is *not* a stand-in for
XWayland-specific behaviour, which is the remaining untested variable.
