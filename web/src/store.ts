import { reactive } from "vue";

import type {
  ClientMessage,
  ConnectionState,
  PortInfo,
  ServerMessage,
  TransportKind,
} from "./shared/protocol";

export interface LogEntry {
  ts: number;
  id: string;
  kind: TransportKind;
  dir: "in" | "out";
  hex: string;
  peer?: string;
}

export interface ConnectionInfo {
  id: string;
  kind: TransportKind;
  label: string;
  state: ConnectionState;
  detail?: string;
}

const MAX_LOGS = 2000;

export const state = reactive({
  wsReady: false,
  serverVersion: "",
  logs: [] as LogEntry[],
  connections: new Map<string, ConnectionInfo>(),
  serialPorts: [] as PortInfo[],
  lastError: "",
});

let started = false;

/** 注册监听后再启动内核，避免漏掉就绪消息。 */
export async function connectBackend(): Promise<void> {
  if (started) return;
  started = true;
  try {
    const { listen } = await import("@tauri-apps/api/event");
    const { invoke } = await import("@tauri-apps/api/core");
    await listen<ServerMessage>("backend-message", (event) => {
      handleServerMessage(event.payload);
      if (event.payload.t === "hello") state.wsReady = true;
    });
    await listen<string>("backend-error", (event) => {
      state.wsReady = false;
      state.lastError = event.payload;
    });
    await invoke("backend_start");
  } catch (err) {
    state.wsReady = false;
    state.lastError = `启动 Java 内核失败：${String(err)}`;
  }
}

export async function sendMsg(msg: ClientMessage): Promise<void> {
  if (!state.wsReady) {
    state.lastError = "与内核的连接未就绪";
    return;
  }
  try {
    const { invoke } = await import("@tauri-apps/api/core");
    await invoke("backend_send", { message: msg });
  } catch (err) {
    state.lastError = `发送失败：${String(err)}`;
  }
}

function handleServerMessage(msg: ServerMessage): void {
  if (msg.t === "hello") {
    state.serverVersion = msg.version;
    return;
  }
  if (msg.t === "error") {
    state.lastError = msg.message;
    return;
  }
  if (msg.t === "serial-ports") {
    state.serialPorts = msg.ports;
    return;
  }
  if (msg.t === "status") {
    if (msg.state === "closed") {
      state.connections.delete(msg.id);
      return;
    }
    const existing = state.connections.get(msg.id);
    if (existing) {
      existing.state = msg.state;
      existing.detail = msg.detail;
    } else {
      state.connections.set(msg.id, {
        id: msg.id,
        kind: msg.kind,
        label: msg.detail ?? msg.kind,
        state: msg.state,
        detail: msg.detail,
      });
    }
    return;
  }
  if (msg.t === "frame") {
    state.logs.push({
      ts: msg.ts,
      id: msg.id,
      kind: msg.kind,
      dir: msg.dir,
      hex: msg.hex,
      peer: msg.peer,
    });
    if (state.logs.length > MAX_LOGS) {
      state.logs.splice(0, state.logs.length - MAX_LOGS);
    }
    state.lastError = "";
  }
}
