import type { TextEncoding, TransportKind } from "./protocol";

const CONNECTION_KEY = "commprobe.connection-form";
const DRAFT_KEY = "commprobe.send-draft";

export interface ConnectionForm {
  kind: TransportKind;
  host: string;
  port: number;
  listenPort: number;
  localPort: number;
  remoteHost: string;
  remotePort: number;
  serialPath: string;
  baudRate: number;
  dataBits: number;
  stopBits: number;
  parity: string;
  flowControl: boolean;
  idleMs: number;
}

export const defaultConnectionForm: ConnectionForm = {
  kind: "tcp-client", host: "192.168.1.100", port: 9000, listenPort: 9000,
  localPort: 9001, remoteHost: "", remotePort: 9000, serialPath: "",
  baudRate: 115200, dataBits: 8, stopBits: 1, parity: "none", flowControl: false, idleMs: 300,
};

export interface SendDraft {
  mode: "hex" | "text";
  encoding: TextEncoding;
  content: string;
  loopMs: number;
  gapMs: number;
}

export const defaultSendDraft: SendDraft = {
  mode: "hex", encoding: "utf-8", content: "", loopMs: 1000, gapMs: 200,
};

type StorageReader = Pick<Storage, "getItem">;
type StorageWriter = Pick<Storage, "setItem">;

function readValue(key: string, storage?: StorageReader): Record<string, unknown> {
  try {
    const raw = (storage ?? globalThis.localStorage).getItem(key);
    const value: unknown = raw ? JSON.parse(raw) : null;
    return value && typeof value === "object" && !Array.isArray(value)
      ? value as Record<string, unknown> : {};
  } catch { return {}; }
}

function writeValue(key: string, value: object, storage?: StorageWriter): void {
  try { (storage ?? globalThis.localStorage).setItem(key, JSON.stringify(value)); }
  catch { /* 存储不可用时，当前会话仍可正常使用 */ }
}

export function loadConnectionForm(storage?: StorageReader): ConnectionForm {
  const saved = readValue(CONNECTION_KEY, storage);
  const result = { ...defaultConnectionForm };
  for (const key of ["host", "remoteHost", "serialPath"] as const) {
    if (typeof saved[key] === "string") result[key] = saved[key];
  }
  for (const key of ["port", "listenPort", "localPort", "remotePort", "baudRate", "dataBits", "stopBits", "idleMs"] as const) {
    if (typeof saved[key] === "number" && Number.isFinite(saved[key])) result[key] = saved[key];
  }
  if (saved.kind === "tcp-client" || saved.kind === "tcp-server" || saved.kind === "udp" || saved.kind === "serial") result.kind = saved.kind;
  if (saved.parity === "none" || saved.parity === "even" || saved.parity === "odd") result.parity = saved.parity;
  if (typeof saved.flowControl === "boolean") result.flowControl = saved.flowControl;
  return result;
}

export function saveConnectionForm(value: ConnectionForm, storage?: StorageWriter): void {
  writeValue(CONNECTION_KEY, value, storage);
}

export function loadSendDraft(storage?: StorageReader): SendDraft {
  const saved = readValue(DRAFT_KEY, storage);
  return {
    mode: saved.mode === "text" ? "text" : "hex",
    encoding: saved.encoding === "gbk" || saved.encoding === "gb18030" ? saved.encoding : "utf-8",
    content: typeof saved.content === "string" ? saved.content : "",
    loopMs: typeof saved.loopMs === "number" && Number.isFinite(saved.loopMs) ? saved.loopMs : defaultSendDraft.loopMs,
    gapMs: typeof saved.gapMs === "number" && Number.isFinite(saved.gapMs) ? saved.gapMs : defaultSendDraft.gapMs,
  };
}

export function saveSendDraft(value: SendDraft, storage?: StorageWriter): void {
  writeValue(DRAFT_KEY, value, storage);
}
