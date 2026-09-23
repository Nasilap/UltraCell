package com.nasilap.ultracell.storage;

import com.nasilap.ultracell.item.FECellItem;

import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.ISaveProvider;

import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

/**
 * 把 Ultra Cell 的元件物品接入 AE2 的存储元件体系。
 *
 * <p>在 {@code FMLCommonSetupEvent} 中通过
 * {@code StorageCells.addCellHandler(FECellHandler.INSTANCE)} 注册。
 */
public class FECellHandler implements ICellHandler {

    public static final FECellHandler INSTANCE = new FECellHandler();

    private FECellHandler() {}

    @Override
    public boolean isCell(ItemStack is) {
        return is != null && !is.isEmpty() && is.getItem() instanceof FECellItem;
    }

    @Override
    public @Nullable FECellInventory getCellInventory(ItemStack is, @Nullable ISaveProvider host) {
        return this.isCell(is) ? new FECellInventory(is, host) : null;
    }
}
