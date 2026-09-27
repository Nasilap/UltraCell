package com.nasilap.ultracell.util;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.GameProfileCache;

import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 归属 UUID → 玩家名反查 + **1 分钟缓存**（tooltip 的「所有者：」行用）。
 *
 * <p><b>两条优先路径</b>：
 * <ol>
 *   <li>{@code ServerLifecycleHooks.getCurrentServer().getProfileCache().get(uuid)}
 *       —— 单人 / 局域网 / 专用服务器都能用，且能查到**离线**玩家；</li>
 *   <li>第二条由**客户端注入**（{@link #installClientLookup}）：多人服务器上
 *       客户端没有 {@code MinecraftServer}，此时用玩家列表里的在线玩家名。</li>
 * </ol>
 *
 * <p><b>为什么用注入而不是直接调客户端类</b>：本模组约定"客户端专有代码只放
 * {@code client} 包"。{@code util} 是公共包，一旦直接 import
 * {@code net.minecraft.client.*}，公共类在专用服务器上就会带上客户端引用。
 * 由 {@code client/UltraCellClientSetup} 在客户端启动时注入一个方法引用，
 * 依赖方向就永远是单向的：{@code client → util}。
 *
 * <p><b>缓存</b>：{@code Map<UUID, 名字或 null>}，1 分钟过期。
 * 这是**全局玩家名**、不是存档数据，所以放 static 是对的 ——
 * 与台账 A4「owner 缓存不放 static」不冲突（那条针对的是存档级的 {@code CellData}）。
 * 查不到（{@code null}）同样缓存 1 分钟，避免每帧重复查。
 *
 * <p>缓存条目上限 {@value #MAX_ENTRIES}：超出就整体清空。玩家名是低频数据，
 * 正常会话远达不到上限；这个上限只是防止长会话里无界增长。
 */
public final class OwnerNameResolver {

    /** 缓存有效期：1 分钟。 */
    private static final long TTL_NANOS = 60_000_000_000L;

    /** 缓存条目上限（超出整体清空）。 */
    private static final int MAX_ENTRIES = 512;

    /** 名字缓存；值为 {@code null} 表示"查过但没查到"。 */
    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();

    /** 客户端注入的反查钩子；专用服务器上**永远为 null**。 */
    @Nullable
    private static volatile Function<UUID, String> clientLookup;

    private OwnerNameResolver() {}

    /** 一条缓存记录。 */
    private record Entry(@Nullable String name, long createdAtNanos) {}

    /**
     * 由客户端初始化时注入"在线玩家名"反查。
     *
     * <p>{@code client/UltraCellClientSetup} 与 tint 注册一起调用，因此
     * 专用服务器上本钩子保持 null，公共代码不会碰任何客户端类。
     */
    public static void installClientLookup(Function<UUID, String> lookup) {
        clientLookup = lookup;
    }

    /**
     * 反查玩家名。
     *
     * @param uuid 归属 UUID，允许为 {@code null}
     * @return 玩家名；查不到（离线且不在档案缓存里、或就是查不到）时返回 {@code null}，
     *         由调用方降级为显示 UUID
     */
    @Nullable
    public static String nameOf(@Nullable UUID uuid) {
        if (uuid == null) {
            return null;
        }

        long now = System.nanoTime();
        Entry cached = CACHE.get(uuid);
        // 用减法比较，避免 now + TTL 的溢出问题
        if (cached != null && now - cached.createdAtNanos() < TTL_NANOS) {
            return cached.name();
        }

        String resolved = resolve(uuid);
        if (CACHE.size() >= MAX_ENTRIES) {
            CACHE.clear();
        }
        CACHE.put(uuid, new Entry(resolved, now));
        return resolved;
    }

    /** 真正去查；两条路径都查不到就返回 {@code null}。 */
    @Nullable
    private static String resolve(UUID uuid) {
        // ① 档案缓存（单人 / 局域网 / 专用服务器；能查到离线玩家）
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            GameProfileCache cache = server.getProfileCache();
            if (cache != null) {
                Optional<GameProfile> profile = cache.get(uuid);
                if (profile.isPresent()) {
                    String name = profile.get().getName();
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        }

        // ② 客户端注入的在线玩家名（多人服务器；离线玩家查不到 → 降级显示 UUID）
        Function<UUID, String> lookup = clientLookup;
        if (lookup != null) {
            String name = lookup.apply(uuid);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }

        return null;
    }
}
