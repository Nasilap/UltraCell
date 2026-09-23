package com.nasilap.ultracell;

import com.nasilap.ultracell.registry.ModComponents;
import com.nasilap.ultracell.registry.ModCreativeTabs;
import com.nasilap.ultracell.registry.ModItems;
import com.nasilap.ultracell.storage.FECellHandler;

import appeng.api.storage.StorageCells;
import appeng.api.upgrades.Upgrades;
import appeng.core.definitions.AEItems;
import appeng.core.localization.GuiText;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Ultra Cell（究极元件）主类。
 *
 * <p>中后期 FE 仓库，只走 AE2 网络，不对外提供 NeoForge 能量能力。
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
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // 把元件接入 AE2 存储体系
            StorageCells.addCellHandler(FECellHandler.INSTANCE);

            // 两个等级的元件都只允许装 void 卡，各 1 张；升级槽共 3 个
            String group = GuiText.StorageCells.getTranslationKey();
            Upgrades.add(AEItems.VOID_CARD, ModItems.HONGMENG_FE_CELL.get(), 1, group);
            Upgrades.add(AEItems.VOID_CARD, ModItems.WUJI_FE_CELL.get(), 1, group);

            LOGGER.debug("Ultra Cell common setup done");
        });
    }
}
