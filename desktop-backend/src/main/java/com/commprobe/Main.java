package com.commprobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fazecast.jSerialComm.SerialPort;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 桌面通信内核：标准输入和输出仅承载逐行 JSON 协议。 */
public final class Main {
    static final ObjectMapper JSON = new ObjectMapper();
    static final ExecutorService IO = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "通信线程"); t.setDaemon(true); return t; });
    static final ScheduledExecutorService TIMERS = Executors.newScheduledThreadPool(2, r -> { Thread t = new Thread(r, "分帧线程"); t.setDaemon(true); return t; });
    final Map<String, Connection> connections = new ConcurrentHashMap<>();
    final PrintWriter output = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
    final BufferedWriter records;

    Main() throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), ".commprobe", "sessions");
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        records = Files.newBufferedWriter(dir.resolve("session-" + stamp + ".jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    synchronized void emit(ObjectNode message) { output.println(message.toString()); }
    ObjectNode message(String type) { return JSON.createObjectNode().put("t", type); }
    void error(String message) { emit(message("error").put("message", message == null ? "未知错误" : message)); }
    synchronized void record(ObjectNode msg) {
        try { ObjectNode copy = msg.deepCopy(); copy.remove("t"); records.write(copy.toString()); records.newLine(); records.flush(); }
        catch (IOException e) { System.err.println("记录会话失败：" + e.getMessage()); }
    }
    void frame(Connection c, String direction, byte[] bytes, String peer) {
        ObjectNode msg = message("frame").put("id", c.id).put("kind", c.kind).put("dir", direction).put("hex", HexFormat.ofDelimiter(" ").withUpperCase().formatHex(bytes)).put("ts", System.currentTimeMillis());
        if (peer != null) msg.put("peer", peer);
        record(msg); emit(msg);
    }
    static byte[] encode(String text, String encoding) throws CharacterCodingException {
        String charset = switch (encoding.toLowerCase(Locale.ROOT)) { case "utf-8" -> "UTF-8"; case "gbk" -> "GBK"; case "gb18030" -> "GB18030"; default -> throw new IllegalArgumentException("不支持的编码：" + encoding); };
        ByteBuffer result = Charset.forName(charset).newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
        byte[] data = new byte[result.remaining()]; result.get(data); return data;
    }
    static byte[] parseHex(String value) {
        String compact = value.replaceAll("[^0-9a-fA-F]", "");
        if (compact.length() % 2 != 0) throw new IllegalArgumentException("HEX 长度必须为偶数");
        return HexFormat.of().parseHex(compact);
    }
    void handle(JsonNode msg) {
        try {
            String t = msg.path("t").asText();
            if (t.equals("serial-list")) {
                ArrayNode ports = JSON.createArrayNode();
                for (SerialPort p : SerialPort.getCommPorts()) {
                    String path = p.getSystemPortPath();
                    if (path.contains("Bluetooth-Incoming-Port") || path.contains("debug-console")) continue;
                    ObjectNode item = ports.addObject().put("path", path); if (p.getDescriptivePortName() != null) item.put("manufacturer", p.getDescriptivePortName());
                }
                emit(message("serial-ports").set("ports", ports)); return;
            }
            String id = msg.path("id").asText();
            if (id.isBlank()) throw new IllegalArgumentException("缺少连接标识");
            if (t.equals("open")) {
                String kind = msg.path("kind").asText();
                if (!Set.of("tcp-client", "tcp-server", "udp", "serial").contains(kind)) throw new IllegalArgumentException("不支持的连接类型：" + kind);
                Connection old = connections.remove(id); if (old != null) old.close();
                Connection c = new Connection(id, kind, msg.path("params")); connections.put(id, c);
                IO.execute(() -> { try { c.open(); } catch (Exception e) { if (connections.remove(id, c) && !c.closed.get()) c.fail(e); } });
            } else if (t.equals("close")) {
                Connection c = connections.remove(id); if (c != null) c.close();
            } else if (t.equals("send")) {
                Connection c = connections.get(id); if (c == null) throw new IllegalStateException("连接不存在：" + id);
                byte[] bytes;
                if (msg.hasNonNull("text") && !msg.path("text").asText().isEmpty()) {
                    try { bytes = encode(msg.path("text").asText(), msg.path("encoding").asText("utf-8")); }
                    catch (CharacterCodingException e) { throw new IllegalArgumentException("文本包含所选编码无法表示的字符", e); }
                } else if (msg.hasNonNull("hex") && !msg.path("hex").asText().isBlank()) bytes = parseHex(msg.path("hex").asText());
                else throw new IllegalArgumentException("发送内容为空");
                c.send(bytes); frame(c, "out", bytes, null);
            } else throw new IllegalArgumentException("未知消息类型：" + t);
        } catch (Exception e) { error(e.getMessage()); }
    }
    final class Connection {
        final String id, kind; final JsonNode params; final int idle;
        final AtomicBoolean closed = new AtomicBoolean();
        final Set<Socket> clients = ConcurrentHashMap.newKeySet();
        volatile Socket client; volatile ServerSocket server; volatile DatagramSocket udp; volatile SocketAddress lastPeer; volatile SerialPort serial;
        final ByteArrayOutputStream pending = new ByteArrayOutputStream(); ScheduledFuture<?> timer; String peer;
        Connection(String id, String kind, JsonNode params) { this.id=id; this.kind=kind; this.params=params; int ms=params.path("idleMs").asInt(300); idle=ms>=30 ? ms : 300; }
        void status(String state, String detail) { ObjectNode m=message("status").put("id",id).put("kind",kind).put("state",state); if(detail!=null) m.put("detail",detail); emit(m); }
        synchronized void incoming(byte[] data, String from) {
            if(closed.get()) return;
            pending.writeBytes(data); if(from!=null) peer=from;
            if(timer!=null) timer.cancel(false);
            if(pending.size()>=65536) flush(); else timer=TIMERS.schedule(this::flush,idle,TimeUnit.MILLISECONDS);
        }
        synchronized void flush() { if(timer!=null) timer.cancel(false); timer=null; if(pending.size()>0) { byte[] data=pending.toByteArray(); pending.reset(); String p=peer; peer=null; frame(this,"in",data,p); } }
        void open() throws Exception {
            if(closed.get()) return;
            switch(kind) {
                case "tcp-client" -> {
                    String host=params.path("host").asText(); int port=params.path("port").asInt(); status("connecting",host+":"+port);
                    Socket s=new Socket(); client=s; s.connect(new InetSocketAddress(host,port),10000); if(closed.get()) {s.close(); return;} status("open",host+":"+port);
                    IO.execute(() -> readSocket(s,host+":"+port,false));
                }
                case "tcp-server" -> {
                    int port=params.path("port").asInt(); ServerSocket s=new ServerSocket(); server=s; s.bind(new InetSocketAddress(port)); if(closed.get()) {s.close(); return;} status("open",":"+port+" 等待客户端连入");
                    IO.execute(() -> { while(!closed.get()) try { Socket c=s.accept(); clients.add(c); clientCount(); IO.execute(() -> readSocket(c,c.getRemoteSocketAddress().toString(),true)); } catch(IOException e) { if(!closed.get()) status("error",e.getMessage()); break; } });
                }
                case "udp" -> {
                    int port=params.path("localPort").asInt(); DatagramSocket s=new DatagramSocket(null); udp=s; s.bind(new InetSocketAddress(port)); if(closed.get()) {s.close(); return;} status("open","udp :"+port);
                    IO.execute(() -> { byte[] buf=new byte[65535]; while(!closed.get()) try { DatagramPacket p=new DatagramPacket(buf,buf.length); s.receive(p); lastPeer=p.getSocketAddress(); incoming(Arrays.copyOfRange(p.getData(),p.getOffset(),p.getOffset()+p.getLength()),p.getAddress().getHostAddress()+":"+p.getPort()); } catch(IOException e) {if(!closed.get()) status("error",e.getMessage()); break;} });
                }
                case "serial" -> openSerial();
                default -> throw new IllegalArgumentException("不支持的连接类型");
            }
        }
        void openSerial() throws IOException {
            String path=params.path("path").asText(); int baud=params.path("baudRate").asInt(9600); status("connecting",path+" @"+baud);
            SerialPort p=SerialPort.getCommPort(path); serial=p;
            int parity=switch(params.path("parity").asText("none")) {case "even" -> SerialPort.EVEN_PARITY; case "odd" -> SerialPort.ODD_PARITY; default -> SerialPort.NO_PARITY;};
            p.setComPortParameters(baud,params.path("dataBits").asInt(8),params.path("stopBits").asInt(1)==2?SerialPort.TWO_STOP_BITS:SerialPort.ONE_STOP_BIT,parity);
            if(params.path("flowControl").asBoolean(false)) p.setFlowControl(SerialPort.FLOW_CONTROL_RTS_ENABLED|SerialPort.FLOW_CONTROL_CTS_ENABLED);
            p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING,200,0);
            if(!p.openPort()) throw new IOException("打开串口失败："+path); if(closed.get()) {p.closePort();return;} status("open",path+" @"+baud);
            IO.execute(() -> { byte[] buf=new byte[8192]; try {while(!closed.get() && p.isOpen()) {int n=p.readBytes(buf,buf.length); if(n>0) incoming(Arrays.copyOf(buf,n),path); else if(n<0) break;}} finally { if(!closed.get()) {connections.remove(id,this); close();} } });
        }
        void readSocket(Socket s,String from,boolean accepted) {
            try(InputStream in=s.getInputStream()) {byte[] buf=new byte[8192]; int n; while(!closed.get() && (n=in.read(buf))!=-1) incoming(Arrays.copyOf(buf,n),from);}
            catch(IOException e) {if(!closed.get() && !accepted) status("error",e.getMessage());}
            finally {try{s.close();}catch(IOException ignored){} if(accepted) {clients.remove(s); if(!closed.get()) clientCount();} else if(!closed.get()) {connections.remove(id,this); close();} }
        }
        void clientCount() {status("open",":"+params.path("port").asInt()+" 客户端 "+clients.size()+" 个");}
        synchronized void send(byte[] bytes) throws IOException {
            if(closed.get()) throw new IOException("连接已关闭");
            switch(kind) {
                case "tcp-client" -> {if(client==null||!client.isConnected()||client.isClosed()) throw new IOException("TCP 连接未建立"); client.getOutputStream().write(bytes);}
                case "tcp-server" -> {if(clients.isEmpty()) throw new IOException("尚无客户端连入，无法发送"); for(Socket s:clients) s.getOutputStream().write(bytes);}
                case "udp" -> {if(udp==null||udp.isClosed()) throw new IOException("UDP 尚未打开"); SocketAddress dest = params.hasNonNull("remoteHost") && !params.path("remoteHost").asText().isBlank() && params.path("remotePort").asInt()>0 ? new InetSocketAddress(params.path("remoteHost").asText(),params.path("remotePort").asInt()) : lastPeer; if(dest==null) throw new IOException("UDP 发送需要配置目标地址或先收到数据"); udp.send(new DatagramPacket(bytes,bytes.length,dest));}
                case "serial" -> {if(serial==null||!serial.isOpen()) throw new IOException("串口未打开"); int count=serial.writeBytes(bytes,bytes.length); if(count!=bytes.length) throw new IOException("串口发送失败或未完全写入");}
                default -> throw new IOException("不支持的连接类型");
            }
        }
        void fail(Exception e) { status("error",e.getMessage()); closeResources(); }
        void closeResources() {try{if(client!=null) client.close();}catch(IOException ignored){} try{if(server!=null) server.close();}catch(IOException ignored){} for(Socket s:clients) try{s.close();}catch(IOException ignored){} if(udp!=null)udp.close(); if(serial!=null)serial.closePort();}
        void close() {if(!closed.compareAndSet(false,true))return; flush(); closeResources(); status("closed",null);}
    }
    void run() throws IOException {
        emit(message("hello").put("version","0.1.5"));
        try(BufferedReader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))) {String line; while((line=in.readLine())!=null) {try{handle(JSON.readTree(line));}catch(Exception e){error("消息解析失败："+e.getMessage());}}}
        finally {for(Connection c:connections.values())c.close(); IO.shutdownNow(); TIMERS.shutdownNow(); records.close();}
    }
    public static void main(String[] args) {try{new Main().run();}catch(Exception e){System.err.println("内核启动失败："+e.getMessage());System.exit(1);}}
}
