package com.nasilap.ultracell.command;

import com.nasilap.ultracell.item.ExternalCellItem;
import com.nasilap.ultracell.item.FECellItem;
import com.nasilap.ultracell.registry.ModItems;
import com.nasilap.ultracell.storage.CellData;
import com.nasilap.ultracell.storage.CellDataManager;
import com.nasilap.ultracell.storage.CellSummaryComponent;
import com.nasilap.ultracell.storage.ExternalCellDataFile;
import com.nasilap.ultracell.storage.ExternalCellStorage;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 元件命令：{@code getuuid} / {@code recover} / {@code trade} / {@code transfer}（UC-004）。
 *
 * <p>全部面向玩家的文本都走 {@code Component.translatable}（中英双语齐备）。
 *
 * <p>权限：只有 {@code transfer} 需要 OP（{@code hasPermission(2)}）；
 * 其余四个所有人可用，且都不做冷却。{@code transfer} 的拒绝由 Brigadier 在派发前完成，
 * 因此不存在「权限不足」自定义文案。
 *
 * <p>需要玩家源的子命令统一用 {@code getPlayerOrException()}：命令方块 / 控制台执行时
 * 会得到标准异常信息，行为明确。
 */
public final class CellCommands {

    /** 背包里可用于发放的空槽搜索范围（主背包 + 快捷栏）。 */
    private static final int INVENTORY_MAIN_SLOTS = 36;

    private CellCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(CommandRegistration.ROOT)
                .then(Commands.literal("getuuid").executes(CellCommands::getUuid))
                .then(Commands.literal("recover")
                        .then(Commands.argument("uuid", StringArgumentType.word()).executes(CellCommands::recover)))
                .then(Commands.literal("trade")
                        .then(Commands.argument("player", EntityArgument.player()).executes(CellCommands::trade)))
                .then(Commands.literal("transfer")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("uuid", StringArgumentType.word())
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(CellCommands::transfer)))));
    }

    // ── /ultracell getuuid ──

    private static int getUuid(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ItemStack stack = player.getMainHandItem();

        if (stack.isEmpty()) {
            source.sendFailure(Component.translatable("ultracell.command.getuuid.hand_empty"));
            return 0;
        }
        if (stack.getItem() instanceof FECellItem) {
            source.sendFailure(Component.translatable("ultracell.command.getuuid.fe_cell"));
            return 0;
        }
        if (!(stack.getItem() instanceof ExternalCellItem)) {
            source.sendFailure(Component.translatable("ultracell.command.getuuid.not_a_cell"));
            return 0;
        }

        CellDataManager manager = DebugCommand.requireManager(source);
        if (manager == null) {
            return 0;
        }

        boolean hadUuid = CellDataManager.summaryOf(stack).hasUuid();
        UUID uuid = manager.ensureUuid(stack);
        if (uuid == null) {
            source.sendFailure(Component.translatable("ultracell.command.getuuid.failed"));
            return 0;
        }

        // 披露写副作用：本次调用才分配 UUID（会在 tick 末建出空数据文件）
        if (!hadUuid) {
            source.sendSuccess(() -> Component.translatable("ultracell.command.getuuid.allocated"), false);
        }
        if (manager.corruptIndex().isCorrupt(uuid)) {
            source.sendSuccess(() -> Component.translatable("ultracell.command.getuuid.corrupt"), false);
        }

        Component clickable = Component.literal(uuid.toString())
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, uuid.toString()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("ultracell.command.getuuid.hover"))));
        source.sendSuccess(() -> Component.translatable("ultracell.command.getuuid.result", clickable), false);
        return 1;
    }

    // ── /ultracell recover <uuid> ──

    private static int recover(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        UUID uuid = DebugCommand.parseUuid(source, StringArgumentType.getString(context, "uuid"));
        if (uuid == null) {
            return 0;
        }
        CellDataManager manager = DebugCommand.requireManager(source);
        if (manager == null) {
            return 0;
        }
        if (manager.corruptIndex().isCorrupt(uuid)) {
            source.sendFailure(Component.translatable("ultracell.command.recover.corrupt"));
            return 0;
        }

        Path file = ExternalCellStorage.fileFor(manager.cellsDir(), uuid);
        ExternalCellDataFile.ReadResult result =
                ExternalCellDataFile.read(source.getServer().registryAccess(), file, uuid);
        switch (result.status()) {
            case MISSING -> {
                source.sendFailure(Component.translatable("ultracell.command.recover.no_file"));
                return 0;
            }
            case UNSUPPORTED_VERSION -> {
                source.sendFailure(Component.translatable("ultracell.command.recover.unsupported",
                        Integer.toString(result.version())));
                return 0;
            }
            case UUID_MISMATCH -> {
                // 以 DC 为准：拒绝加载、不改名（A4 自愈③）
                source.sendFailure(Component.translatable("ultracell.command.recover.uuid_mismatch"));
                return 0;
            }
            case CORRUPT -> {
                try {
                    ExternalCellStorage.renameToCorrupt(file);
                } catch (Exception e) {
                    source.sendFailure(Component.translatable("ultracell.command.recover.rename_failed"));
                    return 0;
                }
                manager.corruptIndex().mark(uuid);
                source.sendFailure(Component.translatable("ultracell.command.recover.corrupt_file"));
                return 0;
            }
            case OK -> {
                // 继续
            }
        }

        CellData fileData = result.data();
        if (fileData == null) {
            source.sendFailure(Component.translatable("ultracell.command.recover.no_file"));
            return 0;
        }

        // 前提：无主，或执行者就是当前所有者
        CellData cached = manager.get(uuid);
        UUID owner = cached != null ? cached.owner() : fileData.owner();
        if (owner != null && !owner.equals(player.getUUID())) {
            source.sendFailure(Component.translatable("ultracell.command.recover.owned_by_other"));
            return 0;
        }

        Item item = ModItems.cellForTierKind(fileData.tier(), fileData.kind());
        if (item == null) {
            source.sendFailure(Component.translatable("ultracell.command.recover.type_unavailable",
                    fileData.tier(), fileData.kind()));
            return 0;
        }

        // E5：发放前检查背包能否容纳，不能则拒绝（否则会掉在地上、不触发入包绑定）
        if (!hasFreeSlot(player)) {
            source.sendFailure(Component.translatable("ultracell.command.recover.inventory_full"));
            return 0;
        }

        // 把磁盘上的数据接入内存缓存（若尚未在缓存里），并**立即**登记归属：
        // 缓存同步、落盘异步 —— 防止同一 tick 内被重复发放
        CellData data = cached != null ? cached : fileData;
        if (cached == null) {
            manager.cache().put(data);
        }
        manager.setOwnerForced(data, player.getUUID());

        ItemStack issued = new ItemStack(item);
        // 回填摘要：优先取内存缓存（即上一步的 data），而不是文件里的旧值
        CellDataManager.setSummary(issued,
                CellSummaryComponent.of(uuid, data.typeCount(), data.used()));
        player.getInventory().placeItemBackInInventory(issued);

        source.sendSuccess(() -> Component.translatable("ultracell.command.recover.success",
                uuid.toString(), fileData.tier(), fileData.kind()), true);
        return 1;
    }

    // ── /ultracell trade <player> ──

    private static int trade(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(context, "player");

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof ExternalCellItem)) {
            source.sendFailure(Component.translatable("ultracell.command.trade.not_a_cell"));
            return 0;
        }
        CellDataManager manager = DebugCommand.requireManager(source);
        if (manager == null) {
            return 0;
        }
        CellData data = manager.dataForItem(stack);
        if (data == null) {
            source.sendFailure(Component.translatable("ultracell.command.trade.not_ready"));
            return 0;
        }
        if (!player.getUUID().equals(data.owner())) {
            source.sendFailure(Component.translatable("ultracell.command.trade.not_owner"));
            return 0;
        }
        if (target.getUUID().equals(player.getUUID())) {
            source.sendFailure(Component.translatable("ultracell.command.trade.self"));
            return 0;
        }

        // 只改归属字段，不搬运物品；归属变更 = ③ 强制立即落盘
        manager.setOwnerForced(data, target.getUUID());

        target.sendSystemMessage(Component.translatable("ultracell.command.trade.notify",
                player.getDisplayName()));
        source.sendSuccess(() -> Component.translatable("ultracell.command.trade.success",
                target.getDisplayName()), false);
        return 1;
    }

    // ── /ultracell transfer <uuid> <player>（OP） ──

    private static int transfer(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        UUID uuid = DebugCommand.parseUuid(source, StringArgumentType.getString(context, "uuid"));
        if (uuid == null) {
            return 0;
        }
        String targetName = StringArgumentType.getString(context, "player");
        CellDataManager manager = DebugCommand.requireManager(source);
        if (manager == null) {
            return 0;
        }
        if (manager.corruptIndex().isCorrupt(uuid)) {
            source.sendFailure(Component.translatable("ultracell.command.transfer.corrupt"));
            return 0;
        }

        UUID targetId = resolvePlayerId(source, targetName);
        if (targetId == null) {
            source.sendFailure(Component.translatable("ultracell.command.transfer.unknown_player", targetName));
            return 0;
        }

        CellData data = manager.get(uuid);
        if (data == null) {
            Path file = ExternalCellStorage.fileFor(manager.cellsDir(), uuid);
            ExternalCellDataFile.ReadResult result =
                    ExternalCellDataFile.read(source.getServer().registryAccess(), file, uuid);
            switch (result.status()) {
                case OK -> {
                    data = result.data();
                    manager.cache().put(data);
                }
                case UNSUPPORTED_VERSION -> {
                    source.sendFailure(Component.translatable("ultracell.command.transfer.unsupported",
                            Integer.toString(result.version())));
                    return 0;
                }
                case UUID_MISMATCH -> {
                    source.sendFailure(Component.translatable("ultracell.command.transfer.uuid_mismatch"));
                    return 0;
                }
                case CORRUPT -> {
                    try {
                        ExternalCellStorage.renameToCorrupt(file);
                    } catch (Exception e) {
                        source.sendFailure(Component.translatable("ultracell.command.transfer.rename_failed"));
                        return 0;
                    }
                    manager.corruptIndex().mark(uuid);
                    source.sendFailure(Component.translatable("ultracell.command.transfer.corrupt_file"));
                    return 0;
                }
                case MISSING -> {
                    source.sendFailure(Component.translatable("ultracell.command.transfer.no_file"));
                    return 0;
                }
            }
        }
        if (data == null) {
            source.sendFailure(Component.translatable("ultracell.command.transfer.no_file"));
            return 0;
        }

        boolean hadOwner = data.hasOwner();
        manager.setOwnerForced(data, targetId);
        source.sendSuccess(() -> Component.translatable(
                hadOwner ? "ultracell.command.transfer.overwritten" : "ultracell.command.transfer.bound",
                uuid.toString(), targetName), true);
        return 1;
    }

    // ── 工具 ──

    /** 主背包 + 快捷栏是否有空槽（发放前检查，避免掉地上）。 */
    private static boolean hasFreeSlot(ServerPlayer player) {
        for (int slot = 0; slot < INVENTORY_MAIN_SLOTS; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 在线优先、离线走档案缓存地解析玩家名 → UUID。 */
    @Nullable
    private static UUID resolvePlayerId(CommandSourceStack source, String name) {
        ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(name);
        if (online != null) {
            return online.getUUID();
        }
        var profileCache = source.getServer().getProfileCache();
        if (profileCache == null) {
            return null;
        }
        return profileCache.get(name).map(profile -> profile.getId()).orElse(null);
    }
}
