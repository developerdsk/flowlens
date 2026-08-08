# FlowLens

**See how a request actually flows through your Java code — from an HTTP endpoint down to the database write.**

FlowLens statically analyzes a Java / Spring source tree and, for each REST endpoint,
builds a **data-flow graph**: the controller entry point, the chain of service and
repository calls, the branch conditions and object mutations along the way, and the
concrete persistence sink (the `INSERT` / `UPDATE` / `DELETE` and the table it hits).

It reads **source only** — no build, no running app, no instrumentation required.

## Why

AI now writes a large and growing share of enterprise code. The bottleneck has shifted
from *writing* code to *understanding* it: reviewers and engineers struggle to follow
what a change actually does across controller → service → repository → database.
Runtime tracers (Jaeger, Zipkin, OpenTelemetry) need a running, instrumented app and
show spans, not statements. Structure tools (jQAssistant, Sourcetrail) show class/method
edges, not how the request data flows and mutates.

FlowLens fills that gap: a **static, per-endpoint, request-scoped data-flow graph** you
can generate from any checkout and read in the browser.

## How it works

```
  Java source tree
        │
        ▼
  ┌───────────────┐   JavaParser + SymbolSolver
  │   analyzer/   │   • detect @RestController endpoints
  │  (Java/Maven) │   • walk the call chain (service → repo)
  │               │   • capture if/switch conditions & field mutations
  │               │   • resolve Spring Data / JPA / JDBC sinks → table + op
  └───────┬───────┘
          │  graph.json
          ▼
  ┌───────────────┐   Cytoscape.js single-page app
  │    viewer/    │   • hierarchical layout of the flow
  │  (static web) │   • click a node → see code, mutations, conditions
  │               │   • filter by layer (controller/service/repo/db)
  └───────────────┘
```

## Quick start

```bash
# 1. Build the analyzer
cd analyzer
mvn -q package

# 2. Analyze a project (points at a source root; no build of the target needed)
java -jar target/flowlens-analyzer.jar ../examples/sample-app ../viewer/graph.json

# 3. Open the viewer
#    serve the viewer/ folder (any static server) and open index.html
cd ../viewer && python -m http.server 8000
# → http://localhost:8000
```

## Status

Early MVP. Working today:

- Spring MVC endpoint detection (`@GetMapping`/`@PostMapping`/`@RequestMapping`, etc.)
- Inter-procedural call-chain walk across project classes (cycle-guarded)
- Spring Data repository sink classification (`save`→UPSERT, `deleteBy…`→DELETE, `find…`→SELECT)
- `@Query` / JPQL / native SQL and `JdbcTemplate` sink resolution (op + table via regex)
- Per-method capture of `if`/`switch` conditions and field/setter mutations
- Interactive Cytoscape viewer with per-node detail

## Roadmap

See [ROADMAP.md](ROADMAP.md). Next up: branch conditions as first-class graph nodes,
MyBatis mapper support, interface→implementation resolution, and an MCP server so AI
agents can query the flow graph directly.

## License

Apache-2.0. Contributions welcome.
