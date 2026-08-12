package com.example.caravan.mixin.client;

import com.example.caravan.CaravanMod;
import com.ldtteam.blockui.controls.Image;
import com.ldtteam.blockui.controls.Text;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import com.minecolonies.core.client.gui.citizen.CitizenWindowUtils;
import com.minecolonies.core.client.gui.citizen.JobWindowCitizen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 需求：商队领袖/成员的市民界面属性百分比显示。
 * <p>本体的 {@link CitizenWindowUtils#updateJobPage} 按固定规则显示：
 * 主属性 100%、互补 10%、相克 -10%、副属性 50%、互补 5%、相克 -5%——与我们的
 * 自定义经验分配（敏捷100/适应10/魔力-10/智力50/运动5/创意-5）不一致。
 * 这里在方法返回前对商队两类职业覆写显示文本与图标。</p>
 */
@Mixin(CitizenWindowUtils.class)
public abstract class CitizenWindowUtilsMixin
{
    @Inject(method = "updateJobPage", at = @At("RETURN"), remap = false)
    private static void caravan$overrideSkillDisplay(
        final ICitizenDataView citizen,
        final JobWindowCitizen window,
        final IColonyView colony,
        final CallbackInfo ci)
    {
        try
        {
            if (citizen.getJobView() == null || citizen.getJobView().getEntry() == null)
            {
                return;
            }
            final ResourceLocation jobKey = citizen.getJobView().getEntry().getKey();
            if (!jobKey.equals(CaravanMod.JOB_CARAVAN_LEADER.getKey())
                && !jobKey.equals(CaravanMod.JOB_CARAVAN_MEMBER.getKey()))
            {
                return;
            }
            setSkill(window, "primary", "primaryimg", "agility", " (100% XP)");
            setSkill(window, "comp1", "comp1img", "adaptability", " (10% XP)");
            setSkill(window, "adverse1", "adverse1img", "mana", " (-10% XP)");
            setSkill(window, "secondary", "secondaryimg", "intelligence", " (50% XP)");
            setSkill(window, "comp2", "comp2img", "athletics", " (5% XP)");
            setSkill(window, "adverse2", "adverse2img", "creativity", " (-5% XP)");
        }
        catch (final Throwable ignored)
        {
            // 界面元素缺失等异常不影响游戏运行。
        }
    }

    private static void setSkill(
        final AbstractWindowSkeleton window,
        final String textId,
        final String imageId,
        final String skillName,
        final String suffix)
    {
        final Text text = window.findPaneOfTypeByID(textId, Text.class);
        if (text != null)
        {
            text.setText(Component.translatableEscape(
                "com.minecolonies.coremod.gui.citizen.job.skills." + skillName).append(suffix));
        }
        final Image image = window.findPaneOfTypeByID(imageId, Image.class);
        if (image != null)
        {
            image.setImage(ResourceLocation.parse(
                "minecolonies:textures/entity/skills/small/" + skillName + ".png"), false);
        }
    }
}
