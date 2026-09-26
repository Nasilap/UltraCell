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
 * <p>六个 long（已定决策）：
 * <pre>
 *   uuid_high / uuid_low      元件 UUID（文件为唯一权威源，这里只是索引）
 *   type_count                已占用的类型位数量
 *   used_high / used_mid / used_low   已用总量（UInt192）
 * </pre>
 *
 * <p><b>刻意不含</b> {@code tier} / {@code kind}（物品身份已隐含它们），
 * <b>也不含</b>版本字段；六个字段一律「等于默认值即省略」，以减小存档体积。
 *
 * <p>归属（owner）**不在这里** —— 归属只存在数据文件里，文件是唯一权威源。
 */
public record CellSummaryComponent(long uuidHigh, long uuidLow, long typeCount,
                                   long usedHigh, long usedMid, long usedLow) {

    /** 全零摘要（等价于「还没分配 UUID」）。 */
    public static final CellSummaryComponent EMPTY = new CellSummaryComponent(0L, 0L, 0L, 0L, 0L, 0L);

    /** 存档用 Codec：等于默认值的字段会被省略。 */
    public static final Codec<CellSummaryComponent> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.LONG.optionalFieldOf("uuid_high", 0L).forGetter(CellSummaryComponent::uuidHigh),
                    Codec.LONG.optionalFieldOf("uuid_low", 0L).forGetter(CellSummaryComponent::uuidLow),
                    Codec.LONG.optionalFieldOf("type_count", 0L).forGetter(CellSummaryComponent::typeCount),
                    Codec.LONG.optionalFieldOf("used_high", 0L).forGetter(CellSummaryComponent::usedHigh),
                    Codec.LONG.optionalFieldOf("used_mid", 0L).forGetter(CellSummaryComponent::usedMid),
                    Codec.LONG.optionalFieldOf("used_low", 0L).forGetter(CellSummaryComponent::usedLow))
            .apply(instance, CellSummaryComponent::new));

    /** 网络同步用 StreamCodec（不做省略）。 */
    public static final StreamCodec<ByteBuf, CellSummaryComponent> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, CellSummaryComponent::uuidHigh,
            ByteBufCodecs.VAR_LONG, CellSummaryComponent::uuidLow,
            ByteBufCodecs.VAR_LONG, CellSummaryComponent::typeCount,
            ByteBufCodecs.VAR_LONG, CellSummaryComponent::usedHigh,
            ByteBufCodecs.VAR_LONG, CellSummaryComponent::usedMid,
            ByteBufCodecs.VAR_LONG, CellSummaryComponent::usedLow,
            CellSummaryComponent::new);

    /** 是否已分配 UUID。 */
    public boolean hasUuid() {
        return this.uuidHigh != 0L || this.uuidLow != 0L;
    }

    /** 取 UUID；未分配时为 {@code null}。 */
    @Nullable
    public UUID uuid() {
        return this.hasUuid() ? new UUID(this.uuidHigh, this.uuidLow) : null;
    }

    /** 已用总量。 */
    public UInt192 used() {
        return new UInt192(this.usedHigh, this.usedMid, this.usedLow);
    }

    /** 已用总量是否为零。 */
    public boolean usedIsZero() {
        return this.usedHigh == 0L && this.usedMid == 0L && this.usedLow == 0L;
    }

    /** 只换 UUID，其余字段保留。 */
    public CellSummaryComponent withUuid(UUID uuid) {
        return new CellSummaryComponent(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(),
                this.typeCount, this.usedHigh, this.usedMid, this.usedLow);
    }

    /**
     * 由 UUID + 类型数 + 已用量构造；{@code uuid} 为 {@code null} 时表示「未分配 UUID」。
     *
     * <p>只保留这一个重载：{@code @Nullable} 只是注解，不构成不同的方法签名，
     * 再写一个非空的 {@code of(UUID, int, UInt192)} 会直接编译失败（重复定义）。
     */
    public static CellSummaryComponent of(@Nullable UUID uuid, int typeCount, UInt192 used) {
        if (uuid == null) {
            return new CellSummaryComponent(0L, 0L, typeCount, used.high(), used.mid(), used.low());
        }
        return new CellSummaryComponent(
                uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(),
                typeCount, used.high(), used.mid(), used.low());
    }
}
