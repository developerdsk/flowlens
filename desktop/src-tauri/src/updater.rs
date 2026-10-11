//! Auto-update (feature "updater"): checks GitHub Releases for a signed update manifest.

use tauri::AppHandle;
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};
use tauri_plugin_updater::UpdaterExt;

/// Check for an update; `interactive` also reports "up to date" and errors.
pub fn check(app: AppHandle, interactive: bool) {
    tauri::async_runtime::spawn(async move {
        let result = async {
            let updater = app.updater().map_err(|e| e.to_string())?;
            updater.check().await.map_err(|e| e.to_string())
        }
        .await;
        match result {
            Ok(Some(update)) => {
                let msg = format!(
                    "FlowLens {} is available (you have {}).\n\n{}\n\nDownload and install it now?",
                    update.version,
                    update.current_version,
                    update.body.clone().unwrap_or_default()
                );
                let yes = app
                    .dialog()
                    .message(msg)
                    .title("Update available")
                    .buttons(MessageDialogButtons::OkCancelCustom("Install and restart".into(), "Later".into()))
                    .blocking_show();
                if yes {
                    match update.download_and_install(|_, _| {}, || {}).await {
                        Ok(()) => app.restart(),
                        Err(e) => {
                            app.dialog().message(format!("The update failed: {e}")).kind(MessageDialogKind::Error).blocking_show();
                        }
                    }
                }
            }
            Ok(None) if interactive => {
                app.dialog().message("You're running the latest version of FlowLens.").title("No updates").blocking_show();
            }
            Err(e) if interactive => {
                app.dialog().message(format!("Could not check for updates: {e}")).kind(MessageDialogKind::Warning).blocking_show();
            }
            _ => {}
        }
    });
}
