package com.nasilap.ultracell.command;

import com.nasilap.ultracell.storage.CellData;
import com.nasilap.ultracell.storage.CellDataManager;
import com.nasilap.ultracell.storage.ExternalCellStorage;
import com.nasilap.ultracell.storage.StorageEvents;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /ultracell scan} —— 列出「有数据文件、但当前加载范围内找不到对应元件」的条目（UC-005a）。
 *
 * <p>它**只报告**，不恢复、不写入、不创建文件：
 * <ul>
 *   <li>只读路径**不自愈**（不会因为 scan 而生成 {@code .dat}）</li>
 *   <li>损坏名单里的条目**不列出**（那属于 {@code getuuid} 与 {@code debug} 的展示范围）</li>
 *   <li>跳过"无法读取"的文件并单独计数</li>
 * </ul>
 *
 * <p>两类条目：
 * <ul>
 *   <li><b>无主</b> → 可恢复，给一个可直接点的 recover 按钮</li>
 *   <li><b>自己的、但当前未加载</b> → 只标注：显式给按钮会鼓励"元件其实在某个未加载区块里"
 *       的误操作；这里给一行**可点击提示**，让玩家自己确认后再执行</li>
 * </ul>
 *
 * <p>门控：pending + TTL 30 秒（不用隐藏子命令）；**confirm 无论成败必清 pending**；
 * 冷却 = 全局 1 分钟 + 玩家自身 1 分钟，两者都满足才执行，且**成功才更新**。
 */
public final class ScanCommand {

    /** 二次确认的 pending 存活时间：30 秒 = 600 tick。 */
    private static final long PENDING_TTL_TICKS = 600L;

    /** 冷却：1 分钟 = 1200 tick（全局与每玩家各一份）。 */
    private static final long COOLDOWN_TICKS = 1200L;

    /** 列表显示上限；超出部分只报数量。 */
    private static final int MAX_ENTRIES_SHOWN = 10;

    /** 服务器本地时区的显示格式（已登记的已知行为）。 */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final Map<UUID, Long> PENDING_UNTIL = new HashMap<>();
    private static final Map<UUID, Long> PLAYER_COOLDOWN_UNTIL = new HashMap<>();
    private static long globalCooldownUntil;

    private ScanCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(CommandRegistration.ROOT)
                .then(Commands.literal("scan").executes(ScanCommand::scan)));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING_UNTIL.clear();
        PLAYER_COOLDOWN_UNTIL.clear();
        globalCooldownUntil = 0L;
    }

    // ── 主流程 ──

    private static int scan(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        CellDataManager manager = DebugCommand.requireManager(source);
        if (manager == null) {
            return 0;
        }

        long now = Integer.toUnsignedLong(source.getServer().getTickCount());
        UUID playerId = player.getUUID();

        Long pendingUntil = PENDING_UNTIL.get(playerId);
        boolean confirming = pendingUntil != null && now <= pendingUntil;

        if (!confirming) {
            // 第一次调用：进入 pending，给确认提示
            PENDING_UNTIL.put(playerId, now + PENDING_TTL_TICKS);
            MutableComponent confirmButton = Component.translatable("ultracell.command.scan.confirm.button")
                    .withStyle(Style.EMPTY
                            .withColor(ChatFormatting.YELLOW)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                    "/" + CommandRegistration.ROOT + " scan"))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.translatable("ultracell.command.scan.confirm.hover"))));
            source.sendSuccess(() -> Component.translatable("ultracell.command.scan.confirm", confirmButton), false);
            return 1;
        }

        // 确认步：无论成败都清 pending
        PENDING_UNTIL.remove(playerId);

        long globalLeft = globalCooldownUntil - now;
        long playerLeft = PLAYER_COOLDOWN_UNTIL.getOrDefault(playerId, 0L) - now;
        if (globalLeft > 0L || playerLeft > 0L) {
            long left = Math.max(globalLeft, playerLeft);
            long seconds = Math.max(1L, (left + 19L) / 20L);
            source.sendFailure(Component.translatable("ultracell.command.scan.cooldown", Long.toString(seconds)));
            return 0;
        }

        boolean success = runScan(source, player, manager, now);
        if (success) {
            globalCooldownUntil = now + COOLDOWN_TICKS;
            PLAYER_COOLDOWN_UNTIL.put(playerId, now + COOLDOWN_TICKS);
        }
        return success ? 1 : 0;
    }

    private static boolean runScan(CommandSourceStack source, ServerPlayer player,
                                   CellDataManager manager, long now) {
        Set<UUID> active = ActiveCellScanner.collect(source.getServer(), storageEvents());

        List<Path> files;
        try {
            files = ExternalCellStorage.listDataFiles(manager.cellsDir());
        } catch (IOException e) {
            source.sendFailure(Component.translatable("ultracell.command.scan.read_failed"));
            return false;
        }

        List<Entry> recoverable = new ArrayList<>();
        List<Entry> own = new ArrayList<>();
        int unreadable = 0;

        for (Path file : files) {
            Optional<UUID> parsed = ExternalCellStorage.uuidFromFileName(file.getFileName().toString());
            if (parsed.isEmpty()) {
                continue;
            }
            UUID uuid = parsed.get();
            if (active.contains(uuid)) {
                continue;
            }
            if (manager.corruptIndex().isCorrupt(uuid)) {
                continue;
            }

            CellData data = manager.get(uuid);
            if (data == null) {
                // 无法读取（例如未知格式版本）⇒ 不提供恢复入口，只计数
                unreadable++;
                continue;
            }

            Entry entry = new Entry(uuid, lastModified(file));
            if (!data.hasOwner()) {
                recoverable.add(entry);
            } else if (player.getUUID().equals(data.owner())) {
                own.add(entry);
            }
            // 别人的条目：不显示
        }

        source.sendSuccess(() -> Component.translatable("ultracell.command.scan.header",
                Integer.toString(recoverable.size()), Integer.toString(own.size())), false);

        int shown = 0;
        int remaining = 0;

        for (Entry entry : recoverable) {
            if (shown >= MAX_ENTRIES_SHOWN) {
                remaining++;
                continue;
            }
            source.sendSuccess(() -> entryLine(entry, true), false);
            shown++;
        }
        for (Entry entry : own) {
            if (shown >= MAX_ENTRIES_SHOWN) {
                remaining++;
                continue;
            }
            source.sendSuccess(() -> entryLine(entry, false), false);
            shown++;
        }

        // 这两行是必须的：remaining / unreadable 在循环里被自增，
        // 属于"非 effectively final"，不能在 lambda 里直接引用。
        final int remainingCount = remaining;
        final int unreadableCount = unreadable;

        if (remainingCount > 0) {
            source.sendSuccess(() -> Component.translatable("ultracell.command.scan.more",
                    Integer.toString(remainingCount)), false);
        }
        if (unreadableCount > 0) {
            source.sendSuccess(() -> Component.translatable("ultracell.command.scan.unreadable",
                    Integer.toString(unreadableCount)), false);
        }
        source.sendSuccess(() -> Component.translatable("ultracell.command.scan.scope_warning"), false);
        return true;
    }

    /** 一行条目：UUID（可点复制）+ 分类 + 最后写入时间 + 相应入口。 */
    private static Component entryLine(Entry entry, boolean ownerless) {
        MutableComponent uuidText = Component.literal(entry.uuid().toString())
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, entry.uuid().toString()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("ultracell.command.scan.copy_hover"))));

        String command = "/" + CommandRegistration.ROOT + " recover " + entry.uuid();

        if (ownerless) {
            MutableComponent button = Component.translatable("ultracell.command.scan.recover_button")
                    .withStyle(Style.EMPTY
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.translatable("ultracell.command.scan.recover_hover"))));
            return Component.translatable("ultracell.command.scan.entry.ownerless",
                    uuidText, entry.timeText(), button);
        }

        // 己方未加载：不代发，只给一条可点击提示（建议命令，不直接执行）
        MutableComponent hint = Component.translatable("ultracell.command.scan.own_hint")
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.GRAY)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("ultracell.command.scan.own_hint_hover"))));
        return Component.translatable("ultracell.command.scan.entry.own",
                uuidText, entry.timeText(), hint);
    }

    private static StorageEvents storageEvents() {
        return StorageEvents.instance();
    }

    private static String lastModified(Path file) {
        try {
            FileTime time = Files.getLastModifiedTime(file);
            return TIME_FORMAT.format(Instant.ofEpochMilli(time.toMillis()));
        } catch (IOException e) {
            return "-";
        }
    }

    /** 一条扫描结果。 */
    private record Entry(UUID uuid, String timeText) {}
}
