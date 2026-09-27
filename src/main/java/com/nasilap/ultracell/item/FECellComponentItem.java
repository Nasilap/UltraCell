package com.nasilap.ultracell.item;

import com.nasilap.ultracell.storage.UInt192;

import net.minecraft.world.item.Item;

/**
 * 组件物品（鸿蒙 / 无极 × FE / 流体 / 化学品，共 **6 个**共用本类）。
 *
 * <p>纯材料物品，不参与任何存储逻辑。
 *
 * <p><b>2.0.1 起 tooltip 为空</b>：原先会附言一行「容量为 xxx」，按裁决整行删除，
 * 组件不再有任何附言（只剩物品名）。因此 {@code tooltip.ultracell.capacity}
 * 这个 lang key 也一并删掉了（引用点只有这里，全仓已确认）。
 *
 * <p>{@code maxCapacity} 字段与 {@link #getMaxCapacity()} **刻意保留**（当前无调用点）：
 * 按"最小改动"处理，避免为一个死字段牵动 6 处注册调用。
 */
public class FECellComponentItem extends Item {

    private final UInt192 maxCapacity;

    public FECellComponentItem(Properties properties, UInt192 maxCapacity) {
        super(properties);
        this.maxCapacity = maxCapacity;
    }

    /** 该组件对应等级的容量上限（当前无调用点，保留以备后用）。 */
    public UInt192 getMaxCapacity() {
        return this.maxCapacity;
    }
}
