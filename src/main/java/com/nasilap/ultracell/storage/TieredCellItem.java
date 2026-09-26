package com.nasilap.ultracell.storage;

/**
 * 「有等级、有类型」的元件物品所实现的最小接口。
 *
 * <p>存在的唯一理由：让 {@link CellDataManager#ensureUuid} 能在**不依赖具体物品类**的前提下
 * 拿到 {@code tier} / {@code kind}（写不出文件头就救不回数据）。
 * 因此本接口**只含两个方法**，不要往里加东西。
 *
 * <p><b>只允许 {@code ExternalCellItem} 实现它</b>：{@code ensureUuid} 用
 * {@code instanceof TieredCellItem} 判断，若 FE 元件也实现它，
 * 就会给 FE 元件建出外置 {@code .dat}（违反「FE 元件不走外置存储」）。
 */
public interface TieredCellItem {

    /** 等级：{@link ExternalCellStorage#TIER_HONGMENG} 或 {@link ExternalCellStorage#TIER_WUJI}。 */
    String getTier();

    /** 类型：{@link ExternalCellStorage#KIND_FLUID} 或 {@link ExternalCellStorage#KIND_CHEMICAL}。 */
    String getKind();
}
