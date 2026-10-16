# Roadmap

## Done (0.2.0)
- [x] Installable desktop app for Windows, macOS (Intel + Apple Silicon), Linux, with bundled runtime
- [x] Release pipeline; signing, notarization and auto-update ready to switch on
- [x] Every merge to `main` publishes unsigned installers (Windows, macOS, Linux) with an auto-bumped patch version
- [x] Java 21 syntax, interface → implementation, multi-module, generated sources
- [x] Branch conditions as first-class graph nodes
- [x] MyBatis, jOOQ, Mongo, Redis, JdbcClient, Feign / HttpExchange, RestTemplate / WebClient, messaging
- [x] JAX-RS / Quarkus, Micronaut, WebFlux routes, listeners, schedulers
- [x] Field-level data lineage (request field → column)
- [x] Endpoint explorer, tables view, search, export (PNG / SVG / Mermaid), snapshots
- [x] MCP server for AI coding agents
- [x] Diff mode (git ref or snapshot)
- [x] Confidence markers and unresolved-call reporting

## Next
- [ ] Signed apps: Apple Developer ID + notarization for macOS and an Authenticode certificate for
      Windows, so users no longer see the "unidentified developer" / SmartScreen prompts; then turn
      on auto-update (the pipeline already supports both, it only needs the secrets)
- [ ] GraalVM native-image engine (see docs/native-image-spike.md)
- [ ] Lineage through MapStruct mappers and constructor arguments
- [ ] PR integration: comment a flow diff on pull requests (GitHub Action using `--diff`)
- [ ] Windows on ARM native build

## Out of scope (for now)
- Kotlin sources
