package com.nasilap.ultracell.item;

import com.nasilap.ultracell.UltraCell;
import com.nasilap.ultracell.storage.FECellHandler;
import com.nasilap.ultracell.storage.FECellInventory;
import com.nasilap.ultracell.storage.UInt192;
import com.nasilap.ultracell.util.NumberUtil;

import appeng.api.config.FuzzyMode;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.core.localization.PlayerMessages;
import appeng.items.AEBaseItem;
import appeng.util.InteractionUtil;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Ultra Cell 的 FE 存储元件物品。两个等级（鸿蒙 / 无极）共用这一个类，
 * 差别只在构造时传入的 {@link UInt192} 上限。
 *
 * <p>刻意<b>不</b>实现 AppliedFlux 的 {@code IFluxCell}：那个接口继承
 * {@code ICapabilityProvider<ItemStack, Void, IEnergyStorage>} 并强制 {@code long getBytes(...)}，
 * 会与本模组「不实现 IEnergyStorage、不走字节体系」两条决策冲突。
 * 这里只实现 AE2 的 {@link ICellWorkbenchItem}。
 *
 * <p>同样刻意<b>不覆写</b> {@code getConfigInventory}：AE2 19.2.17 的
 * {@code ICellWorkbenchItem} 已提供默认的空配置实现（AppliedFlux 自己的 FE 元件也未覆写），
 * 本元件不接受任何过滤卡。
 */
public class FECellItem extends AEBaseItem implements ICellWorkbenchItem {

    /** 升级槽数量（已定决策：3 个）。 */
    public static final int UPGRADE_SLOTS = 3;

    /**
     * 拆解返还的外壳：2.0.0 起改为本模组自建的**究极存储外壳**。
     *
     * <p>用 {@code UltraCell.id(...)}（与 {@code ModItems} 风格一致；{@code MOD_ID} 是
     * 编译期常量，静态初始化无风险）。注意这是一次**单向、不可逆**的材料转换：
     * 1.0.0 时代用 {@code appflux:fe_cell_housing} 合成的旧元件，拆解后返还究极外壳。
     */
    private static final ResourceLocation HOUSING_ID = UltraCell.id("ultimate_cell_housing");

    private final UInt192 maxCapacity;
    private final double idleDrain;
    private final ResourceLocation componentId;

    /**
     * 百分比采样缓存。**每实例一份**（非静态）：鸿蒙与无极的分母差 18 个数量级，
     * 共用单例会让无极元件用鸿蒙的分母且不报错。
     */
    private final CellTooltipFormatter tooltipFormatter;

    public FECellItem(Properties properties, UInt192 maxCapacity, double idleDrain, ResourceLocation componentId) {
        super(properties.stacksTo(1));
        this.maxCapacity = maxCapacity;
        this.idleDrain = idleDrain;
        this.componentId = componentId;
        this.tooltipFormatter = new CellTooltipFormatter(maxCapacity);
    }

    /** 该等级的容量上限。 */
    public UInt192 getMaxCapacity() {
        return this.maxCapacity;
    }

    /** 空闲耗电。 */
    public double getIdleDrain() {
        return this.idleDrain;
    }

    /** 拆解后返还的组件物品注册名。 */
    public ResourceLocation getComponentId() {
        return this.componentId;
    }

    @Override
    public IUpgradeInventory getUpgrades(ItemStack stack) {
        return UpgradeInventories.forItem(stack, UPGRADE_SLOTS);
    }

    @Override
    public FuzzyMode getFuzzyMode(ItemStack is) {
        return null;
    }

    @Override
    public void setFuzzyMode(ItemStack is, FuzzyMode fzMode) {
        // 无模糊模式
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        @Nullable FECellInventory inventory = FECellHandler.INSTANCE.getCellInventory(stack, null);
        if (inventory == null) {
            return;
        }
        UInt192 stored = inventory.getStoredEnergy();
        // 守卫只包住缓存读写；这里的输出在守卫之外完成
        String percent = this.tooltipFormatter.percent(stack, stored);
        lines.add(Component.translatable(
                "tooltip.ultracell.stored",
                NumberUtil.format(stored),
                NumberUtil.format(inventory.getMaxCapacity()),
                percent));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        this.disassembleDrive(stack, level, player);
        return new InteractionResultHolder<>(InteractionResult.sidedSuccess(level.isClientSide()), stack);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        return this.disassembleDrive(stack, context.getLevel(), player)
                ? InteractionResult.sidedSuccess(context.getLevel().isClientSide())
                : InteractionResult.PASS;
    }

    /**
     * Alt 使用模式下的拆解：空元件 → 组件 + 外壳 + 已装升级卡。
     *
     * @return 是否真的完成了拆解
     */
    private boolean disassembleDrive(ItemStack stack, Level level, Player player) {
        if (!InteractionUtil.isInAlternateUseMode(player)) {
            return false;
        }
        if (level.isClientSide()) {
            return false;
        }

        Inventory inventory = player.getInventory();
        if (inventory.getSelected() != stack) {
            return false;
        }

        var cellInventory = StorageCells.getCellInventory(stack, null);
        if (cellInventory == null || !cellInventory.getAvailableStacks().isEmpty()) {
            player.displayClientMessage(PlayerMessages.OnlyEmptyCellsCanBeDisassembled.text(), true);
            return false;
        }

        inventory.setItem(inventory.selected, ItemStack.EMPTY);

        giveOne(player, this.componentId);
        giveOne(player, HOUSING_ID);

        for (ItemStack upgrade : this.getUpgrades(stack)) {
            if (!upgrade.isEmpty()) {
                player.getInventory().placeItemBackInInventory(upgrade);
            }
        }
        return true;
    }

    /** 按注册名发放一个物品；注册名不存在时静默跳过，避免因依赖缺失而抛异常。 */
    private static void giveOne(Player player, ResourceLocation id) {
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item != null && item != Items.AIR) {
            player.getInventory().placeItemBackInInventory(new ItemStack(item));
        }
    }
}
