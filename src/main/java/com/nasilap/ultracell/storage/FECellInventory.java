package com.nasilap.ultracell.storage;

import com.glodblock.github.appflux.common.me.key.FluxKey;
import com.glodblock.github.appflux.common.me.key.type.EnergyType;
import com.nasilap.ultracell.item.FECellItem;
import com.nasilap.ultracell.registry.ModComponents;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.core.definitions.AEItems;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

/**
 * Ultra Cell 的 FE 存储实现（AE2 {@link StorageCell} 契约）。
 *
 * <p>关键设计（已定决策）：
 * <ul>
 *   <li>内部存量是 {@link UInt192}，容量无上限概念（上限由元件等级给出）</li>
 *   <li>AE2 的 API 边界是 {@code long}，对外一律用 {@code saturatingToLong()} 饱和上报</li>
 *   <li>只接受 {@code FluxKey} 且能量类型必须是 {@link EnergyType#FE}</li>
 *   <li>不实现 {@code IEnergyStorage}，不暴露 NeoForge 能量能力</li>
 *   <li>void 卡：溢出部分销毁 —— 向 AE 报告「全部接收」，多余部分被吞掉</li>
 * </ul>
 */
public class FECellInventory implements StorageCell {

    private final ItemStack stack;

    @Nullable
    private final ISaveProvider container;

    private final FECellItem cellItem;

    private final UInt192 maxCapacity;

    private final boolean hasVoidUpgrade;

    private UInt192 storedEnergy;

    private boolean isPersisted = true;

    public FECellInventory(ItemStack stack, @Nullable ISaveProvider container) {
        this.stack = stack;
        this.container = container;
        this.cellItem = (FECellItem) stack.getItem();
        this.maxCapacity = this.cellItem.getMaxCapacity();

        var component = stack.get(ModComponents.FE_ENERGY.get());
        this.storedEnergy = component != null ? component.toUInt192() : UInt192.ZERO;

        this.hasVoidUpgrade = this.getUpgrades().isInstalled(AEItems.VOID_CARD);
    }

    /** 只认 AppliedFlux 的 FE 能量键；{@code EnergyType} 是 enum，用 == 比较即可。 */
    private static boolean isFEKey(AEKey what) {
        return what instanceof FluxKey fluxKey && fluxKey.getEnergyType() == EnergyType.FE;
    }

    @Override
    public CellState getStatus() {
        if (this.storedEnergy.isZero()) {
            return CellState.EMPTY;
        }
        if (this.storedEnergy.compareTo(this.maxCapacity) >= 0) {
            return CellState.FULL;
        }
        return CellState.NOT_EMPTY;
    }

    /** 空闲耗电（已定决策：两个等级都为 0，即不耗电）。 */
    @Override
    public double getIdleDrain() {
        return this.cellItem.getIdleDrain();
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0L || !isFEKey(what)) {
            return 0L;
        }

        UInt192 space = this.maxCapacity.subtract(this.storedEnergy);
        UInt192 accepted = UInt192.fromLong(amount).min(space);

        if (mode == Actionable.MODULATE && !accepted.isZero()) {
            this.storedEnergy = this.storedEnergy.add(accepted);
            this.saveChanges();
        }

        // void 卡：报告「全部收下」，AE 网络不会退回，超出部分即被销毁
        return this.hasVoidUpgrade ? amount : accepted.saturatingToLong();
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0L || !isFEKey(what)) {
            return 0L;
        }

        UInt192 taken = UInt192.fromLong(amount).min(this.storedEnergy);

        if (mode == Actionable.MODULATE && !taken.isZero()) {
            this.storedEnergy = this.storedEnergy.subtract(taken);
            this.saveChanges();
        }

        return taken.saturatingToLong();
    }

    @Override
    public void persist() {
        if (this.isPersisted) {
            return;
        }
        if (this.storedEnergy.isZero()) {
            this.stack.remove(ModComponents.FE_ENERGY.get());
        } else {
            this.stack.set(ModComponents.FE_ENERGY.get(), FEEnergyComponent.of(this.storedEnergy));
        }
        this.isPersisted = true;
    }

    /**
     * 向 AE2 上报存量。
     *
     * <p>真实存量超过 {@link Long#MAX_VALUE} 时上报<b>饱和值</b>（不是回绕值）。
     * 物品自身 tooltip 直接读 {@link UInt192}，因此显示的是真实值。
     */
    @Override
    public void getAvailableStacks(KeyCounter out) {
        if (!this.storedEnergy.isZero()) {
            out.add(FluxKey.of(EnergyType.FE), this.storedEnergy.saturatingToLong());
        }
    }

    @Override
    public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
        return isFEKey(what);
    }

    /** 只有空元件才能被拆解。 */
    @Override
    public boolean canFitInsideCell() {
        return this.storedEnergy.isZero();
    }

    @Override
    public Component getDescription() {
        return this.stack.getHoverName();
    }

    private void saveChanges() {
        this.isPersisted = false;
        if (this.container != null) {
            this.container.saveChanges();
        } else {
            this.persist();
        }
    }

    /** 升级库存（3 槽）。 */
    public IUpgradeInventory getUpgrades() {
        return this.cellItem.getUpgrades(this.stack);
    }

    public UInt192 getStoredEnergy() {
        return this.storedEnergy;
    }

    public UInt192 getMaxCapacity() {
        return this.maxCapacity;
    }
}
