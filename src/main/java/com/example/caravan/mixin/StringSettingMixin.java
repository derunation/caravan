package com.example.caravan.mixin;

import com.example.caravan.colony.buildings.CaravanGuardHelper;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.minecolonies.core.colony.buildings.modules.settings.GuardTaskSetting;
import com.minecolonies.core.colony.buildings.modules.settings.StringSetting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

/**
 * 需求（商队护卫）：在卫兵塔【工作模式】选项（巡逻/驻守/跟随/矿井巡逻）中追加
 * 【商队护卫】。通过 `StringSetting.getSettings()` 动态追加——无论新放置还是
 * 已从 NBT 反序列化的卫兵塔设置都能生效（仅对 {@link GuardTaskSetting} 生效）。
 */
@Mixin(StringSetting.class)
public abstract class StringSettingMixin
{
    /** 需求（诊断）：选项追加是否已输出过日志（防刷屏）。 */
    private static boolean caravan$logged;

    @ModifyReturnValue(method = "getSettings", at = @At("RETURN"), remap = false)
    private List<String> caravan$appendOption(final List<String> settings)
    {
        if (!((Object) this instanceof GuardTaskSetting)
            || settings.contains(CaravanGuardHelper.CARAVAN_TASK_KEY))
        {
            return settings;
        }
        final List<String> result = new ArrayList<>(settings);
        result.add(CaravanGuardHelper.CARAVAN_TASK_KEY);
        if (!caravan$logged)
        {
            caravan$logged = true;
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 卫兵塔工作模式选项已追加【商队护卫】（原 {} 项 → {} 项）",
                settings.size(), result.size());
        }
        return result;
    }
}
