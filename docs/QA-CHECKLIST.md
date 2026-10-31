# Desktop release QA checklist (D13)

Run on **clean** machines/VMs **without Java installed**, using the installers from the draft release.

| Platform | Version(s) |
|---|---|
| Windows | 10 22H2 x64, 11 x64 (and 11 on ARM, via emulation) |
| macOS Apple Silicon | 14, 15 |
| macOS Intel | 12 (minimum supported is 11), 15 |
| Linux | Ubuntu 22.04 / 24.04 (AppImage + deb) |

## Install
- [ ] Installer runs as a normal (non-admin) user; app appears in Start menu / Applications / launcher
- [ ] Unsigned build: first-launch warning matches the README instructions (skip once signed)
- [ ] Signed build: no Gatekeeper / SmartScreen warning; `spctl -a -vv FlowLens.app` → accepted, notarized
- [ ] Windows 10 without WebView2: installer fetches it and the app starts

## First run
- [ ] App opens within ~3 s, sample project visible, version shown in the footer and About
- [ ] `java` is never required: rename any system Java and the app still works
- [ ] *Open Project…* shows the native folder dialog; indexing shows progress and can be cancelled
- [ ] Project paths with spaces and non-ASCII characters (e.g. `C:\Users\Zoë\My Projects\app`)
- [ ] A large project (5k+ files) indexes without freezing the window; *Options → Engine memory* works
- [ ] Recent projects appear in *File → Open Recent* and the toolbar menu after restart
- [ ] Window size/position remembered after restart

## Features
- [ ] Endpoint tree, search, Tables tab, Issues tab
- [ ] An endpoint opens as a process; Process / Call graph switch; a sub-process opens in place; Expand / Collapse all, zoom, Fit
- [ ] *All endpoints* opens the catalog: filters, search, Catalog / Map switch; a card opens its process
- [ ] Compare marks steps and tables in the process view; Export PNG / SVG from the process view
- [ ] Open in VS Code / IntelliJ buttons open the right file and line (with the IDE installed)
- [ ] Export PNG / SVG / Mermaid, Save/Open snapshot via native dialogs
- [ ] Double-clicking a `.flowlens` file opens it (app closed and app already running)
- [ ] Drag & drop a folder and a `.flowlens` file onto the window
- [ ] Compare with git ref (git installed) and with a snapshot
- [ ] Watch mode re-indexes after saving a file in the project
- [ ] Light / dark / auto theme follows the OS
- [ ] *Help → Connect AI Agents (MCP)* command works when pasted into `claude mcp add`

## Robustness
- [ ] Kill the engine process (`java`) from Task Manager / Activity Monitor → app restarts it, keeps working
- [ ] Quit the app (and force-quit it) → no `java` process is left running
- [ ] Corporate proxy / antivirus present: indexing still works (engine only uses 127.0.0.1)
- [ ] Two app launches → second one focuses the first window

## Uninstall
- [ ] Uninstaller removes the app; no running processes left
- [ ] (Optional) remove settings: Windows `%APPDATA%\dev.flowlens.app`, macOS `~/Library/Application Support/dev.flowlens.app`, Linux `~/.config/dev.flowlens.app`

## Update (when the updater key is configured)
- [ ] Install version N, publish N+1 → app offers the update, installs, restarts on N+1
