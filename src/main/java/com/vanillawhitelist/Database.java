package com.vanillawhitelist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** SQLite 持久化。表结构与 Paper 版保持一致：kv_store + message_queue */
public class Database {
	/** 消息队列上限，超出后丢弃最旧的 */
	private static final int MAX_QUEUE = 1000;

	private final Path file;
	private final Object lock = new Object();
	private Connection conn;

	public Database(Path dir) {
		this.file = dir.resolve("data.db");
	}

	/** @return 是否成功打开（失败时降级为无持久化，不影响插件其余功能） */
	public boolean open() {
		synchronized (lock) {
			try {
				if (file.getParent() != null) Files.createDirectories(file.getParent());
				Class.forName("org.sqlite.JDBC");
				conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
				createTables();
				return true;
			} catch (Throwable t) {
				VanillaWhitelistMod.LOGGER.error("[VWL] 数据库打开失败，本次运行不做持久化", t);
				conn = null;
				return false;
			}
		}
	}

	public void close() {
		synchronized (lock) {
			try {
				if (conn != null) conn.close();
			} catch (Exception ignored) {
			}
			conn = null;
		}
	}

	public boolean isReady() {
		synchronized (lock) {
			return conn != null;
		}
	}

	private void createTables() throws SQLException {
		try (Statement st = conn.createStatement()) {
			st.executeUpdate("CREATE TABLE IF NOT EXISTS kv_store (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
			st.executeUpdate("CREATE TABLE IF NOT EXISTS message_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, message TEXT NOT NULL)");
			st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_msg_created ON message_queue(created_at)");
		}
	}

	public long getLong(String key, long defaultValue) {
		synchronized (lock) {
			if (conn == null) return defaultValue;
			try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM kv_store WHERE key = ?")) {
				ps.setString(1, key);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) return Long.parseLong(rs.getString(1));
				}
			} catch (Exception e) {
				VanillaWhitelistMod.LOGGER.debug("[VWL] getLong 失败: {}", e.toString());
			}
			return defaultValue;
		}
	}

	public void setLong(String key, long value) {
		synchronized (lock) {
			if (conn == null) return;
			try (PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO kv_store(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
				ps.setString(1, key);
				ps.setString(2, Long.toString(value));
				ps.executeUpdate();
			} catch (Exception e) {
				VanillaWhitelistMod.LOGGER.debug("[VWL] setLong 失败: {}", e.toString());
			}
		}
	}

	public long incrementLong(String key, long delta) {
		synchronized (lock) {
			long v = getLong(key, 0L) + delta;
			setLong(key, v);
			return v;
		}
	}

	/** 批量写入（单事务） */
	public void bulkUpsertLong(Map<String, Long> entries) {
		if (entries == null || entries.isEmpty()) return;
		synchronized (lock) {
			if (conn == null) return;
			try {
				boolean auto = conn.getAutoCommit();
				conn.setAutoCommit(false);
				try (PreparedStatement ps = conn.prepareStatement(
						"INSERT INTO kv_store(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
					for (Map.Entry<String, Long> e : entries.entrySet()) {
						ps.setString(1, e.getKey());
						ps.setString(2, Long.toString(e.getValue()));
						ps.addBatch();
					}
					ps.executeBatch();
				}
				conn.commit();
				conn.setAutoCommit(auto);
			} catch (Exception e) {
				VanillaWhitelistMod.LOGGER.debug("[VWL] bulkUpsert 失败: {}", e.toString());
				try { conn.rollback(); } catch (Exception ignored) { }
			}
		}
	}

	public void enqueueMessage(String json) {
		synchronized (lock) {
			if (conn == null) return;
			try (PreparedStatement ps = conn.prepareStatement("INSERT INTO message_queue(created_at, message) VALUES(?, ?)")) {
				ps.setLong(1, System.currentTimeMillis());
				ps.setString(2, json);
				ps.executeUpdate();
				// 超出上限则删除最旧的
				try (PreparedStatement del = conn.prepareStatement(
						"DELETE FROM message_queue WHERE id NOT IN (SELECT id FROM message_queue ORDER BY id DESC LIMIT ?)")) {
					del.setInt(1, MAX_QUEUE);
					del.executeUpdate();
				}
			} catch (Exception e) {
				VanillaWhitelistMod.LOGGER.debug("[VWL] enqueue 失败: {}", e.toString());
			}
		}
	}

	public List<String> dequeueAll() {
		List<String> out = new ArrayList<>();
		synchronized (lock) {
			if (conn == null) return out;
			try (Statement st = conn.createStatement();
				 ResultSet rs = st.executeQuery("SELECT message FROM message_queue ORDER BY id ASC")) {
				while (rs.next()) out.add(rs.getString(1));
				st.executeUpdate("DELETE FROM message_queue");
			} catch (Exception e) {
				VanillaWhitelistMod.LOGGER.debug("[VWL] dequeue 失败: {}", e.toString());
			}
		}
		return out;
	}

	public int queueSize() {
		synchronized (lock) {
			if (conn == null) return 0;
			try (Statement st = conn.createStatement();
				 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM message_queue")) {
				if (rs.next()) return rs.getInt(1);
			} catch (Exception ignored) { }
			return 0;
		}
	}
}
