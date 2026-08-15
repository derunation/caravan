package com.example.caravan.client.gui;

import com.example.caravan.colony.buildings.CaravanBuildingView;
import com.example.caravan.colony.buildings.moduleviews.CaravanLogModuleView;
import com.minecolonies.core.client.gui.huts.WindowHutWorkerModulePlaceholder;
import com.minecolonies.core.network.messages.server.colony.building.worker.RecallCitizenMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 商队小屋主窗口：继承 minecolonies 的通用工人模块窗口。
 * <p>拦截【召回工人】按钮——当商队领袖处于消失状态时，
 * 在聊天窗口提示“正在交易中，请稍后再试”，并且不发送召回消息、
 * 不移动商队领袖的位置。</p>
 */
public class WindowHutCaravanLeader extends WindowHutWorkerModulePlaceholder<CaravanBuildingView>
{
    public WindowHutCaravanLeader(final CaravanBuildingView buildingView)
    {
        super(buildingView);
        // 覆盖父类注册的 “recall” 按钮处理器（按钮 id 相同，后注册者生效）。
        registerButton("recall", (Runnable) this::recallClicked);
    }

    /** 召回按钮：领袖消失中 → 聊天栏提示；否则按 minecolonies 原逻辑召回。 */
    private void recallClicked()
    {
        final List<CaravanLogModuleView> logViews = buildingView.getModuleViews(CaravanLogModuleView.class);
        final boolean away = !logViews.isEmpty() && logViews.get(0).isAway();
        if (away)
        {
            final var player = Minecraft.getInstance().player;
            if (player != null)
            {
                player.displayClientMessage(Component.translatable("com.caravan.gui.recall.away"), false);
            }
            return;
        }
        // 未消失：与 minecolonies 原按钮行为一致（发送召回消息）。
        new RecallCitizenMessage(buildingView).sendToServer();
    }
}
