package com.vanillawhitelist;

import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 追踪玩家与世界统计。计数在内存中累加，定时批量落库，避免每个方块事件都写一次数据库。
 */
public class Tracker {
	private static Database db;

	private static final Map<UUID, Long> JOIN_TIMES = new ConcurrentHashMap<>();
	private static final Map<UUID, String> LAST_DIM = new ConcurrentHashMap<>();
	/** 会话内累计：[0]=放置 [1]=破坏 [2]=成就 */
	private static final Map<UUID, long[]> SESSION = new ConcurrentHashMap<>();

	/** 内存权威计数（首次访问时从数据库载入） */
	private static final Map<String, Long> TOTALS = new ConcurrentHashMap<>();
	private static final Set<String> DIRTY = ConcurrentHashMap.newKeySet();

	public static void init(Database database) { db = database; }

	private static String pkey(UUID id, String field) { return "player_" + id + "_" + field; }

	private static long total(String key) {
		return TOTALS.computeIfAbsent(key, k -> (db != null && db.isReady()) ? db.getLong(k, 0L) : 0L);
	}

	private static void add(String key, long delta) {
		TOTALS.merge(key, delta, Long::sum);
		DIRTY.add(key);
	}

	/** 把脏数据批量写回数据库 */
	public static void flush() {
		if (db == null || !db.isReady() || DIRTY.isEmpty()) return;
		Map<String, Long> batch = new HashMap<>();
		for (String k : new ArrayList<>(DIRTY)) {
			if (DIRTY.remove(k)) {
				Long v = TOTALS.get(k);
				if (v != null) batch.put(k, v);
			}
		}
		if (!batch.isEmpty()) db.bulkUpsertLong(batch);
	}

	// ---------- 事件入口 ----------

	public static void onJoin(ServerPlayer p) {
		UUID id = p.getUUID();
		JOIN_TIMES.put(id, System.currentTimeMillis());
		LAST_DIM.put(id, StatsCollector.dimensionName(p.level().dimension().identifier()));
		SESSION.put(id, new long[3]);
		long now = System.currentTimeMillis();
		if (total(pkey(id, "first_join")) == 0L) add(pkey(id, "first_join"), now);
		TOTALS.put(pkey(id, "last_join"), now);
		DIRTY.add(pkey(id, "last_join"));
		add("total_joins", 1L);
	}

	/** @return 本次会话在线秒数 */
	public static long onLeave(ServerPlayer p) {
		UUID id = p.getUUID();
		Long t = JOIN_TIMES.remove(id);
		LAST_DIM.remove(id);
		long secs = t == null ? 0L : (System.currentTimeMillis() - t) / 1000L;
		long[] s = SESSION.remove(id);
		add(pkey(id, "playtime"), secs);
		if (s != null) {
			add(pkey(id, "blocks_placed"), s[0]);
			add(pkey(id, "blocks_broken"), s[1]);
			add(pkey(id, "advancements"), s[2]);
		}
		return secs;
	}

	public static void onBlockPlace(ServerPlayer p, String dimension) {
		long[] s = SESSION.get(p.getUUID());
		if (s != null) s[0]++;
		add("world_" + dimension + "_blocks_placed", 1L);
	}

	public static void onBlockBreak(ServerPlayer p, String dimension) {
		long[] s = SESSION.get(p.getUUID());
		if (s != null) s[1]++;
		add("world_" + dimension + "_blocks_broken", 1L);
	}

	public static void onAdvancement(ServerPlayer p) {
		long[] s = SESSION.get(p.getUUID());
		if (s != null) s[2]++;
		add("total_advancements", 1L);
	}

	/** 维度变化检测，返回新维度；无变化返回 null */
	public static String checkDimensionChange(ServerPlayer p) {
		String now = StatsCollector.dimensionName(p.level().dimension().identifier());
		String prev = LAST_DIM.put(p.getUUID(), now);
		return (prev != null && !prev.equals(now)) ? now : null;
	}

	public static String previousDimension(ServerPlayer p) {
		return LAST_DIM.getOrDefault(p.getUUID(), "overworld");
	}

	// ---------- 读取 ----------

	public static long playtime(UUID id) { return total(pkey(id, "playtime")); }
	public static long blocksPlaced(UUID id) { return total(pkey(id, "blocks_placed")); }
	public static long blocksBroken(UUID id) { return total(pkey(id, "blocks_broken")); }
	public static long advancements(UUID id) { return total(pkey(id, "advancements")); }
	public static long totalJoins() { return total("total_joins"); }
	public static long totalAdvancements() { return total("total_advancements"); }
	public static long worldPlaced(String dim) { return total("world_" + dim + "_blocks_placed"); }
	public static long worldBroken(String dim) { return total("world_" + dim + "_blocks_broken"); }

	public static String isoTime(String key) {
		long ms = total(key);
		return ms <= 0L ? "" : Instant.ofEpochMilli(ms).toString();
	}

	public static String firstJoin(UUID id) { return isoTime(pkey(id, "first_join")); }
	public static String lastJoin(UUID id) { return isoTime(pkey(id, "last_join")); }
}
