package com.nasilap.ultracell.registry;

import com.nasilap.ultracell.UltraCell;
import com.nasilap.ultracell.storage.CellSummaryComponent;
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

    /**
     * 外置存储元件的摘要（六个 long）：UUID、类型数、已用量。
     *
     * <p><b>归属不在这里</b> —— 归属只存在数据文件里，文件是唯一权威源。
     * 不含版本字段；六个字段一律「等于默认值即省略」。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CellSummaryComponent>> CELL_SUMMARY =
            DR.register("cell_summary", () -> DataComponentType.<CellSummaryComponent>builder()
                    .persistent(CellSummaryComponent.CODEC)
                    .networkSynchronized(CellSummaryComponent.STREAM_CODEC)
                    .build());

    private ModComponents() {}

    public static void register(IEventBus modEventBus) {
        DR.register(modEventBus);
    }
}
