<img src="assets/icon.png" align="right" width="96" height="96" alt="TradePlus 图标">

# TradePlus

在原版村民交易界面中预览未来交易，双击 F 重刷整套交易。

![README 浏览次数](https://count.getloli.com/@TradePlus?theme=eva&padding=4&offset=0&align=top&scale=1&pixelated=1&darkmode=auto)

![Paper 26.3](https://img.shields.io/badge/Paper-26.3-22c55e)
![Java 25](https://img.shields.io/badge/Java-25-orange)
![GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue)

[English](README.md) · 简体中文

## 功能

- 右键村民，直接查看当前和未来等级的交易，保留附魔详情并标注解锁等级。
- 在物品格上快速按两次 **F**，同时重刷当前与未来交易。仅限从未交易的成年新手村民。
- 交易计划与交易锁随世界保存；预览和重刷可以独立关闭。

首次预览会生成并固定未来交易计划，升级时兑现。未来价格为基础价，实际成交仍使用原版折扣。

## 安装与使用

已在 **Paper 26.3 build 211 / Java 25** 测试。推测其他 Paper 26.3 构建也可运行，但尚未验证。其他 Minecraft 版本（包括 26.1、26.2 和 1.21.x）会在启动时被拒绝，需要另做内部适配；原因见 [Paper 内部接口说明](https://docs.papermc.io/paper/dev/internals/)。

从 [Releases](https://github.com/leonsama/TradePlus/releases/latest) 下载，或使用 `./gradlew build` 构建，将 `TradePlus-x.x.x.jar` 放入 `plugins/` 后重启。无需客户端 Mod 或额外前置。升级时替换旧 JAR；若已安装 TradeCycle 或 VillagerVision，请移除它们。

双击 F 默认间隔为 **350 ms**，冷却 **500 ms**。F 指原版“交换副手”绑定动作，鼠标需要位于交易界面的物品格上。

## 配置

`plugins/TradePlus/config.yml`：

```yaml
features:
  reroll:
    enabled: true
  preview:
    enabled: true
launch-counter:
  enabled: true
double-press-millis: 350
cycle-cooldown-millis: 500
interaction-distance: 6.0
```

修改后执行 `/tradeplus reload`，并重新打开交易窗口。关闭预览不会删除已保存的计划；重刷始终更新整套计划。

作者的小小虚荣心 QAQ，保持加载计数开启会让作者很开心 (〃'▽'〃)！不附带玩家或设备信息，可用 `launch-counter.enabled: false` 关闭。

权限：`tradeplus.use`、`tradeplus.cycle` 默认允许所有玩家；`tradeplus.admin` 默认仅 OP。

## 开发与许可

构建：`./gradlew build`；Windows 可使用 `./build.ps1`。验证记录与测试步骤见 [TESTING.md](TESTING.md)。图标位于 [assets/icon.png](assets/icon.png)。

改编自 [TradeCycle](https://github.com/S42yt/TradeCycle)（MIT，TradeCycle Authors and Contributors）与 [VillagerVision](https://github.com/salvadorreynaa/VillagerVision)（GPL-3.0，Salvador Reyna）。按 **GPL-3.0** 分发；完整署名与许可见 [NOTICE.md](NOTICE.md)、[LICENSE](LICENSE)。
