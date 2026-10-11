//! Commands the viewer calls through IPC. The viewer never talks to the engine directly:
//! `engine_request` adds the secret token, so other local programs and web pages can't use it.

use std::fs;
use std::path::PathBuf;
use std::time::Duration;

use base64::Engine as _;
use serde::Serialize;
use tauri::{AppHandle, Manager, State};
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_opener::OpenerExt;

use crate::engine::{self, Engine};
use crate::state::{self, PendingOpen};

#[derive(Serialize)]
pub struct EngineResponse {
    status: u16,
    body: String,
}

const ALLOWED_METHODS: [&str; 3] = ["GET", "POST", "DELETE"];

#[tauri::command]
pub async fn engine_request(app: AppHandle, method: String, path: String, body: Option<String>) -> Result<EngineResponse, String> {
    if !ALLOWED_METHODS.contains(&method.as_str()) || !path.starts_with("/api/") {
        return Err("invalid engine request".into());
    }
    tauri::async_runtime::spawn_blocking(move || {
        let engine = app.state::<Engine>();
        let mut attempt = 0;
        loop {
            let (port, token) = engine.endpoint(&app)?;
            match call(port, &token, &method, &path, body.as_deref()) {
                Ok(r) => return Ok(r),
                // engine went away between calls: restart once and retry
                Err(e) if attempt == 0 && matches!(*e, ureq::Error::Transport(_)) => {
                    log::warn!("engine request failed ({e}); restarting engine");
                    attempt += 1;
                    engine.restart(&app)?;
                }
                Err(e) => return Err(format!("engine request failed: {e}")),
            }
        }
    })
    .await
    .map_err(|e| e.to_string())?
}

fn call(port: u16, token: &str, method: &str, path: &str, body: Option<&str>) -> Result<EngineResponse, Box<ureq::Error>> {
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(5))
        .timeout_read(Duration::from_secs(60 * 30))
        .build();
    let req = agent
        .request(method, &format!("http://127.0.0.1:{port}{path}"))
        .set("X-FlowLens-Token", token);
    let result = match body {
        Some(b) => req.set("Content-Type", "application/json").send_string(b),
        None => req.call(),
    };
    match result {
        Ok(resp) => {
            let status = resp.status();
            Ok(EngineResponse { status, body: resp.into_string().unwrap_or_default() })
        }
        Err(ureq::Error::Status(status, resp)) => Ok(EngineResponse { status, body: resp.into_string().unwrap_or_default() }),
        Err(e) => Err(Box::new(e)),
    }
}

#[tauri::command]
pub async fn restart_engine(app: AppHandle) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || app.state::<Engine>().restart(&app).map(|_| ()))
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn pick_folder(app: AppHandle) -> Result<Option<String>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title("Open a Java project folder")
            .blocking_pick_folder()
            .and_then(|p| p.into_path().ok())
            .map(|p| p.to_string_lossy().into_owned())
    })
    .await
    .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn save_file(
    app: AppHandle,
    default_name: String,
    filter_name: String,
    extensions: Vec<String>,
    contents: String,
    is_base64: bool,
) -> Result<Option<String>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let exts: Vec<&str> = extensions.iter().map(String::as_str).collect();
        let mut dialog = app.dialog().file().set_file_name(&default_name);
        if !exts.is_empty() {
            dialog = dialog.add_filter(&filter_name, &exts);
        }
        let Some(target) = dialog.blocking_save_file().and_then(|p| p.into_path().ok()) else {
            return Ok(None);
        };
        let bytes = if is_base64 {
            base64::engine::general_purpose::STANDARD.decode(contents.as_bytes()).map_err(|e| e.to_string())?
        } else {
            contents.into_bytes()
        };
        fs::write(&target, bytes).map_err(|e| format!("Could not save {}: {e}", target.display()))?;
        Ok(Some(target.to_string_lossy().into_owned()))
    })
    .await
    .map_err(|e| e.to_string())?
}

#[derive(Serialize)]
pub struct OpenedFile {
    name: String,
    path: String,
    text: String,
}

#[tauri::command]
pub async fn open_snapshot(app: AppHandle) -> Result<Option<OpenedFile>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let Some(path) = app
            .dialog()
            .file()
            .set_title("Open a FlowLens snapshot")
            .add_filter("FlowLens snapshot", &["flowlens", "json"])
            .blocking_pick_file()
            .and_then(|p| p.into_path().ok())
        else {
            return Ok(None);
        };
        let text = read_limited(&path)?;
        Ok(Some(OpenedFile {
            name: path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default(),
            path: path.to_string_lossy().into_owned(),
            text,
        }))
    })
    .await
    .map_err(|e| e.to_string())?
}

fn read_limited(path: &PathBuf) -> Result<String, String> {
    const MAX: u64 = 512 * 1024 * 1024;
    let meta = fs::metadata(path).map_err(|e| e.to_string())?;
    if meta.len() > MAX {
        return Err("File is too large".into());
    }
    fs::read_to_string(path).map_err(|e| format!("Could not read {}: {e}", path.display()))
}

/// Read a snapshot the user dropped on the window / opened via the OS. Only .flowlens/.json.
#[tauri::command]
pub fn read_text_file(path: String) -> Result<String, String> {
    let p = PathBuf::from(&path);
    let ext = p.extension().and_then(|e| e.to_str()).unwrap_or("").to_ascii_lowercase();
    if ext != "flowlens" && ext != "json" {
        return Err("Only .flowlens and .json files can be opened".into());
    }
    read_limited(&p)
}

#[tauri::command]
pub fn path_kind(path: String) -> &'static str {
    match fs::metadata(&path) {
        Ok(m) if m.is_dir() => "dir",
        Ok(_) => "file",
        Err(_) => "missing",
    }
}

/// Open IDE deep links and web pages with the OS. Other schemes are refused.
#[tauri::command]
pub fn open_external(app: AppHandle, url: String) -> Result<(), String> {
    let parsed = url::Url::parse(&url).map_err(|e| e.to_string())?;
    match parsed.scheme() {
        "https" | "http" | "vscode" | "vscode-insiders" | "idea" | "cursor" | "jetbrains" => {}
        other => return Err(format!("refusing to open {other}: links")),
    }
    app.opener().open_url(url, None::<&str>).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn open_logs(app: AppHandle) -> Result<(), String> {
    let dir = engine::log_dir(&app);
    let _ = fs::create_dir_all(&dir);
    app.opener().open_path(dir.to_string_lossy(), None::<&str>).map_err(|e| e.to_string())
}

#[derive(Serialize)]
pub struct McpInfo {
    command: String,
    args: Vec<String>,
}

/// How an MCP client can launch FlowLens' MCP server with the bundled runtime.
#[tauri::command]
pub fn mcp_info(app: AppHandle) -> Result<McpInfo, String> {
    let files = engine::locate(&app)?;
    Ok(McpInfo {
        command: files.java.to_string_lossy().into_owned(),
        args: vec!["-jar".into(), files.jar.to_string_lossy().into_owned(), "--mcp".into()],
    })
}

#[tauri::command]
pub fn engine_error(engine: State<'_, Engine>) -> Option<String> {
    engine.last_error()
}

#[tauri::command]
pub fn load_state(app: AppHandle) -> Option<String> {
    state::load(&app)
}

#[tauri::command]
pub fn save_state(app: AppHandle, json: String) -> Result<(), String> {
    state::save(&app, &json)
}

#[tauri::command]
pub fn set_recent(app: AppHandle, paths: Vec<String>) -> Result<(), String> {
    let menu = crate::menu::build(&app, &paths).map_err(|e| e.to_string())?;
    app.set_menu(menu).map(|_| ()).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn take_pending_open(pending: State<'_, PendingOpen>) -> Vec<String> {
    std::mem::take(&mut *pending.0.lock().unwrap())
}

#[tauri::command]
pub fn set_title(app: AppHandle, title: String) -> Result<(), String> {
    if let Some(w) = app.get_webview_window("main") {
        w.set_title(&title).map_err(|e| e.to_string())?;
    }
    Ok(())
}
