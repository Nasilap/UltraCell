package com.nasilap.ultracell.registry;

import com.nasilap.ultracell.UltraCell;

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
 * <p>标题键为 {@code itemGroup.ultracell}；图标用无极元件，与模组图标保持一致。
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
                        output.accept(ModItems.HONGMENG_FE_COMPONENT.get());
                        output.accept(ModItems.WUJI_FE_COMPONENT.get());
                        output.accept(ModItems.HONGMENG_FE_CELL.get());
                        output.accept(ModItems.WUJI_FE_CELL.get());
                    })
                    .build());

    private ModCreativeTabs() {}

    public static void register(IEventBus modEventBus) {
        DR.register(modEventBus);
    }
}
