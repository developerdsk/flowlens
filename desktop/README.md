# FlowLens desktop app

An installable app for **Windows**, **macOS (Intel and Apple Silicon)** and **Linux**, like Postman:
download, install, open a project folder. No Java, Maven or terminal needed.

## How it fits together

```
 FlowLens.app / FlowLens.exe          Tauri 2 shell (Rust) + the system WebView
 ├── UI ........ viewer/ (copied to desktop/dist at build time)
 ├── Shell ..... native folder/save dialogs, menus, recent projects, single instance,
 │               .flowlens file association, window state, logs, auto-update (optional)
 └── Engine .... engine/flowlens-analyzer.jar + engine/runtime (minimal JRE made by jlink)
                 started on 127.0.0.1:<random port> with a per-launch secret token
```

- The UI talks to the engine **only through the shell** (`engine_request` IPC command), which adds
  the token. The engine rejects requests without it, from other hosts, or from web pages.
- The engine's stdin is a pipe owned by the app: if the app exits or crashes, the engine exits
  too (no orphaned `java` processes). If the engine crashes, the app restarts it.
- Logs: `flowlens.log` (shell) and `engine.log` (engine) in the OS log folder —
  *Help → Open Logs Folder*. No telemetry.

## Develop

Prerequisites: JDK 17+ (21 recommended), Node 20+, Rust (stable), and on Linux the WebKitGTK
packages (`libwebkit2gtk-4.1-dev libgtk-3-dev librsvg2-dev libayatana-appindicator3-dev`).

```bash
cd desktop
npm ci
node scripts/build-engine.mjs   # jar + minimal Java runtime into src-tauri/engine
npm run dev                     # run the app with hot reload of the viewer
npm run build                   # installers for this OS in src-tauri/target/release/bundle
```

`scripts/prepare.mjs` runs automatically before dev/build: it copies the viewer, syncs the version
from `analyzer/pom.xml` (the single version source) and builds the engine if it is missing.

## Releases

`.github/workflows/release.yml` builds and publishes a GitHub Release on every push to `main` (every
merged PR), on every `v*` tag, or manually from the Actions tab (on a branch other than `main` it
stays a draft, as a test build):

| Download | Built on | Notes |
|---|---|---|
| `FlowLens_x.y.z_x64-setup.exe`, `.msi` | `windows-latest` | per-user install, no admin; WebView2 bootstrapper embedded |
| `FlowLens_x.y.z_aarch64.dmg` | `macos-14` (Apple Silicon) | |
| `FlowLens_x.y.z_x64.dmg` | `macos-15-intel` | see the workflow comment if GitHub retires Intel runners |
| `.AppImage`, `.deb` | `ubuntu-22.04` | |

Every platform uploads into one draft release, which is published only when all builds succeed; if
any build fails, the draft is deleted. Merges that only touch Markdown, `docs/` or `LICENSE` don't
produce a release.

**Versions bump automatically.** Each merge to `main` releases the next patch version (`0.2.0` →
`0.2.1` → `0.2.2` …): the highest published `vX.Y.Z` tag plus one. Nothing is committed back; the
workflow writes the version into `analyzer/pom.xml` for the build only. To start a new minor or major
line, set it in `analyzer/pom.xml` (e.g. `0.3.0`), and the next merge releases exactly that. Pushing
a tag like `v1.0.0` releases that exact version.

### Code signing (optional — builds are unsigned until you add secrets)

Unsigned apps work, but:
- **macOS** says the app "can't be opened because Apple cannot check it". Right-click the app →
  *Open* → *Open* once (or `xattr -dr com.apple.quarantine /Applications/FlowLens.app`).
- **Windows** SmartScreen shows "Windows protected your PC" → *More info* → *Run anyway*.

To sign, add these repository secrets; the workflow picks them up automatically:

| Platform | Secrets | Where to get it |
|---|---|---|
| macOS | `APPLE_CERTIFICATE` (base64 .p12), `APPLE_CERTIFICATE_PASSWORD`, `APPLE_SIGNING_IDENTITY`, `APPLE_ID`, `APPLE_PASSWORD` (app-specific), `APPLE_TEAM_ID` | Apple Developer Program, "Developer ID Application" certificate |
| Windows | `WINDOWS_CERTIFICATE` (base64 .pfx), `WINDOWS_CERTIFICATE_PASSWORD` | an OV/EV code-signing certificate (or adapt `scripts/windows-signing.ps1` for Azure Trusted Signing) |

On macOS the bundled Java runtime is signed first (`scripts/macos-sign-runtime.sh`, hardened runtime
+ `entitlements.plist` with the JIT entitlements the JVM needs), then Tauri signs and notarizes the app.

### Auto-update (optional)

1. `npx tauri signer generate -w ~/.tauri/flowlens.key`
2. Put the **public** key into `src-tauri/tauri.updater.conf.json` (`pubkey`).
3. Add secrets `TAURI_SIGNING_PRIVATE_KEY` (file contents) and `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`.

Releases then include signed update bundles and `latest.json`; the app checks on start and via
*Help → Check for Updates…*. Without the key the updater is compiled out.

### Cross-building macOS Intel on Apple Silicon

```bash
# an x64 JDK of the SAME version as the arm64 JDK used to run jlink
FLOWLENS_JMODS=/path/to/x64-jdk/jmods node scripts/build-engine.mjs
npx tauri build --target x86_64-apple-darwin --bundles app,dmg
```

## Icons

`npm run icons` renders `viewer/icon.svg` and regenerates every icon in `src-tauri/icons`.
