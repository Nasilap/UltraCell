# Ultra Cell（究极元件）

为 AE2 添加超大容量 FE 存储元件。

## 元件

| 名称 | 容量 |
|------|------|
| 鸿蒙 FE 存储元件 | 约 3.4 × 10³⁸ FE |
| 无极 FE 存储元件 | 约 6.28 × 10⁵⁷ FE |

只存 FE，只走 AE2 网络，不对外供能。

## 依赖

- Minecraft 1.21.1
- NeoForge 21.1.x
- Applied Energistics 2 19.2.17+
- AppliedFlux 1.21-2.1.5-neoforge+
- Glodium
- MEGA Cells

## 配方

**鸿蒙 FE 存储组件**

```
奇点        256M组件    奇点
256M组件    能源卡      256M组件
奇点        256M组件    奇点
```

**无极 FE 存储组件**

```
奇点        鸿蒙组件    奇点
鸿蒙组件    大型能源卡  鸿蒙组件
奇点        鸿蒙组件    奇点
```

**元件** = 组件 + `appflux:fe_cell_housing`（无序合成）

物品 ID：

- 奇点：`ae2:singularity`
- 256M 组件：`megacells:cell_component_256m`
- 能源卡：`ae2:energy_card`
- 大型能源卡：`megacells:greater_energy_card`

## 使用

- 放入 ME 驱动器，通过 ME 网络存取 FE。
- 元件只接受 `FluxKey.of(EnergyType.FE)`，不存其他东西。
- 支持 void 卡，满了销毁多余。
- 3 个升级槽。
- Alt + 右键拆解。

## 致谢

参考了 AppliedFlux、BiggerAE2、Applied Energistics 2、MEGA Cells 的设计思路。未直接复制代码。所有商标和版权归各自所有者所有。

## 许可证

MIT License · 作者 Nasilap
