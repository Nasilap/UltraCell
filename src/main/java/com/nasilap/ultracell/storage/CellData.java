package com.nasilap.ultracell.storage;

import appeng.api.stacks.AEKey;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一个外置存储元件的**内存态**：归属 + 全部条目 + 写盘调度标记。
 *
 * <p>生命周期与 {@link OwnerCache} 一致：{@code ServerStartedEvent} 建、{@code ServerStoppedEvent} 销毁。
 *
 * <p>写盘调度三类的标记都在这里：
 * <ul>
 *   <li>{@link #bindPending} —— ① 新绑定 → tick 末即写</li>
 *   <li>{@link #contentSinceTick} —— ② 内容变化 → 受 20 tick 下限节流</li>
 *   <li>{@link #forcePending} —— ③ 强制时机（离开容器 / 关服 / **归属变更**）→ 立即</li>
 * </ul>
 */
public final class CellData {

    private final UUID uuid;
    private final String tier;
    private final String kind;

    /** 条目表：key → 计数。用 LinkedHashMap 保持稳定顺序，便于诊断与 diff。 */
    private final Map<AEKey, UInt192> entries = new LinkedHashMap<>();

    /** 归属；{@code null} = 无主。 */
    @Nullable
    private UUID owner;

    /** 串档标记：文件里的 tier/kind 与元件物品类型不一致 → 只读 + 拒写。 */
    private boolean mismatched;

    /** ① 新绑定待写。 */
    private boolean bindPending;

    /** ② 自上次落盘以来内容变化的 tick（-1 = 无变化）。 */
    private long contentSinceTick = -1L;

    /** ③ 强制立即落盘待办。 */
    private boolean forcePending;

    /**
     * 已用总量的增量缓存。
     *
     * <p>{@code getStatus()} 会被 AE2 高频调用；若每次都遍历全部条目求和，
     * 512 类型位的元件会做出无谓的重复加法。这里只在改动时增量维护。
     */
    private UInt192 usedCache = UInt192.ZERO;

    public CellData(UUID uuid, String tier, String kind) {
        this.uuid = uuid;
        this.tier = tier;
        this.kind = kind;
    }

    public UUID uuid() {
        return this.uuid;
    }

    public String tier() {
        return this.tier;
    }

    public String kind() {
        return this.kind;
    }

    @Nullable
    public UUID owner() {
        return this.owner;
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
    }

    public boolean hasOwner() {
        return this.owner != null;
    }

    public boolean isMismatched() {
        return this.mismatched;
    }

    public void setMismatched(boolean mismatched) {
        this.mismatched = mismatched;
    }

    // ── 条目 ──

    public Map<AEKey, UInt192> entries() {
        return this.entries;
    }

    public int typeCount() {
        return this.entries.size();
    }

    @Nullable
    public UInt192 get(AEKey key) {
        return this.entries.get(key);
    }

    public void put(AEKey key, UInt192 amount) {
        UInt192 previous = this.entries.get(key);
        if (amount.isZero()) {
            if (previous != null) {
                this.entries.remove(key);
                this.usedCache = this.usedCache.subtract(previous);
            }
        } else {
            this.entries.put(key, amount);
            this.usedCache = this.usedCache.subtract(previous == null ? UInt192.ZERO : previous).add(amount);
        }
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    /** 已用总量（增量维护，O(1)）。 */
    public UInt192 used() {
        return this.usedCache;
    }

    /** 批量装入条目（启动扫描时用），不触碰脏标记。 */
    public void replaceEntries(Map<AEKey, UInt192> loaded) {
        this.entries.clear();
        this.entries.putAll(loaded);
        UInt192 total = UInt192.ZERO;
        for (UInt192 value : this.entries.values()) {
            total = total.add(value);
        }
        this.usedCache = total;
    }

    // ── 写盘调度标记 ──

    public boolean isBindPending() {
        return this.bindPending;
    }

    public void markBindPending() {
        this.bindPending = true;
    }

    public void clearBindPending() {
        this.bindPending = false;
    }

    public long contentSinceTick() {
        return this.contentSinceTick;
    }

    public boolean hasContentChange() {
        return this.contentSinceTick >= 0L;
    }

    /** 记一次内容变化；只在没有未落盘变化时打时间戳（保持最早的 tick，保证 20 tick 下限）。 */
    public void markContentChanged(long tick) {
        if (this.contentSinceTick < 0L) {
            this.contentSinceTick = tick;
        }
    }

    public void clearContentChange() {
        this.contentSinceTick = -1L;
    }

    public boolean isForcePending() {
        return this.forcePending;
    }

    public void markForcePending() {
        this.forcePending = true;
    }

    public void clearForcePending() {
        this.forcePending = false;
    }

    /** 是否有任何待落盘的理由。 */
    public boolean isDirty() {
        return this.bindPending || this.forcePending || this.contentSinceTick >= 0L;
    }

    /** 是否不可写（串档）。 */
    public boolean isReadOnly() {
        return this.mismatched;
    }

    /** 诊断用：条目快照（只读）。 */
    public Collection<Map.Entry<AEKey, UInt192>> entryView() {
        return this.entries.entrySet();
    }
}
