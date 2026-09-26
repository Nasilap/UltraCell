package com.nasilap.ultracell.item;

import com.nasilap.ultracell.storage.UInt192;

import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * FE 元件的「已用百分比」格式化 + 采样缓存（UC-001）。
 *
 * <p>每个元件实例**各自持有一个**本对象（不是静态单例）：鸿蒙与无极的分母差 18 个数量级，
 * 做成静态单例会让无极元件用鸿蒙的分母，而且**不报错**。
 *
 * <p>判定顺序（严格六步）：
 * <ol>
 *   <li>存量为 0 → {@code 0.000%}</li>
 *   <li>存量 == 上限（精确判断）→ {@code 100.000%}</li>
 *   <li>算百分比</li>
 *   <li>四舍五入到 0.001% 后为 0、但实际非零 → 强制 {@code 0.001%}</li>
 *   <li>四舍五入到 0.001% 后达 100.000%、但实际未满 → 封顶 {@code 99.999%}</li>
 *   <li>否则按三位小数正常输出</li>
 * </ol>
 *
 * <p>缓存策略（甲 + identity）：identity 变 → 立即重算并重置计时器；
 * identity 同且存量变 → 1 秒节流；其余直接返回缓存。
 * 守卫**只包住缓存的读写**，不包住输出（输出由调用方在守卫之外完成）。
 *
 * <p>{@code %s} 的百分号放在**参数**里（模板里不写 {@code %}）。
 */
public final class CellTooltipFormatter {

    /** 采样节流：1 秒。 */
    private static final long THROTTLE_NANOS = 1_000_000_000L;

    /** 该等级的容量上限；「是否已满」用它做精确比较。 */
    private final UInt192 maxCapacity;

    /** 上限的 double 近似，只用于算比例。 */
    private final double maxCapacityDouble;

    private UInt192 lastSampledEnergy = UInt192.ZERO;

    @Nullable
    private String cachedPercent;

    private long lastSampleNanos;

    @Nullable
    private ItemStack lastStackIdentity;

    public CellTooltipFormatter(UInt192 maxCapacity) {
        this.maxCapacity = maxCapacity;
        this.maxCapacityDouble = maxCapacity.toDouble();
    }

    /**
     * 取「已用 xx.xxx%」里的百分比文本（含百分号）。
     *
     * @param stack  当前被渲染的物品栈，仅用于 identity 比较
     * @param stored 当前存量
     */
    public String percent(ItemStack stack, UInt192 stored) {
        long now = System.nanoTime();

        // cachedPercent == null 是兜底：时间戳初值 0 在首次调用时也会「看起来过期」
        if (this.cachedPercent != null) {
            boolean sameIdentity = this.lastStackIdentity == stack;
            if (sameIdentity && stored.equals(this.lastSampledEnergy)) {
                return this.cachedPercent;
            }
            if (sameIdentity && now - this.lastSampleNanos < THROTTLE_NANOS) {
                // identity 同、存量变，但还没到 1 秒 → 继续用旧值
                return this.cachedPercent;
            }
        }

        String computed = this.compute(stored);
        this.lastSampledEnergy = stored;
        this.cachedPercent = computed;
        this.lastSampleNanos = now;
        this.lastStackIdentity = stack;
        return computed;
    }

    private String compute(UInt192 stored) {
        if (stored.isZero()) {
            return format(0.0);
        }
        if (stored.equals(this.maxCapacity)) {
            return format(100.0);
        }

        double percent = this.maxCapacityDouble <= 0.0
                ? 0.0
                : (stored.toDouble() / this.maxCapacityDouble) * 100.0;

        long rounded = Math.round(percent * 1000.0);
        if (rounded <= 0L) {
            return format(0.001);
        }
        if (rounded >= 100_000L) {
            return format(99.999);
        }
        return format(percent);
    }

    /** 固定三位小数 + 百分号；显式 Locale.ROOT，避免小数点变成逗号。 */
    private static String format(double percent) {
        return String.format(Locale.ROOT, "%.3f", percent) + "%";
    }
}
