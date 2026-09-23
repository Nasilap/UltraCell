package com.nasilap.ultracell.storage;

import com.nasilap.ultracell.util.NumberUtil;

/**
 * UInt192 + NumberUtil 的独立自检程序。
 *
 * <p><b>为什么不是 JUnit</b>：本类零外部依赖（只依赖 UInt192、NumberUtil 与 JDK），
 * 因此可以脱离 Gradle / Minecraft 直接编译运行，真正做到「跑过」而不是「只交源码」：
 * <pre>
 *   javac -d out src/main/java/com/nasilap/ultracell/storage/UInt192.java \
 *                src/main/java/com/nasilap/ultracell/util/NumberUtil.java \
 *                src/test/java/com/nasilap/ultracell/storage/UInt192SelfTest.java
 *   java -cp out com.nasilap.ultracell.storage.UInt192SelfTest
 * </pre>
 *
 * <p><b>期望值来源</b>：全部由 .NET {@code System.Numerics.BigInteger} 独立算出并反算校验，
 * 硬编码在本文件中。刻意<b>不</b>在仓库里 import {@code java.math.BigInteger} ——
 * 实现与测试都不引入它，符合「不要用 BigInteger」的既定约束。
 *
 * <p>覆盖：常量、加法进位链、减法借位链、加法溢出饱和、减法下溢饱和、无符号比较、
 * long/int 饱和转换、fromLong、toDouble 近似、NumberUtil 格式化。
 */
public final class UInt192SelfTest {

    private static final long ALL = 0xFFFFFFFFFFFFFFFFL;
    private static final long LMAX = Long.MAX_VALUE;
    private static final int IMAX = Integer.MAX_VALUE;

    private static int passed = 0;
    private static int failed = 0;

    private UInt192SelfTest() {}

    public static void main(String[] args) {
        System.out.println("=== UInt192 / NumberUtil 自检 ===");

        constants();
        addition();
        subtraction();
        unsignedComparison();
        saturation();
        fromLongAndZero();
        toDoubleApprox();
        hexToString();
        numberFormat();

        System.out.println();
        System.out.println("---------------------------------");
        System.out.printf("通过 %d / 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.out.println("结果: FAILED");
            System.exit(1);
        }
        System.out.println("结果: ALL PASSED");
    }

    // ------------------------------------------------------------------ 用例

    private static void constants() {
        section("常量");
        eq("ZERO", UInt192.ZERO, new UInt192(0L, 0L, 0L));
        eq("MAX_UINT128", UInt192.MAX_UINT128, new UInt192(0L, ALL, ALL));
        eq("MAX_UINT192", UInt192.MAX_UINT192, new UInt192(ALL, ALL, ALL));
    }

    private static void addition() {
        section("加法（进位链）");
        eq("0 + 0", UInt192.ZERO.add(UInt192.ZERO), new UInt192(0L, 0L, 0L));
        eq("低位进位: (0,0,ALL) + 1 = 2^64",
                new UInt192(0L, 0L, ALL).add(new UInt192(0L, 0L, 1L)),
                new UInt192(0L, 1L, 0L));
        eq("跨到 high: MAX_UINT128 + 1 = 2^128",
                UInt192.MAX_UINT128.add(new UInt192(0L, 0L, 1L)),
                new UInt192(1L, 0L, 0L));
        eq("中位进位: (0,ALL,ALL) + (ALL,0,0) = MAX_UINT192",
                new UInt192(0L, ALL, ALL).add(new UInt192(ALL, 0L, 0L)),
                new UInt192(ALL, ALL, ALL));
        eq("Long.MAX_VALUE + 1 = 0x8000000000000000",
                new UInt192(0L, 0L, LMAX).add(new UInt192(0L, 0L, 1L)),
                new UInt192(0L, 0L, 0x8000000000000000L));
        eq("MAX_UINT128 + MAX_UINT128 = 2^129-2",
                UInt192.MAX_UINT128.add(UInt192.MAX_UINT128),
                new UInt192(1L, ALL, 0xFFFFFFFFFFFFFFFEL));
        eq("加法不改变原对象（不可变）",
                UInt192.ZERO,
                UInt192.ZERO.add(new UInt192(0L, 0L, 5L)).subtract(new UInt192(0L, 0L, 5L)));
    }

    private static void subtraction() {
        section("减法（借位链）");
        eq("MAX_UINT128 - 1",
                UInt192.MAX_UINT128.subtract(new UInt192(0L, 0L, 1L)),
                new UInt192(0L, ALL, 0xFFFFFFFFFFFFFFFEL));
        eq("MAX_UINT192 - MAX_UINT192 = 0",
                UInt192.MAX_UINT192.subtract(UInt192.MAX_UINT192),
                UInt192.ZERO);
        eq("跨 limb 借位: 2^128 - 1 = MAX_UINT128",
                new UInt192(1L, 0L, 0L).subtract(new UInt192(0L, 0L, 1L)),
                UInt192.MAX_UINT128);
        eq("中位借位到低位: 2^64 - 1",
                new UInt192(0L, 1L, 0L).subtract(new UInt192(0L, 0L, 1L)),
                new UInt192(0L, 0L, ALL));
        eq("下溢饱和: 0 - 1 = 0",
                UInt192.ZERO.subtract(new UInt192(0L, 0L, 1L)),
                UInt192.ZERO);
        eq("下溢饱和: 5 - MAX_UINT192 = 0",
                new UInt192(0L, 0L, 5L).subtract(UInt192.MAX_UINT192),
                UInt192.ZERO);
    }

    private static void unsignedComparison() {
        section("无符号比较（关键：不能依赖符号位）");
        cmp("MAX_UINT128 < 2^128", UInt192.MAX_UINT128, new UInt192(1L, 0L, 0L), -1);
        cmp("(0,ALL,0) > (0,0,ALL)", new UInt192(0L, ALL, 0L), new UInt192(0L, 0L, ALL), 1);
        cmp("(ALL,0,0) > (0,0,ALL)", new UInt192(ALL, 0L, 0L), new UInt192(0L, 0L, ALL), 1);
        cmp("(0,0,0x8000000000000000) > (0,0,1)",
                new UInt192(0L, 0L, 0x8000000000000000L), new UInt192(0L, 0L, 1L), 1);
        cmp("MAX_UINT192 > MAX_UINT128", UInt192.MAX_UINT192, UInt192.MAX_UINT128, 1);
        cmp("相等", new UInt192(1L, 2L, 3L), new UInt192(1L, 2L, 3L), 0);
        eq("min", new UInt192(0L, ALL, 0L).min(new UInt192(0L, 0L, ALL)), new UInt192(0L, 0L, ALL));
        eq("max", new UInt192(0L, ALL, 0L).max(new UInt192(0L, 0L, ALL)), new UInt192(0L, ALL, 0L));
    }

    private static void saturation() {
        section("饱和转换");
        lng("MAX_UINT192 -> Long.MAX_VALUE", UInt192.MAX_UINT192, LMAX);
        lng("MAX_UINT128 -> Long.MAX_VALUE", UInt192.MAX_UINT128, LMAX);
        lng("(0,0,Long.MAX_VALUE) -> Long.MAX_VALUE", new UInt192(0L, 0L, LMAX), LMAX);
        lng("(1,0,0) -> Long.MAX_VALUE (high!=0)", new UInt192(1L, 0L, 0L), LMAX);
        lng("(0,1,0) -> Long.MAX_VALUE (mid!=0)", new UInt192(0L, 1L, 0L), LMAX);
        lng("(0,0,0x8000000000000000) -> Long.MAX_VALUE (low 符号位)", new UInt192(0L, 0L, 0x8000000000000000L), LMAX);
        lng("(0,0,12345) -> 12345", new UInt192(0L, 0L, 12345L), 12345L);
        lng("(0,0,0) -> 0", UInt192.ZERO, 0L);
        intTest("MAX_UINT192 -> Integer.MAX_VALUE", UInt192.MAX_UINT192, IMAX);
        intTest("(0,0,12345) -> 12345", new UInt192(0L, 0L, 12345L), 12345);
        intTest("(0,0,0) -> 0", UInt192.ZERO, 0);
    }

    private static void fromLongAndZero() {
        section("fromLong / isZero");
        eq("fromLong(0) = ZERO", UInt192.fromLong(0L), UInt192.ZERO);
        eq("fromLong(-1) = ZERO（非负语义）", UInt192.fromLong(-1L), UInt192.ZERO);
        eq("fromLong(Long.MIN_VALUE) = ZERO", UInt192.fromLong(Long.MIN_VALUE), UInt192.ZERO);
        eq("fromLong(Long.MAX_VALUE)", UInt192.fromLong(LMAX), new UInt192(0L, 0L, LMAX));
        eq("fromLong(1000)", UInt192.fromLong(1000L), new UInt192(0L, 0L, 1000L));
        bool("ZERO.isZero()", UInt192.ZERO.isZero(), true);
        bool("MAX_UINT192.isZero()", UInt192.MAX_UINT192.isZero(), false);
        bool("fromLong(1).isZero()", UInt192.fromLong(1L).isZero(), false);
    }

    private static void toDoubleApprox() {
        section("toDouble 近似（容差比较）");
        near("MAX_UINT192.toDouble ≈ 6.27710173538668e57", UInt192.MAX_UINT192.toDouble(), 6.27710173538668e57, 1.0e43);
        near("MAX_UINT128.toDouble ≈ 3.40282366920938e38", UInt192.MAX_UINT128.toDouble(), 3.40282366920938e38, 1.0e24);
        near("ZERO.toDouble = 0", UInt192.ZERO.toDouble(), 0.0, 0.0);
        near("fromLong(1000).toDouble = 1000", UInt192.fromLong(1000L).toDouble(), 1000.0, 0.0);
    }

    private static void hexToString() {
        section("toString（十六进制，仅调试）");
        str("ZERO", UInt192.ZERO.toString(), "0x0000000000000000_0000000000000000_0000000000000000");
        str("MAX_UINT192", UInt192.MAX_UINT192.toString(),
                "0xFFFFFFFFFFFFFFFF_FFFFFFFFFFFFFFFF_FFFFFFFFFFFFFFFF");
        str("MAX_UINT128", UInt192.MAX_UINT128.toString(),
                "0x0000000000000000_FFFFFFFFFFFFFFFF_FFFFFFFFFFFFFFFF");
    }

    private static void numberFormat() {
        section("NumberUtil.format");
        str("null -> 0", NumberUtil.format(null), "0");
        str("ZERO -> 0", NumberUtil.format(UInt192.ZERO), "0");
        str("999 -> 999", NumberUtil.format(UInt192.fromLong(999L)), "999");
        str("1000 -> 1.00K", NumberUtil.format(UInt192.fromLong(1000L)), "1.00K");
        str("1e6 -> 1.00M", NumberUtil.format(UInt192.fromLong(1_000_000L)), "1.00M");
        str("1e9 -> 1.00G", NumberUtil.format(UInt192.fromLong(1_000_000_000L)), "1.00G");
        str("Long.MAX_VALUE -> 9.22E", NumberUtil.format(new UInt192(0L, 0L, LMAX)), "9.22E");

        // 999.99Q 与 1e33 的 limb 由 .NET BigInteger 算出并反算校验
        str("999.99Q -> 999.99Q",
                NumberUtil.format(new UInt192(0L, 0x0000314DA5F4BF34L, 0xFA9C58A8F0000000L)),
                "999.99Q");
        str("1e33 -> (1.00x10^3)Q",
                NumberUtil.format(new UInt192(0L, 0x0000314DC6448D93L, 0x38C15B0A00000000L)),
                "(1.00x10^3)Q");
        str("3.1e45 -> (3.10x10^15)Q",
                NumberUtil.format(new UInt192(0x00000000008B0241L, 0x38080B098C655DF0L, 0x2284F00000000000L)),
                "(3.10x10^15)Q");
        str("MAX_UINT128 -> (3.40x10^8)Q", NumberUtil.format(UInt192.MAX_UINT128), "(3.40x10^8)Q");
        str("MAX_UINT192 -> (6.28x10^27)Q", NumberUtil.format(UInt192.MAX_UINT192), "(6.28x10^27)Q");
    }

    // ------------------------------------------------------------- 断言工具

    private static void section(String name) {
        System.out.println();
        System.out.println("[ " + name + " ]");
    }

    private static void eq(String label, UInt192 actual, UInt192 expected) {
        check(label, expected.toString(), actual.toString());
    }

    private static void cmp(String label, UInt192 a, UInt192 b, int expectedSign) {
        int sign = Integer.signum(a.compareTo(b));
        check(label, "symbol=" + expectedSign, "symbol=" + sign);
    }

    private static void lng(String label, UInt192 value, long expected) {
        check(label, Long.toString(expected), Long.toString(value.saturatingToLong()));
    }

    private static void intTest(String label, UInt192 value, int expected) {
        check(label, Integer.toString(expected), Integer.toString(value.saturatingToInt()));
    }

    private static void bool(String label, boolean actual, boolean expected) {
        check(label, Boolean.toString(expected), Boolean.toString(actual));
    }

    private static void str(String label, String actual, String expected) {
        check(label, expected, actual);
    }

    private static void near(String label, double actual, double expected, double tolerance) {
        boolean ok = Math.abs(actual - expected) <= tolerance;
        check(label, Double.toString(expected) + " ±" + tolerance, Double.toString(actual), ok);
    }

    private static void check(String label, String expected, String actual) {
        check(label, expected, actual, expected.equals(actual));
    }

    private static void check(String label, String expected, String actual, boolean ok) {
        if (ok) {
            passed++;
            System.out.printf("  PASS  %s%n", label);
        } else {
            failed++;
            System.out.printf("  FAIL  %s%n        期望: %s%n        实际: %s%n", label, expected, actual);
        }
    }
}
