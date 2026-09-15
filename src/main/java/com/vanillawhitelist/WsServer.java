package com.vanillawhitelist;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 入站模式（mode=server）：本端监听端口，网站主动连过来。
 * 只允许一个活跃连接；网站离线期间消息写入队列，重连后补发。
 */
public class WsServer implements Transport {
	private final VwlConfig config;
	private final MessageHandler handler;
	private final AtomicReference<WsSession> active = new AtomicReference<>();
	private ServerSocket serverSocket;
	private volatile boolean running = false;
	private volatile Database db;

	public WsServer(VwlConfig config, MessageHandler handler) {
		this.config = config;
		this.handler = handler;
	}

	/** 设置后，网站离线期间的消息会被缓冲，重连时补发 */
	public void setDatabase(Database database) { this.db = database; }

	@Override
	public void start() throws IOException {
		serverSocket = new ServerSocket();
		serverSocket.setReuseAddress(true);
		serverSocket.bind(new InetSocketAddress(config.host, config.port));
		running = true;
		Thread t = new Thread(this::acceptLoop, "VWL-WS-Accept");
		t.setDaemon(true);
		t.start();
	}

	private void acceptLoop() {
		while (running) {
			try {
				Socket s = serverSocket.accept();
				s.setTcpNoDelay(true);
				WsSession old = active.getAndSet(null);
				if (old != null && old.isOpen()) {
					VanillaWhitelistMod.LOGGER.info("[VWL] 新连接进入，关闭旧连接");
					old.close();
				}
				WsSession session = new WsSession(s, handler);
				active.set(session);
				session.start();
			} catch (IOException e) {
				if (running) VanillaWhitelistMod.LOGGER.warn("[VWL] accept 异常: {}", e.toString());
			}
		}
	}

	public WsSession getActive() { return active.get(); }

	public void onSessionClosed(WsSession s) { active.compareAndSet(s, null); }

	@Override
	public void push(String json) {
		// 入队前先盖版本号，保证持久化的副本也是自描述的
		String payload = VanillaWhitelistMod.stampProtocol(json);
		WsSession s = active.get();
		if (s != null && s.isOpen() && s.isAuthenticated()) {
			s.sendText(payload);
			return;
		}
		Database d = db;
		if (d != null && d.isReady()) d.enqueueMessage(payload);
	}

	/** 认证成功后调用：把离线期间缓冲的消息补发出去 */
	public void replayBuffered(WsSession session) {
		Database d = db;
		if (d == null || !d.isReady()) return;
		var pending = d.dequeueAll();
		if (!pending.isEmpty()) {
			VanillaWhitelistMod.LOGGER.info("[VWL] 补发离线期间缓冲的 {} 条消息", pending.size());
			for (String msg : pending) session.sendText(msg);
		}
	}

	@Override
	public boolean isConnected() {
		WsSession s = active.get();
		return s != null && s.isOpen() && s.isAuthenticated();
	}

	@Override
	public void stop() {
		running = false;
		WsSession s = active.getAndSet(null);
		if (s != null) s.close();
		try {
			if (serverSocket != null) serverSocket.close();
		} catch (IOException ignored) { }
		serverSocket = null;
	}
}
