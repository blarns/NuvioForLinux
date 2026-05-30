# Linux Desktop Quick Start

Ready to build Nuvio on Linux? Here's everything you need to get running in 10 minutes.

## Prerequisites

```bash
# Ubuntu/Debian
sudo apt install openjdk-17-jdk libvlc-dev vlc build-essential

# Fedora
sudo dnf install java-17-openjdk-devel vlc-devel gcc g++

# Arch
sudo pacman -S jdk-openjdk vlc base-devel
```

Verify installations:
```bash
java -version       # Should show Java 11+
pkg-config --modversion libvlc  # Should show 3.0+
```

## Clone & Configure

```bash
git clone <nuvio-repo>
cd NuvioMobile

# Optional: Set local auth config
cat > local.properties << EOF
SUPABASE_URL=https://your-supabase-project.supabase.co
SUPABASE_ANON_KEY=your-anon-key
TRAKT_CLIENT_ID=your-trakt-client-id
TRAKT_CLIENT_SECRET=your-trakt-secret
TRAKT_REDIRECT_URI=http://localhost:8080/callback
EOF
```

## Build & Run

### Quick Test (Debug)
```bash
./gradlew composeApp:runJvm
```
Builds and launches the app in debug mode with hot-reload.

### Full Build (Release)
```bash
./gradlew composeApp:packageJvm
ls -lh composeApp/build/compose/jars/
```
Creates distributable JAR in `composeApp/build/compose/jars/`.

### Run Built JAR
```bash
java -jar composeApp/build/compose/jars/nuvio-jvm-*.jar
```

## Troubleshooting

### "libVLC not found"
```bash
# Set explicit path (adjust for your system)
export VLC_LIB_PATH=/usr/lib/x86_64-linux-gnu/libvlc.so
./gradlew composeApp:runJvm
```

### Out of Memory
```bash
export GRADLE_OPTS="-Xmx2g"
./gradlew composeApp:runJvm
```

### Build fails with "cannot find symbol"
```bash
# Clean rebuild
./gradlew clean composeApp:compileKotlin
```

## What Works

✅ **Video Playback:** HLS, DASH, RTSP, HTTP streams  
✅ **Subtitles:** Internal (SRT/VTT/ASS) + external URLs  
✅ **Audio Tracks:** Full track selection & switching  
✅ **Auth:** Trakt OAuth via localhost redirect  
✅ **Controls:** Play/pause, seek, speed adjustment  
✅ **Addons:** Works with any Stremio addon  

## Next Steps

1. **Run tests:** `./gradlew composeApp:testJvm`
2. **Read docs:** See `LINUX_DESKTOP.md` for full configuration
3. **Explore code:** Desktop code in `composeApp/src/desktopMain/kotlin/`
4. **Contribute:** File issues or PRs for improvements

## Common Tasks

| Task | Command |
|------|---------|
| Run in debug | `./gradlew composeApp:runJvm` |
| Run tests | `./gradlew composeApp:testJvm` |
| Build JAR | `./gradlew composeApp:packageJvm` |
| Check errors | `./gradlew composeApp:compileKotlin` |
| Clean | `./gradlew clean` |

## Performance Tuning

For smoother playback on lower-end hardware:

```bash
export VLC_HWDEC=vaapi              # Hardware video decode (if supported)
export VLC_GL=dummy                  # Software OpenGL
export LIBGL_ALWAYS_INDIRECT=1      # Indirect GL rendering
./gradlew composeApp:runJvm
```

## Key Files

| File | Purpose |
|------|---------|
| `composeApp/src/desktopMain/kotlin/.../PlayerEngine.desktop.kt` | VLCJ player |
| `composeApp/src/desktopMain/kotlin/.../DesktopOAuthHandler.kt` | OAuth localhost |
| `composeApp/build.gradle.kts` | JVM target config |

## Getting Help

- **Build docs:** `LINUX_DESKTOP.md`
- **Implementation details:** `IMPLEMENTATION_SUMMARY.md`
- **Issues:** Check GitHub issues with label `platform:linux-desktop`

---

Ready to play? Just run: `./gradlew composeApp:runJvm`
