package com.example.caravan.config;

import com.example.caravan.CaravanMod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 本模组的设置项（NeoForge 配置体系，可在游戏内用 Configured 模组修改）。
 *
 * <p>与 MineColonies 本体一致，使用 {@link ModConfigSpec} 声明设置项，
 * 配置文件落在 {@code config/caravan-server.toml}。因为 Configured 读取的就是
 * 标准 NeoForge 配置，所以无需为本模组做任何适配即可在游戏内编辑。</p>
 *
 * <p>类型选 {@link ModConfig.Type#SERVER}：交易上限属于玩法规则，必须由服务端
 * 裁定。SERVER 配置在多人服务器上会由服务端同步给客户端，因此客户端界面显示的
 * 上限与服务端实际校验使用的值始终一致（CLIENT 类型做不到这一点）。</p>
 */
public final class CaravanConfig
{
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /**
     * 服务端设置：每级小屋可启用的交易数量。
     * 上界取 10000：既远超任何真实玩法需求，又避免"数值 × 小屋等级"溢出成负数。
     */
    public static final IntSetting MAX_SELECTIONS_PER_LEVEL = new IntSetting(
        "maxSelectionsPerLevel",
        4,
        0,
        10000,
        "Max enabled trades per hut level. The limit for one hut is this value multiplied by the hut level."
            + " 0 means unlimited.",
        "每级小屋可启用的交易数量。单个小屋的上限 = 该数值 × 小屋等级。0 表示无限制。");

    public static final ModConfigSpec SPEC = BUILDER.build();

    private CaravanConfig()
    {
    }

    /** 在模组构造阶段注册配置。 */
    public static void register(final ModContainer container)
    {
        container.registerConfig(ModConfig.Type.SERVER, SPEC);
    }

    /**
     * 每级小屋可启用的交易数量；返回 0 表示无限制。
     */
    public static int maxSelectionsPerLevel()
    {
        return MAX_SELECTIONS_PER_LEVEL.get();
    }

    /**
     * 指定小屋等级下的可启用交易上限；{@link Integer#MAX_VALUE} 表示无限制。
     * 配置尚未加载时回退到默认值 4，避免 GUI 打不开。
     */
    public static int maxSelectionsForLevel(final int buildingLevel)
    {
        final int perLevel = maxSelectionsPerLevel();
        if (perLevel <= 0)
        {
            return Integer.MAX_VALUE;
        }
        final long limit = (long) perLevel * Math.max(0, buildingLevel);
        return limit >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) limit;
    }

    /** 单个整数设置项：定义 + 读取，读取失败时回退默认值。 */
    public static final class IntSetting
    {
        private final ModConfigSpec.IntValue value;
        private final int defaultValue;

        private IntSetting(final String key, final int defaultValue, final int min, final int max,
            final String... comments)
        {
            this.defaultValue = defaultValue;
            this.value = BUILDER
                .comment(comments)
                .defineInRange(key, defaultValue, min, max);
        }

        public int get()
        {
            try
            {
                return value.get();
            }
            catch (final Throwable ex)
            {
                CaravanMod.LOGGER.warn("Failed to read caravan config value, using default {}", defaultValue, ex);
                return defaultValue;
            }
        }
    }
}
