# Ultra Cell / 究极元件

A high-capacity AE2 storage cell addon for Minecraft 1.21.1 · NeoForge 21.1.x.

Minecraft 1.21.1 · NeoForge 21.1.x 的大容量 AE2 存储元件扩展。

Provides FE, fluid, and chemical storage cells with massive capacity, integrated into the AE2 network.

提供 FE、流体、化学品三类超大容量存储元件，走 AE2 网络。

## Dependencies / 依赖

- AppliedFlux
- MEGA Cells
- Applied Mekanistics (appmek)

## Cell Tiers / 元件等级

| Tier / 等级 | FE | Fluid Type Slots / 流体类型位 | Chemical Type Slots / 化学品类型位 |
|---|---|---|---|
| Hongmeng / 鸿蒙 | 2^128−1 | 128 | 128 |
| Wuji / 无极 | 2^192−1 | 512 | 512 |

Per-type capacity = total capacity / type slots.

每类型容量上限 = 总容量 / 类型位。

## Commands / 命令

| Command / 命令 | Permission / 权限 | Description / 说明 |
|---|---|---|
| `/ultracell getuuid` | Everyone / 所有人 | Read the UUID of the held cell; click to copy / 读手上元件的 UUID，可点击复制 |
| `/ultracell recover <uuid>` | Everyone / 所有人 | Recover the cell with the given UUID / 恢复指定 UUID 的元件 |
| `/ultracell scan` | Everyone / 所有人 | Scan for orphan data files / 扫描孤儿数据文件 |
| `/ultracell trade <player>` | Everyone / 所有人 | Transfer cell ownership to an online player / 转移元件归属给在线玩家 |
| `/ultracell transfer <uuid> <player>` | OP | Force-transfer ownership; works on offline players / 强制转移归属，可对离线玩家 |
| `/ultracell debug` | Everyone / 所有人 | Debug utilities / 调试工具 |

All subcommands live under `/ultracell`. Every one of them is available to all players except `transfer`, which requires OP.

所有子命令都挂在 `/ultracell` 下。除 `transfer` 需要 OP 外，其余对所有玩家开放。

- `scan` asks for confirmation first, then has a 1-minute cooldown (global and per-player). It only looks at the currently loaded area; cells inside unloaded chunks will look like orphans.
- `getuuid` allocates a UUID if the cell does not have one yet.
- `recover` needs a free inventory slot, otherwise it refuses.
- `debug` provides five sub-operations — `list`, `inspect <uuid>`, `create <tier> <kind>`, `corrupt [clear]`, `flush` — and can also be run from the server console or command blocks.

- `scan` 先二次确认，然后有 1 分钟冷却（全局与每个玩家各一份）。它只检测当前加载范围，未加载区块里的元件看起来会像孤儿。
- `getuuid` 在元件还没有 UUID 时会为它分配一个。
- `recover` 需要背包有空位，否则拒绝。
- `debug` 提供五个子操作 —— `list`、`inspect <uuid>`、`create <tier> <kind>`、`corrupt [clear]`、`flush` —— 也可以从服务器控制台 / 命令方块执行。

## UUID

FE cells have no UUID: their energy lives in the item's data components.

FE 元件没有 UUID：能量存在物品组件里。

Each fluid or chemical cell gets a UUID the first time it is used (first insert, first pickup, or `/ultracell getuuid`). That UUID names the cell's `.dat` file and is the key to getting its data back.

每个流体 / 化学品元件在**首次使用**时获得 UUID（首次存入、首次入包，或执行 `/ultracell getuuid`）。这个 UUID 既是它 `.dat` 数据文件的名字，也是把数据找回来的唯一凭据。

- It is shown in the cell's tooltip and by `/ultracell getuuid` (click to copy).
- `/ultracell scan` lists orphan files by UUID, and `/ultracell recover <uuid>` issues a new cell bound to that data.
- A UUID never changes. Copying a cell copies the UUID as well, so both copies share the same data — there is no copy protection.
- Ownership is a separate UUID: it is stored in the data file, with a read-only mirror in the item component for display.

- tooltip 与 `/ultracell getuuid` 都会显示它（可点击复制）。
- `/ultracell scan` 按 UUID 列出孤儿文件；`/ultracell recover <uuid>` 按该 UUID 发放一个新元件、接回原有数据。
- UUID 一旦分配**永不改变**。复制元件会连 UUID 一起复制 ⇒ 两个副本共享同一份数据，**不防复制**。
- 归属是**另一个 UUID**：存在数据文件里，物品组件中只有一份只读镜像供显示。

## Filtering / 过滤机制

- No whitelist: dynamic allocation, slots released when emptied.
  不配置白名单：动态占位，用完释放。
- Whitelist configured: only accepts whitelisted types.
  配置白名单：只接受白名单内。
- Inverter Card: rejects whitelisted types, dynamic allocation for the rest. With no whitelist configured, the Inverter Card is ignored (native AE2 behaviour).
  反相卡：拒绝白名单内，其余动态占位。未配置白名单时，反相卡会被忽略（AE2 原生语义）。

## Tooltips / Tooltip

Item names are colored by tier: Hongmeng and the Ultimate Cell Housing in purple, Wuji in gold.

物品名按等级着色：鸿蒙与究极存储外壳为紫色，无极为金色。

Fluid and chemical cell tooltips show:

流体与化学品元件的 tooltip 显示：

- **UUID** — amber
- **Type count** and **usage** — values colored by four tiers: green (`0–35%`), yellow (`35–70%`), orange (`70–95%`), red (`95–100%`); capacity limit shown in light blue
- **Owner** — the player name in amber, or `Unassigned` if the cell has no owner

- **UUID** — 亮黄
- **类型数**与**已用** — 数值按四档着色：绿（`0–35%`）、黄（`35–70%`）、橙（`70–95%`）、红（`95–100%`）；上限值浅蓝
- **所有者** — 玩家名亮黄；无主时显示「无主」

Cell textures include a small status light that reflects the cell's usage, using the same four-tier color scheme.

元件贴图带一个状态灯，颜色与 tooltip 的四档占用率一致。

## Development Environment / 开发环境

- Minecraft 1.21.1
- NeoForge 21.1.x
- JDK 21
- Gradle Wrapper 8.13
- ModDevGradle

## How It Works / 原理说明

**FE cells / FE 元件**: stored in the item's data components (three longs). No external files; 1.0.0 saves remain compatible.

存储于物品组件（三 long）。不走外置文件，兼容 1.0.0 存档。

**Fluid / chemical cells / 流体、化学品元件**: each cell has its own `.dat` file (compressed NBT), named by UUID. Write schedule: end-of-tick merge write + shutdown write + cell-leaves-container write + 30-second fallback write. No copy protection.

每元件一个独立 `.dat` 文件（压缩 NBT），按 UUID 命名。写盘策略为 tick 末合并写 + 关服写 + 元件离开容器写 + 每 30 秒兜底写。不防复制。

**Ownership / 归属**: a cell in a player's inventory is bound to that player; a cell in an ME network belongs to the network owner; existing ownership is never rebound. The owner is stored in the cell's data file, with a read-only mirror in the item's data component for client-side display.

元件在玩家背包 → 绑给该玩家；在 ME 网络 → 归网络主人；已有归属不再改绑。归属存于元件数据文件，物品组件中保留一份只读镜像，供客户端显示。

**Orphans / 孤儿**: never auto-deleted, kept for recovery.

不自动删除，保留供恢复。

## Acknowledgements / 致谢

Design ideas were referenced from AppliedFlux, BiggerAE2, Applied Energistics 2, and MEGA Cells. No code was copied directly. All trademarks and copyrights belong to their respective owners.

设计思路参考了 AppliedFlux、BiggerAE2、Applied Energistics 2、MEGA Cells；未直接复制代码。所有商标与版权归各自所有者所有。

## License / 许可

MIT License. See `LICENSE`.

MIT License，详见 `LICENSE`。