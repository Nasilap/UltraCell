package com.nasilap.ultracell.registry;

import com.nasilap.ultracell.UltraCell;
import com.nasilap.ultracell.storage.FEEnergyComponent;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 数据组件注册。
 *
 * <p>只注册一个组件 {@code ultracell:fe_energy}，同时挂上
 * {@code persistent}（存档）与 {@code networkSynchronized}（网络同步）两条编解码路径。
 */
public final class ModComponents {

    public static final DeferredRegister<DataComponentType<?>> DR =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, UltraCell.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<FEEnergyComponent>> FE_ENERGY =
            DR.register("fe_energy", () -> DataComponentType.<FEEnergyComponent>builder()
                    .persistent(FEEnergyComponent.CODEC)
                    .networkSynchronized(FEEnergyComponent.STREAM_CODEC)
                    .build());

    private ModComponents() {}

    public static void register(IEventBus modEventBus) {
        DR.register(modEventBus);
    }
}
