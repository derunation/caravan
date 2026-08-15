package com.example.caravan.mixin.client;

import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 会把职业完整注册名（如 {@code caravan:caravan_leader}）强制拼到
 * {@code minecolonies} 命名空间，产生非法路径
 * {@code minecolonies:caravan:caravan_leader}，在市民渲染时刷屏
 * {@code ResourceLocationException}。
 * <p>这里重定向其拼接调用：路径含冒号时按完整名解析（自定义命名空间职业），
 * 否则保留原逻辑。使用 {@code @Pseudo} + targets 字符串，未安装 EpicColonies
 * 时静默跳过，不引入编译期依赖。</p>
 */
@Pseudo
@Mixin(targets = "net.kenji.epic_colonies.api.data.CitizenMeshCache")
public abstract class CitizenMeshCacheMixin
{
    @Redirect(method = "resolveJob",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/resources/ResourceLocation;fromNamespaceAndPath(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;"),
        remap = false)
    private static ResourceLocation caravan$resolveJobLocation(final String namespace, final String path)
    {
        if (path != null && path.indexOf(':') >= 0)
        {
            try
            {
                return ResourceLocation.parse(path);
            }
            catch (final Exception ignored)
            {
                // 解析失败时走原逻辑。
            }
        }
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
}
