package com.nasilap.ultracell.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * {@code ultracell:fe_energy} 数据组件的值。
 *
 * <p>用三个 long 按 {@code high:mid:low} 保存无符号 192 位整数。
 *
 * <p><b>持久化策略</b>：{@link #CODEC} 的三个字段都用 {@code optionalFieldOf(name, 0L)}，
 * 该写法在编码时会把「等于默认值的字段」整个省略，因此
 * <ul>
 *   <li>鸿蒙（high 恒为 0）只会写 mid / low</li>
 *   <li>数值很小时只会写 low</li>
 * </ul>
 * 以此减小存档体积。网络同步用 {@link #STREAM_CODEC}（VarLong，不做省略）。
 */
public record FEEnergyComponent(long high, long mid, long low) {

    /** 存档用 Codec：高位为 0 时省略字段。 */
    public static final Codec<FEEnergyComponent> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.LONG.optionalFieldOf("high", 0L).forGetter(FEEnergyComponent::high),
                    Codec.LONG.optionalFieldOf("mid", 0L).forGetter(FEEnergyComponent::mid),
                    Codec.LONG.optionalFieldOf("low", 0L).forGetter(FEEnergyComponent::low))
            .apply(instance, FEEnergyComponent::new));

    /** 网络同步用 StreamCodec。 */
    public static final StreamCodec<ByteBuf, FEEnergyComponent> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, FEEnergyComponent::high,
            ByteBufCodecs.VAR_LONG, FEEnergyComponent::mid,
            ByteBufCodecs.VAR_LONG, FEEnergyComponent::low,
            FEEnergyComponent::new);

    /** 由 UInt192 构造组件值。 */
    public static FEEnergyComponent of(UInt192 value) {
        return new FEEnergyComponent(value.high(), value.mid(), value.low());
    }

    /** 还原为 UInt192。 */
    public UInt192 toUInt192() {
        return new UInt192(this.high, this.mid, this.low);
    }

    /** 三个 long 是否全为 0。 */
    public boolean isZero() {
        return this.high == 0L && this.mid == 0L && this.low == 0L;
    }
}
