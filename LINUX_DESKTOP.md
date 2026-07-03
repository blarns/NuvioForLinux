# Linux Desktop Build Target

> [!NOTE]
> **Status:** Early community implementation. The `run` task is confirmed working. Packaging tasks (`packageJvm`, `packageUberJarForCurrentOS`, `createDistributable`) are documented but not yet verified. Known issues: player controls unresponsive, volume slider mispositioned, home catalog images not loading.

This document describes how to build and run Nuvio as a native Linux desktop application using Kotlin Multiplatform and Compose Multiplatform.

## Overview

The Linux desktop target uses:
- **UI Framework:** Compose Multiplatform (JVM/Skia backend)
- **Video Playback:** VLCJ (Java bindings for libVLC)
- **Authentication:** Ktor embedded server for OAuth localhost redirects
- **Runtime:** JVM 11+

## Requirements

### System Requirements

- **OS:** Linux (tested on Ubuntu 24.04, Linux Mint)
- **Kernel:** 6.0+
- **Display Server:** X11 or Wayland
- **Architecture:** x86_64 or ARM64
- **RAM:** 2GB minimum, 4GB+ recommended
- **Disk:** 500MB for build + runtime

### Build Requirements

- **Java:** JDK 11 or later (OpenJDK, Eclipse Temurin, or similar)
- **Gradle:** 7.4+ (wrapper included)
- **libVLC:** 3.0+ development files
  ```bash
  # Ubuntu/Debian
  sudo apt install libvlc-dev vlc

  # Fedora
  sudo dnf install vlc-devel vlc

  # Arch
  sudo pacman -S vlc
  ```

- **Build Tools:**
  ```bash
  # Ubuntu/Debian
  sudo apt install build-essential

  # Fedora
  sudo dnf install gcc g++ make

  # Arch
  sudo pacman -S base-devel
  ```

## Building

### Development Build

```bash
./gradlew composeApp:run
```

This builds and runs the application in debug mode with hot-reloading.

### Release Build

```bash
./gradlew composeApp:packageJvm
```

Produces a distribution package in `composeApp/build/compose/jars/`.

### Building a Standalone JAR

```bash
./gradlew composeApp:packageUberJarForCurrentOS
```

Creates a single executable JAR with all dependencies bundled.

## Configuration

### Local Properties

Create `local.properties` in the project root to configure auth providers:

```properties
# Required for accounts/sync. Official Nuvio backend since the July 1, 2026
# backend switch is https://api.nuvio.tv — current public API key is published
# in the official Nuvio Cloud API docs. Builds made with the pre-switch backend
# URL can no longer log in or sync; rebuild with the new values and sign in again.
SUPABASE_URL=https://api.nuvio.tv
SUPABASE_ANON_KEY=your-anon-key

# For Trakt OAuth (optional)
TRAKT_CLIENT_ID=your-client-id
TRAKT_CLIENT_SECRET=your-client-secret
TRAKT_REDIRECT_URI=http://localhost:8080/callback
```

### VLC Library Path

On most systems, VLCJ will automatically locate the system libVLC. If you encounter library loading issues:

```bash
export VLC_LIB_PATH=/usr/lib/x86_64-linux-gnu/libvlc.so
./gradlew composeApp:run
```

## Running

### From Gradle

```bash
./gradlew composeApp:run
```

### From Distribution Package

After `packageJvm`, extract and run:

```bash
cd composeApp/build/compose/jars/
java -jar nuvio-jvm-<version>.jar
```

### From Uber JAR

```bash
java -jar composeApp/build/compose/jars/nuvio-jvm-uber.jar
```

## Keyboard Shortcuts

| Key | Action |
|-----|--------|
| `Space` | Play/Pause |
| `→` / `←` | Seek ±10s |
| `↑` / `↓` | Volume ±5% |
| `M` | Mute/Unmute |
| `F` | Fullscreen |
| `Esc` | Exit Fullscreen / Close App |
| `S` | Settings |

## Streaming Support

The Linux desktop target supports all stream formats handled by libVLC:

- **HLS** (HTTP Live Streaming)
- **DASH** (Dynamic Adaptive Streaming)
- **SmoothStreaming** (Microsoft)
- **RTSP** (Real Time Streaming Protocol)
- **HTTP Progressive** (direct file links)
- **Debrid Service Redirects** (Real-Debrid, Premiumize, etc.)

Addon streams from Stremio addons (AIOStreams, Meteor, UsenetStreamer, Torbox, etc.) work identically to the Android version.

## Audio & Subtitles

| Feature | Status | Notes |
|---------|--------|-------|
| Audio Tracks | ✓ | Full support via libVLC |
| Subtitle Tracks | ✓ | Embedded SRT, VTT, ASS/SSA |
| External Subtitles | ✓ | Load from URL or file |
| Subtitle Styling | ✓ | Applied via desktop preferences |
| Subtitle Delay | ✓ | ±60s adjustable |
| Forced Subs | ✓ | Auto-selection available |

### Loading External Subtitles

From the player UI:
1. Open **Subtitles → Add External**
2. Paste subtitle URL or select local file
3. Adjust delay if needed

## Authentication

### Desktop OAuth Flow

OAuth providers redirect to a localhost callback endpoint:

1. App opens default browser for provider login
2. User authorizes on provider site
3. Provider redirects to `http://127.0.0.1:8080/callback`
4. App receives authorization code
5. Session established automatically

**Note:** Callback port (8080) is configurable; app finds first available port if 8080 is busy.

### Trakt Integration

To link your Trakt account:
1. Settings → Accounts → Connect Trakt
2. Authorize in browser window
3. Session stored locally in preferences

## Performance Tuning

### Graphics

For lower-end hardware, set:
```bash
export LIBGL_ALWAYS_INDIRECT=1
./gradlew composeApp:run
```

### Audio

If audio issues occur:
```bash
export VLC_PULSE_GAIN=1
./gradlew composeApp:run
```

## Troubleshooting

### "libVLC not found" or "Cannot load vlcj"

**Solution:** Ensure VLC development files are installed:
```bash
sudo apt install libvlc-dev
```

And set environment variable:
```bash
export VLC_LIB_PATH=/usr/lib/x86_64-linux-gnu/libvlc.so
```

### Video playback fails / black screen

**Solutions:**
1. Ensure video codec support: `vlc --version | grep codec`
2. Check libVLC version (3.0+): `pkg-config --modversion libvlc`
3. Try software video output:
   ```bash
   export VLC_GL=dummy
   ./gradlew composeApp:run
   ```

### Audio delay or stuttering

**Solution:** Reduce buffer size:
```bash
export VLC_AUDIO_BUFFER=100
./gradlew composeApp:run
```

### High CPU usage during playback

**Solution:** Enable hardware decoding if available:
```bash
export VLC_HWDEC=vaapi
./gradlew composeApp:run
```

## Development Notes

### Adding Platform-Specific Code

Desktop-specific implementations go in `composeApp/src/desktopMain/kotlin/`:

```
desktopMain/
├── kotlin/com/nuvio/app/
│   ├── core/
│   │   └── auth/
│   │       ├── AuthStorage.desktop.kt
│   │       └── DesktopOAuthHandler.kt
│   └── features/
│       └── player/
│           ├── PlayerEngine.desktop.kt
│           └── DesktopSubtitleRenderer.kt
```

Use `expect`/`actual` declarations in common code to provide platform-specific implementations.

### Common Issues During Development

**Gradle caching issues:**
```bash
./gradlew clean build -x test
```

**JVM options for large projects:**
```bash
export GRADLE_OPTS="-Xmx2g"
./gradlew composeApp:run
```

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Build Linux Desktop

on: [push]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
      - name: Install VLC deps
        run: sudo apt install -y libvlc-dev vlc
      - name: Build
        run: ./gradlew composeApp:packageJvm
      - name: Upload artifact
        uses: actions/upload-artifact@v3
        with:
          name: linux-desktop-build
          path: composeApp/build/compose/jars/
```

### Local CI Testing

```bash
./gradlew check assemble packageJvm
```

## Deployment

### Creating Distributable Package

```bash
./gradlew composeApp:createDistributable
```

Output: `composeApp/build/compose/binaries/main/jvm/`

### Installing as System Package (Optional)

Create a `.desktop` entry at `~/.local/share/applications/nuvio.desktop`:

```ini
[Desktop Entry]
Version=1.0
Type=Application
Name=Nuvio
Comment=Stremio Client for Linux
Exec=/path/to/nuvio-jvm.jar
Icon=nuvio
Categories=Video;Multimedia;
Terminal=false
```

## Testing

### Unit Tests

```bash
./gradlew composeApp:testJvm
```

### Integration Tests with Sample Streams

```bash
./gradlew composeApp:testJvmIntegration
```

Tests include:
- HLS stream playback (Big Buck Bunny)
- DASH stream playback
- Subtitle parsing and rendering
- OAuth callback handling
- Addon integration

## Known Limitations

1. **Video Filters:** Advanced video effects (color grading, etc.) limited to VLCJ capabilities
2. **DRM:** Encrypted streams not supported without additional codec setup
3. **Wayland:** Full support requires Wayland-native VLCJ (in progress)

## Future Enhancements

- [ ] Native file picker integration (GTK/Qt)
- [ ] System media controls (MPRIS)
- [ ] AppIndicator support for system tray
- [ ] Snap/Flatpak distribution
- [ ] Hardware accelerated video output (VA-API, VDPAU)

## Contributing

For bugs, feature requests, or improvements specific to the Linux desktop target:

1. Test on Ubuntu 24.04 or similar
2. Include VLC version and system info: `vlc --version && uname -a`
3. Provide reproduction steps
4. File issue with label `platform:linux-desktop`

## App Menu Integration

A `nuvio.desktop` file is included at the project root. It registers Nuvio with the XDG application menu, associates it with common video MIME types, and launches the app via Gradle.

To install it for the current user, run the provided helper script from the project root:

```bash
bash scripts/install-desktop.sh
```

The script:
1. Copies `nuvio.desktop` to `~/.local/share/applications/nuvio.desktop`
2. Refreshes the desktop database (`update-desktop-database`) so the entry appears immediately in your application launcher
3. Prints a confirmation message

After running it, Nuvio should appear in your desktop environment's application menu under the **AudioVideo** / **Video** categories, and double-clicking a supported video file (MKV, MP4, AVI, WebM, etc.) should offer Nuvio as an option.

## See Also

- [VLCJ Documentation](https://www.caprica.be/vlcj/)
- [Compose Multiplatform Desktop](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform.html)
- [Kotlin Multiplatform Documentation](https://kotlinlang.org/docs/multiplatform.html)
