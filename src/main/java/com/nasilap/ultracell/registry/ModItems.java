package com.nasilap.ultracell.registry;

import com.nasilap.ultracell.UltraCell;
import com.nasilap.ultracell.item.FECellComponentItem;
import com.nasilap.ultracell.item.FECellItem;
import com.nasilap.ultracell.storage.UInt192;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 物品注册。共 4 个：
 * <ul>
 *   <li>鸿蒙 FE 存储组件 / 无极 FE 存储组件 —— 材料</li>
 *   <li>鸿蒙 FE 存储元件 / 无极 FE 存储元件 —— 元件，上限分别为 uint128 / uint192 最大值</li>
 * </ul>
 *
 * <p>空闲耗电按既定决策全部为 0。
 */
public final class ModItems {

    /** 鸿蒙上限：uint128 最大值 ≈ 3.40e38 FE。 */
    public static final UInt192 HONGMENG_CAPACITY = UInt192.MAX_UINT128;

    /** 无极上限：uint192 最大值 ≈ 6.28e57 FE。 */
    public static final UInt192 WUJI_CAPACITY = UInt192.MAX_UINT192;

    /** 两个等级的空闲耗电，均为 0（不耗电）。 */
    public static final double IDLE_DRAIN = 0.0;

    public static final DeferredRegister.Items DR = DeferredRegister.createItems(UltraCell.MOD_ID);

    public static final DeferredItem<FECellComponentItem> HONGMENG_FE_COMPONENT = DR.register(
            "hongmeng_fe_component", () -> new FECellComponentItem(new Item.Properties(), HONGMENG_CAPACITY));

    public static final DeferredItem<FECellComponentItem> WUJI_FE_COMPONENT =
            DR.register("wuji_fe_component", () -> new FECellComponentItem(new Item.Properties(), WUJI_CAPACITY));

    public static final DeferredItem<FECellItem> HONGMENG_FE_CELL = DR.register(
            "hongmeng_fe_cell",
            () -> new FECellItem(
                    new Item.Properties(), HONGMENG_CAPACITY, IDLE_DRAIN, UltraCell.id("hongmeng_fe_component")));

    public static final DeferredItem<FECellItem> WUJI_FE_CELL = DR.register(
            "wuji_fe_cell",
            () -> new FECellItem(new Item.Properties(), WUJI_CAPACITY, IDLE_DRAIN, UltraCell.id("wuji_fe_component")));

    private ModItems() {}

    public static void register(IEventBus modEventBus) {
        DR.register(modEventBus);
    }
}
