package com.nasilap.ultracell;

import com.nasilap.ultracell.command.CellCommands;
import com.nasilap.ultracell.command.DebugCommand;
import com.nasilap.ultracell.command.OrphanCounter;
import com.nasilap.ultracell.command.ScanCommand;
import com.nasilap.ultracell.item.ExternalCellHandler;
import com.nasilap.ultracell.registry.ModComponents;
import com.nasilap.ultracell.registry.ModCreativeTabs;
import com.nasilap.ultracell.registry.ModItems;
import com.nasilap.ultracell.storage.FECellHandler;
import com.nasilap.ultracell.storage.StorageEvents;

import appeng.api.storage.StorageCells;
import appeng.api.upgrades.Upgrades;
import appeng.core.definitions.AEItems;
import appeng.core.localization.GuiText;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Ultra Cell（究极元件）主类。
 *
 * <p>2.0.0：FE 元件加已用百分比 tooltip；新增流体 / 化学品两种外置存储元件
 * （一元件一 {@code .dat}）与一整套 {@code /ultracell} 命令。
 *
 * <p>FE 元件仍然只走 AE2 网络，不对外提供 NeoForge 能量能力，也不走外置存储。
 */
@Mod(UltraCell.MOD_ID)
public class UltraCell {

    public static final String MOD_ID = "ultracell";

    public static final Logger LOGGER = LogUtils.getLogger();

    public UltraCell(IEventBus modEventBus) {
        ModComponents.register(modEventBus);
        ModItems.register(modEventBus);
        ModCreativeTabs.register(modEventBus);

        modEventBus.addListener(UltraCell::commonSetup);

        // 游戏总线：外置存储的生命周期与写盘 tick、孤儿计数与提示、四套命令注册。
        // 四个 RegisterCommandsEvent 监听器各自挂一次是**允许且必要**的：
        // 同名子节点会被 Brigadier 递归合并到同一棵 /ultracell 树上。
        NeoForge.EVENT_BUS.register(new StorageEvents());
        NeoForge.EVENT_BUS.register(new OrphanCounter());
        NeoForge.EVENT_BUS.register(DebugCommand.class);
        NeoForge.EVENT_BUS.register(CellCommands.class);
        NeoForge.EVENT_BUS.register(ScanCommand.class);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // 把元件接入 AE2 存储体系（两个 handler 的 isCell 判定必须互斥）
            StorageCells.addCellHandler(FECellHandler.INSTANCE);
            StorageCells.addCellHandler(ExternalCellHandler.INSTANCE);

            // 元件都只允许装 void 卡，各 1 张；升级槽共 3 个
            String group = GuiText.StorageCells.getTranslationKey();
            Upgrades.add(AEItems.VOID_CARD, ModItems.HONGMENG_FE_CELL.get(), 1, group);
            Upgrades.add(AEItems.VOID_CARD, ModItems.WUJI_FE_CELL.get(), 1, group);
            for (var holder : ModItems.EXTERNAL_CELLS) {
                Upgrades.add(AEItems.VOID_CARD, holder.get(), 1, group);
            }

            // 外置存储元件的 3 个升级槽里，void 卡与**反相卡**各上限 1
            // （反相卡 = AE2 原生过滤语义：无白名单 + 反相卡 → 忽略反相卡）
            for (var holder : ModItems.EXTERNAL_CELLS) {
                Upgrades.add(AEItems.INVERTER_CARD, holder.get(), 1, group);
            }

            LOGGER.debug("Ultra Cell common setup done");
        });
    }
}
