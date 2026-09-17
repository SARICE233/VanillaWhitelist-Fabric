package com.vanillawhitelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 配置文件，落盘为 config/vanillawhitelist.json */
public class VwlConfig {
	/** 出厂默认密钥。仍在用它时拒绝启动 WebSocket（与 Paper 行为一致） */
	public static final String DEFAULT_SECRET = "change-me-to-a-random-string";

	/**
	 * 连接模式：
	 * - server = 本端监听端口，网站主动连过来（默认，需要开放端口）
	 * - client = 本端主动连网站，不需要开放任何入站端口
	 */
	public String mode = "server";

	/** 出站模式（mode=client）下要连接的网站地址，如 wss://example.com/vwl */
	public String url = "";

	public String host = "0.0.0.0";
	public int port = 25585;
	public String secret = "change-me-to-a-random-string";
	public boolean enabled = true;
	public String serverId = "main";
	public int pushIntervalSeconds = 30;
	/**
	 * 出站模式（mode=client）下的保活间隔（秒）。
	 * 本端按这个间隔发 WebSocket ping 帧，必须**明显小于**网站的连接空闲超时，
	 * 否则网站会把"太久没收到数据"的连接切断，表现为连上几秒就重连一次。
	 */
	public int keepAliveSeconds = 5;
	public int worldStatsIntervalSeconds = 300;
	public int playerStatsIntervalSeconds = 600;
	public boolean debug = false;

	// ── 性能告警 ──────────────────────────────────────────────
	/** 是否启用性能告警 */
	public boolean alertsEnabled = true;
	/** TPS 低于此值告警（0 = 关闭该项） */
	public double tpsWarning = 15.0;
	/** TPS 低于此值升级为 critical */
	public double tpsCritical = 10.0;
	/** 内存占用百分比高于此值告警 */
	public double memoryPercentWarning = 85.0;
	/** 内存占用百分比高于此值升级为 critical */
	public double memoryPercentCritical = 95.0;
	/** 同类告警的冷却秒数（严重级别升级不受此限制） */
	public int alertCooldownSeconds = 300;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** 密钥强度校验：非空、非默认值、且不短于 16 字符 */
	public boolean isSecretStrong() {
		return secret != null && !secret.isBlank()
				&& !secret.equals(DEFAULT_SECRET)
				&& secret.length() >= 16;
	}

	/**
	 * 把配置写回 config/vanillawhitelist.json。
	 * 供运行时开关（/vwl on|off）使用，保证「指令切换」与「手改配置」是同一份真相。
	 * @return 是否写入成功
	 */
	public static boolean save(Path dir, VwlConfig cfg) {
		Path file = dir.resolve("vanillawhitelist.json");
		try {
			Files.createDirectories(dir);
			try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(cfg, w);
			}
			return true;
		} catch (Exception e) {
			VanillaWhitelistMod.LOGGER.error("[VWL] 配置写入失败", e);
			return false;
		}
	}

	public static VwlConfig load(Path dir) {
		Path file = dir.resolve("vanillawhitelist.json");
		VwlConfig cfg = new VwlConfig();
		try {
			if (Files.exists(file)) {
				try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					VwlConfig loaded = GSON.fromJson(r, VwlConfig.class);
					if (loaded != null) {
						cfg = loaded;
					}
				}
			} else {
				Files.createDirectories(dir);
				try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
					GSON.toJson(cfg, w);
				}
			}
		} catch (Exception e) {
			VanillaWhitelistMod.LOGGER.error("[VWL] 配置读写失败，使用默认值", e);
		}
		return cfg;
	}
}
