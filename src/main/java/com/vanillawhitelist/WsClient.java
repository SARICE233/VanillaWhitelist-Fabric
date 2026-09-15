package com.vanillawhitelist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 出站模式（mode=client）：本端主动连网站，**不需要开放任何入站端口**。
 *
 * 消息协议与入站模式完全一致，只是连接发起方反过来：
 * 本端发 auth、被网站校验；随后本端推送数据，网站可下发白名单操作。
 *
 * 断线自动重连（指数退避，最长 60 秒）；重连后补发离线期间缓冲的消息。
 */
public class WsClient implements Transport, Peer {

	private final VwlConfig config;
	private final MessageHandler handler;
	private volatile Database db;

	private volatile Socket socket;
	private volatile InputStream in;
	private volatile OutputStream out;
	private volatile boolean authenticated = false;
	private volatile boolean running = false;
	private Thread thread;
	private long backoffMs = 1000L;

	public WsClient(VwlConfig config, MessageHandler handler) {
		this.config = config;
		this.handler = handler;
	}

	public void setDatabase(Database database) { this.db = database; }

	@Override
	public void start() {
		running = true;
		thread = new Thread(this::loop, "VWL-WS-Client");
		thread.setDaemon(true);
		thread.start();
	}

	private void loop() {
		while (running) {
			try {
				runOnce();
			} catch (Exception e) {
				if (running) VanillaWhitelistMod.LOGGER.info("[VWL] 出站连接中断: {}", e.toString());
			}
			cleanup();
			if (!running) break;
			try {
				Thread.sleep(backoffMs);
			} catch (InterruptedException ie) {
				break;
			}
			backoffMs = Math.min(backoffMs * 2, 60_000L);
		}
	}

	private void runOnce() throws Exception {
		URI uri = URI.create(config.url);
		String scheme = uri.getScheme() == null ? "ws" : uri.getScheme().toLowerCase();
		String host = uri.getHost();
		if (host == null) throw new IOException("url 里没有主机名: " + config.url);
		int port = uri.getPort();
		if (port < 0) port = "wss".equals(scheme) ? 443 : 80;
		String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
		if (uri.getRawQuery() != null) path = path + "?" + uri.getRawQuery();

		Socket s;
		if ("wss".equals(scheme)) {
			SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
			ssl.connect(new InetSocketAddress(host, port), 15000);
			ssl.startHandshake();
			s = ssl;
		} else {
			s = new Socket();
			s.connect(new InetSocketAddress(host, port), 15000);
		}
		s.setTcpNoDelay(true);
		s.setSoTimeout(15000);
		socket = s;
		in = new BufferedInputStream(s.getInputStream());
		out = new BufferedOutputStream(s.getOutputStream());

		// ── 客户端握手 ──
		String key = WebSocket.randomKey();
		String req = "GET " + path + " HTTP/1.1\r\n"
				+ "Host: " + host + ":" + port + "\r\n"
				+ "Upgrade: websocket\r\n"
				+ "Connection: Upgrade\r\n"
				+ "Sec-WebSocket-Key: " + key + "\r\n"
				+ "Sec-WebSocket-Version: 13\r\n\r\n";
		out.write(req.getBytes(StandardCharsets.US_ASCII));
		out.flush();

		String status = readLine();
		if (status == null || !status.contains("101")) {
			throw new IOException("握手失败: " + status);
		}
		String expect = WebSocket.accept(key);
		boolean acceptOk = false;
		String line;
		while ((line = readLine()) != null && !line.isEmpty()) {
			int c = line.indexOf(':');
			if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Sec-WebSocket-Accept")) {
				acceptOk = expect.equals(line.substring(c + 1).trim());
			}
		}
		if (!acceptOk) throw new IOException("Sec-WebSocket-Accept 校验失败");

		VanillaWhitelistMod.LOGGER.info("[VWL] 已连接网站: {}", config.url);
		backoffMs = 1000L;

		// ── 认证（出站模式下由本端发起）──
		JsonObject auth = new JsonObject();
		auth.addProperty("type", "auth");
		auth.addProperty("id", "auth-" + System.currentTimeMillis());
		auth.addProperty("secret", config.secret);
		auth.addProperty("protocol_version", VanillaWhitelistMod.PROTOCOL_VERSION);
		auth.addProperty("impl", VanillaWhitelistMod.IMPL);
		auth.addProperty("impl_version", VanillaWhitelistMod.implVersion());
		sendText(auth.toString());

		// ── 读循环 ──
		s.setSoTimeout(0);
		while (running) {
			WebSocket.Frame f = WebSocket.readFrame(in);
			if (f == null) break;
			switch (f.opcode) {
				case 0x1 -> handleText(new String(f.payload, StandardCharsets.UTF_8));
				case 0x8 -> { return; }
				case 0x9 -> writeFrame(0xA, f.payload);
				case 0xA -> { /* pong，忽略 */ }
				default -> { }
			}
		}
	}

	private void handleText(String json) {
		JsonObject msg;
		try {
			msg = JsonParser.parseString(json).getAsJsonObject();
		} catch (Exception e) {
			return;
		}
		String type = msg.has("type") && !msg.get("type").isJsonNull() ? msg.get("type").getAsString() : null;
		if ("auth_result".equals(type)) {
			boolean ok = msg.has("success") && msg.get("success").getAsBoolean();
			if (ok) {
				authenticated = true;
				VanillaWhitelistMod.LOGGER.info("[VWL] 网站认证通过（出站模式）");
				replayBuffered();
				sendInitialState();
			} else {
				String err = msg.has("error") ? msg.get("error").getAsString() : "UNKNOWN";
				VanillaWhitelistMod.LOGGER.error("[VWL] 网站拒绝认证: {}（请检查两侧 secret 是否一致）", err);
				close();
			}
			return;
		}
		// 其余消息（白名单操作、ping 等）交给共用的消息处理层
		handler.onText(this, json);
	}

	private void sendInitialState() {
		replayBuffered();
		try {
			var srv = handler.getServer();
			if (srv != null) {
				push(StatsCollector.serverStats(srv, config).toString());
				push(StatsCollector.playerAdvancements(srv, config, false).toString());
			}
		} catch (Exception e) {
			VanillaWhitelistMod.LOGGER.warn("[VWL] 初始状态推送失败: {}", e.toString());
		}
	}

	/**
	 * 出站模式：本端是客户端，发出的帧必须掩码。
	 * 注意这里只判断 socket 是否打开 —— 认证消息本身就要在 authenticated 之前发出，
	 * 不能用 isConnected()（它要求已认证）。
	 */
	@Override
	public void sendText(String json) {
		if (out == null || socket == null || socket.isClosed()) return;
		try {
			WebSocket.writeFrame(out, 0x1,
					VanillaWhitelistMod.stampProtocol(json).getBytes(StandardCharsets.UTF_8), true);
		} catch (IOException e) {
			close();
		}
	}

	private void writeFrame(int opcode, byte[] data) {
		try {
			WebSocket.writeFrame(out, opcode, data, true);
		} catch (IOException e) {
			close();
		}
	}

	@Override
	public void push(String json) {
		String payload = VanillaWhitelistMod.stampProtocol(json);
		if (isConnected()) {
			writePayload(payload);
			return;
		}
		// 网站不在线：入队，重连后补发
		Database d = db;
		if (d != null && d.isReady()) d.enqueueMessage(payload);
	}

	private void writePayload(String payload) {
		try {
			WebSocket.writeFrame(out, 0x1, payload.getBytes(StandardCharsets.UTF_8), true);
		} catch (IOException e) {
			close();
		}
	}

	private void replayBuffered() {
		Database d = db;
		if (d == null || !d.isReady()) return;
		var pending = d.dequeueAll();
		if (pending.isEmpty()) return;
		VanillaWhitelistMod.LOGGER.info("[VWL] 补发离线期间缓冲的 {} 条消息", pending.size());
		for (String msg : pending) writePayload(msg);
	}

	@Override
	public boolean isAuthenticated() { return authenticated; }

	@Override
	public void setAuthenticated(boolean v) { this.authenticated = v; }

	@Override
	public boolean isConnected() { return running && authenticated && socket != null && !socket.isClosed(); }

	@Override
	public void close() {
		authenticated = false;
		try {
			if (socket != null) socket.close();
		} catch (IOException ignored) { }
	}

	private void cleanup() {
		authenticated = false;
		try {
			if (socket != null) socket.close();
		} catch (IOException ignored) { }
		socket = null;
		in = null;
		out = null;
	}

	@Override
	public void stop() {
		running = false;
		cleanup();
		if (thread != null) thread.interrupt();
	}

	private String readLine() throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int b;
		while ((b = in.read()) >= 0) {
			if (b == 10) break;
			if (b != 13) buf.write(b);
			if (buf.size() > 8192) break;
		}
		if (b < 0 && buf.size() == 0) return null;
		return buf.toString(StandardCharsets.US_ASCII);
	}
}
