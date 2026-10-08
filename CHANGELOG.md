# 更新日志 / Changelog

## 1.0.3

- 修复致命崩溃：MineColonies 新版把 `createPickupRequest(int)` 改为 `createPickupRequest(int, boolean)`，旧签名调用会在商队交易完成返回小屋时抛 `NoSuchMethodError`（属 Error，旧 try/catch 拦不住）并击穿服务端线程；现按运行时实际存在的重载调用，同时兼容 1.1.1285 与 1.1.1403+。
- 编译依赖同步实例：Structurize 1.0.810-snapshot → 1.0.832（minecolonies 最低依赖仍为 1.1.1285）。

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
