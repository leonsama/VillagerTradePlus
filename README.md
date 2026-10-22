<p align="center">
  <img src="assets/icon.png" width="192" height="192" alt="Villager Trade Plus icon">
</p>

# Villager Trade Plus

Preview future villager trades and reroll the entire trade set, right inside the vanilla trading screen.

<p align="left">
  <img src="https://count.getloli.com/@TradePlus?theme=eva&amp;padding=4&amp;offset=0&amp;align=top&amp;scale=1&amp;pixelated=1&amp;darkmode=auto" height="96" alt="README views">
</p>

![Paper 26.3](https://img.shields.io/badge/Paper-26.3-22c55e)
![Java 25](https://img.shields.io/badge/Java-25-orange)
![GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue)

English · [简体中文](README.zh-hans.md) · [Modrinth](https://modrinth.com/project/villagertradeplus)

## Features

- See current and future trades, including enchantments and required villager levels.
- Press **F** over an inventory slot to reroll current and future trades together. Only untraded adult novices are eligible.
- Keep trade plans across world saves. Enable rerolling and previews independently.

The first preview generates and saves future trades, which are applied as the villager levels up. Future prices are base prices; actual purchases retain vanilla discounts.

## Install & use

Tested on **Paper 26.3 build 211 / Java 25**. Other Paper 26.3 builds are expected to work, but are untested. Other Minecraft versions (including 26.1, 26.2 and 1.21.x) are rejected at startup and need a separate internal adapter. See [Paper's internals guidance](https://docs.papermc.io/paper/dev/internals/).

Download the JAR from [Modrinth](https://modrinth.com/project/villagertradeplus) or [Releases](https://github.com/leonsama/VillagerTradePlus/releases/latest), or build with `./gradlew build`. Place `VillagerTradePlus-x.x.x.jar` in `plugins/` and restart. No client mods or extra plugins. Replace older TradePlus/VillagerTradePlus JARs; remove TradeCycle and VillagerVision if installed.

Press F once to reroll; the default cooldown is **400 ms** per villager. Enable `double-press-enabled` for double-tap mode (**350 ms** between presses). F means your vanilla “swap offhand” binding, with the cursor over an inventory slot. Existing configured cooldowns are preserved.

## Configuration

The plugin ID is `VillagerTradePlus`, with Java package `dev.villagertradeplus`. On upgrade, the old `plugins/TradePlus/config.yml` is copied if the new config does not exist; existing settings and villager plans are preserved. `/tradeplus` remains an alias, and explicitly assigned old permissions remain supported unless a new permission is explicitly assigned.

`plugins/VillagerTradePlus/config.yml`:

```yaml
features:
  reroll:
    enabled: true
  preview:
    enabled: true
launch-counter:
  enabled: true
double-press-enabled: false
double-press-millis: 350
cycle-cooldown-millis: 400
interaction-distance: 6.0
```

Chinese clients receive Chinese prompts; all other clients receive English. Prompts and open previews follow language changes automatically. Console output is always English; no language setting is needed.

Run `/villagertradeplus reload` and reopen the trading screen after changes. Disabling previews keeps saved plans intact; rerolling always replaces the whole plan.

[Launch counter](https://count.getloli.com/@TradePlus-launch?theme=booru-lewd&padding=6&offset=0&align=top&scale=1&pixelated=1&darkmode=auto) — requested once per startup, with no personal or device information attached. Keeping it on makes the author happy (〃'▽'〃)! Disable with `launch-counter.enabled: false`.

Permissions: `villagertradeplus.use` and `villagertradeplus.cycle` default to everyone; `villagertradeplus.admin` defaults to operators.

## Development & license

Build: `./gradlew build` (or `./build.ps1` on Windows). See [TESTING.md](TESTING.md) for verification and server tests. Icon: [assets/icon.png](assets/icon.png).

Inspired by [TradeCycle](https://github.com/S42yt/TradeCycle) (MIT, TradeCycle Authors and Contributors) and [VillagerVision](https://github.com/salvadorreynaa/VillagerVision) (GPL-3.0, Salvador Reyna). Distributed under **GPL-3.0**; see [NOTICE.md](NOTICE.md) and [LICENSE](LICENSE).
