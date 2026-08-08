# Roadmap

FlowLens starts small and grows. This is the intended direction, roughly in order.

## Now (MVP)
- [x] Source-only analysis via JavaParser + SymbolSolver (no build of target needed)
- [x] Spring MVC endpoint detection
- [x] Inter-procedural call-chain walk (cycle-guarded)
- [x] Spring Data repository sink classification
- [x] `@Query` / native SQL / `JdbcTemplate` sink resolution
- [x] Per-method capture of conditions & mutations (shown in node detail)
- [x] Interactive Cytoscape viewer

## Next
- [ ] **Branch conditions as first-class graph nodes** (true control-flow shape, not just metadata)
- [ ] Interface → implementation resolution (pick concrete `@Service`/`@Repository` impls)
- [ ] MyBatis mapper XML + annotation support
- [ ] Field-level data lineage (which request field ends up in which column)
- [ ] Multi-endpoint overview graph + search
- [ ] Export to Mermaid / PNG / SVG

## Later
- [ ] **MCP server** so AI coding agents can query the flow graph ("what writes to the `account` table?", "impact of changing `AccountService.create`")
- [ ] Diff mode: show how a PR changes the data flow
- [ ] JAX-RS, Micronaut, Quarkus endpoint detectors
- [ ] Kotlin support
- [ ] Confidence scoring on unresolved edges
