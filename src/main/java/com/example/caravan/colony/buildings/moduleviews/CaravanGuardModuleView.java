package com.example.caravan.colony.buildings.moduleviews;

import com.example.caravan.client.gui.modules.WindowCaravanGuard;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** 需求（商队护卫）：【护卫】页客户端视图——商队护卫模式卫兵列表。 */
public class CaravanGuardModuleView extends AbstractBuildingModuleView
{
    /** 一条护卫卫兵信息。 */
    public record GuardEntry(int citizenId, String name, boolean assigned)
    {
    }

    private final List<GuardEntry> guards = new ArrayList<>();

    @Override
    public void deserialize(final RegistryFriendlyByteBuf buffer)
    {
        guards.clear();
        final int count = buffer.readVarInt();
        for (int i = 0; i < count; i++)
        {
            guards.add(new GuardEntry(
                buffer.readVarInt(), buffer.readUtf(), buffer.readBoolean()));
        }
    }

    public List<GuardEntry> getGuards()
    {
        return guards;
    }

    @Override
    public BOWindow getWindow()
    {
        return new WindowCaravanGuard(getBuildingView(), this);
    }

    @Override
    public Component getDesc()
    {
        return Component.translatable("com.caravan.gui.guard");
    }
}
