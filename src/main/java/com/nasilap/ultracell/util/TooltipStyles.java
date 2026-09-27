package com.nasilap.ultracell.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * tooltip 配色的**唯一**落点（颜色表 = 用户裁决）。
 *
 * <p>规则：
 * <pre>
 *   物品名    鸿蒙 / 究极外壳 §d 紫，无极 §6 金   —— 由 ModItems 挂 ITEM_NAME 默认组件实现
 *   标签      §f 白 + 加粗          （UUID：/ 类型 / 已用 / 所有者：/ 分隔符 / 无主）
 *   值        §e 亮黄 + 不加粗      （UUID 内容、玩家名）
 *   上限值    §b 浅蓝 + 不加粗      （容量上限、类型上限）
 *   数值/百分比  按占用率 4 档变色 + 不加粗（档位由 {@link StatusTint} 判定）
 *   mod 名    不动（由外部追加，本模组不参与）
 * </pre>
 *
 * <p><b>加粗会向下继承</b>：整行用 {@code withStyle(WHITE, BOLD)} 之后，
 * 子组件默认也跟着加粗。所以每个"值"组件都必须**显式** {@code withBold(false)}，
 * 否则会变成"连数字都加粗"，与颜色表不符。
 *
 * <p><b>百分号单独一个组件</b>：需求要求「已用」「%」白加粗、只有数字本体变色，
 * 因此不能把数字与 % 粘成一个字符串（{@code CellTooltipFormatter} 只产出数字）。
 */
public final class TooltipStyles {

    /** 标签 / 分隔符 / 无主：白 + 加粗。 */
    public static final Style LABEL = Style.EMPTY.withColor(ChatFormatting.WHITE).withBold(true);

    /** 值（UUID 内容、玩家名）：亮黄 + 不加粗。 */
    public static final Style VALUE = Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(false);

    /** 上限值（容量上限、类型上限）：浅蓝 + 不加粗。 */
    public static final Style LIMIT = Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(false);

    private TooltipStyles() {}

    /**
     * 整行：模板里的字面量（标签、 "/" 等）自动白 + 加粗。
     *
     * <p>参数允许直接传 {@link Component}，其自带样式会被保留。
     */
    public static MutableComponent line(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD);
    }

    /** 亮黄的值（UUID 内容 / 玩家名），显式取消继承来的加粗。 */
    public static MutableComponent value(String text) {
        return Component.literal(text).withStyle(VALUE);
    }

    /** 浅蓝的上限值，显式取消继承来的加粗。 */
    public static MutableComponent limit(String text) {
        return Component.literal(text).withStyle(LIMIT);
    }

    /** 「无主」三个字：白 + 加粗。 */
    public static MutableComponent ownerless() {
        return Component.translatable("ultracell.tooltip.ownerless").withStyle(LABEL);
    }

    /** 按占用率档位上色的数值（不加粗）。 */
    public static MutableComponent tiered(String text, int level) {
        return Component.literal(text)
                .withStyle(Style.EMPTY.withColor(StatusTint.color(level)).withBold(false));
    }

    /** 白加粗的百分号，与数字分开以满足三段渲染。 */
    public static MutableComponent percentSign() {
        return Component.literal("%").withStyle(LABEL);
    }

    /**
     * 「已用 xx.xxx%」尾段：数字按占用率变色 + 白加粗的 %。
     *
     * <p>渲染结果示例：{@code 0.000}（档位色）紧接 {@code %}（白加粗）。
     */
    public static MutableComponent percentTail(String body, int level) {
        return Component.empty().append(tiered(body, level)).append(percentSign());
    }
}
