# VanillaWhitelist 通信协议规范

> **协议版本：v1** ｜ 三端共用契约
>
> 本文档是 **Paper 插件 / Fabric 模组 / NeoForge 模组** 三者与网站后端之间的**唯一接口契约**。
> 三个实现相互独立、各自演进；本协议是它们之间唯一的强约束。

## 修改规则（重要）

1. 任何字段的增删改，**必须三端同步实现**，不允许只改一端。
2. **不兼容改动**必须递增 `protocol_version`；纯新增可选字段可不递增。
3. 改协议时，三个仓库的 `PROTOCOL.md` 必须一起更新。
4. 发布前跑 `check-protocol.ps1` 校验三端一致性。

---

## 0. 实现标识

| 实现 | `impl` 值 | 分发渠道 | 运行平台 |
| --- | --- | --- | --- |
| Paper 插件 | `paper` | Hangar / SpigotMC | Paper 服务端 |
| Fabric 模组 | `fabric` | Modrinth / CurseForge | Fabric 服务端 |
| NeoForge 模组 | `neoforge` | Modrinth / CurseForge | NeoForge 服务端 |

三端**功能集一致、协议一致、版本号一致**，仅实现方式与分发渠道不同。

---

## 1. 传输层

- 网站作为 WebSocket **Client**，主动连接 `ws://<host>:<port>`（默认 `25585`）。
- 插件/模组作为 WebSocket **Server**。
- 同一时刻只允许 **1 个活跃连接**；新连接进入时旧连接被关闭。
- 所有消息均为 UTF-8 JSON **文本帧**。

---

## 1.5 两种连接模式

协议只有一套，但「谁发起 TCP 连接」可以有两种，用配置项 `mode` 切换：

| 模式 | 谁连谁 | 是否需要开放端口 |
| --- | --- | --- |
| `server`（默认） | 网站主动连服务端 | **需要**（公网 IP / 端口映射 / 内网穿透） |
| `client` | 服务端主动连网站 | **不需要**，像普通客户端一样往外连 |

**消息格式、字段、错误码、方向语义，两种模式完全一致。** 差别只有：

1. 谁发起连接；
2. `auth` 由发起方发送 —— `server` 模式下网站发、服务端校验；`client` 模式下服务端发、网站校验。

### 出站模式（`client`）的额外约定

- 服务端发出的 `auth` 里**额外携带** `protocol_version` / `impl` / `impl_version`，方便网站识别对端。网站可以忽略这些字段。
- 服务端的 WebSocket 帧是**客户端帧**（带掩码），网站端实现需按 RFC6455 客户端帧解析。
- 断线后服务端会自动重连（指数退避，最长 60 秒），并在重连认证成功后补发离线缓冲的消息。
- 网站端需要跑一个**长期在线的 WebSocket 服务端**。若后端是纯 PHP 共享主机之类跑不了常驻进程，则只能用 `server` 模式。

### 为什么提供出站模式

`server` 模式要求**每台游戏服都暴露一个入站端口**。对家庭宽带、内网、以及需要内网穿透的场景，这是持续的成本和故障源。
出站模式把这个前提直接删掉 —— 服务端不监听任何端口。

---

## 2. 协议版本与实现标识

`protocol_version` 当前为 **1**。

- 在 `auth_result` 中返回，并在**每一条出站消息**中携带（含 `pong` 与 `whitelist_result`）。
- 网站应据此判断对端能力；版本不匹配时给出明确提示，而不是静默出错。

`auth_result` 额外返回：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `protocol_version` | int | 协议版本，当前 `1` |
| `impl` | string | `paper` / `fabric` / `neoforge` |
| `impl_version` | string | 实现自身版本号，如 `1.0.4-alpha` |

---

## 3. 连接生命周期

```
网站启动 → 连接 ws://server:25585
         → 等待 auth（10 秒内未收到则断开）
         → 校验 secret → 返回 auth_result
         → 认证成功：先补发离线期间缓冲的消息，再立即推一次 server_stats
         → 双向通信
         → 网站断开 → 清理会话
```

- 认证成功前，**忽略所有其他消息**。
- 认证失败 → 立即关闭连接。

---

## 4. 消息一览

| 方向 | type | 触发 |
| --- | --- | --- |
| 网站 → 服务端 | `auth` | 连接后立即发送 |
| 服务端 → 网站 | `auth_result` | 收到 auth 后 |
| 网站 → 服务端 | `ping` | 每 15 秒 |
| 服务端 → 网站 | `pong` | 收到 ping 后立即 |
| 网站 → 服务端 | `whitelist_add` | 添加白名单 |
| 网站 → 服务端 | `whitelist_remove` | 移除白名单 |
| 服务端 → 网站 | `whitelist_result` | 白名单操作结果 |
| 服务端 → 网站 | `server_stats` | 定时（默认 30s）+ 认证后立即一次 |
| 服务端 → 网站 | `player_event` | 玩家加入/离开/死亡/换维度 |
| 服务端 → 网站 | `world_stats` | 定时（默认 5min） |
| 服务端 → 网站 | `player_stats_batch` | 定时（默认 10min） |
| 服务端 → 网站 | `performance_alert` | TPS / 内存超阈值时（默认 30s 检查一次） |
| 服务端 → 网站 | `player_advancements` | 定时（默认 10min）+ 认证后立即一次 |

---

## 5. 认证

```json
// ← 网站发送
{ "type": "auth", "id": "auth-1", "secret": "配置里的 secret" }

// → 服务端响应（成功）
{
  "type": "auth_result",
  "id": "auth-1",
  "success": true,
  "protocol_version": 1,
  "impl": "fabric",
  "impl_version": "1.0.4-alpha"
}

// → 服务端响应（失败）
{ "type": "auth_result", "id": "auth-1", "success": false, "error": "INVALID_SECRET" }
```

### 密钥强度要求（三端一致）

满足以下**任一**条件时，服务端**拒绝启动 WebSocket** 并在日志中报错：

- `secret` 为空或空白
- `secret` 仍等于出厂默认值 `change-me-to-a-random-string`
- `secret` 长度小于 16 字符

---

## 6. 心跳

```json
// ← 网站发送
{ "type": "ping" }

// → 服务端响应
{ "type": "pong", "protocol_version": 1 }
```

网站每 15 秒发一次 ping；10 秒未收到 pong 则主动断开。
服务端侧也做半开连接检测：30 秒无任何数据则发一次 WebSocket ping 探测，再 30 秒无响应即断开。

---

## 7. 白名单操作

```json
// ← 网站发送
{ "type": "whitelist_add", "id": "wl-1", "player_name": "Steve" }
{ "type": "whitelist_remove", "id": "wl-2", "player_name": "Steve" }

// → 服务端响应
{
  "type": "whitelist_result",
  "id": "wl-1",
  "action": "whitelist_add",
  "success": true,
  "player_name": "Steve"
}
```

失败时追加 `"error": "<错误码>"`。

### 玩家名校验（三端一致）

`player_name` 必须是 3–16 个字符，且只含 `A-Z a-z 0-9 _`。

### 错误码

| 错误码 | 含义 |
| --- | --- |
| `INVALID_SECRET` | 认证密钥错误 |
| `INVALID_JSON` | 收到非法 JSON |
| `MISSING_TYPE` | 消息缺少 `type` 字段 |
| `NOT_AUTHENTICATED` | 未认证就发操作请求 |
| `PLAYER_NAME_EMPTY` | `player_name` 为空 |
| `PLAYER_NAME_INVALID_LENGTH` | 玩家名长度不在 3–16 |
| `PLAYER_NAME_INVALID_CHARS` | 玩家名含非法字符 |
| `PLAYER_LOOKUP_FAILED` | 无法解析该玩家的资料 |
| `ALREADY_WHITELISTED` | 已在白名单中 |
| `NOT_WHITELISTED` | 不在白名单中（移除时） |
| `WHITELIST_DISABLED` | 服务端未开启白名单 |
| `INTERNAL_ERROR` | 内部错误 |

---

## 8. `server_stats`（默认 30 秒，认证后立即一次）

```json
{
  "type": "server_stats",
  "protocol_version": 1,
  "server_id": "main",
  "tps": 19.8,
  "mspt": 12.3,
  "memory_used": 2048,
  "memory_max": 4096,
  "loaded_chunks": 12500,
  "entity_count": 3420,
  "online_count": 15,
  "uptime_seconds": 86400,
  "players": [
    { "name": "Steve", "uuid": "...", "dimension": "overworld", "x": 100.5, "y": 64.0, "z": -200.3 }
  ]
}
```

- `dimension` 归一化取值：`overworld` / `the_nether` / `the_end`（其他维度取路径名）。
- `server_id` 可配置（Paper 见 `config.yml` 的 `server.id`；模组见 `serverId`），用于区分多台服务器。

---

## 9. `player_event`（实时）

```json
{ "type": "player_event", "protocol_version": 1, "event": "join",  "player_name": "Steve", "player_uuid": "..." }
{ "type": "player_event", "protocol_version": 1, "event": "leave", "player_name": "Steve", "player_uuid": "...", "playtime_seconds": 3600 }
{ "type": "player_event", "protocol_version": 1, "event": "death", "player_name": "Steve", "player_uuid": "...", "cause": "fall" }
{ "type": "player_event", "protocol_version": 1, "event": "dimension_change", "player_name": "Steve", "player_uuid": "...", "from": "overworld", "to": "the_nether" }
```

---

## 10. `world_stats`（默认 5 分钟）

```json
{
  "type": "world_stats",
  "protocol_version": 1,
  "worlds": [
    {
      "name": "world",
      "type": "overworld",
      "explored_chunks": 12500,
      "total_blocks_placed": 1500000,
      "total_blocks_broken": 800000,
      "total_players_joined": 120,
      "total_advancements": 340
    }
  ]
}
```

- `total_blocks_placed` / `total_blocks_broken` 是**按该世界分别统计**的，不是全局总数。
- `total_players_joined` / `total_advancements` 是**服务器级全局值**，每个世界条目相同。
- `explored_chunks` 没有直接 API，取当前已加载区块数近似。
- `type` 是归一化维度名，网站应优先按它归类；`name` 是平台各自的世界标识（Paper 为世界文件夹名，模组为维度路径名），跨平台不保证一致。

---

## 11. `player_stats_batch`（默认 10 分钟）

```json
{
  "type": "player_stats_batch",
  "protocol_version": 1,
  "players": [
    {
      "uuid": "...",
      "name": "Steve",
      "playtime_seconds": 72000,
      "deaths": 15,
      "kills": 230,
      "blocks_placed": 50000,
      "blocks_broken": 30000,
      "distance_walked": 125000.5,
      "achievements_count": 25,
      "first_join": "2026-01-15T10:00:00Z",
      "last_join": "2026-07-06T14:30:00Z"
    }
  ]
}
```

当前仅包含**在线玩家**。`kills` = 击杀生物 + 击杀玩家。

### 数据来源（三端一致）

| 字段 | 来源 |
| --- | --- |
| `playtime_seconds` | 自行按 join/quit 累计并持久化（不用原版 `play_time`，1.21+ 不可靠） |
| `deaths` / `kills` / `distance_walked` | 原版统计（`deaths` / `mob_kills`+`player_kills` / `walk_one_cm`） |
| `blocks_placed` | **原版没有此统计**，由事件累加 |
| `blocks_broken` | 事件累加 |
| `achievements_count` | 事件累加（已过滤配方解锁） |
| `first_join` / `last_join` | 自行持久化 |

---

## 12. `performance_alert`（TPS / 内存超阈值时）

```json
{
  "type": "performance_alert",
  "protocol_version": 1,
  "severity": "warning",
  "alerts": [
    { "metric": "tps", "value": 12.3, "threshold": 15.0 }
  ],
  "tps": 12.3,
  "mspt": 81.2,
  "memory_used": 3700,
  "memory_max": 4096
}
```

- `severity`：`warning` 或 `critical`，取所有触发项里最高的那个。
- `metric` 取值：`tps` / `memory_percent`。
- 检查与 `server_stats` 同频（默认 30 秒一次）。
- **冷却**：同类告警在 `alertCooldownSeconds` 内不重复推送；但**严重级别升级（warning → critical）会立即推送**，不受冷却限制。
- 指标恢复正常后再次恶化，会重新告警。

---

## 13. `player_advancements`（默认 10 分钟 + 认证后立即一次）

```json
{
  "type": "player_advancements",
  "protocol_version": 1,
  "players": [
    {
      "uuid": "...",
      "name": "Steve",
      "total": 26,
      "advancements": ["minecraft:story/root", "minecraft:story/mine_diamond"]
    }
  ]
}
```

- `advancements` 是该玩家**完整的**已达成成就 id 列表，网站可直接整体替换本地状态。
- 定时推送时**只包含自上次推送以来有变化的玩家**；一个都没变则不发这条消息。
- 网站刚认证成功时会收到一次全量（含所有在线玩家），作为基准。
- 数据直接读玩家自身的成就进度，不需要额外持久化。

对应的实时事件（`player_event` 的第五种）：

```json
{ "type": "player_event", "protocol_version": 1, "event": "advancement",
  "player_name": "Steve", "player_uuid": "...", "advancement": "minecraft:story/mine_diamond" }
```

---

## 14. 离线缓冲与补发

- 网站断线期间，推送消息写入本地队列（上限 1000 条，超出丢弃最旧的），**持久化到 SQLite**。
- 认证成功后自动补发全部缓冲消息，随后立即推送一次最新 `server_stats`。
- 队列长度可通过 `/vwl status` 查看。

---

## 15. 配置项

**Paper**（`plugins/VanillaWhitelist/config.yml`，YAML）

```yaml
websocket:
  port: 25585
  secret: "改成 16 字符以上的随机串"
  enabled: true
  host: "0.0.0.0"
server:
  id: "main"
stats:
  push-interval-seconds: 30
  world-stats-interval-seconds: 300
  player-stats-interval-seconds: 600
alerts:
  enabled: true
  tps-warning: 15.0
  tps-critical: 10.0
  memory-percent-warning: 85.0
  memory-percent-critical: 95.0
  cooldown-seconds: 300
debug: false
```

**Fabric / NeoForge**（`config/vanillawhitelist.json`，JSON）

```json
{
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

---

> ⚠️ **`port` 不能与游戏端口（`server-port`）相同。**
>
> 两者相同时，WebSocket 会**拒绝启动**并打出错误日志；游戏服务端本身可正常启动。
>
> 这是刻意为之：早期版本把 WebSocket 放在 `ServerStarting` 阶段启动（早于 MC 绑定端口），
> 配成同一端口会**抢占游戏端口**，导致 Minecraft 自身绑定失败、**整个服务端起不来**。
>
> 现已改为在服务端启动完成后才启动 WebSocket，并加了端口冲突检查。

## 16. 游戏内命令

| 命令 | Paper | 模组 | 说明 |
| --- | --- | --- | --- |
| `/vwl status` | ✅ | ✅ | 连接状态与待发队列长度 |
| `/vwl stats` | ✅ | ✅ | 立即推送一次 `server_stats` |
| `/vwl whitelist add\|remove <玩家>` | ✅ | ✅ | 手动增删白名单 |
| `/vwl reload` | ✅ | ✅ | 重载配置并重启 WebSocket |

Paper 的主命令为 `/vanillawhitelist`（别名 `/vwl`）；模组为 `/vwl`。均要求管理员权限。

---

## 17. 安全须知

1. `secret` 不要提交到公开仓库；必须 16 字符以上且不是出厂默认值。
2. WebSocket 端口建议只在内网暴露，或用防火墙限制来源。
3. 只允许一个活跃连接，防止多网站实例冲突。
4. 服务端对 `player_name` 做合法性校验后才操作白名单。
