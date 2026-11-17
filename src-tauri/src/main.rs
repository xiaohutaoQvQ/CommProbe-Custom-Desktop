#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::{io::{BufRead, BufReader, Write}, path::PathBuf, process::{Child, Command, Stdio}, sync::Mutex, thread, time::Duration};
use tauri::{Emitter, Manager};

#[derive(Default)]
struct Backend(Mutex<Option<Child>>);

fn backend_paths(app: &tauri::AppHandle) -> Result<(PathBuf, PathBuf), String> {
    if cfg!(debug_assertions) {
        let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).parent().ok_or("找不到项目目录")?.to_path_buf();
        let java = std::env::var_os("JAVA_HOME").map(|p| PathBuf::from(p).join("bin/java")).unwrap_or_else(|| PathBuf::from("java"));
        Ok((java, root.join("desktop-backend/target/commprobe-backend.jar")))
    } else {
        let root = app.path().resource_dir().map_err(|e| e.to_string())?;
        Ok((root.join("java-runtime/bin/java"), root.join("backend/commprobe-backend.jar")))
    }
}

#[tauri::command]
fn backend_start(app: tauri::AppHandle, state: tauri::State<'_, Backend>) -> Result<(), String> {
    let mut guard = state.0.lock().map_err(|e| e.to_string())?;
    if guard.is_some() { return Ok(()); }
    let (java, jar) = backend_paths(&app)?;
    let mut child = Command::new(java).arg("-jar").arg(jar).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn().map_err(|e| format!("无法启动 Java 内核：{e}"))?;
    let stdout = child.stdout.take().ok_or("无法读取内核输出")?;
    let stderr = child.stderr.take().ok_or("无法读取内核错误输出")?;
    let app_out = app.clone();
    thread::spawn(move || {
        for line in BufReader::new(stdout).lines() {
            match line {
                Ok(line) => match serde_json::from_str::<serde_json::Value>(&line) {
                    Ok(message) => { let _ = app_out.emit("backend-message", message); },
                    Err(_) => { let _ = app_out.emit("backend-error", "内核返回了无法解析的数据"); },
                },
                Err(e) => { let _ = app_out.emit("backend-error", format!("读取内核输出失败：{e}")); break; }
            }
        }
    });
    thread::spawn(move || { for line in BufReader::new(stderr).lines().map_while(Result::ok) { eprintln!("{line}"); } });
    *guard = Some(child);
    drop(guard);
    thread::spawn(move || loop {
        thread::sleep(Duration::from_millis(300));
        let state = app.state::<Backend>();
        let Ok(mut guard) = state.0.lock() else { break };
        let Some(child) = guard.as_mut() else { break };
        match child.try_wait() {
            Ok(Some(status)) => { *guard = None; let _ = app.emit("backend-error", format!("Java 内核已退出：{status}")); break; },
            Err(e) => { *guard = None; let _ = app.emit("backend-error", format!("检查内核状态失败：{e}")); break; },
            Ok(None) => {}
        }
    });
    Ok(())
}

#[tauri::command]
fn backend_send(message: serde_json::Value, state: tauri::State<'_, Backend>) -> Result<(), String> {
    let mut guard = state.0.lock().map_err(|e| e.to_string())?;
    let child = guard.as_mut().ok_or("Java 内核未运行")?;
    let stdin = child.stdin.as_mut().ok_or("Java 内核输入已关闭")?;
    writeln!(stdin, "{}", message).and_then(|_| stdin.flush()).map_err(|e| format!("发送给内核失败：{e}"))
}

fn stop_backend(app: &tauri::AppHandle) {
    let state = app.state::<Backend>();
    if let Ok(mut guard) = state.0.lock() {
        if let Some(mut child) = guard.take() {
            drop(child.stdin.take());
            for _ in 0..20 {
                if matches!(child.try_wait(), Ok(Some(_))) { return; }
                thread::sleep(Duration::from_millis(100));
            }
            let _ = child.kill(); let _ = child.wait();
        }
    };
}

fn main() {
    let app = tauri::Builder::default().manage(Backend::default()).invoke_handler(tauri::generate_handler![backend_start, backend_send]).build(tauri::generate_context!()).expect("无法启动桌面应用");
    app.run(|handle, event| { if let tauri::RunEvent::Exit = event { stop_backend(handle); } });
}
