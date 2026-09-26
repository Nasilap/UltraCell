package com.nasilap.ultracell.storage;

import com.nasilap.ultracell.UltraCell;
import com.nasilap.ultracell.registry.ModComponents;
import com.nasilap.ultracell.util.IOUtilities;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 外置存储的总控：启动扫描、UUID 分配、归属绑定、写盘调度。
 *
 * <p><b>生命周期</b>：{@code ServerStartedEvent} 创建（同时全量解析所有 {@code .dat} 建缓存）、
 * {@code ServerStoppingEvent} 强制 flush + 等 I/O 排空、
 * {@code ServerStoppedEvent} 丢弃（缓存**不放 static**，避免跨存档串档）。
 *
 * <p>{@link #current()} 是一个**随服务器生命周期显式置空的静态引用**：
 * 物品与命令侧拿不到 server 上下文，必须有这么一个入口；它本身不携带任何存档数据，
 * 且在 {@code ServerStoppedEvent} 立刻清空。
 *
 * <p><b>写盘调度三条</b>（外加 30 秒无条件兜底）：
 * <ol>
 *   <li>① 新绑定 → tick 末即写（写当前内存状态；同时清掉②，不重复排期）</li>
 *   <li>② 内容变化 → 受 {@value #CONTENT_FLUSH_DELAY_TICKS} tick 下限节流</li>
 *   <li>③ 强制时机（离开容器 / 关服 / **归属变更**）→ 立即并清空 ①②</li>
 * </ol>
 * 批量落盘：一次 tick 内所有待写条目**合成一个 I/O 任务**，闭包只捕获值快照。
 */
public final class CellDataManager {

    /** ② 内容变化的落盘下限（tick）。 */
    public static final int CONTENT_FLUSH_DELAY_TICKS = 20;

    /** 无条件兜底落盘间隔（tick）= 30 秒。 */
    public static final int FALLBACK_FLUSH_INTERVAL_TICKS = 600;

    /** 当前服务器上的管理器；随服务器生命周期创建与清空。 */
    @Nullable
    private static volatile CellDataManager current;

    private final MinecraftServer server;
    private final Path cellsDir;
    private final OwnerCache cache = new OwnerCache();
    private final CorruptIndex corruptIndex;

    private long lastFallbackFlushTick;

    private CellDataManager(MinecraftServer server, Path cellsDir, CorruptIndex corruptIndex) {
        this.server = server;
        this.cellsDir = cellsDir;
        this.corruptIndex = corruptIndex;
        this.lastFallbackFlushTick = server.getTickCount();
    }

    // ── 生命周期 ──

    /** 建管理器并做一次全量扫描。 */
    public static CellDataManager create(MinecraftServer server) {
        Path cellsDir = ExternalCellStorage.cellsDir(server);
        CorruptIndex index = CorruptIndex.load(server);
        CellDataManager manager = new CellDataManager(server, cellsDir, index);
        current = manager;
        manager.loadAll();
        UltraCell.LOGGER.info("Ultra Cell: loaded {} cell data file(s), {} entr(ies) in corrupt index",
                manager.cache.size(), index.size());
        return manager;
    }

    @Nullable
    public static CellDataManager current() {
        return current;
    }

    /** 关服：丢弃缓存与静态引用。 */
    public void destroy() {
        this.cache.clear();
        if (current == this) {
            current = null;
        }
    }

    public OwnerCache cache() {
        return this.cache;
    }

    public CorruptIndex corruptIndex() {
        return this.corruptIndex;
    }

    public Path cellsDir() {
        return this.cellsDir;
    }

    public MinecraftServer server() {
        return this.server;
    }

    /** 全量扫描：把所有 {@code .dat} 解析进缓存。 */
    private void loadAll() {
        try {
            ExternalCellStorage.ensureDirectory(this.cellsDir);
        } catch (IOException e) {
            UltraCell.LOGGER.error("Ultra Cell: cannot create data directory {}", this.cellsDir, e);
            return;
        }

        List<Path> files;
        try {
            files = ExternalCellStorage.listDataFiles(this.cellsDir);
        } catch (IOException e) {
            UltraCell.LOGGER.error("Ultra Cell: cannot list {}", this.cellsDir, e);
            return;
        }

        for (Path file : files) {
            String name = file.getFileName().toString();
            Optional<UUID> parsed = ExternalCellStorage.uuidFromFileName(name);
            if (parsed.isEmpty()) {
                // 文件名不是 UUID：不加载、不改名（可能是玩家自己放错的文件）
                UltraCell.LOGGER.warn("Ultra Cell: ignoring data file with non-uuid name: {}", name);
                continue;
            }
            UUID uuid = parsed.get();
            if (this.corruptIndex.isCorrupt(uuid)) {
                continue;
            }

            ExternalCellDataFile.ReadResult result =
                    ExternalCellDataFile.read(this.server.registryAccess(), file, uuid);
            switch (result.status()) {
                case OK -> this.cache.put(result.data());
                case MISSING -> {
                    // 列举与读取之间的竞态；忽略
                }
                case UNSUPPORTED_VERSION -> UltraCell.LOGGER.error(
                        "Ultra Cell: refusing to load {} (unknown format version {}), it stays read-only and is NOT renamed",
                        name, result.version());
                case UUID_MISMATCH -> UltraCell.LOGGER.error(
                        "Ultra Cell: refusing to load {} ({}). The DC's uuid wins; the file keeps its name and is NOT renamed",
                        name, result.detail());
                case CORRUPT -> {
                    UltraCell.LOGGER.error("Ultra Cell: cell data {} is corrupt ({}), backing it up",
                            name, result.detail());
                    try {
                        ExternalCellStorage.renameToCorrupt(file);
                    } catch (IOException e) {
                        UltraCell.LOGGER.error("Ultra Cell: failed to rename corrupt file {}", file, e);
                    }
                    this.corruptIndex.mark(uuid);
                }
            }
        }
    }

    // ── 摘要组件的读写（DC 只是索引，文件才是权威源） ──

    public static CellSummaryComponent summaryOf(ItemStack stack) {
        CellSummaryComponent summary = stack.get(ModComponents.CELL_SUMMARY.get());
        return summary == null ? CellSummaryComponent.EMPTY : summary;
    }

    public static void setSummary(ItemStack stack, CellSummaryComponent summary) {
        if (CellSummaryComponent.EMPTY.equals(summary)) {
            stack.remove(ModComponents.CELL_SUMMARY.get());
        } else {
            stack.set(ModComponents.CELL_SUMMARY.get(), summary);
        }
    }

    // ── UUID 分配（自愈①） ──

    /**
     * 保证该元件有 UUID 并把新条目登记进缓存。
     *
     * <p>触发点由调用方决定（首次 insert / 首次入包绑定 / getuuid）；
     * <b>scan 不会调用它</b>（只读路径不自愈）。
     *
     * @return 元件 UUID；不是外置元件物品时返回 {@code null}
     */
    @Nullable
    public UUID ensureUuid(ItemStack stack) {
        CellSummaryComponent summary = summaryOf(stack);
        UUID existing = summary.uuid();
        if (existing != null) {
            return existing;
        }
        if (!(stack.getItem() instanceof TieredCellItem tiered)) {
            return null;
        }

        UUID uuid = UUID.randomUUID();
        setSummary(stack, summary.withUuid(uuid));

        CellData data = new CellData(uuid, tiered.getTier(), tiered.getKind());
        data.markBindPending();
        this.cache.put(data);
        return uuid;
    }

    // ── 缓存查询 ──

    @Nullable
    public CellData get(UUID uuid) {
        return this.cache.get(uuid);
    }

    /**
     * 取某个元件物品对应的缓存条目，并**在这里**完成 tier/kind 串档检查。
     *
     * <p>这是串档检查的唯一落点：文件里的 tier/kind 与元件物品类型不一致时，
     * 把该条目标记为只读（拒绝写入）并记日志；调用方（物品层）只消费这个结果，
     * 不重复实现。
     */
    @Nullable
    public CellData dataForItem(ItemStack stack) {
        CellSummaryComponent summary = summaryOf(stack);
        UUID uuid = summary.uuid();
        if (uuid == null) {
            return null;
        }
        CellData data = this.cache.get(uuid);
        if (data == null) {
            return null;
        }
        if (stack.getItem() instanceof TieredCellItem tiered) {
            boolean mismatch = !data.tier().equals(tiered.getTier())
                    || !data.kind().equals(tiered.getKind());
            if (mismatch && !data.isMismatched()) {
                data.setMismatched(true);
                UltraCell.LOGGER.error(
                        "Ultra Cell: tier/kind mismatch for {} — file says {}/{}, item says {}/{}; marking read-only",
                        uuid, data.tier(), data.kind(), tiered.getTier(), tiered.getKind());
            }
        }
        return data;
    }

    /**
     * 取（必要时创建）某 UUID 的缓存条目 —— 用于「需要文件才能继续的操作」（自愈②）。
     *
     * <p>只读路径（scan / tooltip）**不得**调用本方法。
     */
    public CellData dataForWrite(UUID uuid, String tier, String kind) {
        CellData data = this.cache.get(uuid);
        if (data != null) {
            return data;
        }
        data = new CellData(uuid, tier, kind);
        data.markBindPending();
        this.cache.put(data);
        return data;
    }

    // ── 归属 ──

    /**
     * 给无主条目绑定归属（已有归属时**不改绑**）。
     *
     * <p>归属一律**缓存同步、落盘异步**：这里先把内存缓存改掉，落盘交给 ③ 立即排期。
     *
     * @return 是否发生了绑定
     */
    public boolean bindOwnerIfUnowned(CellData data, UUID owner) {
        if (data.isReadOnly() || data.hasOwner()) {
            return false;
        }
        data.setOwner(owner);
        data.markForcePending();
        return true;
    }

    /** 强制改写归属（{@code trade} / {@code transfer} 用）。 */
    public void setOwnerForced(CellData data, UUID owner) {
        data.setOwner(owner);
        data.markForcePending();
    }

    // ── 写盘调度 ──

    /** 记一次内容变化（②）。 */
    public void markContentChanged(CellData data) {
        data.markContentChanged(this.server.getTickCount());
    }

    /** 立即排期（③：离开容器 / 归属变更）。 */
    public void markForceFlush(CellData data) {
        data.markForcePending();
    }

    /** 周期调度：由 {@code ServerTickEvent.Post} 驱动。 */
    public void tick() {
        long now = this.server.getTickCount();
        boolean fallback = now - this.lastFallbackFlushTick >= FALLBACK_FLUSH_INTERVAL_TICKS;

        List<CellData> due = new ArrayList<>();
        for (CellData data : this.cache.all()) {
            if (data.isForcePending() || data.isBindPending()) {
                due.add(data);
            } else if (data.hasContentChange()) {
                if (now - data.contentSinceTick() >= CONTENT_FLUSH_DELAY_TICKS || fallback) {
                    due.add(data);
                }
            }
        }

        if (!due.isEmpty()) {
            this.flush(due);
        }
        if (fallback) {
            this.lastFallbackFlushTick = now;
        }
    }

    /** 把所有待写条目立刻写掉（关服 / {@code debug flush}）。 */
    public void flushAllNow() {
        this.flush(new ArrayList<>(this.cache.all()));
    }

    /**
     * 批量落盘：主线程冻结快照，I/O 线程写文件。
     *
     * <p>单文件失败只记日志、**保留重试**（重新打脏标记）、不中断整批。
     */
    private void flush(Collection<CellData> candidates) {
        List<Snapshot> snapshots = new ArrayList<>();
        for (CellData data : candidates) {
            if (!data.isDirty()) {
                continue;
            }
            CompoundTag tag = ExternalCellDataFile.toTag(this.server.registryAccess(), data);
            Path file = ExternalCellStorage.fileFor(this.cellsDir, data.uuid());
            snapshots.add(new Snapshot(data, file, tag));
            // ① 与 ② 重叠时一并清掉：本次快照已包含当前内存状态
            data.clearBindPending();
            data.clearForcePending();
            data.clearContentChange();
        }
        if (snapshots.isEmpty()) {
            return;
        }

        IOUtilities.withIOWorker(() -> {
            for (Snapshot snapshot : snapshots) {
                try {
                    ExternalCellStorage.writeCompressedAtomically(snapshot.tag(), snapshot.file());
                } catch (IOException | RuntimeException e) {
                    UltraCell.LOGGER.error("Ultra Cell: failed to write cell data {}, it will be retried",
                            snapshot.file(), e);
                    this.scheduleRetry(snapshot.data());
                }
            }
        });
    }

    /** 写失败后的重试排期：回到主线程重新打脏标记。 */
    private void scheduleRetry(CellData data) {
        try {
            this.server.execute(() -> {
                data.markContentChanged(this.server.getTickCount());
                data.markForcePending();
            });
        } catch (RuntimeException e) {
            UltraCell.LOGGER.error("Ultra Cell: cannot schedule retry for {}", data.uuid(), e);
        }
    }

    /** 一次落盘任务的值快照。 */
    private record Snapshot(CellData data, Path file, CompoundTag tag) {}
}
