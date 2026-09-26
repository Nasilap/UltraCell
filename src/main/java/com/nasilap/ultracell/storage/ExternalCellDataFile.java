package com.nasilap.ultracell.storage;

import appeng.api.stacks.AEKey;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一个 {@code <uuid>.dat} 的读写。
 *
 * <p>文件结构（压缩 NBT）：
 * <pre>
 *   ultracell_format_version  int     格式版本，当前为 1
 *   tier                      string  hongmeng / wuji
 *   kind                      string  fluid / chemical
 *   uuid_high / uuid_low      long    本文件的 UUID
 *   owner_high / owner_low    long    归属；无主时**整个字段省略**
 *   entries                   list    每项 { key: &lt;AEKey 通用标签&gt;, count_high/mid/low }
 * </pre>
 *
 * <p>读取结果的四种状态含义（对应已定决策）：
 * <ul>
 *   <li>{@link Status#OK} —— 正常</li>
 *   <li>{@link Status#MISSING} —— 文件不存在</li>
 *   <li>{@link Status#CORRUPT} —— 解析失败 / 内容自相矛盾 → 触发「改名备份 + 进损坏名单」</li>
 *   <li>{@link Status#UNSUPPORTED_VERSION} —— 版本不认识 → **只读 + 拒写 + 记日志，不改名**</li>
 *   <li>{@link Status#UUID_MISMATCH} —— 文件内 UUID 与文件名不符 → **以 DC 为准、拒绝加载、
 *       只读 + 记日志，不改名**</li>
 * </ul>
 */
public final class ExternalCellDataFile {

    public enum Status {
        OK,
        MISSING,
        CORRUPT,
        UNSUPPORTED_VERSION,
        /**
         * 文件内部 UUID 与文件名（= DC 里的 UUID）不一致。
         *
         * <p>按 A4 自愈③ 的字面要求处理：**以 DC 为准、拒绝加载、只读、记日志，不改名备份**
         * （与 {@link #CORRUPT} 的区别就在这里 —— 后者会改名 + 进损坏名单）。
         */
        UUID_MISMATCH
    }

    /**
     * 读取结果。
     *
     * @param status  状态
     * @param data    仅在 {@link Status#OK} 时非空
     * @param version 文件里读到的版本号（读不到时为 0）
     * @param detail  诊断信息
     */
    public record ReadResult(Status status, @Nullable CellData data, int version, String detail) {

        public boolean isOk() {
            return this.status == Status.OK;
        }
    }

    private ExternalCellDataFile() {}

    // ── 读 ──

    public static ReadResult read(HolderLookup.Provider registries, Path file, UUID expectedUuid) {
        if (!Files.isRegularFile(file)) {
            return new ReadResult(Status.MISSING, null, 0, "file not found");
        }

        CompoundTag tag;
        try {
            tag = ExternalCellStorage.readCompressed(file);
        } catch (IOException | RuntimeException e) {
            return new ReadResult(Status.CORRUPT, null, 0, "unreadable: " + e);
        }

        if (!tag.contains(ExternalCellStorage.TAG_FORMAT_VERSION, Tag.TAG_ANY_NUMERIC)) {
            return new ReadResult(Status.CORRUPT, null, 0, "missing " + ExternalCellStorage.TAG_FORMAT_VERSION);
        }
        int version = tag.getInt(ExternalCellStorage.TAG_FORMAT_VERSION);
        if (version != ExternalCellStorage.FORMAT_VERSION) {
            return new ReadResult(Status.UNSUPPORTED_VERSION, null, version, "unknown format version " + version);
        }

        String tier = tag.getString(ExternalCellStorage.TAG_TIER);
        String kind = tag.getString(ExternalCellStorage.TAG_KIND);
        if (!ExternalCellStorage.isKnownTier(tier) || !ExternalCellStorage.isKnownKind(kind)) {
            return new ReadResult(Status.CORRUPT, null, version, "bad tier/kind: " + tier + "/" + kind);
        }

        long uuidHigh = tag.getLong(ExternalCellStorage.TAG_UUID_HIGH);
        long uuidLow = tag.getLong(ExternalCellStorage.TAG_UUID_LOW);
        UUID fileUuid = new UUID(uuidHigh, uuidLow);
        if (!fileUuid.equals(expectedUuid)) {
            return new ReadResult(Status.UUID_MISMATCH, null, version,
                    "uuid inside file (" + fileUuid + ") does not match its name (" + expectedUuid + ")");
        }

        Map<AEKey, UInt192> entries = new LinkedHashMap<>();
        ListTag list = tag.getList(ExternalCellStorage.TAG_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.contains(ExternalCellStorage.TAG_ENTRY_KEY, Tag.TAG_COMPOUND)) {
                return new ReadResult(Status.CORRUPT, null, version, "entry #" + i + " has no key");
            }
            AEKey key = AEKey.fromTagGeneric(registries, entry.getCompound(ExternalCellStorage.TAG_ENTRY_KEY));
            if (key == null) {
                return new ReadResult(Status.CORRUPT, null, version, "entry #" + i + " key cannot be resolved");
            }
            UInt192 count = new UInt192(
                    entry.getLong(ExternalCellStorage.TAG_COUNT_HIGH),
                    entry.getLong(ExternalCellStorage.TAG_COUNT_MID),
                    entry.getLong(ExternalCellStorage.TAG_COUNT_LOW));
            if (!count.isZero()) {
                entries.put(key, count);
            }
        }

        CellData data = new CellData(fileUuid, tier, kind);
        data.replaceEntries(entries);
        if (tag.contains(ExternalCellStorage.TAG_OWNER_HIGH) || tag.contains(ExternalCellStorage.TAG_OWNER_LOW)) {
            long ownerHigh = tag.getLong(ExternalCellStorage.TAG_OWNER_HIGH);
            long ownerLow = tag.getLong(ExternalCellStorage.TAG_OWNER_LOW);
            if (ownerHigh != 0L || ownerLow != 0L) {
                data.setOwner(new UUID(ownerHigh, ownerLow));
            }
        }
        return new ReadResult(Status.OK, data, version, "");
    }

    // ── 写 ──

    /** 把内存态冻结成 NBT 快照。**必须在主线程调用**（需要注册表）。 */
    public static CompoundTag toTag(HolderLookup.Provider registries, CellData data) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(ExternalCellStorage.TAG_FORMAT_VERSION, ExternalCellStorage.FORMAT_VERSION);
        tag.putString(ExternalCellStorage.TAG_TIER, data.tier());
        tag.putString(ExternalCellStorage.TAG_KIND, data.kind());
        tag.putLong(ExternalCellStorage.TAG_UUID_HIGH, data.uuid().getMostSignificantBits());
        tag.putLong(ExternalCellStorage.TAG_UUID_LOW, data.uuid().getLeastSignificantBits());

        UUID owner = data.owner();
        if (owner != null) {
            tag.putLong(ExternalCellStorage.TAG_OWNER_HIGH, owner.getMostSignificantBits());
            tag.putLong(ExternalCellStorage.TAG_OWNER_LOW, owner.getLeastSignificantBits());
        }

        ListTag list = new ListTag();
        for (Map.Entry<AEKey, UInt192> entry : data.entryView()) {
            UInt192 count = entry.getValue();
            if (count.isZero()) {
                continue;
            }
            CompoundTag entryTag = new CompoundTag();
            entryTag.put(ExternalCellStorage.TAG_ENTRY_KEY, entry.getKey().toTagGeneric(registries));
            entryTag.putLong(ExternalCellStorage.TAG_COUNT_HIGH, count.high());
            entryTag.putLong(ExternalCellStorage.TAG_COUNT_MID, count.mid());
            entryTag.putLong(ExternalCellStorage.TAG_COUNT_LOW, count.low());
            list.add(entryTag);
        }
        tag.put(ExternalCellStorage.TAG_ENTRIES, list);
        return tag;
    }
}
