package com.example.caravan.client.gui.modules;

import com.example.caravan.CaravanMod;
import com.example.caravan.colony.buildings.modules.CaravanStockModule;
import com.example.caravan.colony.buildings.moduleviews.CaravanStockModuleView;
import com.example.caravan.network.CaravanStockMessage;
import com.ldtteam.blockui.controls.TextField;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.core.client.gui.AbstractModuleWindow;
import net.minecraft.resources.ResourceLocation;

/**
 * 商队小屋【设置】标签页：设置最小绿宝石库存（默认 4 组，1 组 = 64 个）。
 * 需求：玩家输入数值后，切换页面/关闭 GUI 时自动保存（无保存按钮）。
 */
public class WindowCaravanStock extends AbstractModuleWindow<CaravanStockModuleView>
{
    private static final String INPUT_STOCK = "stockInput";
    /** 需求（设置）：商队帐篷携带量输入框。 */
    private static final String INPUT_TENT_COUNT = "tentCountInput";
    /** 需求（设置）：商队食物携带组数输入框。 */
    private static final String INPUT_FOOD_COUNT = "foodCountInput";
    /** 需求（设置）：商队火把携带组数输入框。 */
    private static final String INPUT_TORCH_COUNT = "torchCountInput";

    public WindowCaravanStock(final IBuildingView buildingView, final CaravanStockModuleView moduleView)
    {
        super(moduleView, ResourceLocation.fromNamespaceAndPath(CaravanMod.MODID, "gui/layouthuts/layoutcaravanstock.xml"));
    }

    @Override
    public void onOpened()
    {
        super.onOpened();
        final TextField field = findPaneOfTypeByID(INPUT_STOCK, TextField.class);
        if (field != null)
        {
            field.setText(String.valueOf(moduleView.getMinEmeraldStock()));
        }
        final TextField tentField = findPaneOfTypeByID(INPUT_TENT_COUNT, TextField.class);
        if (tentField != null)
        {
            tentField.setText(String.valueOf(moduleView.getTentCarryCount()));
        }
        final TextField foodField = findPaneOfTypeByID(INPUT_FOOD_COUNT, TextField.class);
        if (foodField != null)
        {
            foodField.setText(String.valueOf(moduleView.getFoodCarryCount()));
        }
        final TextField torchField = findPaneOfTypeByID(INPUT_TORCH_COUNT, TextField.class);
        if (torchField != null)
        {
            torchField.setText(String.valueOf(moduleView.getTorchCarryCount()));
        }
    }

    @Override
    public void onClosed()
    {
        super.onClosed();
        // 需求：切换页面/关闭 GUI 时自动保存输入的最小绿宝石库存。
        if (buildingView == null)
        {
            return;
        }
        int stockValue;
        final TextField field = findPaneOfTypeByID(INPUT_STOCK, TextField.class);
        if (field != null)
        {
            try
            {
                stockValue = Integer.parseInt(field.getText().trim());
            }
            catch (final NumberFormatException ignored)
            {
                stockValue = CaravanStockModule.DEFAULT_MIN_STOCK;
            }
        }
        else
        {
            stockValue = CaravanStockModule.DEFAULT_MIN_STOCK;
        }
        int tentValue = CaravanStockModule.DEFAULT_TENT_COUNT;
        final TextField tentField = findPaneOfTypeByID(INPUT_TENT_COUNT, TextField.class);
        if (tentField != null)
        {
            try
            {
                tentValue = Integer.parseInt(tentField.getText().trim());
            }
            catch (final NumberFormatException ignored)
            {
                tentValue = CaravanStockModule.DEFAULT_TENT_COUNT;
            }
        }
        int foodValue = CaravanStockModule.DEFAULT_FOOD_COUNT;
        final TextField foodField = findPaneOfTypeByID(INPUT_FOOD_COUNT, TextField.class);
        if (foodField != null)
        {
            try
            {
                foodValue = Integer.parseInt(foodField.getText().trim());
            }
            catch (final NumberFormatException ignored)
            {
                foodValue = CaravanStockModule.DEFAULT_FOOD_COUNT;
            }
        }
        int torchValue = CaravanStockModule.DEFAULT_TORCH_COUNT;
        final TextField torchField = findPaneOfTypeByID(INPUT_TORCH_COUNT, TextField.class);
        if (torchField != null)
        {
            try
            {
                torchValue = Integer.parseInt(torchField.getText().trim());
            }
            catch (final NumberFormatException ignored)
            {
                torchValue = CaravanStockModule.DEFAULT_TORCH_COUNT;
            }
        }
        new CaravanStockMessage(buildingView, stockValue, tentValue, foodValue, torchValue).sendToServer();
    }
}
