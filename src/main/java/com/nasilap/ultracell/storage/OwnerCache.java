package com.nasilap.ultracell.storage;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内存缓存：{@code UUID → CellData}。
 *
 * <p><b>不放 static</b>（存档级状态，static 会跨存档串档）：
 * 本对象由 {@link CellDataManager} 在 {@code ServerStartedEvent} 创建、
 * 在 {@code ServerStoppedEvent} 随管理器一起丢弃。
 *
 * <p>归属的权威源是数据文件；本缓存只是它在本会话内的镜像。
 * 因此所有归属读取都必须经过这里或文件，**不能**从 DC 摘要里取（DC 里没有归属）。
 */
public final class OwnerCache {

    private final Map<UUID, CellData> cells = new HashMap<>();

    @Nullable
    public CellData get(UUID uuid) {
        return this.cells.get(uuid);
    }

    public void put(CellData data) {
        this.cells.put(data.uuid(), data);
    }

    @Nullable
    public CellData remove(UUID uuid) {
        return this.cells.remove(uuid);
    }

    public boolean contains(UUID uuid) {
        return this.cells.containsKey(uuid);
    }

    public int size() {
        return this.cells.size();
    }

    public Collection<CellData> all() {
        return this.cells.values();
    }

    public Collection<UUID> uuids() {
        return this.cells.keySet();
    }

    public void clear() {
        this.cells.clear();
    }

    /** 无主条目（{@code recover} 的候选）。 */
    public List<CellData> ownerless() {
        List<CellData> result = new ArrayList<>();
        for (CellData data : this.cells.values()) {
            if (!data.hasOwner()) {
                result.add(data);
            }
        }
        return result;
    }

    /** 某玩家的条目。 */
    public List<CellData> ownedBy(UUID player) {
        List<CellData> result = new ArrayList<>();
        for (CellData data : this.cells.values()) {
            if (player.equals(data.owner())) {
                result.add(data);
            }
        }
        return result;
    }

    /** 所有正在等待落盘的条目。 */
    public List<CellData> dirty() {
        List<CellData> result = new ArrayList<>();
        for (CellData data : this.cells.values()) {
            if (data.isDirty()) {
                result.add(data);
            }
        }
        return result;
    }
}
