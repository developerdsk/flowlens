# Spike: GraalVM native-image engine (D15)

**Goal:** ship the engine as a single native binary instead of jar + bundled JRE, for a smaller
download (~30–40 MB instead of ~48 MB unpacked) and a faster cold start (~50 ms vs ~400 ms).

**Status: not done.** A Maven profile exists, but the native image has not been built or tested,
because the build environment used so far had no GraalVM. The desktop app keeps using the jlink
runtime, which works on every platform today.

## Try it

```bash
# GraalVM for JDK 21+, with native-image on PATH
cd analyzer
./mvnw -Pnative -DskipTests package       # -> target/flowlens-engine
./target/flowlens-engine --version
```

## Expected work before it can replace the JRE
1. **Reflection config for Jackson.** Graph/Node/Edge are serialized by field reflection. Generate
   config with the tracing agent while running the test suite:
   `java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image -jar ...`
2. **JavaParser** uses reflection in its metamodel; the agent run above should cover it.
3. **`jdk.httpserver`** works in native-image; verify `com.sun.net.httpserver` with the server tests.
4. **Charsets**: include `windows-1252` (`-H:+AddAllCharsets`).
5. **One binary per OS/arch**, built on each runner (native-image cannot cross-compile).
6. In the desktop shell, `engine::locate` would look for `engine/flowlens-engine[.exe]` before the JRE.

Decision point: adopt it only if startup or size becomes a user complaint. The jlink runtime is the
low-risk default.
