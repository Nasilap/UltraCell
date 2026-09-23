package com.nasilap.ultracell.storage;

/**
 * 无符号 192 位整数。
 *
 * <p>由三个 {@code long} 组成，按 {@code high:mid:low} 排列，三者一律按<b>无符号</b>语义解释
 * （Java 没有无符号 long，因此所有比较、进位、借位都使用位运算完成，不依赖符号位）。
 *
 * <p>溢出与下溢策略（已定决策）：
 * <ul>
 *   <li>加法溢出：<b>饱和</b>，{@code MAX_UINT192 + 1 == MAX_UINT192}</li>
 *   <li>减法下溢：<b>饱和</b>，{@code 0 - 1 == 0}</li>
 * </ul>
 * 任何情况下都不回绕。
 *
 * <p>本类刻意<b>不引入</b> {@link java.math.BigInteger}，也不做十进制长除法。
 * {@link #toString()} 只输出十六进制，仅供调试；
 * 面向玩家的十进制容量显示在 {@code com.nasilap.ultracell.util.NumberUtil}，用 double 近似完成。
 */
public record UInt192(long high, long mid, long low) implements Comparable<UInt192> {

    /** 单个 long 的无符号最大值，即 0xFFFFFFFFFFFFFFFFL（等价于 -1L）。 */
    private static final long ALL_ONES = 0xFFFFFFFFFFFFFFFFL;

    /** 零。 */
    public static final UInt192 ZERO = new UInt192(0L, 0L, 0L);

    /** 鸿蒙上限：uint128 最大值 = 高 64 位为 0，低 128 位全 1。 */
    public static final UInt192 MAX_UINT128 = new UInt192(0L, ALL_ONES, ALL_ONES);

    /** 无极上限：uint192 最大值 = 三个 long 全 1。 */
    public static final UInt192 MAX_UINT192 = new UInt192(ALL_ONES, ALL_ONES, ALL_ONES);

    /**
     * 无符号加法的进位出位（0 或 1）。
     *
     * <p>经典位技巧：{@code (a & b) | ((a | b) & ~sum)} 的最高位即为进位。
     */
    private static long carryOut(long a, long b, long sum) {
        return ((a & b) | ((a | b) & ~sum)) >>> 63;
    }

    /** 无符号减法的借位出位（0 或 1）。 */
    private static long borrowOut(long a, long b, long diff) {
        return ((~a & b) | ((~a | b) & diff)) >>> 63;
    }

    /** long 转无符号 double（符号位为 1 时不能直接强转，否则得到负数）。 */
    private static double unsignedToDouble(long value) {
        if (value >= 0L) {
            return (double) value;
        }
        return ((double) (value >>> 1)) * 2.0 + (double) (value & 1L);
    }

    /**
     * 由 long 构造，语义为<b>非负</b>：负值与 0 一律得到 {@link #ZERO}。
     *
     * <p>插入/提取路径上的 amount 只可能为正，此处只是防御性兜底。
     */
    public static UInt192 fromLong(long value) {
        if (value <= 0L) {
            return ZERO;
        }
        return new UInt192(0L, 0L, value);
    }

    /** 是否为零。 */
    public boolean isZero() {
        return this.high == 0L && this.mid == 0L && this.low == 0L;
    }

    /** 无符号加法，溢出饱和到 {@link #MAX_UINT192}。 */
    public UInt192 add(UInt192 other) {
        long resultLow = this.low + other.low;
        long carryLow = carryOut(this.low, other.low, resultLow);

        long midBase = this.mid + other.mid;
        long carryMidA = carryOut(this.mid, other.mid, midBase);
        long resultMid = midBase + carryLow;
        long carryMidB = carryOut(midBase, carryLow, resultMid);
        long carryMid = carryMidA | carryMidB;

        long highBase = this.high + other.high;
        long carryHighA = carryOut(this.high, other.high, highBase);
        long resultHigh = highBase + carryMid;
        long carryHighB = carryOut(highBase, carryMid, resultHigh);
        long carryHigh = carryHighA | carryHighB;

        if (carryHigh != 0L) {
            return MAX_UINT192;
        }
        return new UInt192(resultHigh, resultMid, resultLow);
    }

    /** 无符号减法，下溢饱和到 {@link #ZERO}。 */
    public UInt192 subtract(UInt192 other) {
        if (this.compareTo(other) < 0) {
            return ZERO;
        }

        long resultLow = this.low - other.low;
        long borrowLow = borrowOut(this.low, other.low, resultLow);

        long midBase = this.mid - other.mid;
        long borrowMidA = borrowOut(this.mid, other.mid, midBase);
        long resultMid = midBase - borrowLow;
        long borrowMidB = borrowOut(midBase, borrowLow, resultMid);
        long borrowMid = borrowMidA | borrowMidB;

        long resultHigh = this.high - other.high - borrowMid;
        return new UInt192(resultHigh, resultMid, resultLow);
    }

    /** 二者中较小者。 */
    public UInt192 min(UInt192 other) {
        return this.compareTo(other) <= 0 ? this : other;
    }

    /** 二者中较大者。 */
    public UInt192 max(UInt192 other) {
        return this.compareTo(other) >= 0 ? this : other;
    }

    /** 饱和转换为 long：超过 {@link Long#MAX_VALUE} 时返回 {@link Long#MAX_VALUE}。 */
    public long saturatingToLong() {
        if (this.high != 0L || this.mid != 0L) {
            return Long.MAX_VALUE;
        }
        // 低 64 位符号位为 1 表示其无符号值已超过 Long.MAX_VALUE
        return this.low < 0L ? Long.MAX_VALUE : this.low;
    }

    /** 饱和转换为 int：超过 {@link Integer#MAX_VALUE} 时返回 {@link Integer#MAX_VALUE}。 */
    public int saturatingToInt() {
        long value = this.saturatingToLong();
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    /** 近似 double 值，仅用于显示格式化（精度约 15~16 位有效数字，足够两位小数）。 */
    public double toDouble() {
        return Math.scalb(unsignedToDouble(this.high), 128)
                + Math.scalb(unsignedToDouble(this.mid), 64)
                + unsignedToDouble(this.low);
    }

    /** 无符号比较。 */
    @Override
    public int compareTo(UInt192 other) {
        int result = Long.compareUnsigned(this.high, other.high);
        if (result != 0) {
            return result;
        }
        result = Long.compareUnsigned(this.mid, other.mid);
        if (result != 0) {
            return result;
        }
        return Long.compareUnsigned(this.low, other.low);
    }

    /**
     * 仅供调试的十六进制表示，形如 {@code 0x0000000000000001_0000000000000000_FFFFFFFFFFFFFFFF}。
     *
     * <p>刻意<b>不</b>输出十进制 —— 192 位十进制转换需要长除法，按既定决策不做。
     * 面向玩家的显示请使用 {@code NumberUtil.format(UInt192)}。
     */
    @Override
    public String toString() {
        return "0x"
                + String.format("%016X", this.high) + "_"
                + String.format("%016X", this.mid) + "_"
                + String.format("%016X", this.low);
    }
}
