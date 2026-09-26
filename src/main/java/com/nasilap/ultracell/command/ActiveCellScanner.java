package com.nasilap.ultracell.command;

import com.nasilap.ultracell.item.ExternalCellItem;
import com.nasilap.ultracell.storage.CellDataManager;
import com.nasilap.ultracell.storage.StorageEvents;

import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import net.neoforged.neoforge.capabilities.Capabilities;

import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 「活跃元件集合」的收集器（UC-005a）。
 *
 * <p>五条来源（缺一不可，否则会把正在使用的元件误判成孤儿）：
 * <ol>
 *   <li>在线玩家背包（41 槽：主背包 36 + 盔甲 4 + 副手 1）</li>
 *   <li>在线玩家末影箱（27 槽）</li>
 *   <li>**已加载区块里的方块实体** —— 判据是 NeoForge 能力
 *       {@code Capabilities.ItemHandler.BLOCK}，<b>不是</b> vanilla {@code Container}：
 *       AE2 的驱动器 / ME 箱子**不实现** {@code Container}，照 {@code Container} 找会
 *       整个漏掉它们，把正在网络里工作的元件报成"无主孤儿"</li>
 *   <li>掉落中的 {@code ItemEntity}</li>
 *   <li>菜单光标 {@code getCarried()}</li>
 * </ol>
 *
 * <p>枚举范围由 {@link StorageEvents} 维护的 (维度, ChunkPos) 集合决定，
 * 且 {@code ChunkEvent.Load} 已延迟到 tick 末并入 —— 因此这里看到的方块实体是就绪的。
 */
public final class ActiveCellScanner {

    private ActiveCellScanner() {}

    /** 收集当前加载范围内的全部活跃元件 UUID。 */
    public static Set<UUID> collect(MinecraftServer server, @Nullable StorageEvents events) {
        Set<UUID> active = new HashSet<>();

        // 来源 1 / 2 / 5：在线玩家
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            collectFromContainer(player.getInventory(), active);
            collectFromContainer(player.getEnderChestInventory(), active);
            collectFromStack(player.containerMenu.getCarried(), active);
        }

        // 来源 4：掉落中的物品
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ItemEntity itemEntity) {
                    collectFromStack(itemEntity.getItem(), active);
                }
            }
        }

        // 来源 3：已加载区块的方块实体（走 NeoForge 能力）
        if (events != null) {
            for (StorageEvents.ChunkKey key : events.loadedChunks()) {
                ServerLevel level = server.getLevel(key.dimension());
                if (level == null) {
                    continue;
                }
                ChunkPos pos = StorageEvents.posOf(key);
                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    collectFromBlockEntity(level, blockEntity, active);
                }
            }
        }

        return active;
    }

    private static void collectFromBlockEntity(ServerLevel level, BlockEntity blockEntity, Set<UUID> active) {
        var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, blockEntity.getBlockPos(),
                (Direction) null);
        if (handler == null) {
            return;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            collectFromStack(handler.getStackInSlot(slot), active);
        }
    }

    private static void collectFromContainer(Container container, Set<UUID> active) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            collectFromStack(container.getItem(slot), active);
        }
    }

    private static void collectFromStack(ItemStack stack, Set<UUID> active) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ExternalCellItem)) {
            return;
        }
        UUID uuid = CellDataManager.summaryOf(stack).uuid();
        if (uuid != null) {
            active.add(uuid);
        }
    }
}
