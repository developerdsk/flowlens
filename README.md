# FlowLens

**See how a request actually flows through your Java code — from the endpoint down to the database write.**

FlowLens statically analyzes a Java / Spring source tree and, for every entry point (HTTP endpoint,
message listener, scheduled job), builds a **data-flow graph**: the call chain through services and
repositories (including through interfaces), the branch conditions and throws along the way, and the
concrete sinks it reaches — `INSERT` / `UPDATE` / `DELETE` / `SELECT` on which table, which HTTP
service, which Kafka topic. It also traces which request field ends up in which column.

It reads **source only** — no build, no running app, no instrumentation. Nothing leaves your machine.

## Install

Download the installer for your computer from the
[latest release](https://github.com/developerdsk/flowlens/releases):

| Computer | File |
|---|---|
| Windows 10/11 | `FlowLens_<version>_x64-setup.exe` (or `.msi`) |
| Mac with Apple Silicon (M1–M4) | `FlowLens_<version>_aarch64.dmg` |
| Mac with Intel | `FlowLens_<version>_x64.dmg` |
| Linux | `.AppImage` or `.deb` |

No Java needed — the app bundles its own runtime. Then **Open project…**, pick a folder, done.

> Until the builds are code-signed: on macOS right-click the app → **Open** the first time; on
> Windows choose **More info → Run anyway** in the SmartScreen prompt.

## What you get

- **Endpoint explorer** — every entry point grouped by class, like a Postman collection; search.
- **Flow graph** — controller → services → repositories → tables / HTTP / topics, with `if`/`switch`
  branches and throws as nodes; dashed edges where FlowLens is unsure.
- **Tables view** — pick a table and see every endpoint that reads or writes it.
- **Detail panel** — field lineage (`request.email → account.email_address`), SQL, conditions,
  mutations, callers/callees, highlighted source, **Open in VS Code / IntelliJ / Cursor**.
- **Compare** — diff the flow against a git branch or a saved snapshot; affected endpoints light up.
- **Watch mode**, export to **PNG / SVG / Mermaid**, shareable **.flowlens** snapshots.
- **MCP server** — let AI coding agents ask "what writes to the `account` table?"
  (Help → *Connect AI Agents*).

### Supported frameworks

| | |
|---|---|
| Entry points | Spring MVC & WebFlux (annotated and router functions, mappings inherited from OpenAPI-generated interfaces), JAX-RS / Quarkus / Jakarta EE, Micronaut, Kafka / RabbitMQ / JMS / SQS / Spring Cloud Stream listeners, `@Scheduled`, Quartz |
| Data access | Spring Data (JPA, JDBC, R2DBC, MongoDB, …) incl. `@Query` and derived queries, JPA `EntityManager` / Hibernate `Session`, `JdbcTemplate`, `JdbcClient`, MyBatis (XML + annotations, MyBatis-Plus), jOOQ, `MongoTemplate`, Redis |
| Outbound | OpenFeign, Spring `@HttpExchange`, MicroProfile REST client, `RestTemplate`, `WebClient`, `RestClient`, Kafka / RabbitMQ / JMS / Stream / SQS / SNS producers, Spring application events |
| Java | up to Java 21 syntax; multi-module Maven/Gradle; non-UTF-8 sources |

## How it works

```
  Java sources ──► engine (analyzer/, Java)            ──► graph.json ──► viewer (viewer/, JS)
                   JavaParser + FlowLens type resolver                     Cytoscape UI
                   entry points → call walk → sinks                        in the desktop app
                   lineage, diff, MCP, local API                           or a browser
```

The desktop app (`desktop/`, Tauri 2) bundles the engine with a minimal Java runtime and talks to it
over a token-protected loopback connection.

## Other ways to run it

Needs Java 17+.

```bash
./run.sh            # macOS / Linux: builds once, then opens http://localhost:7070
run.bat             # Windows
```

```bash
cd analyzer && ./mvnw -DskipTests package
java -jar target/flowlens-analyzer.jar <project> graph.json          # one-shot JSON
java -jar target/flowlens-analyzer.jar <project> --ref main          # analyze a git ref
java -jar target/flowlens-analyzer.jar --diff base.json head.json     # what changed
java -jar target/flowlens-analyzer.jar --mcp <project>                # MCP server on stdio
claude mcp add flowlens -- java -jar /path/flowlens-analyzer.jar --mcp /path/to/project
```

### Privacy & safety

The engine binds to **127.0.0.1 only**, rejects requests whose `Host`/`Origin` isn't itself (no DNS
rebinding or cross-site use), accepts only JSON posts, and in the desktop app requires a per-launch
secret. It never executes or loads the analyzed code. No telemetry.

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md), [desktop/README.md](desktop/README.md) (building and releasing
installers), [docs/QA-CHECKLIST.md](docs/QA-CHECKLIST.md) and [docs/PERFORMANCE.md](docs/PERFORMANCE.md).

## Roadmap

See [ROADMAP.md](ROADMAP.md) and [TODO.md](TODO.md).

## License

Apache-2.0. Contributions welcome.
