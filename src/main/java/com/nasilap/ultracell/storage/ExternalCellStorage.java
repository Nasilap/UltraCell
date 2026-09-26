package com.nasilap.ultracell.storage;

import com.nasilap.ultracell.UltraCell;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 外置存储的**路径、常量与文件列举**。
 *
 * <p>设计要点：
 * <ul>
 *   <li>一元件一文件，扁平目录 {@code <存档根>/ultracell/cells/<uuid>.dat}</li>
 *   <li>{@code cells/} 的列举与过滤**只有这一份实现** ——
 *       {@code OwnerCache} / {@code OrphanCounter} / {@code scan} 三处必须共用它，
 *       否则会出现 {@code endsWith(".dat")} 与 {@code contains(".dat")} 之类
 *       静默口径分歧（无声少算或重算）</li>
 *   <li>写盘走「先写 {@code .tmp} 再原子改名」，避免半截文件</li>
 * </ul>
 */
public final class ExternalCellStorage {

    // ── tier / kind 常量（唯一出处；接口里不放） ──

    public static final String TIER_HONGMENG = "hongmeng";
    public static final String TIER_WUJI = "wuji";
    public static final String KIND_FLUID = "fluid";
    public static final String KIND_CHEMICAL = "chemical";

    // ── 存储模型常量 ──

    /** 文件格式版本；未知版本一律拒绝加载（只读 + 拒写 + 记日志，不改名备份）。 */
    public static final int FORMAT_VERSION = 1;

    /** 类型位上限。 */
    public static final int HONGMENG_TYPE_SLOTS = 128;
    public static final int WUJI_TYPE_SLOTS = 512;

    /** 总量上限（= 元件常量）。 */
    public static final UInt192 HONGMENG_TOTAL = UInt192.MAX_UINT128;
    public static final UInt192 WUJI_TOTAL = UInt192.MAX_UINT192;

    /** 单类型上限 = 总量 / 类型位 = 2^121−1 / 2^183−1。 */
    public static final UInt192 HONGMENG_TYPE_LIMIT = new UInt192(0L, 0x01FFFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFFFL);
    public static final UInt192 WUJI_TYPE_LIMIT = new UInt192(0x007FFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFFFL);

    // ── 目录与文件名 ──

    /** 本模组在存档根下的数据目录名。 */
    public static final String DATA_DIR_NAME = "ultracell";

    /** 元件数据文件所在子目录名。 */
    public static final String CELLS_DIR_NAME = "cells";

    /** 正常数据文件后缀。 */
    public static final String DATA_SUFFIX = ".dat";

    /** 损坏备份文件名里的标记：{@code <uuid>.dat.corrupt-<时间戳>}。 */
    public static final String CORRUPT_MARKER = ".corrupt-";

    /** 损坏名单文件名（**在 {@code cells/} 之外**）。 */
    public static final String CORRUPT_INDEX_NAME = "corrupt_index.dat";

    // ── NBT 字段名 ──

    public static final String TAG_FORMAT_VERSION = "ultracell_format_version";
    public static final String TAG_TIER = "tier";
    public static final String TAG_KIND = "kind";
    public static final String TAG_UUID_HIGH = "uuid_high";
    public static final String TAG_UUID_LOW = "uuid_low";
    public static final String TAG_OWNER_HIGH = "owner_high";
    public static final String TAG_OWNER_LOW = "owner_low";
    public static final String TAG_ENTRIES = "entries";
    public static final String TAG_ENTRY_KEY = "key";
    public static final String TAG_COUNT_HIGH = "count_high";
    public static final String TAG_COUNT_MID = "count_mid";
    public static final String TAG_COUNT_LOW = "count_low";

    /** 损坏名单文件里的 UUID 列表标签。 */
    public static final String TAG_CORRUPT_LIST = "corrupt";

    private ExternalCellStorage() {}

    // ── 等级参数查询 ──

    public static boolean isKnownTier(String tier) {
        return TIER_HONGMENG.equals(tier) || TIER_WUJI.equals(tier);
    }

    public static boolean isKnownKind(String kind) {
        return KIND_FLUID.equals(kind) || KIND_CHEMICAL.equals(kind);
    }

    public static int typeSlotsFor(String tier) {
        return TIER_WUJI.equals(tier) ? WUJI_TYPE_SLOTS : HONGMENG_TYPE_SLOTS;
    }

    public static UInt192 totalFor(String tier) {
        return TIER_WUJI.equals(tier) ? WUJI_TOTAL : HONGMENG_TOTAL;
    }

    public static UInt192 typeLimitFor(String tier) {
        return TIER_WUJI.equals(tier) ? WUJI_TYPE_LIMIT : HONGMENG_TYPE_LIMIT;
    }

    /** 由类型位数反查单类型上限（元件库存只知道槽位数时用）。 */
    public static UInt192 typeLimitForSlots(int typeSlots) {
        return typeSlots >= WUJI_TYPE_SLOTS ? WUJI_TYPE_LIMIT : HONGMENG_TYPE_LIMIT;
    }

    /** 另一个等级（鸿蒙 ↔ 无极）。 */
    public static String otherTier(String tier) {
        return TIER_WUJI.equals(tier) ? TIER_HONGMENG : TIER_WUJI;
    }

    // ── 路径 ──

    /** {@code <存档根>/ultracell}。 */
    public static Path dataDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(DATA_DIR_NAME);
    }

    /** {@code <存档根>/ultracell/cells}。 */
    public static Path cellsDir(MinecraftServer server) {
        return dataDir(server).resolve(CELLS_DIR_NAME);
    }

    /** 损坏名单文件：{@code <存档根>/ultracell/corrupt_index.dat}。 */
    public static Path corruptIndexFile(MinecraftServer server) {
        return dataDir(server).resolve(CORRUPT_INDEX_NAME);
    }

    /** 某 UUID 的数据文件路径。 */
    public static Path fileFor(Path cellsDir, UUID uuid) {
        return cellsDir.resolve(fileNameFor(uuid));
    }

    /** 某 UUID 的数据文件名：小写 UUID + {@code .dat}。 */
    public static String fileNameFor(UUID uuid) {
        return uuid.toString().toLowerCase(Locale.ROOT) + DATA_SUFFIX;
    }

    /** 保证目录存在。 */
    public static void ensureDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
        }
    }

    // ── 文件列举（**唯一一份过滤规则**） ──

    /** 正常数据文件判定：以 {@code .dat} 结尾。 */
    public static boolean isDataFileName(String fileName) {
        return fileName.endsWith(DATA_SUFFIX);
    }

    /** 损坏备份文件判定：名字里含 {@code .corrupt-}。 */
    public static boolean isCorruptFileName(String fileName) {
        return fileName.contains(CORRUPT_MARKER);
    }

    /** 从正常数据文件名反解 UUID；不是正常文件或名字不是合法 UUID 时返回空。 */
    public static Optional<UUID> uuidFromFileName(String fileName) {
        if (!isDataFileName(fileName) || isCorruptFileName(fileName)) {
            return Optional.empty();
        }
        String bare = fileName.substring(0, fileName.length() - DATA_SUFFIX.length());
        try {
            return Optional.of(UUID.fromString(bare));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** {@code cells/} 下的**正常**数据文件（不含损坏备份、不含目录、不含杂项文件）。 */
    public static List<Path> listDataFiles(Path cellsDir) throws IOException {
        return listMatching(cellsDir, true);
    }

    /** {@code cells/} 下的**损坏**备份文件。 */
    public static List<Path> listCorruptFiles(Path cellsDir) throws IOException {
        return listMatching(cellsDir, false);
    }

    private static List<Path> listMatching(Path cellsDir, boolean wantData) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(cellsDir)) {
            return result;
        }
        try (var stream = Files.list(cellsDir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String name = path.getFileName().toString();
                boolean matched = wantData
                        ? (isDataFileName(name) && !isCorruptFileName(name))
                        : isCorruptFileName(name);
                if (matched) {
                    result.add(path);
                }
            }
        }
        return result;
    }

    // ── 读写 ──

    /** 读取压缩 NBT；文件不存在或解析失败都会抛 {@link IOException}，由调用方区分。 */
    public static CompoundTag readCompressed(Path file) throws IOException {
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
    }

    /**
     * 原子写入压缩 NBT：先写同目录的 {@code .tmp}，再原子改名覆盖目标。
     *
     * <p>这样即使进程在写盘途中被杀，也不会留下半截的 {@code <uuid>.dat}。
     * 原子改名不被文件系统支持时退化为普通替换。
     */
    public static void writeCompressedAtomically(CompoundTag tag, Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            ensureDirectory(parent);
        }
        Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        NbtIo.writeCompressed(tag, temp);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 把某个正常数据文件改名为损坏备份，返回备份路径。 */
    public static Path renameToCorrupt(Path file) throws IOException {
        Path backup = file.resolveSibling(file.getFileName().toString() + CORRUPT_MARKER + System.currentTimeMillis());
        Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
        UltraCell.LOGGER.warn("Ultra Cell: cell data file {} marked corrupt, backup kept at {}", file, backup);
        return backup;
    }
}
