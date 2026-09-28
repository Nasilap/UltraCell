package com.nasilap.ultracell.command;

/**
 * 命令树根节点名与共享常量。
 *
 * <p>根节点 {@code /ultracell} **不加 {@code requires}、不加 {@code executes}**：
 * Brigadier 合并同名子节点时会**覆盖节点的 command**、且**不合并 requirement**
 * （{@code requirement} 是 {@code private final}），把权限挂在根上会导致
 * 后续合并出现难以察觉的行为。权限一律挂在子命令上。
 *
 * <p>本模组有**三个** {@code RegisterCommandsEvent} 监听器
 * （{@code DebugCommand} / {@code CellCommands} / {@code ScanCommand} 各挂一次），
 * 这是允许且必要的：同名子节点会被 Brigadier 递归合并。
 *
 * <p>{@code OrphanCounter} **不是命令注册者** —— 它只挂 tick / 登录 / 关服三个事件
 * （{@code ServerTickEvent.Post} / {@code PlayerLoggedInEvent} / {@code ServerStoppedEvent}），
 * 进存档时发一句提示把玩家引向 {@code /ultracell scan}，且该消息**不可点击**。
 */
public final class CommandRegistration {

    /** 命令树根。 */
    public static final String ROOT = "ultracell";

    private CommandRegistration() {}
}
