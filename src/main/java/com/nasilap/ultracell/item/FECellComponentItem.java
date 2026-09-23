package com.nasilap.ultracell.item;

import com.nasilap.ultracell.storage.UInt192;
import com.nasilap.ultracell.util.NumberUtil;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 组件物品（鸿蒙 FE 存储组件 / 无极 FE 存储组件）。
 *
 * <p>纯材料物品，不参与任何存储逻辑；只在附言里标注它对应等级的容量上限，
 * 与元件本身共用同一个 {@link UInt192} 常量，保证两处显示永远一致。
 */
public class FECellComponentItem extends Item {

    private final UInt192 maxCapacity;

    public FECellComponentItem(Properties properties, UInt192 maxCapacity) {
        super(properties);
        this.maxCapacity = maxCapacity;
    }

    /** 该组件对应等级的容量上限。 */
    public UInt192 getMaxCapacity() {
        return this.maxCapacity;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.ultracell.capacity", NumberUtil.format(this.maxCapacity)));
    }
}
