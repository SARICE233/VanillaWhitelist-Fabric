package com.vanillawhitelist;

/**
 * 「对端」抽象：能被回消息的一方。
 * - 入站模式下是 {@link WsSession}（网站连过来）
 * - 出站模式下是 {@link WsClient} 自己（本端连过去）
 * 消息处理层只依赖这个接口，因此两种模式共用同一套协议实现。
 */
public interface Peer {
	void sendText(String json);
	boolean isAuthenticated();
	void setAuthenticated(boolean v);
	void close();
}
