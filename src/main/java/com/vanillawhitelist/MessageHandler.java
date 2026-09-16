package com.vanillawhitelist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.StoredUserEntry;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 处理网站发来的消息。**两种连接模式共用同一套实现**，
 * 只依赖 {@link Peer}（回消息）与 {@link Transport}（推送）两个抽象。
 */
public class MessageHandler {
	private final VwlConfig config;
	private volatile MinecraftServer server;
	private volatile Transport transport;

	public MessageHandler(VwlConfig config) { this.config = config; }
	public void setServer(MinecraftServer s) { this.server = s; }
	public MinecraftServer getServer() { return server; }
	public void setTransport(Transport t) { this.transport = t; }

	public void onText(Peer peer, String json) {
		JsonObject msg;
		try {
			msg = JsonParser.parseString(json).getAsJsonObject();
		} catch (Exception e) {
			return;
		}
		String type = str(msg, "type");
		if (type == null) return;
		switch (type) {
			case "auth" -> auth(peer, msg);
			case "ping" -> peer.sendText("{\"type\":\"pong\"}");
			case "whitelist_add" -> whitelist(peer, msg, true);
			case "whitelist_remove" -> whitelist(peer, msg, false);
			default -> { }
		}
	}

	public void onClosed(WsSession s) {
		Transport t = transport;
		if (t instanceof WsServer ws) ws.onSessionClosed(s);
	}

	private void auth(Peer peer, JsonObject msg) {
		String id = str(msg, "id");
		String secret = str(msg, "secret");
		boolean ok = secret != null && secret.equals(config.secret);
		JsonObject r = new JsonObject();
		r.addProperty("type", "auth_result");
		if (id != null) r.addProperty("id", id);
		r.addProperty("success", ok);
		// 三端统一的身份握手：网站据此判断对端能力
		r.addProperty("protocol_version", VanillaWhitelistMod.PROTOCOL_VERSION);
		r.addProperty("impl", VanillaWhitelistMod.IMPL);
		r.addProperty("impl_version", VanillaWhitelistMod.implVersion());
		if (!ok) r.addProperty("error", "INVALID_SECRET");
		peer.sendText(r.toString());
		if (ok) {
			peer.setAuthenticated(true);
			VanillaWhitelistMod.LOGGER.info("[VWL] 网站认证成功");
			Transport t = transport;
			MinecraftServer srv = server;
			if (t instanceof WsServer ws && peer instanceof WsSession session) {
				// 入站模式：先补发离线期间缓冲的消息
				ws.replayBuffered(session);
			}
			if (t != null && srv != null) {
				t.push(StatsCollector.serverStats(srv, config).toString());
				// 给一份完整的成就明细作基准
				t.push(StatsCollector.playerAdvancements(srv, config, false).toString());
				// 再补一份玩家统计基准：定时推送默认 600 秒一次，认证时不补的话
				// 网站会长时间停在空的玩家数据上。切回服务端线程再读，避免跨线程访问玩家。
				srv.execute(() -> t.push(StatsCollector.playerStatsBatch(srv, config).toString()));
			}
		} else {
			VanillaWhitelistMod.LOGGER.warn("[VWL] 认证失败，断开连接");
			peer.close();
		}
	}

	private void whitelist(Peer peer, JsonObject msg, boolean add) {
		if (!peer.isAuthenticated()) return;
		String id = str(msg, "id");
		String name = str(msg, "player_name");
		String action = add ? "whitelist_add" : "whitelist_remove";
		MinecraftServer srv = server;
		if (srv == null) {
			result(peer, id, action, false, name, "INTERNAL_ERROR");
			return;
		}
		String nameError = validatePlayerName(name);
		if (nameError != null) {
			result(peer, id, action, false, name, nameError);
			return;
		}
		srv.execute(() -> {
			try {
				UserWhiteList wl = srv.getPlayerList().getWhiteList();
				StoredUserEntry<NameAndId> existing = findByName(wl, name);
				if (!add) {
					if (existing == null) {
						result(peer, id, action, false, name, "NOT_WHITELISTED");
						return;
					}
					wl.remove(existing);
					wl.save();
					VanillaWhitelistMod.LOGGER.info("[VWL] 白名单移除成功: {}", name);
					result(peer, id, action, true, name, null);
					return;
				}
				if (!srv.isUsingWhitelist()) {
					result(peer, id, action, false, name, "WHITELIST_DISABLED");
					return;
				}
				if (existing != null) {
					result(peer, id, action, false, name, "ALREADY_WHITELISTED");
					return;
				}
				// 资料解析可能走网络，放后台线程，避免阻塞服务端线程
				CompletableFuture.supplyAsync(() -> resolve(srv, name)).thenAccept(target -> srv.execute(() -> {
					try {
						wl.add(new UserWhiteListEntry(target));
						wl.save();
						VanillaWhitelistMod.LOGGER.info("[VWL] 白名单添加成功: {}", name);
						result(peer, id, action, true, name, null);
					} catch (Exception ex) {
						VanillaWhitelistMod.LOGGER.error("[VWL] 白名单添加异常", ex);
						result(peer, id, action, false, name, "INTERNAL_ERROR");
					}
				}));
			} catch (Exception e) {
				VanillaWhitelistMod.LOGGER.error("[VWL] 白名单操作异常", e);
				result(peer, id, action, false, name, "INTERNAL_ERROR");
			}
		});
	}

	/**
	 * 供游戏内命令调用：执行白名单操作。
	 * 必须在服务端线程调用。@return 错误码；成功返回 null
	 */
	public static String applyWhitelist(MinecraftServer srv, String name, boolean add) {
		String nameError = validatePlayerName(name);
		if (nameError != null) return nameError;
		try {
			UserWhiteList wl = srv.getPlayerList().getWhiteList();
			StoredUserEntry<NameAndId> existing = findByName(wl, name);
			if (!add) {
				if (existing == null) return "NOT_WHITELISTED";
				wl.remove(existing);
				wl.save();
				return null;
			}
			if (!srv.isUsingWhitelist()) return "WHITELIST_DISABLED";
			if (existing != null) return "ALREADY_WHITELISTED";
			wl.add(new UserWhiteListEntry(resolve(srv, name)));
			wl.save();
			return null;
		} catch (Exception e) {
			VanillaWhitelistMod.LOGGER.error("[VWL] 白名单操作异常", e);
			return "INTERNAL_ERROR";
		}
	}

	/** @return 错误码；合法则返回 null */
	public static String validatePlayerName(String name) {
		if (name == null || name.isEmpty()) return "PLAYER_NAME_EMPTY";
		if (name.length() < 3 || name.length() > 16) return "PLAYER_NAME_INVALID_LENGTH";
		if (!name.matches("[A-Za-z0-9_]+")) return "PLAYER_NAME_INVALID_CHARS";
		return null;
	}

	private static StoredUserEntry<NameAndId> findByName(UserWhiteList wl, String name) {
		for (UserWhiteListEntry e : wl.getEntries()) {
			NameAndId u = e.getUser();
			if (u != null && u.name() != null && u.name().equalsIgnoreCase(name)) return e;
		}
		return null;
	}

	private static NameAndId resolve(MinecraftServer srv, String name) {
		try {
			ServerPlayer online = srv.getPlayerList().getPlayer(name);
			if (online != null) return new NameAndId(online.getGameProfile());
			Optional<GameProfile> fetched = srv.services().profileResolver().fetchByName(name);
			if (fetched.isPresent()) return new NameAndId(fetched.get());
		} catch (Exception e) {
			VanillaWhitelistMod.LOGGER.debug("[VWL] 解析玩家资料失败: {}", e.toString());
		}
		return NameAndId.createOffline(name);
	}

	private static void result(Peer peer, String id, String action, boolean success, String name, String error) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "whitelist_result");
		if (id != null) o.addProperty("id", id);
		o.addProperty("action", action);
		o.addProperty("success", success);
		if (name != null) o.addProperty("player_name", name);
		if (error != null) o.addProperty("error", error);
		peer.sendText(o.toString());
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}
}
