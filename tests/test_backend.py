"""通信内核端到端测试：使用本地套接字及伪终端，不连接真实设备。"""
import json
import os
import pty
import queue
import select
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "desktop-backend/target/commprobe-backend.jar"

class BackendTest(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.TemporaryDirectory()
        self.proc = subprocess.Popen(["java", "-Duser.home=" + self.home.name, "-jar", str(JAR)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
        self.events = queue.Queue()
        threading.Thread(target=lambda: [self.events.put(json.loads(line)) for line in self.proc.stdout], daemon=True).start()
        self.wait_event(lambda m: m.get("t") == "hello")

    def tearDown(self):
        if self.proc.stdin and not self.proc.stdin.closed: self.proc.stdin.close()
        try: self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired: self.proc.kill(); self.proc.wait(); self.fail("内核未退出")
        self.proc.stdout.close(); self.proc.stderr.close()
        self.home.cleanup()

    def send(self, **message):
        self.proc.stdin.write(json.dumps(message, ensure_ascii=False) + "\n"); self.proc.stdin.flush()

    def wait_event(self, check, timeout=5):
        deadline = time.monotonic() + timeout
        seen = []
        while time.monotonic() < deadline:
            try: msg = self.events.get(timeout=max(0.01, deadline-time.monotonic()))
            except queue.Empty: break
            seen.append(msg)
            if check(msg): return msg
        self.fail(f"没有收到预期消息，已收到：{seen}")

    def status(self, ident, value):
        return self.wait_event(lambda m: m.get("id") == ident and m.get("t") == "status" and m.get("state") == value)

    def test_tcp_client_encodings_hex_and_recording(self):
        listener = socket.socket(); listener.bind(("127.0.0.1", 0)); listener.listen(1); listener.settimeout(4)
        self.addCleanup(listener.close)
        self.send(t="open", id="client", kind="tcp-client", params={"host":"127.0.0.1", "port":listener.getsockname()[1], "idleMs":50})
        peer,_ = listener.accept(); peer.settimeout(4); self.addCleanup(peer.close); self.status("client", "open")
        for encoding in ("utf-8", "gbk", "gb18030"):
            self.send(t="send", id="client", text="品名", encoding=encoding)
            expected="品名".encode(encoding); self.assertEqual(peer.recv(128), expected)
            m=self.wait_event(lambda m: m.get("t")=="frame" and m.get("dir")=="out")
            self.assertEqual(bytes.fromhex(m["hex"]), expected)
        self.send(t="send", id="client", text="😀", encoding="gbk")
        self.assertIn("无法表示", self.wait_event(lambda m: m.get("t")=="error")["message"])
        self.send(t="send", id="client", hex="00 7F FF")
        self.assertEqual(peer.recv(128), b"\x00\x7f\xff")
        self.wait_event(lambda m: m.get("t")=="frame" and m.get("dir")=="out")
        peer.sendall(b"hello"); peer.sendall(b" world")
        frame=self.wait_event(lambda m: m.get("t")=="frame" and m.get("dir")=="in")
        self.assertEqual(bytes.fromhex(frame["hex"]), b"hello world")
        self.send(t="close", id="client"); self.status("client", "closed")
        files=list((Path(self.home.name)/".commprobe/sessions").glob("*.jsonl")); self.assertEqual(len(files),1)
        self.assertTrue(any(json.loads(line)["dir"]=="in" for line in files[0].read_text().splitlines()))

    def test_server_broadcast_and_udp_reply(self):
        reserve=socket.socket(); reserve.bind(("127.0.0.1",0)); port=reserve.getsockname()[1]; reserve.close()
        self.send(t="open", id="server", kind="tcp-server", params={"port":port,"idleMs":50}); self.status("server","open")
        a=socket.create_connection(("127.0.0.1",port)); b=socket.create_connection(("127.0.0.1",port)); self.addCleanup(a.close); self.addCleanup(b.close); a.settimeout(4); b.settimeout(4)
        self.wait_event(lambda m: m.get("id")=="server" and "客户端 2 个" in m.get("detail", ""))
        self.send(t="send",id="server",hex="AB CD"); self.assertEqual(a.recv(20),b"\xab\xcd"); self.assertEqual(b.recv(20),b"\xab\xcd")
        self.send(t="close",id="server"); self.status("server","closed")
        reserve=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); reserve.bind(("127.0.0.1",0)); port=reserve.getsockname()[1]; reserve.close()
        peer=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); peer.bind(("127.0.0.1",0)); peer.settimeout(4); self.addCleanup(peer.close)
        self.send(t="open",id="udp",kind="udp",params={"localPort":port,"idleMs":50}); self.status("udp","open")
        peer.sendto(b"ping",("127.0.0.1",port)); self.wait_event(lambda m: m.get("id")=="udp" and m.get("dir")=="in")
        self.send(t="send",id="udp",text="pong",encoding="utf-8"); self.assertEqual(peer.recvfrom(20)[0],b"pong")
        self.send(t="close",id="udp"); self.status("udp","closed")

    def test_serial_pty_and_enumeration(self):
        self.send(t="serial-list"); self.assertIsInstance(self.wait_event(lambda m:m.get("t")=="serial-ports")["ports"],list)
        master,slave=pty.openpty(); self.addCleanup(os.close,master); self.addCleanup(os.close,slave)
        self.send(t="open",id="serial",kind="serial",params={"path":os.ttyname(slave),"baudRate":9600,"dataBits":8,"stopBits":1,"parity":"none","idleMs":50}); self.status("serial","open")
        self.send(t="send",id="serial",hex="01 02 03"); self.assertTrue(select.select([master],[],[],4)[0]); self.assertEqual(os.read(master,10),b"\x01\x02\x03")
        os.write(master,b"serial reply"); m=self.wait_event(lambda m:m.get("id")=="serial" and m.get("dir")=="in"); self.assertEqual(bytes.fromhex(m["hex"]),b"serial reply")
        self.send(t="close",id="serial"); self.status("serial","closed")

    def test_connection_failure_and_reopen(self):
        s=socket.socket(); s.bind(("127.0.0.1",0)); port=s.getsockname()[1]; s.close()
        self.send(t="open",id="bad",kind="tcp-client",params={"host":"127.0.0.1","port":port}); self.status("bad","error")
        s=socket.socket(); s.bind(("127.0.0.1",port)); s.listen(1); s.settimeout(4); self.addCleanup(s.close)
        self.send(t="open",id="bad",kind="tcp-client",params={"host":"127.0.0.1","port":port}); peer,_=s.accept(); self.addCleanup(peer.close); self.status("bad","open")

if __name__ == "__main__": unittest.main(verbosity=2)
