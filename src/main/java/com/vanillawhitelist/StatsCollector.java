package com.vanillawhitelist;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/** 采集服务器 / 世界 / 玩家统计 */
public class StatsCollector {

	private static volatile long startedAt = 0L;

	/** 上次告警时间与级别，用于冷却与升级判定 */
	private static volatile long lastAlertAt = 0L;
	private static volatile int lastAlertSeverity = 0;

	/** 服务端启动时调用，用于计算 uptime_seconds */
	public static void markServerStart() {
		startedAt = System.currentTimeMillis();
	}

	private static long uptimeSeconds() {
		return startedAt == 0L ? 0L : (System.currentTimeMillis() - startedAt) / 1000L;
	}

	/**
	 * 最近一次 server_stats 的完整快照。
	 * 空服暂停期间的兜底心跳复用它，避免跨线程去读区块表 / 实体表。
	 */
	private static volatile String cachedServerStats = null;

	/** server_stats：TPS、MSPT、内存、区块、实体、在线玩家 */
	public static JsonObject serverStats(MinecraftServer server, VwlConfig config) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "server_stats");
		o.addProperty("server_id", config.serverId);
		o.addProperty("uptime_seconds", uptimeSeconds());

		long[] ticks = server.getTickTimesNanos();
		double avgNanos = 0.0;
		for (long t : ticks) avgNanos += t;
		avgNanos /= Math.max(1, ticks.length);
		double mspt = avgNanos / 1_000_000.0;
		double tps = mspt <= 0.0 ? 20.0 : Math.min(20.0, 1000.0 / mspt);
		o.addProperty("tps", round(tps));
		o.addProperty("mspt", round(mspt));

		Runtime rt = Runtime.getRuntime();
		o.addProperty("memory_used", (rt.totalMemory() - rt.freeMemory()) / 1048576L);
		o.addProperty("memory_max", rt.maxMemory() / 1048576L);

		long[] counts = countChunksAndEntities(server);
		o.addProperty("loaded_chunks", counts[0]);
		o.addProperty("entity_count", counts[1]);

		var players = server.getPlayerList().getPlayers();
		o.addProperty("online_count", players.size());
		JsonArray arr = new JsonArray();
		for (ServerPlayer p : players) {
			JsonObject po = new JsonObject();
			po.addProperty("name", p.getName().getString());
			po.addProperty("uuid", p.getUUID().toString());
			po.addProperty("dimension", dimensionName(p.level().dimension().identifier()));
			po.addProperty("x", round(p.getX()));
			po.addProperty("y", round(p.getY()));
			po.addProperty("z", round(p.getZ()));
			arr.add(po);
		}
		o.add("players", arr);
		cachedServerStats = o.toString();
		return o;
	}

	/**
	 * 服务端空置暂停期间的兜底心跳。
	 *
	 * 服务器连续 pause-when-empty-seconds（默认 60 秒）没有玩家时会暂停 tick，
	 * ServerTickEvent 不再触发，定时推送会整体停摆。此时用最近一次快照做底，
	 * 只刷新 uptime / 内存 / 在线人数 —— 这几个字段跨线程读取是安全的；
	 * 区块数、实体数、TPS 沿用暂停前的值（暂停的空服本来就不再变化）。
	 *
	 * @return 心跳 JSON；还没有任何快照可用时返回 null
	 */
	public static String pausedHeartbeat(VwlConfig config) {
		String cached = cachedServerStats;
		if (cached == null) return null;
		try {
			JsonObject o = JsonParser.parseString(cached).getAsJsonObject();
			o.addProperty("server_id", config.serverId);
			o.addProperty("uptime_seconds", uptimeSeconds());
			Runtime rt = Runtime.getRuntime();
			o.addProperty("memory_used", (rt.totalMemory() - rt.freeMemory()) / 1048576L);
			o.addProperty("memory_max", rt.maxMemory() / 1048576L);
			o.addProperty("online_count", 0);
			o.add("players", new JsonArray());
			return o.toString();
		} catch (Exception e) {
			return null;
		}
	}

	/** world_stats：每个维度一条 */
	public static JsonObject worldStats(MinecraftServer server, VwlConfig config) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "world_stats");
		JsonArray worlds = new JsonArray();
		for (ServerLevel level : server.getAllLevels()) {
			Identifier id = level.dimension().identifier();
			String name = id.getPath();
			JsonObject w = new JsonObject();
			w.addProperty("name", name);
			w.addProperty("type", dimensionName(id));
			// 没有"已探索区块"的直接 API，用当前已加载区块数近似（与 Paper 版一致）
			w.addProperty("explored_chunks", level.getChunkSource().getLoadedChunksCount());
			w.addProperty("total_blocks_placed", Tracker.worldPlaced(name));
			w.addProperty("total_blocks_broken", Tracker.worldBroken(name));
			w.addProperty("total_players_joined", Tracker.totalJoins());
			w.addProperty("total_advancements", Tracker.totalAdvancements());
			worlds.add(w);
		}
		o.add("worlds", worlds);
		return o;
	}

	/** player_stats_batch：当前在线玩家的统计 */
	public static JsonObject playerStatsBatch(MinecraftServer server, VwlConfig config) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "player_stats_batch");
		JsonArray arr = new JsonArray();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			UUID id = p.getUUID();
			ServerStatsCounter st = p.getStats();
			JsonObject po = new JsonObject();
			po.addProperty("uuid", id.toString());
			po.addProperty("name", p.getName().getString());
			po.addProperty("playtime_seconds", Tracker.playtime(id));
			po.addProperty("deaths", st.getValue(Stats.CUSTOM.get(Stats.DEATHS)));
			po.addProperty("kills", st.getValue(Stats.CUSTOM.get(Stats.MOB_KILLS))
					+ st.getValue(Stats.CUSTOM.get(Stats.PLAYER_KILLS)));
			po.addProperty("blocks_placed", Tracker.blocksPlaced(id));
			po.addProperty("blocks_broken", Tracker.blocksBroken(id));
			po.addProperty("distance_walked", Math.round(st.getValue(Stats.CUSTOM.get(Stats.WALK_ONE_CM))) / 100.0);
			po.addProperty("achievements_count", Tracker.advancements(id));
			po.addProperty("first_join", Tracker.firstJoin(id));
			po.addProperty("last_join", Tracker.lastJoin(id));
			arr.add(po);
		}
		o.add("players", arr);
		return o;
	}

	/**
	 * 性能告警检查。
	 * 超过阈值且不在冷却期内时返回告警消息，否则返回 null。
	 * 严重级别升级（warning → critical）会立即告警，不受冷却限制。
	 */
	public static JsonObject checkAlerts(MinecraftServer server, VwlConfig config) {
		if (!config.alertsEnabled) return null;

		JsonArray alerts = new JsonArray();
		int severity = 0;

		long[] ticks = server.getTickTimesNanos();
		double avgNanos = 0.0;
		for (long t : ticks) avgNanos += t;
		avgNanos /= Math.max(1, ticks.length);
		double mspt = avgNanos / 1_000_000.0;
		double tps = mspt <= 0.0 ? 20.0 : Math.min(20.0, 1000.0 / mspt);

		if (config.tpsCritical > 0 && tps < config.tpsCritical) {
			severity = 2;
			alerts.add(alertItem("tps", tps, config.tpsCritical));
		} else if (config.tpsWarning > 0 && tps < config.tpsWarning) {
			severity = Math.max(severity, 1);
			alerts.add(alertItem("tps", tps, config.tpsWarning));
		}

		Runtime rt = Runtime.getRuntime();
		long memUsed = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
		long memMax = rt.maxMemory() / 1048576L;
		double memPct = memMax <= 0L ? 0.0 : (memUsed * 100.0 / memMax);

		if (config.memoryPercentCritical > 0 && memPct > config.memoryPercentCritical) {
			severity = 2;
			alerts.add(alertItem("memory_percent", memPct, config.memoryPercentCritical));
		} else if (config.memoryPercentWarning > 0 && memPct > config.memoryPercentWarning) {
			severity = Math.max(severity, 1);
			alerts.add(alertItem("memory_percent", memPct, config.memoryPercentWarning));
		}

		if (severity == 0) {
			lastAlertSeverity = 0;
			return null;
		}

		long now = System.currentTimeMillis();
		boolean escalated = severity > lastAlertSeverity;
		if (!escalated && now - lastAlertAt < config.alertCooldownSeconds * 1000L) return null;

		lastAlertAt = now;
		lastAlertSeverity = severity;

		JsonObject o = new JsonObject();
		o.addProperty("type", "performance_alert");
		o.addProperty("severity", severity == 2 ? "critical" : "warning");
		o.add("alerts", alerts);
		o.addProperty("tps", round(tps));
		o.addProperty("mspt", round(mspt));
		o.addProperty("memory_used", memUsed);
		o.addProperty("memory_max", memMax);
		return o;
	}

	private static JsonObject alertItem(String metric, double value, double threshold) {
		JsonObject m = new JsonObject();
		m.addProperty("metric", metric);
		m.addProperty("value", round(value));
		m.addProperty("threshold", threshold);
		return m;
	}

	/** 上次推送时各玩家的成就指纹，用于「只推变化过的玩家」 */
	private static final java.util.Map<java.util.UUID, Integer> ADV_SIG = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * 成就明细：每位在线玩家的完整成就 id 列表。
	 * 数据直接读玩家自身的成就进度，无需额外持久化。
	 *
	 * @param onlyChanged true 时只包含自上次推送以来有变化的玩家（用于定时推送）
	 *                    false 时包含全部在线玩家（用于网站刚连上时给一份完整状态）
	 */
	public static JsonObject playerAdvancements(MinecraftServer server, VwlConfig config, boolean onlyChanged) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "player_advancements");
		JsonArray arr = new JsonArray();
		java.util.Collection<net.minecraft.advancements.AdvancementHolder> all =
				server.getAdvancements().getAllAdvancements();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			JsonArray ids = new JsonArray();
			for (net.minecraft.advancements.AdvancementHolder holder : all) {
				if (p.getAdvancements().getOrStartProgress(holder).isDone()) {
					ids.add(holder.id().toString());
				}
			}
			int sig = ids.toString().hashCode();
			Integer prev = ADV_SIG.put(p.getUUID(), sig);
			if (onlyChanged && prev != null && prev == sig) continue;
			JsonObject po = new JsonObject();
			po.addProperty("uuid", p.getUUID().toString());
			po.addProperty("name", p.getName().getString());
			po.addProperty("total", ids.size());
			po.add("advancements", ids);
			arr.add(po);
		}
		o.add("players", arr);
		return o;
	}

	private static long[] countChunksAndEntities(MinecraftServer server) {
		long chunks = 0;
		long entities = 0;
		for (ServerLevel level : server.getAllLevels()) {
			chunks += level.getChunkSource().getLoadedChunksCount();
			for (Entity ignored : level.getAllEntities()) entities++;
		}
		return new long[] { chunks, entities };
	}

	private static double round(double v) {
		return Math.round(v * 10.0) / 10.0;
	}

	/** minecraft:overworld / the_nether / the_end → overworld / the_nether / the_end */
	public static String dimensionName(Identifier id) {
		String path = id.getPath();
		return switch (path) {
			case "overworld" -> "overworld";
			case "the_nether" -> "the_nether";
			case "the_end" -> "the_end";
			default -> path;
		};
	}
}
