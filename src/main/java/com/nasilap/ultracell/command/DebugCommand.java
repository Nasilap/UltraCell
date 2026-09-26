package com.nasilap.ultracell.command;

import com.nasilap.ultracell.UltraCell;
import com.nasilap.ultracell.storage.CellData;
import com.nasilap.ultracell.storage.CellDataManager;
import com.nasilap.ultracell.storage.ExternalCellDataFile;
import com.nasilap.ultracell.storage.ExternalCellStorage;
import com.nasilap.ultracell.util.IOUtilities;
import com.nasilap.ultracell.util.NumberUtil;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /ultracell debug …} —— 排障子命令（UC-002a）。
 *
 * <p><b>按 A12 的规定，恰好 5 个子操作</b>，且**没有重复路径**：
 * <ol>
 *   <li>{@code debug list} —— 列出内存缓存里的全部元件</li>
 *   <li>{@code debug inspect <uuid>} —— 打印该 UUID 的缓存快照 **+** 数据文件状态</li>
 *   <li>{@code debug create <tier> <kind>} —— 造一个**无主**的空数据文件（随机 UUID），
 *       用于验证 scan / recover / 孤儿提示</li>
 *   <li>{@code debug corrupt} —— 显示损坏名单；{@code debug corrupt clear} 清空它
 *       （A4 要求的「手动清除入口」）</li>
 *   <li>{@code debug flush} —— 立即强制落盘全部待写条目</li>
 * </ol>
 *
 * <p>刻意**不再提供** {@code debug <uuid>} 这种简写：它会在 Brigadier 的用法文本里
 * 与 {@code debug inspect <uuid>} 形成两条看起来重复的路径。
 *
 * <p>{@code debug} **允许非玩家源**（命令方块 / 控制台），因此不使用
 * {@code getPlayerOrException()}。
 *
 * <p>本子树由 UC-002a 挂载；UC-004 / UC-005a 各自再挂一次
 * {@code RegisterCommandsEvent} 是**允许且必要**的（同名子节点会被递归合并）。
 */
public final class DebugCommand {

    /** 单次输出最多列出的条目数（防止刷屏）。 */
    private static final int MAX_ENTRIES_SHOWN = 20;

    private DebugCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal(CommandRegistration.ROOT).then(build()));
    }

    /** 构建 {@code debug} 子树：5 个子操作，无重复路径。 */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> debug = Commands.literal("debug");

        debug.then(Commands.literal("list").executes(DebugCommand::list));

        debug.then(Commands.literal("inspect")
                .then(Commands.argument("uuid", StringArgumentType.word()).executes(DebugCommand::inspect)));

        debug.then(Commands.literal("create")
                .then(Commands.argument("tier", StringArgumentType.word())
                        .then(Commands.argument("kind", StringArgumentType.word())
                                .executes(DebugCommand::create))));

        debug.then(Commands.literal("corrupt")
                .executes(DebugCommand::showCorrupt)
                .then(Commands.literal("clear").executes(DebugCommand::clearCorrupt)));

        debug.then(Commands.literal("flush").executes(DebugCommand::flush));

        return debug;
    }

    // ── list ──

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        CellDataManager manager = requireManager(source);
        if (manager == null) {
            return 0;
        }
        var cache = manager.cache();
        source.sendSuccess(() -> Component.translatable("ultracell.command.debug.list.header",
                Integer.toString(cache.size())), false);

        int shown = 0;
        for (CellData data : cache.all()) {
            if (shown >= MAX_ENTRIES_SHOWN) {
                source.sendSuccess(() -> Component.translatable("ultracell.command.debug.list.more",
                        Integer.toString(MAX_ENTRIES_SHOWN)), false);
                break;
            }
            source.sendSuccess(() -> Component.translatable("ultracell.command.debug.list.entry",
                    data.uuid().toString(),
                    data.tier(),
                    data.kind(),
                    data.hasOwner() ? data.owner().toString() : "-",
                    Integer.toString(data.typeCount()),
                    NumberUtil.format(data.used())), false);
            shown++;
        }
        return 1;
    }

    // ── inspect ──

    private static int inspect(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        UUID uuid = parseUuid(source, StringArgumentType.getString(context, "uuid"));
        if (uuid == null) {
            return 0;
        }
        CellDataManager manager = requireManager(source);
        if (manager == null) {
            return 0;
        }

        // 缓存部分
        CellData data = manager.get(uuid);
        if (data == null) {
            source.sendSuccess(() -> Component.translatable("ultracell.command.debug.cell.not_cached",
                    uuid.toString()), false);
        } else {
            source.sendSuccess(() -> Component.translatable("ultracell.command.debug.cell.header",
                    uuid.toString()), false);
            source.sendSuccess(() -> Component.translatable("ultracell.command.debug.cell.tier_kind",
                    data.tier(), data.kind()), false);
            source.sendSuccess(() -> Component.translatable("ultracell.command.debug.cell.owner",
                    data.hasOwner() ? data.owner().toString() : "-"), false);
            source.sendSuccess(() -> Component.translatable("ultracell.command.debug.cell.types",
                    Integer.toString(data.typeCount())), false);
            source.sendSuccess(() -> Component.translatable("ultracell.command.debug.cell.used",
                    NumberUtil.format(data.used())), false);
            source.sendSuccess(() -> Component.translatable("ultracell.command.debug.cell.flags",
                    Boolean.toString(data.isReadOnly()),
                    Boolean.toString(manager.corruptIndex().isCorrupt(uuid))), false);

            int shown = 0;
            for (Map.Entry<appeng.api.stacks.AEKey, com.nasilap.ultracell.storage.UInt192> entry
                    : data.entryView()) {
                if (shown >= MAX_ENTRIES_SHOWN) {
                    source.sendSuccess(() -> Component.translatable("ultracell.command.debug.cell.more",
                            Integer.toString(MAX_ENTRIES_SHOWN)), false);
                    break;
                }
                source.sendSuccess(() -> Component.translatable("ultracell.command.debug.cell.entry",
                        entry.getKey().getDisplayName().getString(),
                        NumberUtil.format(entry.getValue())), false);
                shown++;
            }
        }

        // 文件部分
        Path file = ExternalCellStorage.fileFor(manager.cellsDir(), uuid);
        if (!Files.isRegularFile(file)) {
            source.sendSuccess(() -> Component.translatable("ultracell.command.debug.file.missing",
                    uuid.toString()), false);
            return 1;
        }

        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = -1L;
        }
        final long reportedSize = size;

        int version = 0;
        boolean readable = false;
        try {
            CompoundTag tag = ExternalCellStorage.readCompressed(file);
            readable = tag.contains(ExternalCellStorage.TAG_FORMAT_VERSION, Tag.TAG_ANY_NUMERIC);
            version = tag.getInt(ExternalCellStorage.TAG_FORMAT_VERSION);
        } catch (IOException | RuntimeException ignored) {
            readable = false;
        }
        final int reportedVersion = version;
        final boolean reportedReadable = readable;

        source.sendSuccess(() -> Component.translatable("ultracell.command.debug.file.header",
                uuid.toString()), false);
        source.sendSuccess(() -> Component.translatable("ultracell.command.debug.file.size",
                Long.toString(reportedSize)), false);
        source.sendSuccess(() -> Component.translatable("ultracell.command.debug.file.version",
                reportedReadable ? Integer.toString(reportedVersion) : "-"), false);
        return 1;
    }

    // ── create ──

    private static int create(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String tier = StringArgumentType.getString(context, "tier");
        String kind = StringArgumentType.getString(context, "kind");
        CellDataManager manager = requireManager(source);
        if (manager == null) {
            return 0;
        }
        if (!ExternalCellStorage.isKnownTier(tier) || !ExternalCellStorage.isKnownKind(kind)) {
            source.sendFailure(Component.translatable("ultracell.command.debug.create.bad_type", tier, kind));
            return 0;
        }

        // 随机 UUID + 无主：正好是 scan / recover / 孤儿提示 的测试样本
        UUID uuid = UUID.randomUUID();
        CellData data = manager.dataForWrite(uuid, tier, kind);

        CompoundTag tag = ExternalCellDataFile.toTag(source.getServer().registryAccess(), data);
        Path file = ExternalCellStorage.fileFor(manager.cellsDir(), uuid);
        IOUtilities.withIOWorker(() -> {
            try {
                ExternalCellStorage.writeCompressedAtomically(tag, file);
            } catch (IOException e) {
                UltraCell.LOGGER.error("Ultra Cell: debug create failed to write {}", file, e);
            }
        });
        // 已经手动写过一次，取消 tick 末的重复写
        data.clearBindPending();

        MutableComponent clickable = Component.literal(uuid.toString())
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, uuid.toString()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("ultracell.command.debug.create.hover"))));
        source.sendSuccess(() -> Component.translatable("ultracell.command.debug.create.done",
                clickable, tier, kind), true);
        return 1;
    }

    // ── corrupt / corrupt clear ──

    private static int showCorrupt(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        CellDataManager manager = requireManager(source);
        if (manager == null) {
            return 0;
        }
        var index = manager.corruptIndex();
        source.sendSuccess(() -> Component.translatable("ultracell.command.debug.corrupt.header",
                Integer.toString(index.size())), false);

        int shown = 0;
        for (UUID uuid : index.all()) {
            if (shown >= MAX_ENTRIES_SHOWN) {
                source.sendSuccess(() -> Component.translatable("ultracell.command.debug.corrupt.more",
                        Integer.toString(MAX_ENTRIES_SHOWN)), false);
                break;
            }
            source.sendSuccess(() -> Component.translatable("ultracell.command.debug.corrupt.entry",
                    uuid.toString()), false);
            shown++;
        }
        return 1;
    }

    private static int clearCorrupt(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        CellDataManager manager = requireManager(source);
        if (manager == null) {
            return 0;
        }
        manager.corruptIndex().clear();
        source.sendSuccess(() -> Component.translatable("ultracell.command.debug.clear_corrupt.done"), true);
        return 1;
    }

    // ── flush ──

    private static int flush(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        CellDataManager manager = requireManager(source);
        if (manager == null) {
            return 0;
        }
        manager.flushAllNow();
        source.sendSuccess(() -> Component.translatable("ultracell.command.debug.flush.done"), true);
        return 1;
    }

    // ── 供其它命令复用的工具方法 ──

    /** 取管理器；未就绪时报错并返回 null。 */
    @Nullable
    public static CellDataManager requireManager(CommandSourceStack source) {
        CellDataManager manager = CellDataManager.current();
        if (manager == null) {
            source.sendFailure(Component.translatable("ultracell.command.common.not_ready"));
        }
        return manager;
    }

    /** 解析 UUID 参数；格式不对时报错并返回 null。 */
    @Nullable
    public static UUID parseUuid(CommandSourceStack source, String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable("ultracell.command.common.bad_uuid", raw));
            return null;
        }
    }
}
