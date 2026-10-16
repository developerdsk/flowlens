//! The native application menu. Item ids are forwarded to the viewer as "menu" events, which
//! runs the same actions as the toolbar (see viewer/js/app.js `runAction`).

use tauri::menu::{Menu, MenuBuilder, MenuItemBuilder, PredefinedMenuItem, SubmenuBuilder};
use tauri::{AppHandle, Runtime};

pub fn build<R: Runtime>(app: &AppHandle<R>, recent: &[String]) -> tauri::Result<Menu<R>> {
    let item = |id: &str, text: &str, accel: Option<&str>| {
        let b = MenuItemBuilder::with_id(id, text);
        match accel {
            Some(a) => b.accelerator(a).build(app),
            None => b.build(app),
        }
    };

    let mut recent_menu = SubmenuBuilder::new(app, "Open Recent");
    if recent.is_empty() {
        recent_menu = recent_menu.item(&MenuItemBuilder::with_id("recent-none", "No recent projects").enabled(false).build(app)?);
    } else {
        for path in recent.iter().take(10) {
            recent_menu = recent_menu.item(&MenuItemBuilder::with_id(format!("recent:{path}"), path).build(app)?);
        }
    }
    let recent_menu = recent_menu.build()?;

    let export = SubmenuBuilder::new(app, "Export")
        .item(&item("export-png", "PNG Image…", None)?)
        .item(&item("export-svg", "SVG Image…", None)?)
        .item(&item("export-mermaid", "Mermaid Diagram…", None)?)
        .build()?;

    let mut file = SubmenuBuilder::new(app, "File")
        .item(&item("open", "Open Project…", Some("CmdOrCtrl+O"))?)
        .item(&recent_menu)
        .item(&item("open-snapshot", "Open Snapshot…", Some("CmdOrCtrl+Shift+O"))?)
        .separator()
        .item(&item("reindex", "Re-index", Some("CmdOrCtrl+R"))?)
        .item(&item("compare-ref", "Compare with Git Ref…", None)?)
        .item(&item("compare-snapshot", "Compare with Snapshot…", None)?)
        .separator()
        .item(&item("save-snapshot", "Save Snapshot…", Some("CmdOrCtrl+S"))?)
        .item(&export);
    if !cfg!(target_os = "macos") {
        file = file.separator().item(&PredefinedMenuItem::quit(app, Some("Quit"))?);
    }
    let file = file.build()?;

    let edit = SubmenuBuilder::new(app, "Edit")
        .item(&PredefinedMenuItem::undo(app, None)?)
        .item(&PredefinedMenuItem::redo(app, None)?)
        .separator()
        .item(&PredefinedMenuItem::cut(app, None)?)
        .item(&PredefinedMenuItem::copy(app, None)?)
        .item(&PredefinedMenuItem::paste(app, None)?)
        .item(&PredefinedMenuItem::select_all(app, None)?)
        .separator()
        .item(&item("find", "Find…", Some("CmdOrCtrl+F"))?)
        .build()?;

    let view = SubmenuBuilder::new(app, "View")
        .item(&item("show-all", "All Entry Points", None)?)
        .item(&item("fit", "Fit to Window", Some("CmdOrCtrl+0"))?)
        .item(&item("tidy", "Tidy Layout", None)?)
        .item(&item("toggle-dir", "Toggle Layout Direction", None)?)
        .item(&item("toggle-branches", "Show/Hide Branches", Some("CmdOrCtrl+B"))?)
        .separator()
        .item(&item("toggle-theme", "Switch Theme (Auto / Light / Dark)", None)?)
        .item(&PredefinedMenuItem::fullscreen(app, None)?)
        .build()?;

    let mut help = SubmenuBuilder::new(app, "Help")
        .item(&item("help-mcp", "Connect AI Agents (MCP)…", None)?)
        .item(&item("help-shortcuts", "Keyboard Shortcuts", None)?)
        .item(&item("open-logs", "Open Logs Folder", None)?);
    if cfg!(feature = "updater") {
        help = help.separator().item(&item("check-updates", "Check for Updates…", None)?);
    }
    help = help.separator().item(&item("help-about", "About FlowLens", None)?);
    let help = help.build()?;

    let mut menu = MenuBuilder::new(app);
    if cfg!(target_os = "macos") {
        let app_menu = SubmenuBuilder::new(app, "FlowLens")
            .item(&item("about", "About FlowLens", None)?)
            .separator()
            .item(&PredefinedMenuItem::services(app, None)?)
            .separator()
            .item(&PredefinedMenuItem::hide(app, None)?)
            .item(&PredefinedMenuItem::hide_others(app, None)?)
            .item(&PredefinedMenuItem::show_all(app, None)?)
            .separator()
            .item(&PredefinedMenuItem::quit(app, None)?)
            .build()?;
        menu = menu.item(&app_menu);
    }
    let window = SubmenuBuilder::new(app, "Window")
        .item(&PredefinedMenuItem::minimize(app, None)?)
        .item(&PredefinedMenuItem::maximize(app, None)?)
        .build()?;
    menu.item(&file).item(&edit).item(&view).item(&window).item(&help).build()
}
