import assert from "node:assert/strict";
import { test } from "node:test";
import {
  defaultConnectionForm,
  defaultSendDraft,
  loadConnectionForm,
  loadSendDraft,
  saveConnectionForm,
  saveSendDraft,
} from "../../web/src/shared/preferences";

function memoryStorage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
  };
}

test("连接参数按类型和字段完整恢复，不包含运行中的连接", () => {
  const storage = memoryStorage();
  const form = { ...defaultConnectionForm, kind: "udp" as const, host: "10.168.1.7", port: 9100, listenPort: 9300, localPort: 9200, remoteHost: "10.168.1.8", remotePort: 9400, serialPath: "/dev/cu.test", baudRate: 9600, dataBits: 7, stopBits: 2, parity: "even", flowControl: true, idleMs: 150 };
  saveConnectionForm(form, storage);
  assert.deepEqual(loadConnectionForm(storage), form);
});

test("文本草稿、编码与间隔恢复，缺失或损坏的存储安全回退", () => {
  const storage = memoryStorage();
  const draft = { mode: "text" as const, encoding: "gb18030" as const, content: "^XA\n^FD品名^FS\n^XZ", loopMs: 600, gapMs: 180 };
  saveSendDraft(draft, storage);
  assert.deepEqual(loadSendDraft(storage), draft);
  storage.setItem("commprobe.connection-form", "{bad json");
  assert.deepEqual(loadConnectionForm(storage), defaultConnectionForm);
  storage.setItem("commprobe.send-draft", JSON.stringify({ mode: "bad", encoding: "bad", content: 5 }));
  assert.deepEqual(loadSendDraft(storage), defaultSendDraft);
  assert.doesNotThrow(() => saveConnectionForm(defaultConnectionForm, { setItem: () => { throw new Error("不可用"); } }));
});
