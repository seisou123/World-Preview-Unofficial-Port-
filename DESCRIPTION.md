# World Preview

*World Preview* lets you peek at a Minecraft world before you actually create it. Pick a seed, and the mod generates a map of biomes, structures, terrain height, and more — right there in the Singleplayer screen.

This is a **community fork** of the original [World Preview](https://modrinth.com/mod/world-preview) by Caeruleus Draconis & Taiterio, which stopped updating after Minecraft 1.21. The fork keeps the original experience intact while bringing the mod forward to newer versions and adding a bunch of new stuff along the way.

Works on both **Fabric** and **NeoForge**.

## Installation

Drop the JAR into your `mods` folder. Fabric users also need [Fabric API](https://modrinth.com/mod/fabric-api); NeoForge users don't need anything extra.

## How it works

World Preview adds a `Preview` tab to the Singleplayer menu. Open it and you'll get a random seed with a biome map already rendering:

![open](IMAGE_URL_PLACEHOLDER_OPEN)

![biomes](IMAGE_URL_PLACEHOLDER_BIOMES)

The toolbar lets you switch between several views — biomes, structures, a colorized heightmap, Y-layer cross-sections, and even the raw noise parameters that decide where biomes appear. Not all of these are on by default; you can toggle them in Settings.

![structures](IMAGE_URL_PLACEHOLDER_STRUCTURES)

![heightmap](IMAGE_URL_PLACEHOLDER_HEIGHTMAP)

![y-int](IMAGE_URL_PLACEHOLDER_YINT)

The Y-intersection view is handy for cave hunting — it shows what blocks sit at your current Y-level, with the layer below faintly visible too.

### Moving around

Drag the map to pan. Scroll moves you through Y-levels by default (so you can scroll down into caves). **Ctrl+scroll** zooms, **Alt+scroll** always adjusts Y, and **Home** snaps back to the origin. Arrow keys also work for panning if you prefer the keyboard.

### What else is in there

Rather than list every feature and how it works, here's the short version:

- A **world analysis engine** — select a region, get biome distribution, height stats, slope data, and terrain profiles. You can also search for seeds containing a specific biome.
- **Terrain map export** — save the current seed's terrain as a classified PNG.
- **Spawn point override** — drop a pin on the map and that's where you'll spawn. No cheats.
- **Hillshade and contour lines** — optional overlays that make the heightmap actually look like terrain.
- **In-game preview** — open the preview from the pause menu without leaving your world.
- **Minimap, statistics, coordinate readout, seed storage, biome highlighting** — the usual QoL stuff.

A lot of these have their own settings. Poke around the wrench menu and you'll find them.

![analysis](IMAGE_URL_PLACEHOLDER_ANALYSIS)

![terrain](IMAGE_URL_PLACEHOLDER_TERRAIN)

![ingame](IMAGE_URL_PLACEHOLDER_INGAME)

For the full list of what changed between versions, see the changelog.

## Mod compatibility

The mod auto-detects worldgen mods at runtime and applies workarounds where needed. Most biome-adding mods (Terralith, Biomes O' Plenty, Oh The Biomes You'll Go, Nature's Spirit, and others) work out of the box — biome colors are handled through the datapack system.

One known limitation: **TerraFirmaCraft** causes the Y-intersection view to show a blank white screen at every Y-level. This isn't a bug in TFC — its chunk generator simply doesn't expose the column data World Preview needs for that view. Everything else works fine.

New biomes and structures from any mod (or your own datapack) can be registered through the standard Minecraft datapack mechanism. See the [data format docs](https://github.com/caeruleusDraconis/world-preview) for details.

## FAQ

**The preview is all white in Y-intersection mode.**

The starting Y-level is around sea level. Try scrolling down a bit — you might just be above the terrain.

**Scrolling doesn't zoom.**

That's by default — scroll moves Y-levels. Use Ctrl+scroll to zoom, or flip the setting in `Settings → General` to make scroll zoom instead.

**My CPU is pegged at 100%.**

That's World Preview working as fast as it can. You can cap the thread count in `Settings → General` if it's too much.

**Will older Minecraft versions be supported?**

No. This fork only targets newer versions.

**Multiplayer?**

No.

## Credits

Original mod by [Caeruleus Draconis](https://github.com/caeruleusDraconis) & [Taiterio]. Community fork, Apache-2.0.
