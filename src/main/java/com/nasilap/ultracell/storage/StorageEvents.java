package com.nasilap.ultracell.storage;

import com.nasilap.ultracell.util.IOUtilities;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 外置存储的服务端事件驱动：生命周期、写盘 tick、已加载区块集合。
 *
 * <p>本类是**实例**事件处理器（由主类注册），因此它的字段天然不跨存档：
 * 关服即随管理器一起丢。
 *
 * <p><b>已加载区块集合</b>（{@code (维度, ChunkPos)}）：
 * <ul>
 *   <li>{@code ChunkEvent.Load} **必须延迟到 tick 末**处理 —— 区块刚加载时方块实体尚未就绪</li>
 *   <li>{@code ChunkEvent.Unload} 立即移除</li>
 *   <li>只处理 {@code ServerLevel}</li>
 *   <li>{@code ServerStoppingEvent} 清空</li>
 *   <li>不使用 AE2 的轮询信号</li>
 * </ul>
 */
public final class StorageEvents {

    /** (维度, 区块坐标) 的键。 */
    public record ChunkKey(ResourceKey<Level> dimension, long chunkPos) {}

    /**
     * 当前实例（供 scan 读已加载区块集合）。
     *
     * <p>与 {@link CellDataManager#current()} 同性质：随服务器生命周期显式置空，
     * 本身不携带存档数据。缓存的"不放 static"约束针对的是 {@link OwnerCache} 里的存档状态。
     */
    @Nullable
    private static volatile StorageEvents instance;

    @Nullable
    public static StorageEvents instance() {
        return instance;
    }

    private final Set<ChunkKey> loadedChunks = new HashSet<>();
    private final Set<ChunkKey> pendingLoads = new HashSet<>();

    @Nullable
    private CellDataManager manager;

    public StorageEvents() {
        instance = this;
    }

    @Nullable
    public CellDataManager manager() {
        return this.manager;
    }

    /** 扫描用：当前已加载区块的快照。 */
    public Set<ChunkKey> loadedChunks() {
        return Collections.unmodifiableSet(this.loadedChunks);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        this.manager = CellDataManager.create(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        this.loadedChunks.clear();
        this.pendingLoads.clear();
        if (this.manager != null) {
            this.manager.flushAllNow();
            IOUtilities.waitUntilIOWorkerComplete();
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        if (this.manager != null) {
            this.manager.destroy();
            this.manager = null;
        }
        this.loadedChunks.clear();
        this.pendingLoads.clear();
        if (instance == this) {
            instance = null;
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        // 区块加载延迟到 tick 末并入集合
        if (!this.pendingLoads.isEmpty()) {
            this.loadedChunks.addAll(this.pendingLoads);
            this.pendingLoads.clear();
        }
        if (this.manager != null) {
            this.manager.tick();
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        this.pendingLoads.add(keyOf(serverLevel, event.getChunk().getPos()));
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        ChunkKey key = keyOf(serverLevel, event.getChunk().getPos());
        this.loadedChunks.remove(key);
        this.pendingLoads.remove(key);
    }

    private static ChunkKey keyOf(ServerLevel level, ChunkPos pos) {
        return new ChunkKey(level.dimension(), pos.toLong());
    }

    /** 供 scan 使用：把键还原成区块坐标。 */
    public static ChunkPos posOf(ChunkKey key) {
        return new ChunkPos(key.chunkPos());
    }

    /** 起服前的空集合（scan 在无管理器时也能给出空结果）。 */
    public List<ChunkKey> snapshot() {
        return new ArrayList<>(this.loadedChunks);
    }
}
