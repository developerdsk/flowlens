# Process view: implementation plan

Turn the approved design into the real FlowLens viewer, one small PR at a time.

- **Design (approved 2026-10-25):** https://claude.ai/artifact/Via71WZ4NuwVaojK3dGmKg. It has four boards: the endpoint process, a sub-process expanded in place, the all-endpoints catalog, and notation and layout rules.
- **Status:** the "Progress" table below is the source of truth. Update it whenever a PR is opened or merged.

## How to resume

1. Read the "Progress" table and find the first PR that isn't `merged`.
2. If it's `open`, check its review comments with `gh pr view <n> --comments` and address them. Don't start the next PR yet.
3. If it's `todo`, run `git switch main && git pull` and create its branch (`process-view/<nn>-<slug>`). Build only that PR's scope, run the checks under "Every PR", then push and open the PR.
4. Open the next PR only after the user merges the previous one. Only one PR is under review at a time.

## Ground rules

- **Each merge to `main` publishes a desktop release** (`.github/workflows/release.yml`). The new view stays behind **Options → "Process view (preview)"**, off by default, until PR 10. With it off, the app behaves exactly as before.
- **Analyzer changes only add fields** (no `SCHEMA_VERSION` bump). Older viewers, the MCP server and saved `.flowlens` snapshots keep working. Snapshots without the new fields fall back to edge-list order.
- **The Cytoscape graph stays.** It becomes "Call graph" in the Process / Call graph switch and "Map" in the Catalog / Map switch.
- **Fonts:** bundle IBM Plex Sans and Mono (OFL licence) under `viewer/fonts/`. The desktop app has to work offline, so no Google Fonts.
- **Layout engine:** elkjs (layered, orthogonal routing, lane partitions), copied into `viewer/vendor/` like Cytoscape. dagre stays for the call graph.
- **Not part of any PR:** the uncommitted line-ending change to `analyzer/mvnw.cmd`.
- **Size:** aim for ≤ ~600 changed lines per PR. Split a PR that grows past that.

## Every PR

- Create a branch off the latest `main`. Never push to `main`.
- Before pushing, all of these must pass:
  - `cd analyzer && ./mvnw -B verify` (engine tests)
  - `cd analyzer && ./mvnw -q -DskipTests package && cd ../viewer && npm run e2e` (viewer e2e)
  - `cd viewer && npm test` (unit tests, from PR 2 on)
- The PR description says what changed, where to start reading, and how to try it. UI changes include before/after screenshots.

## Progress

| # | PR | Branch | Status | Link |
|---|---|---|---|---|
| 1 | Analyzer: step order and return types | `process-view/01-call-order` | merged | [#5](https://github.com/developerdsk/flowlens/pull/5) |
| 2 | Viewer: process model, no UI | `process-view/02-process-model` | merged | [#6](https://github.com/developerdsk/flowlens/pull/6) |
| 3 | Theme tokens and fonts | `process-view/03-tokens` | merged | [#7](https://github.com/developerdsk/flowlens/pull/7) |
| 4 | Toolbar and sidebar layout | `process-view/04-shell` | merged | [#8](https://github.com/developerdsk/flowlens/pull/8) |
| 5 | Process view, read-only (preview) | `process-view/05-process-view` | open | |
| 6 | Step detail panel | `process-view/06-detail-panel` | todo | |
| 7 | Expand sub-processes in place | `process-view/07-drill-in` | todo | |
| 8 | All-endpoints catalog | `process-view/08-catalog` | todo | |
| 9 | Compare and export in the process view | `process-view/09-compare-export` | todo | |
| 10 | Make the process view the default | `process-view/10-default` | todo | |

Statuses: `todo` → `in progress` → `open` → `merged`.

PRs 1 and 2 don't depend on each other. From PR 3 on, each PR builds on the one before.

## PR details

### 1. Analyzer: step order and return types (~150 lines)
- `Edge`: add `line` (the call-site line) and `seq` (the order of the call inside its calling method). Set them in `FlowAnalyzer.link()` from the `site` node. Branch-guard edges take the guard's position. When two calls produce the same edge key, the first call site wins.
- `Node`: add `returns` (the simple return type) on endpoint and method nodes, filled in `fillDetails`.
- Regenerate `viewer/graph.sample.json`.
- Tests:
  - `FlowTest`: `AccountServiceImpl.create()` edges must be ordered existsByEmail → branch/throw → save → record → event.
  - `FlowTest`: `createAccount()` must have `returns == "Account"`.
  - The golden snapshot (labels only) and the MCP tests stay unchanged.

### 2. Viewer: process model, no UI (~400 lines)
- `viewer/js/process.js`: `buildProcess(graph, endpointId, { expanded })` returns `{ start, steps, decisions, ends, lanes, connectors, tables, systems, subprocesses, summary }`. It follows the Notation board's rules:
  - **Start event:** a controller that only delegates becomes the start event.
  - **Step order:** steps are ordered by `seq`.
  - **Lanes:** one lane per layer (Service, Database, Async listeners, External). Empty lanes are hidden.
  - **Decisions:** a decision guards a throw or a sink. A throw becomes an error end under its decision.
  - **Data steps:** a sink edge becomes a leaf step with one table card per use.
  - **Events:** an event edge becomes a publish event, and its listener goes in the async lane.
  - **Sub-processes:** a method with its own steps becomes a sub-process, collapsed by default.
  - **Inferred calls:** a dispatch or low-confidence edge becomes an inferred connector.
  - **Cycles:** stop at a method already on the stack and mark it as recursive.
- `summary` holds the reads, writes, publishes and calls counts, plus the number of decisions, error ends and sub-processes. The context strip and the catalog cards both use it.
- `viewer/tests/process.test.mjs` (`node --test`):
  - The sample graph's `POST /api/accounts` matches the design board.
  - Snapshots without `seq` still build.
  - A recursive graph terminates.
- `viewer/package.json` gets a `test` script, and `.github/workflows/ci.yml` gets an `npm test` step in `viewer-e2e`.

### 3. Theme tokens and fonts (~250 lines)
- `viewer/css/app.css`: the design's palette as variables for light and dark (auto / light / dark stays). This covers the ground, panel, line and text colours, the blue accent, the colours for each kind of step, the table operation colours (SELECT / INSERT / UPSERT / UPDATE / DELETE), and the compare colours. Text meets 4.5:1 contrast.
- Add the bundled Plex fonts and `@font-face`.
- `graph.js` already reads the CSS variables, so the call graph restyles itself. Only adjust colours that no longer read well.
- e2e stays the same. The PR includes before/after screenshots in both themes.

### 4. Toolbar and sidebar layout (~350 lines)
- **Toolbar:** brand, project picker (open, recent and paste-a-path in one menu), the "Watching" pill (the watch toggle), and search moved here with ⌘K. Then Compare, Export, Theme and Help. Indexing options and layers live in menus.
- **Sidebar:** the Endpoints, Tables and Issues tabs. Endpoints are grouped by class, with coloured method chips (GET, POST, PATCH, DELETE, CRON) and an "All endpoints" row.
- Keep every ID and `data-*` hook that `tests/e2e.mjs` and `desktop.js` use, including `#pathInput`, `#indexBtn`, `#reindexBtn`, `#watchToggle`, `[data-ep]`, `[data-all]`, `[data-table]`, `.tab[data-tab]`, `#status`, the menu toggles and `data-action`.

### 5. Process view, read-only (preview) (~600 lines)
- `viewer/js/processView.js`: renders `buildProcess()` with HTML step boxes and SVG connectors. elkjs handles the layout, with the lanes as partitions.
- **Header:** breadcrumb, the Process / Call graph switch, zoom −/+, and Fit.
- **Context strip** under the header: Reads, Writes, Publishes, Calls, then the step counts.
- **Interaction:** clicking a step selects its node (or, for a data step, its edge) through the existing `selectNode`, so the current detail panel opens. Steps are real `<button>`s, reachable with Tab, and arrow keys move along the sequence.
- Add the preview toggle in Options, stored in `state.js` as `processPreview`.
- e2e: turn preview on, select `POST /api/accounts`, and check the step order, the lane labels and the error end. Turn preview off and run the existing steps unchanged.

### 6. Step detail panel (~300 lines)
- Redo `detail.js` to the design's layout:
  - What kind of step it is, the method name, and the class with its caller.
  - Open in IDE as the main button, opening at the exact call `line` (from PR 1). Callers is the second button.
  - Writes or reads with the table operation chip.
  - Field lineage, and "Set before save" (the mutations).
  - Source with the call line highlighted.
- The panel serves both views. For a data step, it shows the sink.
- The existing e2e assertions still pass: `#panel h2` for the endpoint and the lineage text.

### 7. Expand sub-processes in place (~400 lines)
- The + button on a sub-process opens it as a container with its own steps, and neighbouring steps shift right. "Expand all" and "Collapse all" go in the header, and the breadcrumb shows the expanded path.
- Tables and systems always stay visible: collapsing hides steps, never the data they touch.
- Recursion shows a "recursive call" marker. There's a depth cap.
- e2e: expanding `onAccountCreated()` shows `send()` and `postForObject()`. The large-project e2e (whose 90 services call each other in a loop) renders with preview on, with no console errors.

### 8. All-endpoints catalog (~350 lines)
- With preview on, "All endpoints" shows cards grouped by class. Each card has the method chip, path and handler, a small flow sketch, Reads / Writes / Deletes chips, and the counts in a footer. A scheduled job card says when it runs, e.g. "Runs daily at 03:00".
- The header has the totals line and the All / Writes data / Has errors filters. The Catalog / Map switch keeps the old graph as "Map".
- Search filters the cards, and clicking a card opens its process.
- e2e: the catalog shows 5 cards, the "Writes data" filter leaves 4, and clicking a card opens the process.

### 9. Compare and export in the process view (~300 lines)
- Show the diff on steps and tables:
  - added: badge and border
  - changed: badge and border
  - removed: dashed and struck through
- The compare banner keeps working.
- Export → PNG and SVG export the process view when it's showing. Mermaid and snapshot export don't change.
- e2e: repeat the compare-with-HEAD step with preview on, so `INSERT audit_events` shows as added and `audit_log` as removed. Check the exported SVG and PNG.

### 10. Make the process view the default (~100 lines)
- Remove the preview switch: the process view is the default, and Call graph / Map are one click away.
- Update the README screenshots and walkthrough, `docs/QA-CHECKLIST.md` and `CHANGELOG.md`.
- Run the full e2e, then the QA checklist on a desktop build.
- Mark this plan done.
