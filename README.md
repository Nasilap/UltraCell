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
| `/ultracell debug` | OP | Debug utilities / 调试工具 |

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