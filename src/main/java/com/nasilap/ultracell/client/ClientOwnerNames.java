package com.nasilap.ultracell.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 客户端侧「在线玩家名」反查 —— **只在客户端加载**（由 {@code UltraCellClientSetup}
 * 在客户端初始化时注入给 {@code util.OwnerNameResolver}）。
 *
 * <p>为什么需要它：{@code MinecraftServer.getProfileCache()} 挂在服务端对象上，
 * **多人服务器的客户端根本没有 MinecraftServer**，只有整合服（单人 / 局域网主机）才有。
 * 所以多人环境下按原方案会一律降级显示 UUID；补上玩家列表这一条之后，
 * "当前在线的玩家"能显示名字，离线玩家仍然降级为 UUID。
 *
 * <p>本类不 import 任何本模组的其它包以外的客户端代码，也不被公共代码直接引用 ——
 * 公共代码只拿到一个 {@code Function<UUID, String>}。
 */
public final class ClientOwnerNames {

    private ClientOwnerNames() {}

    /**
     * 在客户端玩家列表里找该 UUID 的玩家名。
     *
     * @return 玩家名；未连接 / 玩家不在线 / 名字为空时返回 {@code null}
     */
    @Nullable
    public static String lookupOnline(UUID uuid) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) {
            return null;
        }
        PlayerInfo info = connection.getPlayerInfo(uuid);
        if (info == null) {
            return null;
        }
        String name = info.getProfile().getName();
        return name == null || name.isEmpty() ? null : name;
    }
}
