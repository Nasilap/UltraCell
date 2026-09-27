package com.nasilap.ultracell.util;

import com.nasilap.ultracell.storage.UInt192;

import net.minecraft.ChatFormatting;

/**
 * 占用率 **4 档分档** —— tooltip 上色与贴图状态灯 tint 的**唯一**实现。
 *
 * <p>两条需求（tooltip 颜色方案、状态灯 tint）都要求"按占用率分 4 档、与对方一致"，
 * 因此分档逻辑**只在这里写一份**。将来改阈值只需改这一个文件，
 * 不会出现"贴图是黄的、tooltip 是橙的"。
 *
 * <p><b>档位定义</b>（下界含、上界不含）：
 * <pre>
 *   0  绿  [0.00, 0.35)
 *   1  黄  [0.35, 0.70)
 *   2  橙  [0.70, 0.95)
 *   3  红  [0.95, 1.00]
 * </pre>
 *
 * <p><b>判档用未取整的真实比值</b>：不用 tooltip 上显示的三位小数文本，
 * 否则 99.999% 的封顶值之类会与真实占用率脱节。
 * 显示用的百分比文本仍由 {@code item.CellTooltipFormatter} 负责（含六步判定与缓存）。
 *
 * <p><b>刻意不 import {@code item} 包</b>：{@code item} 已经依赖 {@code util}（NumberUtil），
 * 这里再反向引用会形成包级双向依赖。两个包都只用本类的静态方法即可。
 *
 * <p>本类**无状态、零分配**：{@link #levelOfCapacity} 直接用组件里的三个 long 算比值，
 * 不构造 {@link UInt192}。因此 tint provider 可以每帧每栈直接调用，**不需要缓存**。
 */
public final class StatusTint {

    /** 档位数量。 */
    public static final int LEVEL_COUNT = 4;

    /** 黄档下界。 */
    private static final double THRESHOLD_YELLOW = 0.35;

    /** 橙档下界。 */
    private static final double THRESHOLD_ORANGE = 0.70;

    /** 红档下界。 */
    private static final double THRESHOLD_RED = 0.95;

    /** 绿 = §a。 */
    public static final int RGB_GREEN = 0x55FF55;

    /** 黄 = §e。 */
    public static final int RGB_YELLOW = 0xFFFF55;

    /** 橙 = §6。 */
    public static final int RGB_ORANGE = 0xFFAA00;

    /** 红 = §c。 */
    public static final int RGB_RED = 0xFF5555;

    /** 档位 → tooltip 用颜色。 */
    private static final ChatFormatting[] FORMATTINGS = {
            ChatFormatting.GREEN, ChatFormatting.YELLOW, ChatFormatting.GOLD, ChatFormatting.RED
    };

    /** 档位 → 贴图 tint 用 RGB。 */
    private static final int[] RGBS = {RGB_GREEN, RGB_YELLOW, RGB_ORANGE, RGB_RED};

    private StatusTint() {}

    /**
     * 由**未取整的真实比值**判档。比值为 1.0 时归红档；NaN 归绿档（防御性兜底）。
     *
     * @param ratio 占用率，取值 0.0 ~ 1.0
     * @return 档位 0~3
     */
    public static int level(double ratio) {
        // 写成 !(ratio >= 下界) 是为了让 NaN 也落进绿档（NaN 的所有比较都是 false）
        if (!(ratio >= THRESHOLD_YELLOW)) {
            return 0;
        }
        if (ratio < THRESHOLD_ORANGE) {
            return 1;
        }
        if (ratio < THRESHOLD_RED) {
            return 2;
        }
        return 3;
    }

    /**
     * 容量占用率档位：{@code used / max}。
     *
     * <p>直接吃数据组件里的三个 long（{@code FEEnergyComponent} 或 {@code CellSummaryComponent}），
     * 全程**零分配**。上限 &le; 0 时按 0% 处理。
     */
    public static int levelOfCapacity(long usedHigh, long usedMid, long usedLow, UInt192 max) {
        double maxDouble = max.toDouble();
        if (maxDouble <= 0.0) {
            return 0;
        }
        return level(UInt192.toDouble(usedHigh, usedMid, usedLow) / maxDouble);
    }

    /**
     * 类型位占用率档位：{@code usedTypes / slots}。
     *
     * <p><b>与容量档位完全独立</b>：类型行按类型位占用率上色，不看容量。
     * 槽位 &le; 0 时按 0% 处理。
     */
    public static int levelOfCount(long usedTypes, int slots) {
        if (slots <= 0) {
            return 0;
        }
        return level((double) usedTypes / (double) slots);
    }

    /**
     * 档位 → 贴图 tint 用的颜色，**不透明 ARGB（0xFFRRGGBB）**。越界档位会被夹到合法范围。
     *
     * <p>⚠⚠ <b>alpha 必须显式补成 0xFF，这不是可选的美化</b>：
     * {@code ItemRenderer.renderQuadList} 是这么取 tint 的
     * （1.21.1 源码，约 200~208 行）：
     * <pre>
     *   int i = -1;
     *   if (flag &amp;&amp; bakedquad.isTinted()) {
     *       i = this.itemColors.getColor(itemStack, bakedquad.getTintIndex());
     *   }
     *   float alpha = (float)FastColor.ARGB32.alpha(i) / 255.0F;   // ← 高 8 位就是面片透明度
     *   float red   = (float)FastColor.ARGB32.red(i)   / 255.0F;
     *   …
     * </pre>
     * 也就是说 {@code ItemColor} 的返回值是 **ARGB 乘数**：只给 {@code 0xRRGGBB}
     * 等于 {@code alpha = 0} ⇒ **整个面片被渲染成全透明 ⇒ 状态灯看不见**
     * （这正是 {@code -1} 能当"不染色"哨兵的原因：{@code 0xFFFFFFFF} = 不透明白 = 乘 1 不变）。
     *
     * <p>参照：AE2 的 {@code appeng.init.client.InitItemColors} 里有个 {@code makeOpaque}，
     * 把每个 provider 都包一层 {@code FastColor.ARGB32.opaque(...)} 再注册
     * —— 同一个坑，他们用包装器统一兜住了。我们直接在这个出口补 alpha。
     */
    public static int argb(int level) {
        return 0xFF000000 | RGBS[clamp(level)];
    }

    /** 档位 → tooltip 用的 {@link ChatFormatting}。越界档位会被夹到合法范围。 */
    public static ChatFormatting color(int level) {
        return FORMATTINGS[clamp(level)];
    }

    private static int clamp(int level) {
        if (level <= 0) {
            return 0;
        }
        return level >= LEVEL_COUNT ? LEVEL_COUNT - 1 : level;
    }
}
