<img src="assets/icon.png" align="right" width="96" height="96" alt="TradePlus icon">

# TradePlus

Preview future villager trades and reroll the entire trade set, right inside the vanilla trading screen.

![Paper 26.3](https://img.shields.io/badge/Paper-26.3-22c55e)
![Java 25](https://img.shields.io/badge/Java-25-orange)
![GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue)
![README views](https://komarev.com/ghpvc/?username=leonsama-tradeplus&label=README+views&color=22c55e&style=flat-square)

English · [简体中文](readme.zh-hans.md)

## Features

- See current and future trades, including enchantments and required villager levels.
- Double-tap **F** over an inventory slot to reroll current and future trades together. Only untraded adult novices are eligible.
- Keep trade plans across world saves. Enable rerolling and previews independently.

The first preview generates and saves future trades, which are applied as the villager levels up. Future prices are base prices; actual purchases retain vanilla discounts.

## Install & use

Requires **Paper 26.3 and Java 25**. No client mods or extra plugins.

Build with `./gradlew build`, place `build/libs/TradePlus-0.1.1.jar` in `plugins/`, and restart. Replace older TradePlus JARs; remove TradeCycle and VillagerVision if installed.

Double-tap F within **350 ms**; rerolls have a **500 ms** cooldown. F means your vanilla “swap offhand” binding, with the cursor over an inventory slot.

## Configuration

`plugins/TradePlus/config.yml`:

```yaml
features:
  reroll:
    enabled: true
  preview:
    enabled: true
double-press-millis: 350
cycle-cooldown-millis: 500
interaction-distance: 6.0
```

Run `/tradeplus reload` and reopen the trading screen after changes. Disabling previews keeps saved plans intact; rerolling always replaces the whole plan.

Permissions: `tradeplus.use` and `tradeplus.cycle` default to everyone; `tradeplus.admin` defaults to operators.

## Development & license

Build: `./gradlew build` (or `./build.ps1` on Windows). See [TESTING.md](TESTING.md) for verification and server tests. Icon: [assets/icon.png](assets/icon.png).

Adapted from [TradeCycle](https://github.com/S42yt/TradeCycle) (MIT, TradeCycle Authors and Contributors) and [VillagerVision](https://github.com/salvadorreynaa/VillagerVision) (GPL-3.0, Salvador Reyna). Distributed under **GPL-3.0**; see [NOTICE.md](NOTICE.md) and [LICENSE](LICENSE).

<sub>The view badge counts image requests, not unique visitors. It tracks README views only; the plugin collects no player analytics.</sub>
