# Performance baseline (F5)

Measured with `java -jar flowlens-analyzer.jar <project> out.json` (JDK 21, 4 vCPU Linux container),
including JVM start-up. Parsing runs in parallel; re-indexing in the app/server reuses parsed
files whose content did not change (watch mode, Re-index).

| Project | Java files | Entry points | Reach a DB | DB sinks | Time |
|---|---:|---:|---:|---:|---:|
| spring-projects/spring-petclinic | 50 | 17 | 9 (the rest are form views) | 3 | 0.5 s |
| jhipster/jhipster-sample-app | 136 | 39 | 37 | 16 | 0.9 s |
| macrozheng/mall (7 modules, MyBatis XML) | 524 | 248 | 232 | 141 | 3.8 s |

No files were skipped in any of them. To reproduce:

```bash
git clone --depth 1 https://github.com/macrozheng/mall
java -jar analyzer/target/flowlens-analyzer.jar mall mall.json
```

Memory: the engine uses the JVM default heap (¼ of RAM). For very large monorepos raise it in the
desktop app (*Options → Engine memory*) or with `java -Xmx8g -jar …`.
