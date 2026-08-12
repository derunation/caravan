# Caravan - MineColonies 测试附属 Mod

一个参考 `mctradepost` 编写的 MineColonies（1.21.1 / NeoForge 21.1）测试附属 mod。

## 内容

- **职业【商队领袖】**（`caravan:caravan_leader`）：由小屋的工作模块分配，AI 参考下界矿工
  （`EntityAIWorkNether`）实现。
- **小屋方块**（`caravan:blockhutcaravanleader`）：建筑注册名为 `caravan:caravanleader`，
  蓝图从 mctradepost 的 `Trade Post/economic/station1-5.blueprint` 提取并改写为
  `caravanleader1-5.blueprint`（蓝图内的方块 ID、TE 类型、建筑类型、蓝图路径均已替换）。
- **测试物品【商队标记】**（`caravan:caravan_marker`）：右键原版村民记录其第一项交易
  （支付/获得物品）与最近村庄中心（搜索 48 格内的钟，找不到则用村民自身坐标），
  数据存入物品的 `trade_record` DataComponent。

## 商队领袖 AI 流程

1. **接单**：AI 检查小屋存储，找到带 `trade_record` 的商队标记并消耗一个作为订单。
2. **备货**：通过 MineColonies 请求系统（`checkIfRequestForItemExistOrCreate`）逐项请求
   交易所需物品，集齐后出发。
3. **出发**：寻路走向标记记录的村庄坐标；离开殖民地（距中心 > 100 格）或到达目的地后
   **消失**：扣除交易成本、把随身物品与“交易成果”存入 Job NBT、`setInvisible(true)`
   （与下界矿工进门后消失同理），并记录返回时刻。
4. **往返时间**：按目标距离计算 1–10 个游戏日（约 500 格/天，取整并钳制），
   `returnTime = 出发时游戏时间 + 天数 * 24000`，期间 AI 仅停留在 `AWAY` 状态。
5. **回归**：到点后在消失位置现身、恢复背包并放入交易成果，寻路返回小屋，
   把成果放入小屋存储（`building.getItemHandlerCap`），清除订单回到待机。

消失/回归状态全部持久化在 `JobCaravanLeader` 的 NBT 中（`away`、`returnTime`、
`supplies`、`results` 等），重载世界不会丢失。

## 构建

需要 JDK 21 与网络（首次构建会下载 NeoForge/MC/依赖）。

```bash
# 本机已准备好构建工具链（G:\AI工作区\.buildtools 下的 JDK 21 与 Gradle 8.10.2）：
$env:JAVA_HOME='G:\AI工作区\.buildtools\jdk-21.0.7+6'
$env:Path="$env:JAVA_HOME\bin;"+$env:Path
& 'G:\AI工作区\.buildtools\gradle-8.10.2\bin\gradle.bat' --no-daemon build

# 产物：build/libs/caravan-0.1.0.jar
# 也可用 IDE（IntelliJ）导入后运行 gradle 任务
```

`build.gradle` 说明：
- `minecolonies` 使用项目旁的本地 jar（`../minecolonies-1.1.1285-1.21.1.jar`，该版本未发布到公共 Maven）。
- `structurize` / `blockui` 来自 LDT Team maven；`domum_ornamentum` 作为传递依赖。
- JEI 已在依赖中排除（LDT maven 只有旧版 JEI，且 JEI 对 minecolonies 与本 mod 均为可选）。

## 直接安装到游戏

把 `G:\AI工作区\caravan-0.1.4.jar` 放入 Minecraft 的 `mods/` 文件夹即可
（0.1.1 修复了打开创造物品栏时因缺少小屋方块物品导致的崩溃；0.1.2 将蓝图包改名为
`Caravan` 并按 minecolonies 风格包规范放置于 `craftsmanship` 分类；0.1.3 将蓝图移至
`craftsmanship/storage` 并修复放置小屋时的方块实体类型崩溃；0.1.4 修复风格包
pack.json（补齐官方 structurize 必需的 authors/desc 字段）、重做商队标记（绑定小屋后
潜行右键村民写入全部交易）、为小屋增加【设置】（获得工具）与【交易列表】标签页）。
游戏环境需为：

- NeoForge 21.1.x（对应 MC 1.21.1）
- MineColonies 1.1.1285-1.21.1
- Structurize、Domum Ornamentum、BlockUI（minecolonies 的依赖）

注意：请使用启动器（CurseForge/Modrinth）分发的正式版 Structurize。
LDT Maven 上的 `1.21.1-1.0.746-beta` 包存在专用服务器端加载缺陷（构造时引用客户端类），
仅用于开发编译；本 mod 本身无此问题。

构建已通过本机冒烟验证：mod 可被 FML 正常发现并构造（`caravan 0.1.0` 出现在 Mod List 中，
无任何 caravan 相关报错）。

## 游戏内测试步骤

1. 建造小屋：使用建筑工具/木匠放置 `blockhutcaravanleader`（蓝图在
   `blueprints/caravan/Caravan/craftsmanship/storage/`，1–5 级），并指派一名【商队领袖】。
2. 记录交易：手持【商队标记】右键任意村民（提示“交易已记录！村庄位于 …”）。
3. 下单：把标记放入小屋的箱子/存储中，商队领袖会自动接单、备货并出发。
4. 观察：领袖走出殖民地后消失；数天后在原位置现身，回到小屋并把交易成果存入小屋。
5. 仓库配送员（courier）会像对待其他小屋库存一样把成果运回仓库。

## 已知限制（测试用）

- 只记录村民的**第一项**交易；多次右键会覆盖旧记录。
- 商队领袖不处理饥饿/进食（依赖殖民地的餐厅机制，简化为测试目的）。
- “交易成果”为模拟生成（记录的交易输出 ×1），并非真的与村民实体交互。
- 若同时安装 mctradepost，两个 mod 的蓝图包都叫 `Trade Post`，建筑工具目录可能合并，
  建议单独测试本 mod。
- 若领袖消失位置所在的区块长时间不加载，回归时间会在区块加载后才会推进。

## 目录结构

```text
src/main/java/com/example/caravan/
  CaravanMod.java                 # @Mod 入口、所有 DeferredRegister 与条目
  init/ModJobs.java               # JobEntry（caravan_leader）
  init/ModBuildings.java          # BuildingEntry（caravan:caravanleader）
  block/BlockHutCaravanLeader.java
  colony/buildings/BuildingCaravanLeader.java
  colony/jobs/JobCaravanLeader.java        # 消失/回归状态与 NBT 持久化
  entity/ai/EntityAIWorkCaravanLeader.java # AI 状态机（PREPARE/DEPART/AWAY/RETURN）
  item/CaravanMarkerItem.java              # 记录交易与村庄坐标
  item/TradeRecord.java                    # DataComponent 记录 + Codec/StreamCodec
src/main/resources/
  assets/caravan/…                # 模型、blockstate、贴图、en_us/zh_cn 本地化
  blueprints/caravan/Caravan/craftsmanship/storage/… # 风格包 Caravan → craftsmanship/storage
  data/caravan/recipe/…           # 商队标记合成配方
tools/                             # 一次性开发工具（蓝图改写、贴图生成）
```
