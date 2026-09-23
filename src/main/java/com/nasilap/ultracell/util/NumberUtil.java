package com.nasilap.ultracell.util;

import com.nasilap.ultracell.storage.UInt192;

import java.util.Locale;

/**
 * 容量显示格式化。
 *
 * <p>规则（已定决策，tooltip 边界读法 (i)）：
 * <ul>
 *   <li>数值 &lt; 1000：直接显示整数，无单位</li>
 *   <li>1000 &le; 数值 &lt; 1000Q（即 1e33）：使用 K/M/G/T/P/E/Z/Y/R/Q 单位，保留两位小数，
 *       因此 Q 之前的单位（含 R、Q）都能完整用到 999.99Q</li>
 *   <li>数值 &ge; 1000Q：显示为 {@code (M.MMx10^E)Q}，括号保留，
 *       其中尾数 = 数值 / 10^30 后再取科学计数法</li>
 * </ul>
 *
 * <p>参考用例：
 * <pre>
 *   3.1e45  -&gt; (3.10x10^15)Q
 *   2^192-1 -&gt; (6.28x10^27)Q
 *   999.99Q -&gt; 999.99Q
 *   2^128-1 -&gt; (3.40x10^8)Q
 * </pre>
 *
 * <p>本类<b>零外部依赖</b>（只依赖 UInt192 与 JDK），因此可以脱离 Minecraft / Gradle
 * 直接用 {@code javac} + {@code java} 编译运行，便于独立验证格式化结果。
 */
public final class NumberUtil {

    /** 单位表，索引 i 对应 1000^i；索引 10 = Q = 10^30。 */
    private static final String[] UNITS = {"", "K", "M", "G", "T", "P", "E", "Z", "Y", "R", "Q"};

    /** Q 的十进制指数。 */
    private static final int Q_EXPONENT = 30;

    /** 进入括号写法的门限：1000Q = 10^33。 */
    private static final double PAREN_THRESHOLD = 1.0e33;

    /** 单位路径的最小门限。 */
    private static final double UNIT_THRESHOLD = 1000.0;

    private NumberUtil() {}

    /**
     * 把容量格式化为可读字符串。
     *
     * @param value 容量，允许为 null（按 0 处理）
     * @return 格式化结果，如 {@code "999.99Q"} 或 {@code "(6.28x10^27)Q"}
     */
    public static String format(UInt192 value) {
        if (value == null || value.isZero()) {
            return "0";
        }

        double amount = value.toDouble();

        if (amount < UNIT_THRESHOLD) {
            return Long.toString((long) amount);
        }
        if (amount < PAREN_THRESHOLD) {
            return formatWithUnit(amount);
        }
        return formatParenthesised(amount);
    }

    /** 1000 &le; amount &lt; 1e33：K~Q 单位 + 两位小数。 */
    private static String formatWithUnit(double amount) {
        int unitIndex = (int) Math.floor(Math.log10(amount) / 3.0);
        if (unitIndex < 1) {
            unitIndex = 1;
        }
        if (unitIndex > UNITS.length - 1) {
            unitIndex = UNITS.length - 1;
        }

        double scaled = amount / Math.pow(1000.0, unitIndex);

        // 浮点误差兜底：四舍五入到两位小数后若达到 1000.00，则进位到下一个单位
        if (unitIndex < UNITS.length - 1 && Math.round(scaled * 100.0) >= 100_000L) {
            unitIndex++;
            scaled = amount / Math.pow(1000.0, unitIndex);
        }

        return twoDecimals(scaled) + UNITS[unitIndex];
    }

    /** amount &ge; 1e33：先除以 10^30 得到 Q 的倍数，再写成 (M.MMx10^E)Q。 */
    private static String formatParenthesised(double amount) {
        double inQ = amount / Math.pow(10.0, Q_EXPONENT);

        int exponent = (int) Math.floor(Math.log10(inQ));
        double mantissa = inQ / Math.pow(10.0, exponent);

        // 浮点误差兜底：尾数达到 10 时规格化回 [1, 10)
        if (mantissa >= 9.995) {
            mantissa /= 10.0;
            exponent++;
        }

        return "(" + twoDecimals(mantissa) + "x10^" + exponent + ")Q";
    }

    /** 固定两位小数；显式指定 Locale.ROOT，避免小数点变成逗号。 */
    private static String twoDecimals(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
