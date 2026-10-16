//! Owns the FlowLens engine: a Java process (bundled minimal JRE + analyzer jar) serving the
//! analysis API on 127.0.0.1 with a random port and a per-launch secret token.
//!
//! - The token is passed in the environment, never on the command line.
//! - The engine's stdin is a pipe held by this process: if the app dies for any reason the
//!   pipe closes and the engine exits (`--exit-on-stdin-close`), so no orphaned `java`.
//! - If the engine crashes it is restarted (a few times), transparently to the UI.

use std::fs::{self, File, OpenOptions};
use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use tauri::{AppHandle, Emitter, Manager};

const READY_TIMEOUT: Duration = Duration::from_secs(60);
const MAX_RESTARTS: usize = 3;
const MAX_LOG_BYTES: u64 = 5 * 1024 * 1024;

pub struct Running {
    child: Arc<Mutex<Child>>,
    _stdin: ChildStdin,
    pub port: u16,
    pub token: String,
}

#[derive(Default)]
pub struct Engine {
    running: Mutex<Option<Running>>,
    quitting: AtomicBool,
    /** Bumped for every start and every deliberate stop; a watcher only reacts to its own generation. */
    generation: AtomicU64,
    restarts: Mutex<Vec<Instant>>,
    last_error: Mutex<Option<String>>,
}

/// Where the Java runtime and the engine jar are.
#[derive(Clone, serde::Serialize)]
pub struct EngineFiles {
    pub java: PathBuf,
    pub jar: PathBuf,
    pub bundled: bool,
}

impl Engine {
    /// Port and token of the running engine, starting it if needed.
    pub fn endpoint(&self, app: &AppHandle) -> Result<(u16, String), String> {
        {
            let guard = self.running.lock().unwrap();
            if let Some(r) = guard.as_ref() {
                if is_alive(&r.child) {
                    return Ok((r.port, r.token.clone()));
                }
            }
        }
        self.start(app)
    }

    pub fn start(&self, app: &AppHandle) -> Result<(u16, String), String> {
        let mut guard = self.running.lock().unwrap();
        if let Some(r) = guard.as_ref() {
            if is_alive(&r.child) {
                return Ok((r.port, r.token.clone()));
            }
        }
        if let Some(old) = guard.take() {
            kill(&old.child);
        }
        let files = locate(app)?;
        let token = random_token();
        let log_path = log_file(app);
        let mut cmd = Command::new(&files.java);
        cmd.arg("-Dfile.encoding=UTF-8")
            .arg("-Dstdout.encoding=UTF-8")
            .arg("-Dstderr.encoding=UTF-8")
            .arg("-Djava.awt.headless=true")
            .arg("-XX:+UseSerialGC")
            .arg("-Xss4m");
        if let Some(mb) = configured_heap_mb(app) {
            cmd.arg(format!("-Xmx{mb}m"));
        }
        cmd.arg("-jar")
            .arg(&files.jar)
            .args(["--serve", "--port", "0", "--exit-on-stdin-close"])
            .env("FLOWLENS_TOKEN", &token)
            .env_remove("JAVA_TOOL_OPTIONS")
            .env_remove("_JAVA_OPTIONS")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }
        ensure_executable(&files.java);
        log::info!("starting engine: {} -jar {}", files.java.display(), files.jar.display());
        let mut child = cmd
            .spawn()
            .map_err(|e| self.fail(format!("Could not start the FlowLens engine ({}): {e}", files.java.display())))?;

        let stdin = child.stdin.take().ok_or("engine stdin unavailable")?;
        let stdout = child.stdout.take().ok_or("engine stdout unavailable")?;
        let stderr = child.stderr.take().ok_or("engine stderr unavailable")?;
        let log = Arc::new(Mutex::new(open_log(&log_path)));

        let (tx, rx) = mpsc::channel::<u16>();
        let out_log = log.clone();
        thread::spawn(move || {
            let mut sent = false;
            for_each_line(stdout, |line| {
                if !sent {
                    if let Some(port) = parse_ready(line) {
                        let _ = tx.send(port);
                        sent = true;
                    }
                }
                write_log(&out_log, line);
            });
        });
        let err_log = log.clone();
        thread::spawn(move || for_each_line(stderr, |line| write_log(&err_log, line)));

        let child = Arc::new(Mutex::new(child));
        let port = match rx.recv_timeout(READY_TIMEOUT) {
            Ok(p) => p,
            Err(_) => {
                kill(&child);
                return Err(self.fail(format!(
                    "The FlowLens engine did not start. See the log: {}",
                    log_path.display()
                )));
            }
        };
        log::info!("engine ready on 127.0.0.1:{port}");
        *self.last_error.lock().unwrap() = None;
        *guard = Some(Running { child: child.clone(), _stdin: stdin, port, token: token.clone() });
        drop(guard);
        let generation = self.generation.fetch_add(1, Ordering::SeqCst) + 1;
        self.watch(app.clone(), child, generation);
        Ok((port, token))
    }

    /// Restart the engine if it exits while the app is running.
    fn watch(&self, app: AppHandle, child: Arc<Mutex<Child>>, generation: u64) {
        thread::spawn(move || {
            let exit = loop {
                thread::sleep(Duration::from_millis(500));
                match child.lock().unwrap().try_wait() {
                    Ok(None) => continue,
                    Ok(Some(status)) => break status.to_string(),
                    Err(e) => break e.to_string(),
                }
            };
            let engine = app.state::<Engine>();
            // a deliberate stop/restart bumped the generation: not a crash
            if engine.quitting.load(Ordering::SeqCst) || engine.generation.load(Ordering::SeqCst) != generation {
                return;
            }
            log::warn!("engine exited: {exit}");
            let allowed = {
                let mut r = engine.restarts.lock().unwrap();
                r.retain(|t| t.elapsed() < Duration::from_secs(120));
                r.push(Instant::now());
                r.len() <= MAX_RESTARTS
            };
            let message = if !allowed {
                "The analysis engine keeps stopping. See Help → Open Logs Folder.".to_string()
            } else {
                let _ = app.emit("engine", serde_json::json!({ "message": "The analysis engine stopped unexpectedly; restarting it…" }));
                match engine.start(&app) {
                    Ok(_) => "Analysis engine restarted.".to_string(),
                    Err(e) => e,
                }
            };
            let _ = app.emit("engine", serde_json::json!({ "message": message }));
        });
    }

    pub fn restart(&self, app: &AppHandle) -> Result<(u16, String), String> {
        if let Some(old) = self.running.lock().unwrap().take() {
            self.generation.fetch_add(1, Ordering::SeqCst); // the old watcher will ignore this exit
            kill(&old.child);
        }
        self.start(app)
    }

    pub fn shutdown(&self) {
        self.quitting.store(true, Ordering::SeqCst);
        if let Some(r) = self.running.lock().unwrap().take() {
            kill(&r.child);
        }
    }

    /// Whether the current engine process is alive (without starting one).
    pub fn is_running(&self) -> bool {
        self.running.lock().unwrap().as_ref().map(|r| is_alive(&r.child)).unwrap_or(false)
    }

    pub fn last_error(&self) -> Option<String> {
        self.last_error.lock().unwrap().clone()
    }

    fn fail(&self, msg: String) -> String {
        log::error!("{msg}");
        *self.last_error.lock().unwrap() = Some(msg.clone());
        msg
    }
}

/// Calls `f` for every line, decoding lossily: one non-UTF-8 line must not end the log.
fn for_each_line(stream: impl Read, mut f: impl FnMut(&str)) {
    let mut reader = BufReader::new(stream);
    let mut buf = Vec::new();
    loop {
        buf.clear();
        match reader.read_until(b'\n', &mut buf) {
            Ok(0) | Err(_) => return,
            Ok(_) => f(String::from_utf8_lossy(&buf).trim_end()),
        }
    }
}

fn is_alive(child: &Arc<Mutex<Child>>) -> bool {
    matches!(child.lock().unwrap().try_wait(), Ok(None))
}

fn kill(child: &Arc<Mutex<Child>>) {
    let mut c = child.lock().unwrap();
    let _ = c.kill();
    let _ = c.wait();
}

fn parse_ready(line: &str) -> Option<u16> {
    let rest = line.strip_prefix("FLOWLENS_READY")?;
    rest.split_whitespace()
        .find_map(|kv| kv.strip_prefix("port="))
        .and_then(|p| p.parse().ok())
}

fn random_token() -> String {
    let mut buf = [0u8; 32];
    getrandom::getrandom(&mut buf).expect("OS random source unavailable");
    buf.iter().map(|b| format!("{b:02x}")).collect()
}

/// Bundled runtime first; in development fall back to a system Java and the Maven build output.
pub fn locate(app: &AppHandle) -> Result<EngineFiles, String> {
    let exe = if cfg!(windows) { "java.exe" } else { "java" };
    if let Ok(res) = app.path().resource_dir() {
        let base = res.join("engine");
        let java = base.join("runtime").join("bin").join(exe);
        let jar = base.join("flowlens-analyzer.jar");
        if java.is_file() && jar.is_file() {
            return Ok(EngineFiles { java, jar, bundled: true });
        }
        // a bundle without a runtime: use a system Java with the bundled jar
        if jar.is_file() {
            if let Some(java) = system_java(exe) {
                return Ok(EngineFiles { java, jar, bundled: false });
            }
        }
    }
    let jar = std::env::var_os("FLOWLENS_ENGINE_JAR")
        .map(PathBuf::from)
        .unwrap_or_else(|| Path::new(env!("CARGO_MANIFEST_DIR")).join("../../analyzer/target/flowlens-analyzer.jar"));
    let java = system_java(exe).ok_or_else(|| {
        "No Java runtime found. Build the bundled runtime (desktop/scripts/build-engine.mjs) or install Java 17+.".to_string()
    })?;
    if !jar.is_file() {
        return Err(format!("Engine jar not found at {}. Run: cd analyzer && ./mvnw -DskipTests package", jar.display()));
    }
    Ok(EngineFiles { java, jar, bundled: false })
}

fn system_java(exe: &str) -> Option<PathBuf> {
    if let Some(p) = std::env::var_os("FLOWLENS_JAVA") {
        return Some(PathBuf::from(p));
    }
    if let Some(home) = std::env::var_os("JAVA_HOME") {
        let p = PathBuf::from(home).join("bin").join(exe);
        if p.is_file() {
            return Some(p);
        }
    }
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path).map(|d| d.join(exe)).find(|p| p.is_file())
}

/// Resource copies can lose the executable bit on some platforms/bundlers.
fn ensure_executable(_path: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(meta) = fs::metadata(_path) {
            let mut perm = meta.permissions();
            if perm.mode() & 0o111 == 0 {
                perm.set_mode(perm.mode() | 0o755);
                let _ = fs::set_permissions(_path, perm);
            }
        }
        // the JVM also spawns this helper
        if let Some(lib) = _path.parent().and_then(|b| b.parent()).map(|r| r.join("lib").join("jspawnhelper")) {
            if let Ok(meta) = fs::metadata(&lib) {
                let mut perm = meta.permissions();
                if perm.mode() & 0o111 == 0 {
                    perm.set_mode(perm.mode() | 0o755);
                    let _ = fs::set_permissions(&lib, perm);
                }
            }
        }
    }
}

/// Optional max heap from the user's settings (state.json: "maxHeapMb").
fn configured_heap_mb(app: &AppHandle) -> Option<u64> {
    let path = crate::state::state_path(app).ok()?;
    let mut s = String::new();
    File::open(path).ok()?.read_to_string(&mut s).ok()?;
    let v: serde_json::Value = serde_json::from_str(&s).ok()?;
    v.get("maxHeapMb").and_then(|m| m.as_u64()).filter(|m| *m >= 256)
}

pub fn log_dir(app: &AppHandle) -> PathBuf {
    app.path().app_log_dir().unwrap_or_else(|_| std::env::temp_dir().join("FlowLens"))
}

fn log_file(app: &AppHandle) -> PathBuf {
    let dir = log_dir(app);
    let _ = fs::create_dir_all(&dir);
    let path = dir.join("engine.log");
    if fs::metadata(&path).map(|m| m.len() > MAX_LOG_BYTES).unwrap_or(false) {
        let _ = fs::rename(&path, dir.join("engine.log.1"));
    }
    path
}

fn open_log(path: &Path) -> Option<File> {
    OpenOptions::new().create(true).append(true).open(path).ok()
}

fn write_log(log: &Arc<Mutex<Option<File>>>, line: &str) {
    if let Some(f) = log.lock().unwrap().as_mut() {
        let _ = writeln!(f, "{line}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_ready_line() {
        assert_eq!(parse_ready("FLOWLENS_READY port=51234 version=0.2.0"), Some(51234));
        assert_eq!(parse_ready("FlowLens 0.2.0 running"), None);
    }

    #[test]
    fn invalid_utf8_lines_do_not_stop_the_log() {
        let input: &[u8] = b"first\nJ\xf6rg\nlast\n";
        let mut lines = Vec::new();
        for_each_line(input, |l| lines.push(l.to_string()));
        assert_eq!(lines, vec!["first", "J\u{fffd}rg", "last"]);
    }

    #[test]
    fn tokens_are_random_hex() {
        let a = random_token();
        assert_eq!(a.len(), 64);
        assert_ne!(a, random_token());
        assert!(a.chars().all(|c| c.is_ascii_hexdigit()));
    }
}
