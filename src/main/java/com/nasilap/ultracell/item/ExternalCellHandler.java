package com.nasilap.ultracell.item;

import com.nasilap.ultracell.UltraCell;
import com.nasilap.ultracell.storage.ExternalCellInventory;

import appeng.api.stacks.AEKeyType;
import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.ISaveProvider;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

/**
 * 把外置存储元件接入 AE2 存储体系。
 *
 * <p><b>位于 {@code item/} 包</b>：依赖方向保持单向 {@code item → storage}。
 *
 * <p>与 {@code FECellHandler} 的 {@code isCell} 判定**必须互斥**：
 * {@code StorageCells.getCellInventory} 返回**第一个**返回非 null 的处理器。
 *
 * <p>keyType 不可用时（没装 appmek 的化学品元件）：
 * <ul>
 *   <li>{@link #isCell} 也返回 {@code false} —— 这一点很关键：AE2 驱动器判断
 *       "这个格子能不能放"用的是 {@code StorageCells.isCellHandled(stack)}，
 *       也就是只看 {@code isCell}。若这里为 true、而 {@code getCellInventory} 返回 null，
 *       元件会**插得进驱动器却永远不会被挂载**，表现为"能放、但永远存不进、也取不出"。
 *       所以两者必须同时为假，驱动器才会**明确拒收**。</li>
 *   <li>{@link #getCellInventory} 返回 {@code null} ⇒ AE2 视为非元件</li>
 * </ul>
 * 这与「可 /give、可建文件、但放不进 ME 网络」的降级口径一致。
 */
public class ExternalCellHandler implements ICellHandler {

    public static final ExternalCellHandler INSTANCE = new ExternalCellHandler();

    /** 降级告警只打一次，避免刷屏。 */
    private static volatile boolean loggedUnavailable;

    private ExternalCellHandler() {}

    @Override
    public boolean isCell(ItemStack is) {
        if (is == null || is.isEmpty() || !(is.getItem() instanceof ExternalCellItem item)) {
            return false;
        }
        if (item.getKeyType() == null) {
            logUnavailable(item);
            return false;
        }
        return true;
    }

    @Override
    public @Nullable ExternalCellInventory getCellInventory(ItemStack is, @Nullable ISaveProvider host) {
        if (!this.isCell(is)) {
            return null;
        }
        ExternalCellItem item = (ExternalCellItem) is.getItem();
        AEKeyType keyType = item.getKeyType();
        if (keyType == null) {
            // isCell 已保证非空，这里是双保险
            return null;
        }
        return new ExternalCellInventory(is, host, keyType, item.getTotalCapacity(), item.getTypeSlots());
    }

    private static void logUnavailable(ExternalCellItem item) {
        if (loggedUnavailable) {
            return;
        }
        loggedUnavailable = true;
        UltraCell.LOGGER.warn(
                "Ultra Cell: {} has no usable key type (appmek missing, or its channel is not registered yet) "
                        + "— it is treated as a non-cell, so drives and networks will reject it",
                BuiltInRegistries.ITEM.getKey(item));
    }
}
