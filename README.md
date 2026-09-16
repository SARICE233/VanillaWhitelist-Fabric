# VanillaWhitelist（Fabric 版）

[![Minecraft](https://img.shields.io/badge/minecraft-26.2-brightgreen)](https://minecraft.net)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

> ⚠️ **开发中 (Alpha)** — 功能基本可用，但可能存在 Bug 与协议变动。

把服务器状态实时推送到你自己的网站，并支持从网站远程管理白名单。

## 关于三个版本

本项目有三个**相互独立**的实现，功能集与通信协议完全一致，但不是同一个 jar：

| 版本 | 运行平台 |
| --- | --- |
| Paper 插件 | Paper 服务端 |
| Fabric 模组 | Fabric 服务端 |
| NeoForge 模组 | NeoForge 服务端 |

三者同源同名、协议互通。**网站端只需实现一次协议，三种平台都能接。**

完整协议定义见 [`PROTOCOL.md`](./PROTOCOL.md)。

---

## ⚠️ 仅安装在服务端

**这是服务端模组，不要装进客户端。** 它会在服务端上监听一个 WebSocket 端口并读取服务器统计数据；装到客户端没有意义，也会在单人世界里误开监听端口。

mod 元数据里已声明 `"environment": "server"`，客户端不会加载它。

---

## 功能

| 功能 | 说明 |
| --- | --- |
| 📡 WebSocket 服务端 | 本模组作为 WS 服务端，网站主动连接 |
| 🔐 密钥认证 | 连接需验证 `secret`；密钥不合格时拒绝启动 |
| 📊 服务器状态推送 | TPS / MSPT / 内存 / 区块数 / 实体数 / 在线玩家坐标与维度 / 运行时长（默认 30s） |
| 🎮 玩家事件实时推送 | 加入 / 离开（含本次时长）/ 死亡（含死因）/ 换维度 / 达成成就 |
| 🗺️ 世界统计推送 | **按维度分别统计**：区块数、放置与破坏方块数、加入人次、成就数（默认 5min） |
| 📈 玩家统计批量推送 | 在线时长、死亡、击杀、放置/破坏、行走距离、成就数、首次与最近加入（默认 10min） |
| 🏆 成就明细同步 | 每位在线玩家的完整成就 id 列表，定时只推有变化的玩家（默认 10min） |
| ⚠️ 性能告警 | TPS / 内存超阈值时主动告警，分 warning / critical，带冷却 |
| 🔨 远程白名单管理 | 网站可添加/移除白名单，写入真实的白名单数据 |
| 💾 SQLite 持久化 | 累计数据跨重启保留，**不随换世界/换档清零** |
| 📥 离线缓冲 | 网站断线期间消息入队，重连后自动补发 |
| ⌨️ 管理命令 | `/vwl status` `/vwl stats` `/vwl whitelist add|remove` `/vwl reload` |

---

## 环境要求

| 项 | 版本 |
| --- | --- |
| Minecraft | **26.2** |
| Fabric Loader 0.19.5+ / Fabric API | 必需 |
| Java | **25** 或更高 |

> Minecraft 26.x 起官方不再混淆代码，Fabric 已弃用 Yarn 映射。两个模组都使用 Mojang 官方映射。

---

## 安装

1. 安装对应的模组加载器服务端
2. 把本模组的 jar 放进 `mods/`
3. 启动服务器，首次运行会在 `config/` 下生成 `vanillawhitelist.json`

---

## 配置

`config/vanillawhitelist.json`：

```json
{
  "mode": "server",
  "url": "",
  "host": "0.0.0.0",
  "port": 25585,
  "secret": "改成 16 字符以上的随机串",
  "enabled": true,
  "serverId": "main",
  "pushIntervalSeconds": 30,
  "worldStatsIntervalSeconds": 300,
  "playerStatsIntervalSeconds": 600,
  "alertsEnabled": true,
  "tpsWarning": 15.0,
  "tpsCritical": 10.0,
  "memoryPercentWarning": 85.0,
  "memoryPercentCritical": 95.0,
  "alertCooldownSeconds": 300,
  "debug": false
}
```

### ⚠️ 密钥强度要求

以下任一情况，WebSocket 服务**不会启动**并在日志中报错：

- `secret` 为空
- `secret` 仍是出厂默认值 `change-me-to-a-random-string`
- `secret` 长度小于 16 字符

这是为了防止把默认密钥直接暴露到公网。生成随机串：

```bash
openssl rand -hex 24
```

---

## 游戏内命令

| 命令 | 说明 |
| --- | --- |
| `/vwl status` | 查看连接状态与待发队列长度 |
| `/vwl stats` | 立即推送一次全量快照：`server_stats` / `world_stats` / `player_stats_batch` / `player_advancements` |
| `/vwl whitelist add <玩家>` | 手动添加白名单 |
| `/vwl whitelist remove <玩家>` | 手动移除白名单 |
| `/vwl reload` | 重载配置并重启 WebSocket 服务 |

以上均要求管理员权限。

---

## 网站端接入

网站作为 WebSocket **Client** 连接 `ws://<服务器地址>:25585`，连上后立即发送认证：

```json
{ "type": "auth", "id": "auth-1", "secret": "配置里的 secret" }
```

认证成功后你会先收到一条 `server_stats` 和一条 `player_advancements`，随后按配置的间隔持续推送。

**完整消息格式、字段说明与错误码见 [`PROTOCOL.md`](./PROTOCOL.md)。**

仓库里的 `test-ws.html` 是浏览器版调试工具，可直接连上查看推送内容。

---

## 从源码构建

```bash
./gradlew build
```

需要 **JDK 25**。产物在 `build/libs/vanillawhitelist-<版本>.jar`。

开发环境起服务端：

```bash
./gradlew runServer
```

---

## 技术说明

- 通信：手写的最小 RFC6455 实现，无第三方 WebSocket 依赖
- JSON：使用 Minecraft 自带的 Gson
- 持久化：SQLite（`sqlite-jdbc` 打包在嵌套 jar 中）
- 方块放置与成就计数依赖 **Mixin** —— Fabric API 没有这两个事件

---

## 许可

MIT
