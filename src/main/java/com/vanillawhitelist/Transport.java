package com.vanillawhitelist;

/**
 * 传输层抽象。
 *
 * 两种模式共用同一套消息协议，只有「谁发起连接」不同：
 * - {@link WsServer} 入站模式（mode=server）：网站主动连过来，需要开放端口
 * - {@link WsClient} 出站模式（mode=client）：本端主动连网站，不需要开放任何端口
 */
public interface Transport {

	/** 启动传输层（失败时应记录日志而不是抛出到调用方之外） */
	void start() throws Exception;

	/** 停止传输层，并尽快释放资源 */
	void stop();

	/** 推送消息；对端不在线时写入本地队列，等重连补发 */
	void push(String json);

	/** 是否已有「已认证」的对端 */
	boolean isConnected();
}
