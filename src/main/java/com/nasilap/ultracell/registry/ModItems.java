package com.nasilap.ultracell.registry;

import com.nasilap.ultracell.UltraCell;
import com.nasilap.ultracell.item.ExternalCellItem;
import com.nasilap.ultracell.item.FECellComponentItem;
import com.nasilap.ultracell.item.FECellItem;
import com.nasilap.ultracell.storage.ExternalCellStorage;
import com.nasilap.ultracell.storage.UInt192;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 物品注册。共 **13 个**：
 * <ul>
 *   <li>4 个 FE：鸿蒙/无极 × 组件/元件（1.0.0 已有，注册名不变）</li>
 *   <li>8 个外置存储：鸿蒙/无极 × 流体/化学品 × 组件/元件</li>
 *   <li>1 个究极存储外壳（材料，普通 {@link Item}，默认 64 堆叠）</li>
 * </ul>
 *
 * <p><b>化学品物品恒定注册</b>：只把「配方」与「功能」放进门控
 * （appmek 缺席时化学品元件没有 keyType 可用 ⇒ 放不进 ME 网络，但仍可 /give 获取）。
 * 这样同一会话内双端物品集恒等，跨会话换模组组合也不会让存档出现缺失物品。
 *
 * <p><b>物品名颜色</b>（2.0.1）：13 个物品全部挂上 {@code DataComponents.ITEM_NAME}
 * 默认组件 —— 鸿蒙 / 究极外壳 §d 紫，无极 §6 金。
 * 用默认组件而不是覆写 {@code Item#getName}，好处有三：
 * <ul>
 *   <li>究极外壳是普通 {@code new Item(...)}，本来无法就地覆写方法；</li>
 *   <li>不必给 {@code FECellItem} 再补一个 tier 字段（它只有容量常量）；</li>
 *   <li>默认组件来自物品注册表、**不进 NBT 也不走网络**，双端天然一致。</li>
 * </ul>
 * 名字在物品栏 / GUI / 展示框里同样会变色 —— 这是用户明确要的效果。
 *
 * <p>{@link #cellForTierKind} 是给 {@code recover}（以及 scan 的条目标注）用的映射，
 * <b>不要在静态初始化里调用</b>：{@code DeferredItem.get()} 需要注册表就绪。
 */
public final class ModItems {

    /** 鸿蒙上限：uint128 最大值 ≈ 3.40e38。 */
    public static final UInt192 HONGMENG_CAPACITY = UInt192.MAX_UINT128;

    /** 无极上限：uint192 最大值 ≈ 6.28e57。 */
    public static final UInt192 WUJI_CAPACITY = UInt192.MAX_UINT192;

    /** 两个等级的空闲耗电，均为 0（不耗电）。 */
    public static final double IDLE_DRAIN = 0.0;

    /** 鸿蒙 / 究极外壳的物品名颜色：§d 紫。 */
    private static final ChatFormatting HONGMENG_NAME_COLOR = ChatFormatting.LIGHT_PURPLE;

    /** 无极的物品名颜色：§6 金。 */
    private static final ChatFormatting WUJI_NAME_COLOR = ChatFormatting.GOLD;

    public static final DeferredRegister.Items DR = DeferredRegister.createItems(UltraCell.MOD_ID);

    // ── 究极存储外壳 ──

    /** 究极存储外壳：所有元件的共同外壳材料（不进 {@code {tier}_{type}_{kind}} 命名体系）。 */
    public static final DeferredItem<Item> ULTIMATE_CELL_HOUSING = DR.register(
            "ultimate_cell_housing",
            () -> new Item(named("ultimate_cell_housing", HONGMENG_NAME_COLOR)));

    // ── FE（1.0.0 已有） ──

    public static final DeferredItem<FECellComponentItem> HONGMENG_FE_COMPONENT = DR.register(
            "hongmeng_fe_component",
            () -> new FECellComponentItem(named("hongmeng_fe_component", HONGMENG_NAME_COLOR), HONGMENG_CAPACITY));

    public static final DeferredItem<FECellComponentItem> WUJI_FE_COMPONENT = DR.register(
            "wuji_fe_component",
            () -> new FECellComponentItem(named("wuji_fe_component", WUJI_NAME_COLOR), WUJI_CAPACITY));

    public static final DeferredItem<FECellItem> HONGMENG_FE_CELL = DR.register(
            "hongmeng_fe_cell",
            () -> new FECellItem(named("hongmeng_fe_cell", HONGMENG_NAME_COLOR),
                    HONGMENG_CAPACITY, IDLE_DRAIN, UltraCell.id("hongmeng_fe_component")));

    public static final DeferredItem<FECellItem> WUJI_FE_CELL = DR.register(
            "wuji_fe_cell",
            () -> new FECellItem(named("wuji_fe_cell", WUJI_NAME_COLOR),
                    WUJI_CAPACITY, IDLE_DRAIN, UltraCell.id("wuji_fe_component")));

    // ── 流体 ──

    public static final DeferredItem<FECellComponentItem> HONGMENG_FLUID_COMPONENT = DR.register(
            "hongmeng_fluid_component",
            () -> new FECellComponentItem(named("hongmeng_fluid_component", HONGMENG_NAME_COLOR), HONGMENG_CAPACITY));

    public static final DeferredItem<FECellComponentItem> WUJI_FLUID_COMPONENT = DR.register(
            "wuji_fluid_component",
            () -> new FECellComponentItem(named("wuji_fluid_component", WUJI_NAME_COLOR), WUJI_CAPACITY));

    public static final DeferredItem<ExternalCellItem> HONGMENG_FLUID_CELL = DR.register(
            "hongmeng_fluid_cell",
            () -> new ExternalCellItem(named("hongmeng_fluid_cell", HONGMENG_NAME_COLOR),
                    ExternalCellStorage.TIER_HONGMENG, ExternalCellStorage.KIND_FLUID,
                    UltraCell.id("hongmeng_fluid_component")));

    public static final DeferredItem<ExternalCellItem> WUJI_FLUID_CELL = DR.register(
            "wuji_fluid_cell",
            () -> new ExternalCellItem(named("wuji_fluid_cell", WUJI_NAME_COLOR),
                    ExternalCellStorage.TIER_WUJI, ExternalCellStorage.KIND_FLUID,
                    UltraCell.id("wuji_fluid_component")));

    // ── 化学品（恒定注册；功能由 appmek 门控） ──

    public static final DeferredItem<FECellComponentItem> HONGMENG_CHEMICAL_COMPONENT = DR.register(
            "hongmeng_chemical_component",
            () -> new FECellComponentItem(
                    named("hongmeng_chemical_component", HONGMENG_NAME_COLOR), HONGMENG_CAPACITY));

    public static final DeferredItem<FECellComponentItem> WUJI_CHEMICAL_COMPONENT = DR.register(
            "wuji_chemical_component",
            () -> new FECellComponentItem(named("wuji_chemical_component", WUJI_NAME_COLOR), WUJI_CAPACITY));

    public static final DeferredItem<ExternalCellItem> HONGMENG_CHEMICAL_CELL = DR.register(
            "hongmeng_chemical_cell",
            () -> new ExternalCellItem(named("hongmeng_chemical_cell", HONGMENG_NAME_COLOR),
                    ExternalCellStorage.TIER_HONGMENG, ExternalCellStorage.KIND_CHEMICAL,
                    UltraCell.id("hongmeng_chemical_component")));

    public static final DeferredItem<ExternalCellItem> WUJI_CHEMICAL_CELL = DR.register(
            "wuji_chemical_cell",
            () -> new ExternalCellItem(named("wuji_chemical_cell", WUJI_NAME_COLOR),
                    ExternalCellStorage.TIER_WUJI, ExternalCellStorage.KIND_CHEMICAL,
                    UltraCell.id("wuji_chemical_component")));

    /** 4 个外置存储元件，供升级注册遍历用（顺序：流体鸿蒙/无极 → 化学品鸿蒙/无极）。 */
    public static final List<DeferredItem<ExternalCellItem>> EXTERNAL_CELLS = List.of(
            HONGMENG_FLUID_CELL, WUJI_FLUID_CELL, HONGMENG_CHEMICAL_CELL, WUJI_CHEMICAL_CELL);

    private ModItems() {}

    public static void register(IEventBus modEventBus) {
        DR.register(modEventBus);
    }

    /**
     * 带名字颜色的物品属性。
     *
     * <p>翻译键由**注册名**推导（{@code Util.makeDescriptionId} 与
     * {@code Item#getDescriptionId()} 用的是同一套规则），因此 {@code path}
     * 必须与 {@code DR.register(...)} 的第一个参数**逐字相同** ——
     * 两处写在一起就是为了让这一点一眼可见。
     */
    private static Item.Properties named(String path, ChatFormatting color) {
        return new Item.Properties().component(DataComponents.ITEM_NAME,
                Component.translatable(Util.makeDescriptionId("item", UltraCell.id(path))).withStyle(color));
    }

    /**
     * {@code tier + kind → 元件物品} 的映射（4 种组合；FE 不走外置存储故不在列）。
     *
     * <p>返回 {@code @Nullable}：未知组合返回 {@code null}，由调用方处理为「该类型不可用」，
     * 不抛异常。<b>不要在静态初始化里调用</b>（{@code DeferredItem.get()} 需注册表就绪；
     * 命令执行期可以）。
     */
    @Nullable
    public static Item cellForTierKind(String tier, String kind) {
        if (ExternalCellStorage.TIER_HONGMENG.equals(tier)) {
            if (ExternalCellStorage.KIND_FLUID.equals(kind)) {
                return HONGMENG_FLUID_CELL.get();
            }
            if (ExternalCellStorage.KIND_CHEMICAL.equals(kind)) {
                return HONGMENG_CHEMICAL_CELL.get();
            }
        } else if (ExternalCellStorage.TIER_WUJI.equals(tier)) {
            if (ExternalCellStorage.KIND_FLUID.equals(kind)) {
                return WUJI_FLUID_CELL.get();
            }
            if (ExternalCellStorage.KIND_CHEMICAL.equals(kind)) {
                return WUJI_CHEMICAL_CELL.get();
            }
        }
        return null;
    }
}
