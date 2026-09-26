package com.nasilap.ultracell.command;

import com.nasilap.ultracell.UltraCell;
import com.nasilap.ultracell.storage.ExternalCellStorage;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 孤儿数据的计数与进存档提示（UC-005b）。
 *
 * <p>只做两件事：**每小时数一次文件**、**玩家进存档时提示一次**。不删除任何东西。
 *
 * <p>计数口径（已定决策）：
 * <ul>
 *   <li>只数 {@code cells/} 目录内的文件（损坏名单在上一级，天然不计入）</li>
 *   <li>N = **正常数据文件总数**（{@code .dat} 结尾且不含 {@code .corrupt-}）—— 不是精确孤儿数</li>
 *   <li>M = **损坏备份数**（名字含 {@code .corrupt-}）</li>
 *   <li>过滤规则与 {@code OwnerCache} / {@code scan} **同源**（都在 {@link ExternalCellStorage}）</li>
 * </ul>
 *
 * <p>刷新用 {@code ServerTickEvent.Post} + {@code hasTime()} + {@code nextRefreshTick} 变量，
 * **不用** {@code now % 72000 == 0}（那样在暂停/重启后会漏拍）。
 * tick 计数转 {@code Integer.toUnsignedLong} 后再比较，避免 int 溢出。
 */
public final class OrphanCounter {

    /** 刷新间隔：1 小时 = 72000 tick。 */
    private static final long REFRESH_INTERVAL_TICKS = 72_000L;

    private int normalFiles;
    private int corruptFiles;
    private long nextRefreshTick;
    private boolean initialised;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!event.hasTime()) {
            // 单机暂停时不推进计时，避免暂停期间把整点"跳过"
            return;
        }
        MinecraftServer server = event.getServer();
        long now = Integer.toUnsignedLong(server.getTickCount());
        if (!this.initialised) {
            this.refresh(server);
            this.nextRefreshTick = now + REFRESH_INTERVAL_TICKS;
            this.initialised = true;
            return;
        }
        if (now >= this.nextRefreshTick) {
            this.refresh(server);
            this.nextRefreshTick = now + REFRESH_INTERVAL_TICKS;
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!this.initialised) {
            this.refresh(player.getServer());
            this.initialised = true;
        }
        if (this.normalFiles <= 0 && this.corruptFiles <= 0) {
            return;
        }
        // 消息**不可点击**，措辞中性
        if (this.corruptFiles > 0) {
            player.sendSystemMessage(Component.translatable("ultracell.orphan.prompt.corrupt",
                    Integer.toString(this.normalFiles), Integer.toString(this.corruptFiles)));
        } else {
            player.sendSystemMessage(Component.translatable("ultracell.orphan.prompt.normal",
                    Integer.toString(this.normalFiles)));
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        this.normalFiles = 0;
        this.corruptFiles = 0;
        this.initialised = false;
    }

    /** 重新计数。 */
    private void refresh(MinecraftServer server) {
        Path cellsDir = ExternalCellStorage.cellsDir(server);
        try {
            List<Path> normal = ExternalCellStorage.listDataFiles(cellsDir);
            List<Path> corrupt = ExternalCellStorage.listCorruptFiles(cellsDir);
            this.normalFiles = normal.size();
            this.corruptFiles = corrupt.size();
        } catch (IOException e) {
            UltraCell.LOGGER.error("Ultra Cell: failed to count cell data files in {}", cellsDir, e);
        }
    }
}
