package com.nasilap.ultracell.item;

import com.nasilap.ultracell.UltraCell;
import com.nasilap.ultracell.storage.CellData;
import com.nasilap.ultracell.storage.CellDataManager;
import com.nasilap.ultracell.storage.CellSummaryComponent;
import com.nasilap.ultracell.storage.ExternalCellStorage;
import com.nasilap.ultracell.storage.TieredCellItem;
import com.nasilap.ultracell.storage.UInt192;
import com.nasilap.ultracell.util.ChemicalIntegration;
import com.nasilap.ultracell.util.NumberUtil;

import appeng.api.config.FuzzyMode;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.core.localization.PlayerMessages;
import appeng.items.AEBaseItem;
import appeng.items.contents.CellConfig;
import appeng.util.ConfigInventory;
import appeng.util.InteractionUtil;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
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
import java.util.Set;
import java.util.UUID;

/**
 * 外置存储元件物品（流体 / 化学品，两个等级共用一个类）。
 *
 * <p>四种组合由构造参数 {@code tier} × {@code kind} 决定，差别只在：
 * <ul>
 *   <li>容量上限（{@code 2^128−1} / {@code 2^192−1}）</li>
 *   <li>类型位上限（128 / 512）</li>
 *   <li>keyType 的来源（流体 = 常量；化学品 = 懒解析，**可为 null**）</li>
 * </ul>
 *
 * <p><b>keyType 可空的降级路径</b>（appmek 缺席时）：
 * <ul>
 *   <li>{@link #getKeyType()} 返回 {@code null}</li>
 *   <li>{@link #getConfigInventory} 返回 {@link ConfigInventory#emptyTypes()}（0 槽）
 *       —— <b>绝不</b> {@code CellConfig.create(Set.of(null), …)}</li>
 *   <li>handler 返回 {@code null} ⇒ AE2 视为非元件 ⇒ 驱动器拒收（可 /give、放不进网络）</li>
 *   <li>tooltip 不受影响（只读 DC + 元件常量）；{@code isEditable} 仍为 true（3 升级槽）</li>
 * </ul>
 *
 * <p>刻意<b>不</b>实现 {@code TieredCellItem} 之外的多余接口，也<b>不</b>让
 * {@code FECellItem} 实现 {@code TieredCellItem}（否则 {@code ensureUuid} 会给 FE 元件建外置文件）。
 */
public class ExternalCellItem extends AEBaseItem implements ICellWorkbenchItem, TieredCellItem {

    /** 升级槽数量（与 FE 元件一致：3）。 */
    public static final int UPGRADE_SLOTS = 3;

    /**
     * 拆解返还的外壳：与 FE 元件一致，统一为本模组自建的**究极存储外壳**。
     *
     * <p>刻意保留一份字面量（不去 import 物品类）：保持依赖方向单向 {@code item → storage}，
     * 不让 {@code item} 包反向依赖 {@code registry} 包。
     */
    private static final ResourceLocation HOUSING_ID = UltraCell.id("ultimate_cell_housing");

    /**
     * 流体 keyType 的静态缓存。
     *
     * <p>keyType 是**进程级注册表项**，不随存档变化，因此可以 static
     * （与「owner 缓存不放 static」不矛盾 —— 那是存档级状态）。
     */
    @Nullable
    private static volatile AEKeyType cachedFluidKeyType;

    private final String tier;
    private final String kind;
    private final UInt192 totalCapacity;
    private final int typeSlots;
    private final ResourceLocation componentId;

    /**
     * 百分比采样缓存。**每实例一份**（分母 = 本等级总量，四种组合各不相同）。
     *
     * <p>与 FE 元件**共用同一个工具类** {@link CellTooltipFormatter}（UC-001），
     * 因此格式、六步判定、节流策略全部一致。
     */
    private final CellTooltipFormatter tooltipFormatter;

    public ExternalCellItem(Properties properties, String tier, String kind, ResourceLocation componentId) {
        super(properties.stacksTo(1));
        this.tier = tier;
        this.kind = kind;
        this.componentId = componentId;
        this.totalCapacity = ExternalCellStorage.totalFor(tier);
        this.typeSlots = ExternalCellStorage.typeSlotsFor(tier);
        this.tooltipFormatter = new CellTooltipFormatter(this.totalCapacity);
    }

    /** 拆解后返还的组件物品注册名。 */
    public ResourceLocation getComponentId() {
        return this.componentId;
    }

    @Override
    public String getTier() {
        return this.tier;
    }

    @Override
    public String getKind() {
        return this.kind;
    }

    public UInt192 getTotalCapacity() {
        return this.totalCapacity;
    }

    public int getTypeSlots() {
        return this.typeSlots;
    }

    /** 单类型上限（= 总量 / 类型位）。 */
    public UInt192 getTypeLimit() {
        return ExternalCellStorage.typeLimitFor(this.tier);
    }

    /** 该元件的 keyType；化学品在 appmek 缺席时为 {@code null}。 */
    @Nullable
    public AEKeyType getKeyType() {
        if (ExternalCellStorage.KIND_CHEMICAL.equals(this.kind)) {
            return ChemicalIntegration.chemicalKeyType();
        }
        return fluidKeyType();
    }

    /** 流体通道：首次使用时解析并缓存。 */
    private static AEKeyType fluidKeyType() {
        AEKeyType cached = cachedFluidKeyType;
        if (cached == null) {
            cached = AEKeyType.fluids();
            cachedFluidKeyType = cached;
        }
        return cached;
    }

    @Override
    public IUpgradeInventory getUpgrades(ItemStack stack) {
        return UpgradeInventories.forItem(stack, UPGRADE_SLOTS);
    }

    @Override
    public FuzzyMode getFuzzyMode(ItemStack stack) {
        return null;
    }

    @Override
    public void setFuzzyMode(ItemStack stack, FuzzyMode fuzzyMode) {
        // 无模糊模式
    }

    /**
     * 白名单配置槽。
     *
     * <p>keyType 不可用时返回 0 槽的空配置 —— 不能把 {@code null} 塞进
     * {@code CellConfig.create}（会炸）。白名单语义（含反相卡）由 AE2 原生实现。
     */
    @Override
    public ConfigInventory getConfigInventory(ItemStack stack) {
        AEKeyType keyType = this.getKeyType();
        if (keyType == null) {
            return ConfigInventory.emptyTypes();
        }
        return CellConfig.create(Set.of(keyType), stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        CellSummaryComponent summary = CellDataManager.summaryOf(stack);

        // ① UUID 行（最上面）：DC 六个 long 全 0（新合成、未入包、未 getuuid）时显示「未分配」。
        //    FE 元件不加这一行（本类只用于流体/化学品元件）。
        UUID uuid = summary.uuid();
        if (uuid != null) {
            lines.add(Component.translatable("ultracell.tooltip.uuid.assigned", uuid.toString()));
        } else {
            lines.add(Component.translatable("ultracell.tooltip.uuid.unassigned"));
        }

        // ② 类型位占用（DSH 自行添加的行，规格未要求 —— 见交付报告说明）
        lines.add(Component.translatable("ultracell.tooltip.external.types",
                Long.toString(summary.typeCount()), Integer.toString(this.typeSlots)));

        // ③ 「已用」行：与 FE 元件**同一个 key、同一种格式**：`<已用> / <总量> 已用xx.xxx%`
        // 守卫只包住缓存读写，输出在守卫之外（与 A8 一致）
        UInt192 used = summary.used();
        String percent = this.tooltipFormatter.percent(stack, used);
        lines.add(Component.translatable("tooltip.ultracell.stored",
                NumberUtil.format(used),
                NumberUtil.format(this.totalCapacity),
                percent));
    }

    /**
     * 入包绑定 + UUID 自愈①（严格按 A5 的六步顺序）：
     * <ol>
     *   <li>客户端早退</li>
     *   <li>物品类型快速失败</li>
     *   <li>读 DC 取 UUID</li>
     *   <li><b>UUID 为空 → {@code ensureUuid(stack)} 分配并回写 DC</b>
     *       —— 「首次入包绑定」是 A4 明文规定的自愈①触发点之一</li>
     *   <li>查内存 owner 缓存（{@code dataForItem} 顺带完成 tier/kind 串档检查）</li>
     *   <li>只有「无主且需要绑定」时才做文件 I/O（{@code dataForWrite} =
     *       「有 UUID 无文件」的自愈②）</li>
     * </ol>
     *
     * <p>每 tick 都会跑，因此**只能碰内存**：**绝不**每 tick 读 {@code .dat} 或列目录。
     * 分配只会发生一次 —— 回写 DC 之后后续 tick 直接命中缓存。
     * DC 改动由 {@code ServerPlayer} 每 tick 的 {@code broadcastChanges()} 自动同步到客户端
     * （{@code ItemStack.matches} 是组件敏感的），因此不需要额外发包。
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        // 1. 客户端早退
        if (level.isClientSide()) {
            return;
        }
        // 2. 物品类型快速失败（本方法只定义在 ExternalCellItem 上，这里是双保险）
        if (!(stack.getItem() instanceof ExternalCellItem)) {
            return;
        }
        if (!(entity instanceof Player player)) {
            return;
        }
        CellDataManager manager = CellDataManager.current();
        if (manager == null) {
            return;
        }

        // 3. 读 DC 取 UUID
        UUID uuid = CellDataManager.summaryOf(stack).uuid();

        // 4. 空 → 分配并回写 DC（自愈①：首次入包绑定）
        if (uuid == null) {
            uuid = manager.ensureUuid(stack);
            if (uuid == null) {
                return;
            }
        }

        // 5. 查内存 owner 缓存
        CellData data = manager.dataForItem(stack);
        if (data == null) {
            // 损坏名单里的 UUID 绝不复活；其余情况属于「有 UUID 无文件」的自愈②
            if (manager.corruptIndex().isCorrupt(uuid)) {
                return;
            }
            data = manager.dataForWrite(uuid, this.tier, this.kind);
        }

        // 6. 无主才绑定（已有归属不再改绑）；归属变更 = ③ 强制立即落盘
        manager.bindOwnerIfUnowned(data, player.getUUID());
    }

    // ── 拆解（与 FE 元件完全一致的条件与产出）──

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
     * 备用使用模式（Alt/潜行）下的拆解：**空**元件 → 组件 + 究极外壳 + 已装升级卡。
     *
     * <p>条件与 FE 元件逐条一致：备用使用模式 → 服务端 → 手持槽就是它 → 元件是空的。
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

        if (!isCellEmpty(stack)) {
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

    /**
     * 元件是否为空（可拆解）。
     *
     * <p>优先走 AE2 的存储视图（与 FE 元件同一条判据）；
     * keyType 不可用（没装 appmek）时退化为读 DC 摘要，避免"化学品元件永远拆不了"。
     */
    private static boolean isCellEmpty(ItemStack stack) {
        var inventory = StorageCells.getCellInventory(stack, null);
        if (inventory != null) {
            return inventory.getAvailableStacks().isEmpty();
        }
        CellSummaryComponent summary = CellDataManager.summaryOf(stack);
        return summary.typeCount() == 0L && summary.usedIsZero();
    }

    /** 按注册名发放一个物品；注册名不存在时静默跳过，避免因依赖缺失而抛异常。 */
    private static void giveOne(Player player, ResourceLocation id) {
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item != null && item != Items.AIR) {
            player.getInventory().placeItemBackInInventory(new ItemStack(item));
        }
    }
}
