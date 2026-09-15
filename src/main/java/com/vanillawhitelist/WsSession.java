package com.vanillawhitelist;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** 一条 WebSocket 连接（服务端侧）：握手 + 帧收发。帧编解码复用 {@link WebSocket} */
public class WsSession implements Peer {
	private final Socket socket;
	private final InputStream in;
	private final OutputStream out;
	private final MessageHandler handler;

	private volatile boolean open = true;
	private volatile boolean authenticated = false;
	private final long connectedAt = System.currentTimeMillis();

	public WsSession(Socket socket, MessageHandler handler) throws IOException {
		this.socket = socket;
		this.handler = handler;
		this.in = new BufferedInputStream(socket.getInputStream());
		this.out = new BufferedOutputStream(socket.getOutputStream());
	}

	public boolean isAuthenticated() { return authenticated; }
	public void setAuthenticated(boolean v) { this.authenticated = v; }
	public boolean isOpen() { return open; }
	public long getConnectedAt() { return connectedAt; }

	public void start() {
		Thread t = new Thread(this::run, "VWL-WS-Session");
		t.setDaemon(true);
		t.start();
	}

	private void run() {
		try {
			if (!handshake()) { close(); return; }
			VanillaWhitelistMod.LOGGER.info("[VWL] WebSocket 已连接: {}", socket.getRemoteSocketAddress());
			// 10 秒内未认证则断开
			Thread watchdog = new Thread(() -> {
				try { Thread.sleep(10_000L); } catch (InterruptedException ignored) { return; }
				if (!authenticated && open) {
					VanillaWhitelistMod.LOGGER.warn("[VWL] 连接未在 10 秒内认证，断开");
					close();
				}
			}, "VWL-WS-AuthWatchdog");
			watchdog.setDaemon(true);
			watchdog.start();

			// 半开连接检测：读超时 30 秒后发 WebSocket ping 探测；
			// 再等 30 秒仍无任何数据，判定连接已死并断开
			socket.setSoTimeout(30_000);
			boolean awaitingPong = false;
			while (open) {
				WebSocket.Frame f;
				try {
					f = WebSocket.readFrame(in);
				} catch (java.net.SocketTimeoutException te) {
					if (awaitingPong) {
						VanillaWhitelistMod.LOGGER.info("[VWL] 连接长时间无响应，判定为半开连接并断开");
						break;
					}
					WebSocket.writeFrame(out, 0x9, new byte[0], false);
					awaitingPong = true;
					continue;
				}
				if (f == null) break;
				awaitingPong = false; // 收到完整帧即认为连接存活
				switch (f.opcode) {
					case 0x1 -> handler.onText(this, new String(f.payload, StandardCharsets.UTF_8));
					case 0x8 -> { close(); return; }
					case 0x9 -> WebSocket.writeFrame(out, 0xA, f.payload, false);
					case 0xA -> { /* pong，忽略 */ }
					default -> { /* 二进制/续帧暂不支持 */ }
				}
			}
		} catch (Exception e) {
			if (open) VanillaWhitelistMod.LOGGER.debug("[VWL] 会话结束: {}", e.toString());
		} finally {
			close();
			handler.onClosed(this);
		}
	}

	private boolean handshake() throws IOException {
		String key = null;
		String line;
		while ((line = readLine()) != null) {
			if (line.isEmpty()) break;
			int c = line.indexOf(':');
			if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Sec-WebSocket-Key")) {
				key = line.substring(c + 1).trim();
			}
		}
		if (key == null) return false;
		String acc;
		try {
			acc = WebSocket.accept(key);
		} catch (Exception e) {
			return false;
		}
		String resp = "HTTP/1.1 101 Switching Protocols\r\n"
				+ "Upgrade: websocket\r\n"
				+ "Connection: Upgrade\r\n"
				+ "Sec-WebSocket-Accept: " + acc + "\r\n\r\n";
		out.write(resp.getBytes(StandardCharsets.US_ASCII));
		out.flush();
		return true;
	}

	public void sendText(String json) {
		// 所有出站消息统一盖协议版本号（推送与回复都走这里）
		sendFrame(0x1, VanillaWhitelistMod.stampProtocol(json).getBytes(StandardCharsets.UTF_8));
	}

	public void sendFrame(int opcode, byte[] data) {
		if (!open) return;
		try {
			WebSocket.writeFrame(out, opcode, data, false);
		} catch (IOException e) {
			close();
		}
	}

	public void close() {
		if (!open) return;
		open = false;
		try { socket.close(); } catch (IOException ignored) { }
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
