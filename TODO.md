# FlowLens — TODO and implementation status

> **Status (0.2.0, branch `claude/implement-todo`):** everything below is implemented, except
> where marked. ✅ done · 🟡 partly done · ❌ not done.
>
> Decisions taken: **Tauri 2** shell · **unsigned** builds for now (signing wired up, switched on
> by adding secrets) · **two Mac downloads** (Intel, Apple Silicon) · scope **M1–M4**.
>
> Not done:
> - 🟡 **D15 native-image:** Maven profile and notes only (`docs/native-image-spike.md`); not built.
> - ❌ **Kotlin support:** needs a Kotlin parser (the Kotlin compiler is ~60 MB); left for a decision.
> - Signing and auto-update only switch on once you add certificates and keys (see
>   `desktop/README.md`). The release workflow has not run on GitHub yet: the first `v*` tag will
>   exercise it.
>
> Main goal: ship FlowLens as an **installable desktop app** (like Postman) for
> **Windows (x64)**, **macOS Intel (x86_64)** and **macOS Apple Silicon (arm64)**.
> The user installs it and launches it from the Start menu or Dock. They should not
> need Java, Maven or a terminal.

**Legend**
- Priority: **P0** blocks the desktop release · **P1** should ship in 1.0 · **P2** nice to have · **P3** later
- Size: **S** ≤ 1 day · **M** a few days · **L** a week or more
- Evidence: ✅ reproduced by running the analyzer on a probe project · 🔍 found by reading the code

---

## 0. Decisions needed from you

| # | Decision | Options | My recommendation |
|---|----------|---------|-------------------|
| Q1 | **Desktop shell** | **Tauri 2** (Rust + system WebView) · **Electron** (what Postman uses) · **pure Java** (`jpackage` + JavaFX WebView) | **Tauri 2.** Smallest installer (~50–70 MB with the Java runtime, versus ~150 MB+ for Electron). It has a native folder dialog, menus, auto-updater, single-instance and signed `.msi`/`.exe`/`.dmg` output built in. Our existing `viewer/` HTML runs in it unchanged. Electron is the safe fallback if you'd rather stay JS-only. |
| Q2 | **How the Java analyzer ships** | **Bundled minimal JRE** (`jlink`, ~40 MB per platform) · **GraalVM native-image** (no JRE, ~30–50 MB, fast start) | **Bundled `jlink` JRE** for 1.0 because it's low risk. Spike native-image later (Jackson and JavaParser need reflection config). |
| Q3 | **Code-signing budget** | Apple Developer Program (~$99/yr) for signing and notarization · Windows code-signing (Azure Trusted Signing ~$10/mo, or an OV/EV certificate) | Needed for a "real app" feel. Unsigned builds show *"unidentified developer / app is damaged"* on macOS and a **SmartScreen** warning on Windows. Unsigned is fine for an internal alpha. |
| Q4 | **Mac build flavour** | Two downloads (*Mac Intel*, *Mac Apple Silicon*, as Postman does) · one Universal `.dmg` | **Two downloads.** A Universal build has to carry both JREs, which roughly doubles the size. |
| Q5 | **Distribution and updates** | GitHub Releases + built-in auto-update · manual download only | GitHub Releases + Tauri updater (free, signed update manifests). |
| Q6 | **Keep the browser/CLI mode?** (`java -jar … --serve` and one-shot CLI) | Keep · drop | **Keep.** It costs little and is needed for CI, headless servers and the future MCP server. |
| Q7 | **Linux build** | Include `.AppImage`/`.deb` · skip | Include. It's nearly free once the CI matrix exists. |

---

## 1. Desktop app (Postman-style installer) — new track

### Target architecture

```
 FlowLens.app / FlowLens.exe   (Tauri shell, system WebView: WKWebView on macOS, WebView2 on Windows)
 ├── UI ............ existing viewer/ (index.html + Cytoscape), loaded from the app bundle
 ├── Native ........ folder dialog, app menu, recent projects, single instance, auto-update
 └── Engine ........ flowlens-analyzer.jar + bundled jlink JRE, started as a child process on
                     127.0.0.1:<random port> with a per-launch secret token; killed on app quit
```

### Platform matrix

| Platform | Installer | Built on (GitHub Actions) | Notes |
|---|---|---|---|
| Windows 10/11 x64 | `.exe` (NSIS) + `.msi` | `windows-latest` | WebView2 is preinstalled on Win 11 and most Win 10 machines. Embed the bootstrapper for the rest. Per-user install, no admin. |
| macOS Apple Silicon | `.dmg` (arm64) | `macos-14`/`macos-15` (arm64) | |
| macOS Intel | `.dmg` (x86_64) | Intel runner while GitHub still offers one, otherwise cross-build on arm64 (Rust `x86_64-apple-darwin` target + `jlink` against x64 JDK jmods) | Smoke-test on a real Intel Mac, or Rosetta at minimum. |
| Linux (optional) | `.AppImage` + `.deb` | `ubuntu-latest` | |
| Windows on ARM (later) | — | — | The x64 build runs under emulation. Native build later if asked for. |

### Tasks

| ID | P | Size | Task |
|----|---|------|------|
| ✅ D1 | P0 | S | **Engine mode for the shell.** Add `--serve --port 0` (OS picks a free port). Print a machine-readable `FLOWLENS_READY port=… token=…` line on stdout. Require the token header on every `/api/*` call. Exit when the parent process dies. |
| ✅ D2 | P0 | S | **Native folder picker from the shell** (Tauri dialog plugin), then call `/api/analyze {path}`. Drop the Swing `JFileChooser` from the desktop flow (`Server.java:103`). It isn't truly native on macOS, it opens an odd Dock icon, and it forces `java.desktop` into the bundled JRE. Keep `/api/pick` only for browser mode. |
| ✅ D3 | P0 | M | **Minimal JRE via `jdeps` + `jlink`** for each OS/arch (expected modules: `java.base`, `jdk.httpserver`, `java.logging`, …). Target ≤ 45 MB. |
| ✅ D4 | P0 | M | **Tauri project scaffold** (`desktop/`): window, icons (`.icns`/`.ico`), bundle id `dev.flowlens.app`. Sidecar lifecycle: spawn, health-check, restart on crash, and **no orphaned `java` processes** after quit or crash. Single-instance lock. |
| ✅ D5 | P0 | S | **Viewer desktop mode:** detect the shell, wire *Open project* to the native dialog, add a busy spinner, and add shortcuts (`Ctrl/Cmd+O` open, `Ctrl/Cmd+R` re-index, `Ctrl/Cmd+F` search). |
| ✅ D6 | P0 | M | **Release pipeline:** on tag `v*`, a GitHub Actions matrix builds jar → jlink JRE → `tauri build` and uploads installers to a draft GitHub Release. |
| ✅ D7 | P1 | M | **Code signing:** macOS Developer ID + hardened runtime + notarization (`notarytool`). Every dylib in the bundled JRE must be signed. The JVM needs the `allow-jit` / `allow-unsigned-executable-memory` entitlements. Windows: Authenticode-sign the `.exe`/`.msi`. *(Needs Q3.)* |
| ✅ D8 | P1 | S | **Auto-update** with the Tauri updater: signed `latest.json` on GitHub Releases, plus a *Help → Check for updates* menu item. |
| ✅ D9 | P1 | S | **App menu:** File (Open Project…, Open Recent ▸, Re-index, Export ▸, Quit), View (Fit, Direction, Theme), Help (Docs, Logs folder, About + version). |
| ✅ D10 | P1 | S | **Persisted app state** in the OS app-data dir: recent projects, last selected endpoint, layout direction, window size and position. |
| ✅ D11 | P1 | M | **Large-project UX:** streaming progress ("parsing 1,240 / 5,300 files"), *Cancel* button, configurable memory (`-Xmx`), and no frozen UI. |
| ✅ D12 | P1 | S | **Logs and support:** a rolling log file in the OS log dir, *Help → Open logs folder*, and no telemetry by default. |
| ✅ D13 | P1 | S | **Install QA checklist** on clean VMs without Java: Win 10, Win 11, macOS 12–15 Intel, macOS 14+ Apple Silicon. Non-admin install, project paths with spaces and non-ASCII characters, corporate proxy and antivirus present, uninstall leaves nothing behind. |
| ✅ D14 | P2 | S | **Drag and drop** a folder onto the window to index it. **File association** for saved `.flowlens` snapshots (see E10). |
| 🟡 D15 | P3 | M | **Spike:** GraalVM native-image engine (drops the JRE, faster cold start). |

---

## 2. Bugs

| ID | P | Size | Evidence | Bug | Where / fix idea |
|----|---|------|----------|-----|------------------|
| ✅ B1 | **P0** | S | ✅ | **Modern Java is rejected.** Files containing records, text blocks, switch expressions, sealed classes and similar are *silently skipped*. JavaParser defaults to an old language level. In the probe, a repository with a text-block `@Query` and a `record` DTO were both dropped, so the DB sink disappeared. | `FlowAnalyzer.java:56`: use a `JavaParser` instance with `LanguageLevel.JAVA_21` (or `BLEEDING_EDGE`). Also treat `TextBlockLiteralExpr` as a query string in `SinkClassifier.java:35`. |
| ✅ B2 | **P0** | M | ✅ | **Calls through an interface stop dead.** `OrderService` (interface) → `OrderServiceImpl` (`@Service`) is never followed, so no repository or DB node appears. This is the most common Spring layout, so many real projects show *no DB sinks*. | Map each interface to its `@Service`/`@Component`/`@Repository` implementations. Honour `@Primary`/`@Qualifier`. If there are several impls, show all of them with a "multiple impls" marker. |
| ✅ B3 | **P0** | S | ✅ | **Duplicated conditions and mutations.** A method reachable from N endpoints lists every `if` and mutation N times (probe: `if (n > 0)` ×3). | `FlowAnalyzer.java:136`: call `fillDetails` only when the node is first created. |
| ✅ B4 | **P1** | S | ✅ | **Build and output folders are scanned** (`target/`, `build/`, `out/`, `.git`, `node_modules`, generated sources). In the probe, a copy under `target/` *replaced* the real controller (the node pointed at `target/gen/…`). Test sources (`src/test/java`) are scanned too. | `FlowAnalyzer.java:328`: skip those dirs. Default to `src/main/java` roots when present, and add an "include tests" toggle. |
| ✅ B5 | **P1** | S | ✅ | **`produces`/`consumes` read as the URL path.** `@GetMapping(produces = "application/json")` became `GET /api/application/json`. Arrays (`{"/a","/b"}`) keep only the first path. | `FlowAnalyzer.java:227,233`: read only `value`/`path` (or the single-member value), and emit one endpoint per path. Resolve `static final String` constants. |
| ✅ B6 | **P1** | S | ✅ | **Non-UTF-8 source files are skipped** (Windows-1252 / ISO-8859-1 are common in older Windows codebases). | `FlowAnalyzer.java:332`: try UTF-8, fall back to the platform charset or ISO-8859-1, and record a warning. |
| ✅ B7 | **P1** | S | ✅ | **Server error responses are invalid JSON.** Messages containing newlines aren't escaped, so the viewer's `r.json()` throws and shows the misleading *"Cannot reach the FlowLens server"*. A `null` message prints as "failed: null". | `Server.java:163`: build the error body with Jackson. |
| ✅ B8 | **P1** | S | ✅ | **Security: DNS-rebinding / cross-site access to the local server.** A request with a foreign `Host`/`Origin` is served (HTTP 200), so a malicious web page could rebind its domain to 127.0.0.1 and read source code from any local path through `/api/analyze`. Any page can also trigger `/api/pick` dialogs. **Must be fixed before we distribute an app.** | Only accept `Host` = `127.0.0.1:<port>` or `localhost:<port>`, reject foreign `Origin`, require `Content-Type: application/json`, and add the per-launch token (D1). |
| ✅ B9 | **P1** | S | ✅ | **Busy port crashes the server** with a raw `BindException` stack trace. A bad port argument throws `NumberFormatException`. | `Main.java:13`, `Server.java:42`: show a friendly message and fall back to a free port (desktop always uses a random port). |
| ✅ B10 | P2 | S | ✅ | **`var` locals aren't resolved.** `var r = repo; r.deleteAll();` produced no DB sink. | `FlowAnalyzer.java:172` area: infer the type from the initializer, or wire the symbol solver (B16). |
| ✅ B11 | P2 | S | 🔍 | **`@Table(schema = "sales", name = "orders")` gives table `sales`.** The code takes the first string literal, which here is the schema. The same "first literal wins" pattern exists for `@RequestMapping` and `@Query`. | `ProjectModel.tableName()`: read the `name` attribute explicitly and show `schema.table`. |
| ✅ B12 | P2 | S | 🔍 | **Custom base repositories aren't detected** (`interface BaseRepo<T> extends JpaRepository<T,Long>` → `UserRepo extends BaseRepo<User>`). | `ProjectModel.java:79`: resolve the extends chain transitively within the project. |
| ✅ B13 | P2 | S | 🔍 | **Simple-name collisions.** `bySimpleName` keeps only the last class with a given name (nested `Builder`s, same name in two modules), which leads to wrong edges. Overloaded handler methods share one endpoint id. Overloads are picked by argument count only. | `ProjectModel.java:62`, `FlowAnalyzer.java:74`: resolve through imports and package, and include parameter types in ids. |
| ✅ B14 | P2 | S | 🔍 | **Viewer:** the "No Spring endpoints found" message is immediately overwritten by "Indexed 0 nodes…". There is no busy state, so double-clicking *Open project* starts several dialogs or analyses. | `viewer/index.html:173`: guard the status message and disable buttons while busy. |
| ✅ B15 | P2 | S | ✅ | **Skipped files are invisible to the user.** Parse and read warnings only go to stderr, so in the UI a partial result looks complete. | Add `diagnostics: [{file, reason}]` to `graph.json` and show a "⚠ 3 files skipped" badge with details. |
| ✅ B16 | P2 | S | 🔍 | **SymbolSolver is a dependency but never configured,** although the README says it is used. | Either wire `JavaSymbolSolver` (helps B2, B10 and chained calls at `FlowAnalyzer.java:167`) or remove it to shrink the jar. |
| ✅ B17 | P3 | S | 🔍 | **Full graph laid out twice on load.** Dagre runs once in `initCy` and again in `applyFilters`, which is slow on big projects. | `viewer/index.html:222`: start with `layout: {name:'preset'}` and run one layout. |
| ✅ B18 | P3 | S | ✅ / 🔍 | **Polish:** the console prints `?` instead of `—` on non-UTF-8 terminals (Windows `cmd`). The version string is hard-coded (`Graph.java:10`). The `files` upload branch of `/api/analyze` is dead code with no size limit. | Use ASCII in console output, make the pom the single version source, and remove or cap the upload path. |

---

## 3. Engineering foundations (needed before a release)

| ID | P | Size | Task |
|----|---|------|------|
| ✅ F1 | P0 | M | **Tests.** There are none today. Add JUnit 5 with small fixture projects and golden `graph.json` snapshots. Each bug above gets a regression test (the probe cases from this review are a ready starting set). |
| ✅ F2 | P0 | S | **CI on every PR:** build and test on Ubuntu, Windows and macOS. |
| ✅ F3 | P1 | S | **Maven wrapper (`mvnw`)** so contributors don't need Maven installed. Update `run.sh`/`run.bat` to use it. |
| ✅ F4 | P1 | S | **One version source** (pom → engine, `graph.json`, desktop app, About dialog). Add `schemaVersion` to `graph.json` so saved snapshots stay readable. |
| ✅ F5 | P1 | M | **Performance baseline** on real projects (spring-petclinic plus one large monolith with ~5k files): time and peak memory. Parse in parallel. Use a JavaParser instance per thread instead of the static, shared configuration. |
| ✅ F6 | P2 | S | `CONTRIBUTING.md`, issue templates, and a changelog. |

---

## 4. Enhancements

### Analysis depth
| ID | P | Size | Enhancement |
|----|---|------|-------------|
| ✅ E1 | P1 | M | **Multi-module Maven/Gradle projects:** detect modules and source roots, and pick which modules to index. |
| ✅ E2 | P1 | M | **More entry points:** controller methods inherited from interfaces (OpenAPI-generator style), WebFlux `RouterFunction`s, `@KafkaListener`/`@RabbitListener`/`@JmsListener`, and `@Scheduled`. |
| ✅ E3 | P1 | M | **More sinks:** outbound HTTP (`RestTemplate`, `WebClient`, `@FeignClient`) and messaging producers (`KafkaTemplate`, …) as "external" nodes. MyBatis mappers (annotation and XML). jOOQ. Spring Data Mongo/Redis. |
| ✅ E4 | P2 | L | **Branch conditions as first-class graph nodes** (true control-flow shape). *(Existing roadmap item.)* |
| ✅ E5 | P2 | M | **Confidence markers:** dashed edges or "?" badges where a receiver, table or implementation couldn't be resolved, so users know what to trust. |
| ✅ E6 | P3 | L | **Field-level lineage:** which request field ends up in which column. *(Roadmap.)* |

### Viewer / UX (Postman-like experience)
| ID | P | Size | Enhancement |
|----|---|------|-------------|
| ✅ E7 | P1 | M | **Left sidebar endpoint tree** grouped by controller, colour-coded by HTTP verb (as in a Postman collection), with **search** by path, class or table. Replaces the long dropdown. |
| ✅ E8 | P1 | S | **Open in IDE:** click a file:line to open it in VS Code (`vscode://file/…`) or IntelliJ (`idea://open?file=…&line=…`). |
| ✅ E9 | P1 | S | **Syntax-highlighted source** in the side panel. **Light/dark theme** that follows the OS. |
| ✅ E10 | P1 | M | **Export and save:** PNG/SVG of the current view, Mermaid text, and `.flowlens` snapshot files that can be reopened later or shared without re-indexing. |
| ✅ E11 | P2 | M | **Table-centric view:** pick a table and see every endpoint that reads or writes it (reverse reachability). |
| ✅ E12 | P2 | M | **Watch mode:** re-index on file save, with an incremental cache keyed by file hash. |
| ✅ E13 | P2 | S | **Big-graph helpers:** minimap, collapse a subtree, and focus-on-node (neighbourhood only). |

### Later (existing roadmap)
- ✅ **MCP server** so AI coding agents can query the graph (`--mcp`; Help → Connect AI Agents).
- ✅ **Diff mode:** compare with a git ref or a snapshot; affected endpoints are marked.
- ✅ JAX-RS / Micronaut / Quarkus detectors. ❌ Kotlin sources.

---

## 5. Suggested sequencing (for discussion)

1. **M1 — "Correct on real projects":** F1, F2, F3, B1, B2, B3, B4, B5, B6, B7, B15.
   *Exit: indexing 3 real open-source Spring apps shows endpoints and DB sinks with no silently skipped files.*
2. **M2 — "Desktop alpha" (unsigned, internal):** B8, B9, D1–D6, D9, D10, F4, B14, E7.
   *Exit: download → install → open folder → see flow on Windows, Mac Intel and Mac Apple Silicon, with no Java installed.*
3. **M3 — "Public 1.0":** D7, D8, D11, D12, D13, E8, E9, E10, E1, F5.
   *Exit: signed and notarized installers, auto-update works, QA checklist passes.*
4. **M4 — "Deeper analysis":** E2, E3, E4, E5, E11, E12, then the MCP server.

---

### Appendix — how the ✅ items were verified
A throwaway probe project was analyzed with the current `main` build (`flowlens-analyzer.jar`):
- A controller calling an interface-typed service → `OrderService.run()` with no outgoing edges (B2).
- One service method called from 3 endpoints → `conditions: ["if (n > 0)" ×3]` (B3).
- A copy of a controller under `target/gen/` → the endpoint's `file` became `target/gen/Ctl2.java` (B4).
- `@GetMapping(produces = "application/json")` → `GET /api/application/json` (B5).
- `record`, text-block `@Query` and a Latin-1 file → `[warn] skipped…` on stderr only (B1, B6, B15).
- `POST /api/analyze` with body `{bad` → a response body with a raw newline, which is not valid JSON (B7).
- `POST /api/analyze` with `Host: evil.example.com` → HTTP 200 (B8).
- A second `--serve` on the same port → `BindException` stack trace (B9).
- `var r = repo; r.deleteAll();` → no DB sink (B10).
