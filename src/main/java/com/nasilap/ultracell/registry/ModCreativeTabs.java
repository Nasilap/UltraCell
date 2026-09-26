package com.nasilap.ultracell.registry;

import com.nasilap.ultracell.UltraCell;
import com.nasilap.ultracell.util.ChemicalIntegration;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 创造模式标签页。
 *
 * <p>标题键 {@code itemGroup.ultracell}；图标用无极 FE 元件，与模组图标一致。
 *
 * <p>全序（已定决策）：FE 组件 → 流体组件 → 化学品组件 → **究极外壳** → FE 元件 →
 * 流体元件 → 化学品元件；每类内**先鸿蒙后无极**。共 13 项。
 *
 * <p>化学品条目按 {@link ChemicalIntegration#ENABLED} 门控：物品本身恒定注册
 * （避免双端物品集不一致），但在没装 appmek 时不进创造页。
 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> DR =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, UltraCell.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = DR.register(
            "ultracell",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ultracell"))
                    .icon(() -> new ItemStack(ModItems.WUJI_FE_CELL.get()))
                    .displayItems((parameters, output) -> {
                        // FE 组件
                        output.accept(ModItems.HONGMENG_FE_COMPONENT.get());
                        output.accept(ModItems.WUJI_FE_COMPONENT.get());
                        // 流体组件
                        output.accept(ModItems.HONGMENG_FLUID_COMPONENT.get());
                        output.accept(ModItems.WUJI_FLUID_COMPONENT.get());
                        // 化学品组件（appmek 门控）
                        if (ChemicalIntegration.ENABLED) {
                            output.accept(ModItems.HONGMENG_CHEMICAL_COMPONENT.get());
                            output.accept(ModItems.WUJI_CHEMICAL_COMPONENT.get());
                        }
                        // 究极存储外壳
                        output.accept(ModItems.ULTIMATE_CELL_HOUSING.get());
                        // FE 元件
                        output.accept(ModItems.HONGMENG_FE_CELL.get());
                        output.accept(ModItems.WUJI_FE_CELL.get());
                        // 流体元件
                        output.accept(ModItems.HONGMENG_FLUID_CELL.get());
                        output.accept(ModItems.WUJI_FLUID_CELL.get());
                        // 化学品元件（appmek 门控）
                        if (ChemicalIntegration.ENABLED) {
                            output.accept(ModItems.HONGMENG_CHEMICAL_CELL.get());
                            output.accept(ModItems.WUJI_CHEMICAL_CELL.get());
                        }
                    })
                    .build());

    private ModCreativeTabs() {}

    public static void register(IEventBus modEventBus) {
        DR.register(modEventBus);
    }
}
