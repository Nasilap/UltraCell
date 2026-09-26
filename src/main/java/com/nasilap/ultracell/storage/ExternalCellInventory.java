package com.nasilap.ultracell.storage;

import com.nasilap.ultracell.UltraCell;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import appeng.core.definitions.AEItems;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 外置存储元件的 AE2 契约实现。
 *
 * <p>构造参数刻意**只吃值**（{@code ItemStack + ISaveProvider + AEKeyType + UInt192 + int}），
 * 不接受物品类 —— 依赖方向保持单向 {@code item → storage}。
 *
 * <p>数据来源是内存缓存（{@link CellDataManager}）；本类只做四件事：
 * 判定状态、增删条目、把 DC 摘要刷回物品、在需要时抛出「强制落盘」。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>只接受与 {@code keyType} 同通道的 key</li>
 *   <li>单类型不超过 {@code 总量 / 类型位}；类型位用满即 {@link CellState#TYPES_FULL}</li>
 *   <li>类型位**动态释放**：计数归零即移除条目</li>
 *   <li>串档（文件 tier/kind 与物品不符）⇒ 只读：读写一律拒绝</li>
 *   <li>装 void 卡时「报告全部接收」，多余部分被吞掉（与 FE 元件同语义）</li>
 * </ul>
 */
public class ExternalCellInventory implements StorageCell {

    private final ItemStack stack;

    @Nullable
    private final ISaveProvider container;

    private final AEKeyType keyType;
    private final UInt192 totalCapacity;
    private final UInt192 typeLimit;
    private final int typeSlots;
    private final boolean hasVoidUpgrade;

    /** 通道不匹配的告警只打一次（每个库存实例一次）。 */
    private boolean loggedKeyTypeMismatch;

    public ExternalCellInventory(ItemStack stack, @Nullable ISaveProvider container, AEKeyType keyType,
                                 UInt192 totalCapacity, int typeSlots) {
        this.stack = stack;
        this.container = container;
        this.keyType = keyType;
        this.totalCapacity = totalCapacity;
        this.typeLimit = ExternalCellStorage.typeLimitForSlots(typeSlots);
        this.typeSlots = typeSlots;
        this.hasVoidUpgrade = readVoidUpgrade(stack);
        this.bindToNetworkOwner();
    }

    private static boolean readVoidUpgrade(ItemStack stack) {
        if (stack.getItem() instanceof ICellWorkbenchItem workbenchItem) {
            return workbenchItem.getUpgrades(stack).isInstalled(AEItems.VOID_CARD);
        }
        return false;
    }

    // ── 归属：ME 网络兜底 ──

    /**
     * 元件在驱动器 / ME 箱子里时，归属取该 **ME 网络所有者**。
     *
     * <p>路径：{@code host instanceof IActionHost → getActionableNode() →
     * getOwningPlayerProfileId()}；三个 null 边界都要判。
     *
     * <p>只在服务端做（客户端构造出来的实例不碰归属）。命中候选后**立即写内存缓存**，
     * 落盘交给写盘调度的 ③（归属变更 → 立即）。
     */
    private void bindToNetworkOwner() {
        if (!(this.container instanceof BlockEntity blockEntity)) {
            return;
        }
        if (!(blockEntity.getLevel() instanceof ServerLevel)) {
            return;
        }
        if (!(blockEntity instanceof IActionHost actionHost)) {
            return;
        }
        IGridNode node = actionHost.getActionableNode();
        if (node == null) {
            return;
        }
        UUID owner = node.getOwningPlayerProfileId();
        if (owner == null) {
            return;
        }
        CellDataManager manager = CellDataManager.current();
        if (manager == null) {
            return;
        }
        UUID uuid = this.uuidOf();
        if (uuid == null || manager.corruptIndex().isCorrupt(uuid)) {
            return;
        }

        CellData data = manager.dataForItem(this.stack);
        if (data == null) {
            if (!(this.stack.getItem() instanceof TieredCellItem tiered)) {
                return;
            }
            data = manager.dataForWrite(uuid, tiered.getTier(), tiered.getKind());
        }
        manager.bindOwnerIfUnowned(data, owner);
    }

    // ── 数据入口 ──

    @Nullable
    private UUID uuidOf() {
        return CellDataManager.summaryOf(this.stack).uuid();
    }

    /** 只读取数：不分配 UUID、不自愈、不建文件。 */
    @Nullable
    private CellData data() {
        UUID uuid = this.uuidOf();
        if (uuid == null) {
            return null;
        }
        CellDataManager manager = CellDataManager.current();
        if (manager == null || manager.corruptIndex().isCorrupt(uuid)) {
            return null;
        }
        return manager.dataForItem(this.stack);
    }

    /** 写入取数：需要文件才能继续 ⇒ 允许自愈①（分配 UUID）与自愈②（建空文件）。 */
    @Nullable
    private CellData dataForWrite() {
        CellDataManager manager = CellDataManager.current();
        if (manager == null) {
            return null;
        }
        UUID uuid = this.uuidOf();
        if (uuid == null) {
            uuid = manager.ensureUuid(this.stack);
            if (uuid == null) {
                return null;
            }
        } else if (manager.corruptIndex().isCorrupt(uuid)) {
            return null;
        }

        CellData data = manager.dataForItem(this.stack);
        if (data == null) {
            if (!(this.stack.getItem() instanceof TieredCellItem tiered)) {
                return null;
            }
            data = manager.dataForWrite(uuid, tiered.getTier(), tiered.getKind());
        }
        return data;
    }

    @Nullable
    private CellDataManager manager() {
        return CellDataManager.current();
    }

    @Nullable
    private static String tierOf(ItemStack stack) {
        return stack.getItem() instanceof TieredCellItem tiered ? tiered.getTier() : null;
    }

    // ── StorageCell ──

    @Override
    public CellState getStatus() {
        CellData data = this.data();
        if (data == null || data.isEmpty()) {
            return CellState.EMPTY;
        }
        if (data.used().compareTo(this.totalCapacity) >= 0) {
            return CellState.FULL;
        }
        if (data.typeCount() >= this.typeSlots) {
            return CellState.TYPES_FULL;
        }
        return CellState.NOT_EMPTY;
    }

    /** 空闲耗电：0（已定决策）。 */
    @Override
    public double getIdleDrain() {
        return 0.0;
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0L) {
            return 0L;
        }
        if (!this.keyType.contains(what)) {
            this.logKeyTypeMismatch(what);
            return 0L;
        }

        CellData data = mode == Actionable.MODULATE ? this.dataForWrite() : this.data();
        if (data == null) {
            // 只读路径下没有数据 —— 要么是「新元件还没建文件」（空元件），
            // 要么是「损坏名单 / 未知格式版本 / 串档」这类不可写状态。
            if (mode == Actionable.MODULATE || !this.isInsertTargetAvailable()) {
                return 0L;
            }
            // SIMULATE + 空元件：按「0 类型位、0 已用」如实回答可接受量。
            // 若这里返回 0，先 SIMULATE 再 MODULATE 的调用方会以为没空间而直接放弃。
            UInt192 acceptedSim = UInt192.fromLong(amount).min(this.typeLimit);
            return this.hasVoidUpgrade ? amount : acceptedSim.saturatingToLong();
        }
        if (data.isReadOnly()) {
            return 0L;
        }

        UInt192 current = data.get(what);
        if (current == null) {
            if (data.typeCount() >= this.typeSlots) {
                // 类型位用满：void 卡把它当「已接收」吞掉，否则拒收
                return this.hasVoidUpgrade ? amount : 0L;
            }
            current = UInt192.ZERO;
        }

        UInt192 headroom = this.typeLimit.subtract(current);
        UInt192 accepted = UInt192.fromLong(amount).min(headroom);

        if (mode == Actionable.MODULATE && !accepted.isZero()) {
            data.put(what, current.add(accepted));
            this.onContentChanged(data);
        }

        // void 卡：报告「全部收下」，超出部分被销毁
        return this.hasVoidUpgrade ? amount : accepted.saturatingToLong();
    }

    /**
     * 该元件是否「可写、但当前没有缓存数据」（= 新元件的空状态）。
     *
     * <p>用来把「空元件」与「损坏 / 未知版本 / 串档的只读元件」区分开。
     */
    private boolean isInsertTargetAvailable() {
        UUID uuid = this.uuidOf();
        if (uuid == null) {
            return true;
        }
        CellDataManager manager = CellDataManager.current();
        if (manager == null || manager.corruptIndex().isCorrupt(uuid)) {
            return false;
        }
        CellData data = manager.dataForItem(this.stack);
        return data == null || !data.isReadOnly();
    }

    /** 通道不匹配时打一次日志（带两边通道 id），便于实机排查。 */
    private void logKeyTypeMismatch(AEKey what) {
        if (this.loggedKeyTypeMismatch) {
            return;
        }
        this.loggedKeyTypeMismatch = true;
        UltraCell.LOGGER.warn("Ultra Cell: rejecting key {} (channel {}) — this cell's channel is {}",
                what, what.getType().getId(), this.keyType.getId());
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0L) {
            return 0L;
        }
        if (!this.keyType.contains(what)) {
            this.logKeyTypeMismatch(what);
            return 0L;
        }
        CellData data = this.data();
        if (data == null || data.isReadOnly()) {
            return 0L;
        }

        UInt192 current = data.get(what);
        if (current == null) {
            return 0L;
        }
        UInt192 taken = UInt192.fromLong(amount).min(current);

        if (mode == Actionable.MODULATE && !taken.isZero()) {
            // put(0) 会移除条目 ⇒ 类型位在此释放
            data.put(what, current.subtract(taken));
            this.onContentChanged(data);
        }
        return taken.saturatingToLong();
    }

    @Override
    public void persist() {
        CellData data = this.data();
        if (data == null) {
            return;
        }
        // DC 摘要立即刷新；文件落盘走 ③ 强制时机（AE2 主动要求保存 = 关键时机）
        CellDataManager.setSummary(this.stack, CellSummaryComponent.of(data.uuid(), data.typeCount(), data.used()));
        CellDataManager manager = this.manager();
        if (manager != null) {
            manager.markForceFlush(data);
        }
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        CellData data = this.data();
        if (data == null) {
            return;
        }
        for (var entry : data.entryView()) {
            out.add(entry.getKey(), entry.getValue().saturatingToLong());
        }
    }

    @Override
    public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
        return this.keyType.contains(what);
    }

    @Override
    public boolean canFitInsideCell() {
        CellData data = this.data();
        return data == null || data.isEmpty();
    }

    @Override
    public Component getDescription() {
        return this.stack.getHoverName();
    }

    // ── 内部 ──

    private void onContentChanged(CellData data) {
        CellDataManager manager = this.manager();
        if (manager != null) {
            manager.markContentChanged(data);
        }
        this.saveChanges();
    }

    /** 与 FE 元件同形的保存约定：有容器就交给容器，没有就自己刷。 */
    private void saveChanges() {
        if (this.container != null) {
            this.container.saveChanges();
        } else {
            this.persist();
        }
    }

    /** 供命令层诊断用：本元件当前的 tier。 */
    @Nullable
    public String tierName() {
        return tierOf(this.stack);
    }
}
