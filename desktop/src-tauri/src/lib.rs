//! FlowLens desktop shell (Tauri 2). See desktop/README.md for the architecture.

mod commands;
mod engine;
mod menu;
mod state;
#[cfg(feature = "updater")]
mod updater;

use std::path::Path;

use tauri::{Emitter, Manager};
use tauri_plugin_log::{RotationStrategy, Target, TargetKind};

use crate::engine::Engine;
use crate::state::PendingOpen;

/// Folders and .flowlens files among command-line arguments.
fn openable(args: impl IntoIterator<Item = String>) -> Vec<String> {
    args.into_iter()
        .filter(|a| !a.starts_with('-'))
        .filter(|a| {
            let p = Path::new(a);
            p.is_dir() || p.extension().map(|e| e == "flowlens").unwrap_or(false) && p.is_file()
        })
        .collect()
}

fn deliver(app: &tauri::AppHandle, paths: Vec<String>) {
    if paths.is_empty() {
        return;
    }
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
    app.state::<PendingOpen>().0.lock().unwrap().extend(paths.clone());
    // if the UI is up it handles the event and drains the queue
    let _ = app.emit("open-paths", paths);
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let builder = tauri::Builder::default()
        // must be first: a second launch (e.g. double-clicking a .flowlens file) focuses this one
        .plugin(tauri_plugin_single_instance::init(|app, argv, _cwd| {
            let paths = openable(argv.into_iter().skip(1));
            deliver(app, paths);
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.set_focus();
            }
        }))
        .plugin(
            tauri_plugin_log::Builder::new()
                .targets([Target::new(TargetKind::LogDir { file_name: Some("flowlens".into()) }), Target::new(TargetKind::Stdout)])
                .rotation_strategy(RotationStrategy::KeepOne)
                .max_file_size(5 * 1024 * 1024)
                .level(log::LevelFilter::Info)
                .build(),
        )
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_window_state::Builder::default().build());

    #[cfg(feature = "updater")]
    let builder = builder.plugin(tauri_plugin_updater::Builder::new().build());

    let app = builder
        .manage(Engine::default())
        .manage(PendingOpen::default())
        .setup(|app| {
            let handle = app.handle().clone();
            let recent = state::recent_projects(&handle);
            app.set_menu(menu::build(&handle, &recent)?)?;
            app.on_menu_event(move |app, event| {
                let id = event.id().0.as_str();
                match id {
                    "about" => { let _ = app.emit("menu", "help-about"); }
                    #[cfg(feature = "updater")]
                    "check-updates" => updater::check(app.clone(), true),
                    _ => { let _ = app.emit("menu", id); }
                }
            });

            app.state::<PendingOpen>().0.lock().unwrap().extend(openable(std::env::args().skip(1)));

            // start the engine in the background so the first index is fast
            let h = handle.clone();
            std::thread::spawn(move || {
                if let Err(e) = h.state::<Engine>().endpoint(&h) {
                    let _ = h.emit("engine", serde_json::json!({ "message": e }));
                }
            });

            #[cfg(feature = "updater")]
            updater::check(handle.clone(), false);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::engine_request,
            commands::restart_engine,
            commands::engine_error,
            commands::pick_folder,
            commands::save_file,
            commands::open_snapshot,
            commands::read_text_file,
            commands::path_kind,
            commands::open_external,
            commands::open_logs,
            commands::mcp_info,
            commands::load_state,
            commands::save_state,
            commands::set_recent,
            commands::take_pending_open,
            commands::set_title,
        ])
        .build(tauri::generate_context!())
        .expect("error while building FlowLens");

    app.run(|handle, event| match event {
        tauri::RunEvent::Exit => handle.state::<Engine>().shutdown(),
        #[cfg(target_os = "macos")]
        tauri::RunEvent::Opened { urls } => {
            let paths = urls.into_iter().filter_map(|u| u.to_file_path().ok()).map(|p| p.to_string_lossy().into_owned()).collect();
            deliver(handle, paths);
        }
        _ => {}
    });
}
