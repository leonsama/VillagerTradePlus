<p align="center">
  <img src="assets/icon.png" width="192" height="192" alt="TradePlus icon">
</p>

# TradePlus

Preview future villager trades and reroll the entire trade set, right inside the vanilla trading screen.

<p align="left">
  <img src="https://count.getloli.com/@TradePlus?theme=eva&amp;padding=4&amp;offset=0&amp;align=top&amp;scale=1&amp;pixelated=1&amp;darkmode=auto" height="96" alt="README views">
</p>

![Paper 26.3](https://img.shields.io/badge/Paper-26.3-22c55e)
![Java 25](https://img.shields.io/badge/Java-25-orange)
![GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue)

English · [简体中文](README.zh-hans.md)

## Features

- See current and future trades, including enchantments and required villager levels.
- Double-tap **F** over an inventory slot to reroll current and future trades together. Only untraded adult novices are eligible.
- Keep trade plans across world saves. Enable rerolling and previews independently.

The first preview generates and saves future trades, which are applied as the villager levels up. Future prices are base prices; actual purchases retain vanilla discounts.

## Install & use

Tested on **Paper 26.3 build 211 / Java 25**. Other Paper 26.3 builds are expected to work, but are untested. Other Minecraft versions (including 26.1, 26.2 and 1.21.x) are rejected at startup and need a separate internal adapter. See [Paper's internals guidance](https://docs.papermc.io/paper/dev/internals/).

Download the JAR from [Releases](https://github.com/leonsama/TradePlus/releases/latest), or build with `./gradlew build`. Place `TradePlus-x.x.x.jar` in `plugins/` and restart. No client mods or extra plugins. Replace older TradePlus JARs; remove TradeCycle and VillagerVision if installed.

Double-tap F within **350 ms**; rerolls have a **500 ms** cooldown. F means your vanilla “swap offhand” binding, with the cursor over an inventory slot.

## Configuration

`plugins/TradePlus/config.yml`:

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

Run `/tradeplus reload` and reopen the trading screen after changes. Disabling previews keeps saved plans intact; rerolling always replaces the whole plan.

[A tiny vanity counter](https://count.getloli.com/@TradePlus-launch?theme=booru-lewd&padding=6&offset=0&align=top&scale=1&pixelated=1&darkmode=auto) QAQ — keeping it on makes the author happy (〃'▽'〃)! No player or device details attached; disable with `launch-counter.enabled: false`.

Permissions: `tradeplus.use` and `tradeplus.cycle` default to everyone; `tradeplus.admin` defaults to operators.

## Development & license

Build: `./gradlew build` (or `./build.ps1` on Windows). See [TESTING.md](TESTING.md) for verification and server tests. Icon: [assets/icon.png](assets/icon.png).

Adapted from [TradeCycle](https://github.com/S42yt/TradeCycle) (MIT, TradeCycle Authors and Contributors) and [VillagerVision](https://github.com/salvadorreynaa/VillagerVision) (GPL-3.0, Salvador Reyna). Distributed under **GPL-3.0**; see [NOTICE.md](NOTICE.md) and [LICENSE](LICENSE).
