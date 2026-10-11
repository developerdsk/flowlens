# Contributing to FlowLens

Thanks for helping! The project has three parts:

| Folder | What | Stack |
|---|---|---|
| `analyzer/` | the engine: parser, flow analysis, local server, MCP server, CLI | Java 17+, Maven (wrapper included) |
| `viewer/` | the UI, served by the engine and bundled in the desktop app | plain JS modules + Cytoscape, no build step |
| `desktop/` | the installable app | Tauri 2 (Rust) |

## Build and test

```bash
cd analyzer && ./mvnw verify                 # engine + 40-odd tests (golden snapshot included)
java -jar target/flowlens-analyzer.jar --serve --open    # browser mode
cd ../viewer && npm install && npm run e2e   # drives the real UI against the engine
cd ../desktop && npm ci && npm run dev       # desktop app (see desktop/README.md)
```

## Guidelines
- **Every analyzer bug fix gets a test.** Tests build tiny throwaway projects (`TestProject`), so
  a regression test is usually 15 lines. See `FlowTest`, `SinksTest`, `EntryPointsTest`.
- If you intentionally change the sample app's flow, refresh the golden file:
  `./mvnw test -Dtest=SampleAppGoldenTest -Dflowlens.updateGolden=true` and review the diff.
- The engine must never execute or load the analyzed code: **source only**.
- The local server must stay loopback-only with its Host/Origin/token checks (`ServerTest`).
- Keep the viewer dependency-free (vendored libs only) so it works offline and in the desktop app.
- Bump the version only in `analyzer/pom.xml`; everything else reads it from there.

## README screenshots and video
`viewer/tests/capture-media.mjs` regenerates everything in `docs/images/` from the real UI
(needs Playwright's Chromium and ffmpeg with libx264):

```bash
git clone --depth 1 https://github.com/macrozheng/mall /tmp/mall
cp -r examples/sample-app /tmp/shop-service && git -C /tmp/shop-service init -q && git -C /tmp/shop-service add . && git -C /tmp/shop-service commit -qm base
cd viewer && npm install && node tests/capture-media.mjs /tmp/shop-service /tmp/mall ../docs/images
```

## Reporting analysis gaps
Open an issue with the "Missed flow" template and a minimal code sample: what FlowLens shows, and
what you expected. Small examples become test fixtures.
