# Changelog

## 0.2.0 — unreleased

### Desktop app
- Installable app for Windows, macOS (Intel and Apple Silicon) and Linux (Tauri 2), with a bundled
  minimal Java runtime: no Java or Maven needed. Native dialogs and menus, recent projects,
  `.flowlens` file association, drag & drop, window state, logs, optional auto-update.
- Release pipeline building all installers, with optional macOS notarization and Windows signing.

### Analysis
- Java 21 syntax (records, text blocks, sealed types, switch expressions, pattern matching).
- Calls through interfaces reach their implementations (`@Qualifier` / `@Primary` aware).
- More entry points: JAX-RS / Quarkus, Micronaut, WebFlux router functions, mappings inherited from
  OpenAPI-generated interfaces, Kafka / RabbitMQ / JMS / SQS listeners, `@Scheduled`, Quartz.
- More sinks: MyBatis (XML and annotations), JdbcClient, jOOQ, MongoDB, Redis, EntityManager,
  OpenFeign / `@HttpExchange`, RestTemplate, WebClient / RestClient, Kafka / RabbitMQ / JMS /
  Spring Cloud Stream / SQS producers; Spring events reach their `@EventListener`s.
- Branch conditions and throws are graph nodes; confidence markers; unresolved-call reporting.
- Request-field → column lineage.
- Multi-module projects, generated sources, test sources opt-in, non-UTF-8 files.
- MCP server (`--mcp`) for AI coding agents; graph diff (`--diff`, compare with a git ref).

### Viewer
- Postman-style sidebar (endpoints by class, tables, issues), search, progress + cancel, compare
  mode, export (PNG, SVG, Mermaid, snapshots), watch mode, minimap, focus/collapse, open in IDE,
  syntax highlighting, light/dark theme, keyboard shortcuts.

### Fixes
- Duplicated conditions/mutations; `produces` read as the path; build output scanned; invalid JSON
  error responses; port-in-use crash; `var` locals; `@Table(schema, name)`; custom base
  repositories; simple-name collisions; overloaded handlers.
- Security: the local server rejects foreign Host/Origin headers (DNS rebinding), non-JSON posts,
  and — in the desktop app — requests without the per-launch token.

## 0.1.0
- MVP: Spring MVC endpoints → call chain → Spring Data / JDBC sinks, Cytoscape viewer.
