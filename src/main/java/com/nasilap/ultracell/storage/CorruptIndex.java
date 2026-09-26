package com.nasilap.ultracell.storage;

import com.nasilap.ultracell.UltraCell;
import com.nasilap.ultracell.util.IOUtilities;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 持久化的「损坏元件」名单。
 *
 * <p>位置：{@code <存档根>/ultracell/corrupt_index.dat}（**在 {@code cells/} 之外**，
 * 因此不会被孤儿计数与 scan 当成数据文件）。
 *
 * <p>语义（已定决策）：
 * <ul>
 *   <li>进入名单的 UUID，**所有自动路径都跳过**：UUID 自愈①② / scan / recover /
 *       入包绑定 / transfer</li>
 *   <li>只在 {@code /ultracell getuuid} 与 {@code /ultracell debug} 里显示</li>
 *   <li>**自动失效**：对应的 {@code .dat.corrupt-*} 备份文件不存在了，条目即被丢弃</li>
 *   <li>提供手动清除入口（{@code /ultracell debug clear-corrupt}）</li>
 * </ul>
 */
public final class CorruptIndex {

    private final Path indexFile;
    private final Path cellsDir;
    private final Set<UUID> corrupt = new HashSet<>();

    private CorruptIndex(Path indexFile, Path cellsDir) {
        this.indexFile = indexFile;
        this.cellsDir = cellsDir;
    }

    /**
     * 读取损坏名单并做一次自动失效。
     *
     * <p>文件缺失或解析失败都按「空名单」处理 —— 名单本身坏了不该拦住启动。
     */
    public static CorruptIndex load(MinecraftServer server) {
        Path cellsDir = ExternalCellStorage.cellsDir(server);
        Path indexFile = ExternalCellStorage.corruptIndexFile(server);
        CorruptIndex index = new CorruptIndex(indexFile, cellsDir);

        if (!Files.isRegularFile(indexFile)) {
            return index;
        }
        try {
            CompoundTag tag = ExternalCellStorage.readCompressed(indexFile);
            ListTag list = tag.getList(ExternalCellStorage.TAG_CORRUPT_LIST, Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                try {
                    index.corrupt.add(UUID.fromString(list.getString(i)));
                } catch (IllegalArgumentException e) {
                    UltraCell.LOGGER.warn("Ultra Cell: ignoring malformed uuid in corrupt index: {}", list.getString(i));
                }
            }
        } catch (IOException | RuntimeException e) {
            UltraCell.LOGGER.error("Ultra Cell: failed to read corrupt index {}, treating as empty", indexFile, e);
            return index;
        }

        index.expireMissingBackups();
        return index;
    }

    /**
     * 自动失效：某个 UUID 的损坏备份已经不存在（被玩家手工清理）时，把它从名单里去掉。
     */
    private void expireMissingBackups() {
        if (this.corrupt.isEmpty()) {
            return;
        }
        Set<UUID> withBackup = new HashSet<>();
        try {
            for (Path backup : ExternalCellStorage.listCorruptFiles(this.cellsDir)) {
                String name = backup.getFileName().toString();
                int marker = name.indexOf(ExternalCellStorage.CORRUPT_MARKER);
                if (marker <= 0) {
                    continue;
                }
                String bare = name.substring(0, marker);
                if (bare.endsWith(ExternalCellStorage.DATA_SUFFIX)) {
                    bare = bare.substring(0, bare.length() - ExternalCellStorage.DATA_SUFFIX.length());
                }
                try {
                    withBackup.add(UUID.fromString(bare));
                } catch (IllegalArgumentException ignored) {
                    // 备份文件名不是 UUID，忽略
                }
            }
        } catch (IOException e) {
            UltraCell.LOGGER.error("Ultra Cell: failed to enumerate corrupt backups", e);
            return;
        }
        this.corrupt.retainAll(withBackup);
    }

    public boolean isCorrupt(UUID uuid) {
        return this.corrupt.contains(uuid);
    }

    public int size() {
        return this.corrupt.size();
    }

    public Set<UUID> all() {
        return Collections.unmodifiableSet(this.corrupt);
    }

    /** 记入名单并落盘。 */
    public void mark(UUID uuid) {
        if (this.corrupt.add(uuid)) {
            this.save();
        }
    }

    /** 清空名单并落盘（{@code /ultracell debug clear-corrupt}）。 */
    public void clear() {
        if (this.corrupt.isEmpty()) {
            return;
        }
        this.corrupt.clear();
        this.save();
    }

    private void save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (UUID uuid : this.corrupt) {
            list.add(StringTag.valueOf(uuid.toString()));
        }
        tag.put(ExternalCellStorage.TAG_CORRUPT_LIST, list);

        Path target = this.indexFile;
        IOUtilities.withIOWorker(() -> {
            try {
                ExternalCellStorage.writeCompressedAtomically(tag, target);
            } catch (IOException e) {
                UltraCell.LOGGER.error("Ultra Cell: failed to write corrupt index {}", target, e);
            }
        });
    }
}
