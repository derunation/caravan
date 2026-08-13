# Caravan（商队）— MineColonies 附属模组

一个为 MineColonies（1.21.1 / NeoForge 21.1）开发的商队贸易附属模组。玩家可以在殖民地建造【商队小屋】，雇佣商队领袖与商队成员，把原版村民的交易录入小屋的交易列表，由商队自动备货、出远门与村民交易，并将成果带回殖民地；还提供商队护卫、模拟旅行、请求系统联动与旅行地图联动等完整玩法。

## 中文说明

### 核心玩法
- **商队小屋**：殖民地新增建筑，提供【交易列表】【总览】【日志】【菜单】【设置】【选区工具】【护卫】等标签页。
- **商队领袖与成员**：由小屋工作模块分配。领袖按照交易列表依次出行，成员作为扩展背包随行并参与交易提速。
- **商队护卫**：复用卫兵塔的【骑士】等卫兵职业，将卫兵塔【工作模式】设为【商队护卫】并在小屋【护卫】页选中后生效：商队未出发时驻守小屋，出发后跟随领袖并索敌战斗；战斗时领袖停等，卫兵归队后继续行程。每名护卫使模拟旅行速度 +10%。
- **模拟旅行**：离开殖民地范围后商队进入隐形模拟状态，按距离推进去程/交易/回程；途中支持扎营/夜行/露宿、火把与食物消耗、无帐篷过夜的患病风险。
- **旅行地图联动**：商队出行时在大地图显示绿宝石图标与状态文本（旅行中/夜行中/交易中/扎营中/露宿中），每 20 刻自动刷新。

### 交易系统
- **交易列表**：用【商队标记】潜行右键村民即可把该村民的全部交易写入小屋；支持禁用/单次/重复/按需四种状态、重命名、按距离排序与总览排序。
- **备货与出发**：自动按差额向请求系统请求交易品（绿宝石最低存量保护），满足出发条件（上午、帐篷/食物/火把携带量、至少一项交易备齐）后出发；交易时间受领袖智力与成员数量影响（最低每笔 20 刻）。
- **请求系统联动**：商队小屋注册为请求解析方（优先级 75），可承接殖民地其他建筑与信箱的请求；按需交易可递归满足售出品（如 书架→绿宝石→木棍 的请求链），完成后由快递员送货并注销请求。
- **交易日志**：记录每次出行的交易、售出品供应比例、消耗品与扎营总结。

### 消耗品与状态
- **商队帐篷**：合成获得，60 点耐久；扎营时按人数损耗，无帐篷露宿会累积患病概率。
- **食物与火把**：通过【菜单】页选定食物，按【设置】页携带组数备货；夜行期间每殖民地刻消耗火把，无火把则停止前进。
- **饥饿与速度**：商队人员饱食度归零时移动速度降低，最低保持 1 格/20 刻。
- **经验**：领袖与成员每次完成交易获得经验并升级。

### 兼容性
- 与旅行地图（JourneyMap）、Pathfinding Edition、EpicColonies 等常用附属模组兼容。
- 属性、装备请求与敌对列表沿用 Minecolonies 原版机制。

---

## English Description

### Core Features
- **Caravan Hut**: A new MineColonies building with tabs for Trade List, Overview, Log, Menu, Settings, Scepter Tool, and Guards.
- **Caravan Leader & Members**: Assigned from the hut's worker module. The leader executes the trade list trip by trip, while members act as extra inventory space and speed up trading.
- **Caravan Guards**: Reuse guard tower professions (e.g., Knight). Set the tower's work mode to "Caravan Guard" and select it in the hut's Guard tab: guards garrison the hut while the caravan is home, follow the leader and fight enemies while travelling; the leader waits during combat and resumes once guards regroup. Each guard boosts simulated travel speed by 10%.
- **Simulated Travel**: Outside the colony border, the caravan enters an invisible simulated state, progressing through outbound / trading / return phases, with camping, night travel, torch & food consumption, and illness risk from sleeping without a tent.
- **JourneyMap Integration**: While travelling, an emerald marker with status text (Travelling / Night Travel / Trading / Camping / Sleeping Rough) is shown on the map and refreshed every 20 ticks.

### Trading System
- **Trade List**: Sneak-right-click villagers with a Caravan Marker to record all their trades into the hut; supports Disabled / Single / Repeat / On-Demand modes, renaming, distance sorting, and overview reordering.
- **Preparation & Departure**: Missing trade goods are requested through the request system automatically (with an emerald minimum-stock guard); the caravan departs when conditions are met (morning departure, tent/food/torch quotas, at least one trade ready). Trade time scales with leader intelligence and member count (minimum 20 ticks per trade).
- **Request System Integration**: The caravan hut registers as a request resolver (priority 75) and can fulfill requests from other buildings and the Post Box; on-demand trades recursively satisfy sellable goods (e.g., Bookshelf → Emerald → Sticks), with deliveries handled by couriers and proper request completion.
- **Trade Log**: Records trades, supply ratios, consumables, and daily camp summaries per trip.

### Consumables & Status
- **Caravan Tent**: Craftable item with 60 durability; durability is drained per person while camping; sleeping rough without a tent accumulates illness chance.
- **Food & Torches**: Food is selected in the Menu tab and stocked per the Settings tab; torches are consumed every colony tick during night travel, and the caravan stops when torches run out.
- **Hunger & Speed**: Hungry members (zero saturation) slow the caravan, with a hard minimum of 1 block per 20 ticks.
- **Experience**: Leaders and members earn experience and level up after each completed trade.

### Compatibility
- Compatible with JourneyMap, Pathfinding Edition, EpicColonies, and other common add-ons.
- Attributes, equipment requests, and hostile lists follow MineColonies' native mechanics.
