package com.example.caravan.colony.buildings;

import com.example.caravan.client.gui.WindowHutCaravanLeader;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.core.colony.buildings.views.EmptyView;
import net.minecraft.core.BlockPos;

/**
 * 商队小屋的客户端建筑视图。
 * <p>覆盖 {@link #getWindow()}，让主窗口使用 {@link WindowHutCaravanLeader}，
 * 从而拦截【召回工人】按钮（商队领袖消失期间只提示、不召回）。</p>
 */
public class CaravanBuildingView extends EmptyView
{
    public CaravanBuildingView(final IColonyView colony, final BlockPos pos)
    {
        super(colony, pos);
    }

    @Override
    public BOWindow getWindow()
    {
        return new WindowHutCaravanLeader(this);
    }
}
