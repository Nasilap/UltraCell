package com.nasilap.ultracell.client;

import com.nasilap.ultracell.item.ExternalCellItem;
import com.nasilap.ultracell.item.FECellItem;
import com.nasilap.ultracell.registry.ModComponents;
import com.nasilap.ultracell.registry.ModItems;
import com.nasilap.ultracell.storage.CellDataManager;
import com.nasilap.ultracell.storage.CellSummaryComponent;
import com.nasilap.ultracell.storage.FEEnergyComponent;
import com.nasilap.ultracell.util.OwnerNameResolver;
import com.nasilap.ultracell.util.StatusTint;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/**
 * 客户端专有初始化 —— **本模组唯一引用 {@code net.minecraft.client.*} 的地方**。
 *
 * <p>由 {@code UltraCell} 主类**在 {@code Dist.CLIENT} 守卫内**调用 {@link #register}，
 * 专用服务器上永远不会解析/加载本包。
 *
 * <p><b>为什么不用 {@code @EventBusSubscriber}</b>：FML 4.0.44 里
 * {@code EventBusSubscriber.bus()} 与 {@code EventBusSubscriber.Bus} 都已标记为
 * **待删除（deprecated for removal）**，而 {@code bus()} 的默认值又是 {@code Bus.GAME}
 * （不是 MOD）—— 写它就必然吃警告、不写就注册到错误的 bus。
 * 改用主类里显式 {@code modEventBus.addListener(...)}，与主类现有的注册风格一致，
 * 且**零警告**、零待删除 API。
 *
 * <p>做两件事：
 * <ol>
 *   <li>给 6 个元件注册**状态灯 tint provider**（贴图 overlay 按容量占用率 **4 档静态**变色）；</li>
 *   <li>把"在线玩家名"反查注入给 {@code util.OwnerNameResolver}
 *       —— 公共代码因此不需要引用任何客户端类。</li>
 * </ol>
 *
 * <p>硬约束：无 Mixin、无反射、不碰 AE2 内部 —— 这里只用公开 API
 * （数据组件读取 + 颜色注册事件）。
 */
public final class UltraCellClientSetup {

    /**
     * 状态灯 overlay 所在的层号。
     *
     * <p>{@code item/generated} 的模型层 {@code layerN} 会被
     * {@code ItemModelGenerator} 以**层下标**作为 tintIndex
     * （源码：{@code processFrames(i, ...)}），所以 layer1 = tintindex 1。
     * 参照 AE2 的便携元件：`portable_item_cell_*.json` 也是把 LED 放在 layer1。
     */
    private static final int TINT_STATUS_LIGHT = 1;

    /** 不染色。 */
    private static final int NO_TINT = -1;

    private UltraCellClientSetup() {}

    /** 由主类在客户端分支里调用；两个监听器都挂在**模组事件总线**上。 */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(UltraCellClientSetup::onClientSetup);
        modEventBus.addListener(UltraCellClientSetup::onRegisterItemColors);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        OwnerNameResolver.installClientLookup(ClientOwnerNames::lookupOnline);
    }

    private static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
        event.register(UltraCellClientSetup::statusLightColor,
                ModItems.HONGMENG_FE_CELL.get(),
                ModItems.WUJI_FE_CELL.get(),
                ModItems.HONGMENG_FLUID_CELL.get(),
                ModItems.WUJI_FLUID_CELL.get(),
                ModItems.HONGMENG_CHEMICAL_CELL.get(),
                ModItems.WUJI_CHEMICAL_CELL.get());
    }

    /**
     * 状态灯颜色：按容量占用率取 4 档基础色，**静态**。
     *
     * <p><b>⚠ 返回值必须是"不透明 ARGB"</b>（见 {@link StatusTint#argb}）。
     * 曾经这里返回过纯 RGB（{@code 0xRRGGBB}，高位 alpha = 0），
     * 结果 {@code ItemRenderer} 把 alpha 当成面片透明度 ⇒ **整个面片被画成全透明
     * ⇒ 状态灯完全看不见**（不是"颜色不对"，是"整片没了"）。
     * 参照 AE2：{@code appeng.init.client.InitItemColors} 用
     * {@code FastColor.ARGB32.opaque(...)} 把每个 provider 都包了一层，兜的就是这个坑。
     *
     * <p><b>为什么是静态的</b>：曾经叠加过 1 Hz 正弦呼吸灯，但当时整片透明、
     * 根本没有可见像素可供调制；且状态灯只有 **1 个像素**，
     * 明暗变化落在 1~3 个屏幕像素上肉眼也分辨不出。故定稿为静态 4 档
     * （见决策卷 A17，**不要再加回来**）。
     *
     * <p><b>每帧每个被渲染的栈都会调用</b>（同一元件的每个带 tintindex 的 quad 各调一次），
     * 因此全程零分配、不查缓存、不用任何 Minecraft 状态。
     *
     * <p>分母来自元件物品自身的常量（双端一致）；分子直接读数据组件里的三个 long
     * —— FE 走 {@code fe_energy}，流体 / 化学品走 {@code cell_summary}。
     * 两个组件都挂了 networkSynchronized，客户端拿得到。
     *
     * <p>数据尚未同步到客户端时按 0% 处理（显示绿），不会抛异常。
     */
    private static int statusLightColor(ItemStack stack, int tintIndex) {
        if (tintIndex != TINT_STATUS_LIGHT) {
            // 必须返回 -1，否则 layer0（主图）也会被染色
            return NO_TINT;
        }
        int level = statusLevel(stack);
        if (level < 0) {
            return NO_TINT;
        }
        return StatusTint.argb(level);
    }

    /**
     * 占用率档位（0~3）；该栈不是 6 个元件之一时返回 {@code -1}。
     *
     * <p>档位判定与 tooltip 共用 {@link StatusTint}，所以贴图与文字**永远同一档**。
     */
    private static int statusLevel(ItemStack stack) {
        Item item = stack.getItem();

        if (item instanceof FECellItem feCell) {
            FEEnergyComponent energy = stack.get(ModComponents.FE_ENERGY.get());
            if (energy == null) {
                return StatusTint.levelOfCapacity(0L, 0L, 0L, feCell.getMaxCapacity());
            }
            return StatusTint.levelOfCapacity(
                    energy.high(), energy.mid(), energy.low(), feCell.getMaxCapacity());
        }

        if (item instanceof ExternalCellItem externalCell) {
            CellSummaryComponent summary = CellDataManager.summaryOf(stack);
            return StatusTint.levelOfCapacity(
                    summary.usedHigh(), summary.usedMid(), summary.usedLow(),
                    externalCell.getTotalCapacity());
        }

        return -1;
    }
}
