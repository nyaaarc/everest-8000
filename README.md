# Everest 8000

A pack for climbing mountains with friends, for Minecraft Java 1.21.1 (Fabric).

It was built for [Mt. Everest 8000 by Amon](https://www.patreon.com/Amon6669/posts/mount-everest-138313374), a huge
Everest map. The first expedition took five friends about five hours from Lukla Airport to the summit. The mountain does
the work: the wind picks up as you climb, storms roll over the upper mountain, the cold builds, and above Camp 3 you
breathe bottled oxygen. There's no minimap and no name tags, so you find your way by the mountain and find each other
over the radio.

## What's in it

- **Everest Expedition** (`mods/everest-expedition`): the mountain itself.
  - Jet-stream wind that grows with altitude. Crouch to brace against it, or shelter behind ridges and rocks.
  - Storms over the upper mountain, frostbite, and a down suit that keeps the cold out.
  - Bottled oxygen and hypoxia above Camp 3, with refills at the high camps.
  - Wildlife only in the valleys, an hour-long day, and clouds at their real height.
  - `/hood` to pick a hood, goggles, and suit and pack colours, so friends can tell you apart from a distance.
- **Everest Radio** (`mods/everest-radio`): expedition radios over
  [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat). Hold one and talk, and everyone carrying one hears
  you. The signal is clear up close and breaks up into static with distance and behind terrain, but never goes silent.
- **Route datapacks** (`datapacks/`): an advancement for each stop from Lukla to the summit, with a respawn point at
  the last stop you reached, the starter and Base Camp kits, and no maps or name tags.
- **The client pack** (`pack/`): builds the `.mrpack` with the mods above, plus Distant Horizons, Sodium, Iris,
  Complementary Reimagined, Particle Rain, Traveler's Backpack, Naturalist and more, and settings tuned for the climb.
- **Cinema** (`mods/everest-cinema`): the scriptable camera the trailer was filmed with. It's a separate tool, not part
  of the pack; see [its README](mods/everest-cinema/README.md).
- **Route tools** (`route/`): find walkable paths between stops on a map and generate the route datapack and waypoints.

Everest Expedition is tuned to the Everest 8000 map for now: its altitudes (where the oxygen line, the death zone and the
snowline sit) and the oxygen refill camps are set for that mountain. Making them a per-map setting, so the pack works
on other mountains, is the next step.

## Playing

Download the `.mrpack` from [Releases](https://github.com/nyaaarc/everest-8000/releases) and import it in
[Prism Launcher](https://prismlauncher.org) or the Modrinth App. Your host may give you their own build of the pack
with their server already in the list. [docs/playing.md](docs/playing.md) covers setup, the keys and how the mountain
works.

## Hosting

The map isn't included: get it from [Amon's Patreon](https://www.patreon.com/Amon6669/posts/mount-everest-138313374).
[docs/hosting.md](docs/hosting.md) covers the server mods, datapacks, settings and building a pack for your players.

## Building from source

Each mod in `mods/` is its own Gradle project (Fabric Loom 1.18.4, Mojang mappings). Loom needs JDK 25 to build; the
mods themselves run on Java 21.

```
cd mods/everest-expedition
./gradlew build          # build/libs/everest-expedition-<version>.jar, and runs the tests
```

Then `python pack/build_pack.py` puts the built mods into `dist/Everest-8000-<version>.mrpack`.

## Credits

- Map: [Mt. Everest 8000](https://www.patreon.com/Amon6669/posts/mount-everest-138313374) by Amon
- Shaders: [Complementary Reimagined](https://www.complementary.dev/) by EminGT
- Wind recording: ["Icy Heights"](https://opengameart.org/content/icy-heights) by Ecrivain (CC0)
- Mods the pack downloads: Distant Horizons, Sodium, Iris, Fabric API, Particle Rain, Naturalist, Traveler's Backpack,
  Simple Voice Chat, Xaero's Minimap and World Map, Nicer Skies, Better Clouds, LambDynamicLights, AutoHUD, Zoomify and
  their libraries, each by its own authors under its own license

Brought to you by nyaaarcCORP.

## License

GPL-3.0-only (see [LICENSE](LICENSE)), covering the code and assets in this repository except the CC0 wind recording.
The map and the third-party mods are not part of this repository.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
