# 更新日志 / Changelog

## 1.0.3

- 修复致命崩溃：MineColonies 新版把 `createPickupRequest(int)` 改为 `createPickupRequest(int, boolean)`，旧签名调用会在商队交易完成返回小屋时抛 `NoSuchMethodError`（属 Error，旧 try/catch 拦不住）并击穿服务端线程；现按运行时实际存在的重载调用，同时兼容 1.1.1285 与 1.1.1403+。
- **依赖范围回落**：`neoforge.mods.toml` 里 structurize 的下限由 `1.0.832-1.21.1` 回落到 **1.0.2 的水平 `1.0.810-1.21.1-snapshot`**，不再因 1.0.3 抬高下限而把旧版 Structurize 整合包挡在门外。
  - 安全性依据：逐字节码核对确认本模组**不引用任何 `com.ldtteam.structurize` 类**（只用配方里的 `structurize:sceptergold` 物品 ID 与蓝图包声明），且 1.0.2 与 1.0.3 的外部类引用集合**完全一致**（各 160 项，逐项无差异），故降低下限不会引入 `NoSuchMethodError` 之类的运行期风险。
  - 其余依赖范围与 1.0.2 逐项比对后**无变化**：minecolonies `[1.1.1285-1.21.1,)`、blockui `[1.0.199-1.21.1-snapshot,)`、domum_ornamentum `[1.0.223-snapshot,)`、neoforge `[21.1.235,22.0.0)`。
- 构建脚本不再硬编码实例内 `F:/MC/.../structurize-1.0.832-1.21.1.jar` 绝对路径：该 jar 已复制到项目的 `参考/` 目录并作为编译期依赖引用，与 minecolonies 参考依赖同一套做法，换机器/换工作区都能直接编译。
  - 编译期仍用 1.0.832 而不是回退到 1.0.2 时代的 `com.ldtteam:structurize:1.21.1-1.0.746-beta`：后者是已知在专用服务器上会崩的旧 beta（见下方 1.0.3 首条修复上下文），回退它会让构建脚本重新依赖一个有问题的构件；而**运行期下限**（mods.toml 声明的 `[1.0.810-1.21.1-snapshot,)`）已按 1.0.2 水平回落，用户实际用哪个 Structurize 版本不受影响。

## 1.0.2

- 新增【商队小屋】工作台合成配方：任意木板 + 建筑工具（Structurize）+ 绿宝石，图案 PBP/PEP/PPP。

## 1.0.1

- 新增【商队小屋】工作台合成配方：任意木板 + 建筑工具（Structurize）+ 绿宝石，图案 PBP/PEP/PPP。
- 正式更名：Minecolonies Caravans / 模拟殖民地商队附属。
- 清理开发期诊断日志、调试命令与冗余注释；更新作者信息与展示名称。
- Renamed to Minecolonies Caravans; removed development diagnostics and stale notes.

## 1.0.0

- 正式版本发布。首个稳定版本，包含商队小屋、交易列表、模拟旅行、请求系统联动、商队护卫与旅行地图联动等全部功能。
- First stable release.
