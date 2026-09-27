package com.nasilap.ultracell.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * {@code ultracell:cell_summary} 数据组件的值 —— 外置存储元件挂在物品上的**摘要**。
 *
 * <p>八个 long（已定决策）：
 * <pre>
 *   uuid_high / uuid_low              元件 UUID（文件为唯一权威源，这里只是索引）
 *   type_count                        已占用的类型位数量
 *   used_high / used_mid / used_low   已用总量（UInt192）
 *   owner_high / owner_low            归属 UUID（**只读镜像**，全 0 = 无主）
 * </pre>
 *
 * <p><b>刻意不含</b> {@code tier} / {@code kind}（物品身份已隐含它们），
 * <b>也不含</b>版本字段；字段一律「等于默认值即省略」，以减小存档体积。
 *
 * <p><b>归属语义</b>：文件（{@code .dat}）才是唯一权威源，这里的 owner 只是
 * **给客户端 tooltip 读的只读镜像**；冲突时以文件为准（台账 A4）。
 * 写镜像的纪律见 {@code CellDataManager#syncOwnerMirror}：
 * 只在「归属确实变了」时写，且值一律取自内存 {@code CellData}，绝不保留旧 DC 的值。
 */
public record CellSummaryComponent(long uuidHigh, long uuidLow, long typeCount,
                                   long usedHigh, long usedMid, long usedLow,
                                   long ownerHigh, long ownerLow) {

    /** 全零摘要（等价于「还没分配 UUID」且「无主」）。 */
    public static final CellSummaryComponent EMPTY =
            new CellSummaryComponent(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

    /** 存档用 Codec：等于默认值的字段会被省略（缺字段的老存档读出 0 = 无主）。 */
    public static final Codec<CellSummaryComponent> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.LONG.optionalFieldOf("uuid_high", 0L).forGetter(CellSummaryComponent::uuidHigh),
                    Codec.LONG.optionalFieldOf("uuid_low", 0L).forGetter(CellSummaryComponent::uuidLow),
                    Codec.LONG.optionalFieldOf("type_count", 0L).forGetter(CellSummaryComponent::typeCount),
                    Codec.LONG.optionalFieldOf("used_high", 0L).forGetter(CellSummaryComponent::usedHigh),
                    Codec.LONG.optionalFieldOf("used_mid", 0L).forGetter(CellSummaryComponent::usedMid),
                    Codec.LONG.optionalFieldOf("used_low", 0L).forGetter(CellSummaryComponent::usedLow),
                    Codec.LONG.optionalFieldOf("owner_high", 0L).forGetter(CellSummaryComponent::ownerHigh),
                    Codec.LONG.optionalFieldOf("owner_low", 0L).forGetter(CellSummaryComponent::ownerLow))
            .apply(instance, CellSummaryComponent::new));

    /**
     * 网络同步用 StreamCodec（不做省略）。
     *
     * <p><b>刻意手写，不用 {@code StreamCodec.composite}</b>：1.21.1 的
     * {@code StreamCodec.composite} 只有 1~6 个参数的重载（源码里最大的是 T1..T6），
     * 本组件有 **8 个字段**，用 composite 会直接编译不过。
     *
     * <p>手写版本与 composite **行为完全一致**：按字段顺序写、按同一顺序读，
     * 且都走 {@code ByteBufCodecs.VAR_LONG}（其底层即 {@code net.minecraft.network.VarLong}），
     * 因此网络字节流与等价 composite 写法逐字节相同。
     *
     * <p>⚠ <b>不要写成 {@code buf.writeVarLong(...)}</b>：{@code ByteBuf} 是 Netty 的接口，
     * **没有** {@code writeVarLong / readVarLong}（那是 Minecraft 的 {@code FriendlyByteBuf} 才有）。
     * 直接复用 {@code ByteBufCodecs.VAR_LONG} 的 encode / decode 最稳。
     */
    public static final StreamCodec<ByteBuf, CellSummaryComponent> STREAM_CODEC =
            StreamCodec.<ByteBuf, CellSummaryComponent>of(
                    (ByteBuf buf, CellSummaryComponent value) -> {
                        ByteBufCodecs.VAR_LONG.encode(buf, value.uuidHigh);
                        ByteBufCodecs.VAR_LONG.encode(buf, value.uuidLow);
                        ByteBufCodecs.VAR_LONG.encode(buf, value.typeCount);
                        ByteBufCodecs.VAR_LONG.encode(buf, value.usedHigh);
                        ByteBufCodecs.VAR_LONG.encode(buf, value.usedMid);
                        ByteBufCodecs.VAR_LONG.encode(buf, value.usedLow);
                        ByteBufCodecs.VAR_LONG.encode(buf, value.ownerHigh);
                        ByteBufCodecs.VAR_LONG.encode(buf, value.ownerLow);
                    },
                    (ByteBuf buf) -> new CellSummaryComponent(
                            ByteBufCodecs.VAR_LONG.decode(buf),
                            ByteBufCodecs.VAR_LONG.decode(buf),
                            ByteBufCodecs.VAR_LONG.decode(buf),
                            ByteBufCodecs.VAR_LONG.decode(buf),
                            ByteBufCodecs.VAR_LONG.decode(buf),
                            ByteBufCodecs.VAR_LONG.decode(buf),
                            ByteBufCodecs.VAR_LONG.decode(buf),
                            ByteBufCodecs.VAR_LONG.decode(buf)));

    /** 是否已分配 UUID。 */
    public boolean hasUuid() {
        return this.uuidHigh != 0L || this.uuidLow != 0L;
    }

    /** 取 UUID；未分配时为 {@code null}。 */
    @Nullable
    public UUID uuid() {
        return this.hasUuid() ? new UUID(this.uuidHigh, this.uuidLow) : null;
    }

    /** 归属镜像是否非空（全 0 = 无主）。 */
    public boolean hasOwner() {
        return this.ownerHigh != 0L || this.ownerLow != 0L;
    }

    /** 取归属镜像；无主时为 {@code null}。**只读镜像，权威源是文件。** */
    @Nullable
    public UUID owner() {
        return this.hasOwner() ? new UUID(this.ownerHigh, this.ownerLow) : null;
    }

    /** 已用总量。 */
    public UInt192 used() {
        return new UInt192(this.usedHigh, this.usedMid, this.usedLow);
    }

    /** 已用总量是否为零。 */
    public boolean usedIsZero() {
        return this.usedHigh == 0L && this.usedMid == 0L && this.usedLow == 0L;
    }

    /** 只换 UUID，其余字段（含归属镜像）保留。 */
    public CellSummaryComponent withUuid(UUID uuid) {
        return new CellSummaryComponent(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(),
                this.typeCount, this.usedHigh, this.usedMid, this.usedLow,
                this.ownerHigh, this.ownerLow);
    }

    /**
     * 只换归属镜像，其余字段保留；{@code owner} 为 {@code null} 表示写回「无主」。
     *
     * <p>调用方必须保证 {@code owner} 来自权威源（内存 {@code CellData}）。
     */
    public CellSummaryComponent withOwner(@Nullable UUID owner) {
        if (owner == null) {
            return new CellSummaryComponent(this.uuidHigh, this.uuidLow, this.typeCount,
                    this.usedHigh, this.usedMid, this.usedLow, 0L, 0L);
        }
        return new CellSummaryComponent(this.uuidHigh, this.uuidLow, this.typeCount,
                this.usedHigh, this.usedMid, this.usedLow,
                owner.getMostSignificantBits(), owner.getLeastSignificantBits());
    }

    /**
     * 由 UUID + 归属 + 类型数 + 已用量构造；两个 UUID 为 {@code null} 时分别表示
     * 「未分配 UUID」与「无主」。
     *
     * <p>只保留这一个重载：{@code @Nullable} 只是注解，不构成不同的方法签名，
     * 再写一个参数个数相同的重载会直接编译失败（重复定义）。
     */
    public static CellSummaryComponent of(@Nullable UUID uuid, @Nullable UUID owner, int typeCount, UInt192 used) {
        long uuidHigh = 0L;
        long uuidLow = 0L;
        if (uuid != null) {
            uuidHigh = uuid.getMostSignificantBits();
            uuidLow = uuid.getLeastSignificantBits();
        }
        long ownerHigh = 0L;
        long ownerLow = 0L;
        if (owner != null) {
            ownerHigh = owner.getMostSignificantBits();
            ownerLow = owner.getLeastSignificantBits();
        }
        return new CellSummaryComponent(uuidHigh, uuidLow, typeCount,
                used.high(), used.mid(), used.low(), ownerHigh, ownerLow);
    }
}
