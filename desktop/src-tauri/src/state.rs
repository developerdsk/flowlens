//! Small persisted app state: the viewer's preferences and recent projects (state.json in the
//! OS config folder), plus files the OS asked us to open before the UI was ready.

use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

use tauri::{AppHandle, Manager};

pub fn state_path(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app.path().app_config_dir().map_err(|e| e.to_string())?;
    fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    Ok(dir.join("state.json"))
}

pub fn load(app: &AppHandle) -> Option<String> {
    fs::read_to_string(state_path(app).ok()?).ok()
}

pub fn save(app: &AppHandle, json: &str) -> Result<(), String> {
    // validate before writing so a bad call can't corrupt the file
    serde_json::from_str::<serde_json::Value>(json).map_err(|e| e.to_string())?;
    let path = state_path(app)?;
    let tmp = path.with_extension("json.tmp");
    fs::write(&tmp, json).map_err(|e| e.to_string())?;
    fs::rename(&tmp, &path).map_err(|e| e.to_string())
}

pub fn recent_projects(app: &AppHandle) -> Vec<String> {
    load(app)
        .and_then(|s| serde_json::from_str::<serde_json::Value>(&s).ok())
        .and_then(|v| v.get("recent").cloned())
        .and_then(|r| serde_json::from_value::<Vec<String>>(r).ok())
        .unwrap_or_default()
}

/// Paths from the command line / file association / dock, waiting for the UI to ask.
#[derive(Default)]
pub struct PendingOpen(pub Mutex<Vec<String>>);
