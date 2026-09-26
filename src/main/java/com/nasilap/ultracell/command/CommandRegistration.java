package com.nasilap.ultracell.command;

/**
 * 命令树根节点名与共享常量。
 *
 * <p>根节点 {@code /ultracell} **不加 {@code requires}、不加 {@code executes}**：
 * Brigadier 合并同名子节点时会**覆盖节点的 command**、且**不合并 requirement**
 * （{@code requirement} 是 {@code private final}），把权限挂在根上会导致
 * 后续合并出现难以察觉的行为。权限一律挂在子命令上。
 *
 * <p>本模组有**四个** {@code RegisterCommandsEvent} 监听器
 * （debug / 元件命令 / 孤儿提示 / scan 各自挂一次），这是允许且必要的：
 * 同名子节点会被 Brigadier 递归合并。
 */
public final class CommandRegistration {

    /** 命令树根。 */
    public static final String ROOT = "ultracell";

    private CommandRegistration() {}
}
