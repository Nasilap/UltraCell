package com.nasilap.ultracell.util;

import com.nasilap.ultracell.UltraCell;

import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import org.jetbrains.annotations.Nullable;

/**
 * 化学品通道（appmek）的集成点。
 *
 * <p>门控口径（已定决策）：**物品恒定注册**，只把「配方」与「功能」放进门控。
 *
 * <p><b>为什么不能把「解析失败」也缓存下来</b>：appmek 是在它自己的
 * {@code RegisterEvent}（BLOCK 阶段）里调用 {@code AEKeyTypes.register(...)} 的，
 * 如果我们的第一次查询早于那一刻，就会拿到 {@code null}。若把 null 也当成"已解析"，
 * 整个会话都再也拿不到 keyType —— 表现为
 * 「化学品元件能插进驱动器，却永远存不进东西」（驱动器只要求 {@code isCell} 为真）。
 * 因此这里的策略是：**只有成功才缓存；失败按节流重试**。
 *
 * <p>没装 appmek 时直接降级，不做任何查询（避免每次调用都构造一次异常）。
 *
 * <p>刻意<b>不 import Mekanism API</b>：只通过 appmek 注册的 keyType id 取用。
 */
public final class ChemicalIntegration {

    /** appmek 的 mod id。 */
    public static final String MOD_ID = "appmek";

    /** appmek 注册的化学品通道 id（对应 {@code AppliedMekanistics.id("chemical")}）。 */
    public static final ResourceLocation CHEMICAL_KEY_TYPE_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "chemical");

    /** appmek 是否在场（用途：配方门控 + 功能门控 + 创造页条目）。 */
    public static final boolean ENABLED = ModList.get().isLoaded(MOD_ID);

    /** 解析失败后的重试间隔（纳秒）：1 秒。 */
    private static final long RETRY_INTERVAL_NANOS = 1_000_000_000L;

    /** 解析成功后的缓存（**只缓存成功**；进程级注册表项，可 static）。 */
    @Nullable
    private static volatile AEKeyType chemicalKeyType;

    /** 上次尝试的时刻，用于失败重试节流。 */
    private static volatile long lastAttemptNanos;

    private static volatile boolean failureLogged;

    private ChemicalIntegration() {}

    /**
     * 化学品 keyType；appmek 缺席时为 {@code null}。
     *
     * <p>注意 {@code AEKeyTypes.get} 对未注册 id **会抛 IllegalArgumentException**
     * （不是返回 null），所以这里必须接住异常 —— 降级路径全靠它。
     *
     * <p><b>双端可用</b>（不依赖服务端事件）；成功结果永久缓存，失败按 1 秒节流重试。
     */
    @Nullable
    public static AEKeyType chemicalKeyType() {
        AEKeyType cached = chemicalKeyType;
        if (cached != null) {
            return cached;
        }
        if (!ENABLED) {
            // 没装 appmek：本次会话不可能出现，直接降级（不做查询、不构造异常）
            return null;
        }

        long now = System.nanoTime();
        if (now - lastAttemptNanos < RETRY_INTERVAL_NANOS) {
            return null;
        }
        lastAttemptNanos = now;

        AEKeyType resolved = null;
        try {
            resolved = AEKeyTypes.get(CHEMICAL_KEY_TYPE_ID);
        } catch (IllegalArgumentException e) {
            if (!failureLogged) {
                failureLogged = true;
                UltraCell.LOGGER.warn(
                        "Ultra Cell: chemical key type {} is not registered yet; will retry. "
                                + "Until it resolves, chemical cells are treated as non-cells.",
                        CHEMICAL_KEY_TYPE_ID);
            }
        }

        if (resolved != null) {
            chemicalKeyType = resolved;
            UltraCell.LOGGER.info("Ultra Cell: chemical key type resolved: id={} class={}",
                    resolved.getId(), resolved.getClass().getName());
        }
        return resolved;
    }
}
