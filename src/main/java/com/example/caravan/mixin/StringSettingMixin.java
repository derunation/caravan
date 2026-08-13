package com.example.caravan.mixin;

import com.example.caravan.colony.buildings.CaravanGuardHelper;
import com.minecolonies.core.colony.buildings.modules.settings.GuardTaskSetting;
import com.minecolonies.core.colony.buildings.modules.settings.StringSetting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 需求（商队护卫）：在卫兵塔【工作模式】选项（巡逻/驻守/跟随/矿井巡逻）中追加
 * 【商队护卫】。必须在【构造时】写入内部 settings 列表（而非 getSettings() 的副本）——
 * 否则保存/加载时 StringSetting 按索引保存，updateSetting 会把越界索引收敛到
 * size-1（=巡逻矿井），导致重新进入游戏后工作模式跳回【巡逻矿井】。
 */
@Mixin(StringSetting.class)
public abstract class StringSettingMixin
{
    /** 选项列表（目标类自身字段，@Shadow 安全）。 */
    @Shadow(remap = false)
    private List<String> settings;

    /** 需求（状态保存）：仅在 GuardTaskSetting 构造完成后，把【商队护卫】追加进
     *  内部列表——getValue/trigger/set/序列化/updateSetting 全部基于该列表，
     *  索引 4（商队护卫）因此能被正确保存与恢复。 */
    @Inject(method = "<init>*", at = @At("RETURN"), remap = false)
    private void caravan$appendOption(final CallbackInfo ci)
    {
        if (!((Object) this instanceof GuardTaskSetting)
            || settings.contains(CaravanGuardHelper.CARAVAN_TASK_KEY))
        {
            return;
        }
        settings.add(CaravanGuardHelper.CARAVAN_TASK_KEY);
    }
}
