package com.example.caravan.mixin;

import com.minecolonies.core.colony.buildings.modules.settings.GuardTaskSetting;
import com.minecolonies.core.colony.buildings.modules.settings.StringSettingWithDesc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Arrays;

/**
 * 需求（商队护卫）：在卫兵塔【工作模式】设置（巡逻/驻守/跟随/矿井巡逻）中
 * 追加第 5 个选项【商队护卫】（com.caravan.guard.setting.caravan）。
 */
@Mixin(GuardTaskSetting.class)
public abstract class GuardTaskSettingMixin
{
    /** 无参构造传入 StringSettingWithDesc 的选项数组追加一项。 */
    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lcom/minecolonies/core/colony/buildings/modules/settings/StringSettingWithDesc;<init>([Ljava/lang/String;)V"),
        index = 0,
        remap = false)
    private static String[] caravan$appendOption(final String[] options)
    {
        final String[] result = Arrays.copyOf(options, options.length + 1);
        result[options.length] = com.example.caravan.colony.buildings.CaravanGuardHelper.CARAVAN_TASK_KEY;
        return result;
    }
}
