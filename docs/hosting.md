# Hosting Everest 8000

## The map

Get [Mt. Everest 8000](https://www.patreon.com/Amon6669/posts/mount-everest-138313374) from Amon and use it as the
server's world. It comes with its own datapacks (Extended World for the tall build height, and WorldPainter's); leave
those in place.

## The server

Minecraft 1.21.1 with Fabric Loader 0.19.5. We gave it 12 GB.

Server mods, in `mods/`:

| Mod | Version |
|---|---|
| Everest Expedition | built from `mods/everest-expedition`, or from Releases |
| Everest Radio | built from `mods/everest-radio`, or from Releases |
| [Fabric API](https://modrinth.com/mod/fabric-api) | 0.116.17+1.21.1 |
| [Distant Horizons](https://modrinth.com/mod/distanthorizons) | 3.3.3 (must match the pack's) |
| [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat) | fabric-1.21.1-2.6.22 |
| [Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap) | 26.6.0 (on the server for its no-minimap effect) |
| [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) | 1.47.0 (on the server for its no-world-map effect) |
| [Traveler's Backpack](https://modrinth.com/mod/travelersbackpack) | 10.1.39 |
| [Cardinal Components API](https://modrinth.com/mod/cardinal-components-api) | 6.1.3 |
| [Cloth Config](https://modrinth.com/mod/cloth-config) | 15.0.140 |
| [Naturalist](https://modrinth.com/mod/naturalist) | 2.0.5 |
| [Lithium](https://modrinth.com/mod/lithium) | 0.15.4+mc1.21.1 (optional, performance) |
| [FerriteCore](https://modrinth.com/mod/ferrite-core) | 7.0.3 (optional, performance) |

Copy `server/config/` into the server's `config/`. These are the settings we ran with: Distant Horizons sends the
distant terrain to players from the existing map only (no new generation), and Xaero's server profiles turn off its
player radar and player tracking. Everest Radio writes `config/everest_radio.properties` with its defaults on first start; each
setting is explained there.

Open TCP 25565 for the game and UDP 24454 for voice chat.

## Datapacks

Copy `datapacks/everest_route` and `datapacks/everest_social` into the world's `datapacks/` folder:

- `everest_route`: one advancement per stop from Lukla to the summit, each also setting your respawn point there, plus
  the Lukla kit (a backpack, food and a radio) and the Base Camp kit (the down suit and two oxygen cylinders).
- `everest_social`: hidden name tags, and no minimap or world map, only the route markers.

## Game rules

Start the server, then in the console:

```
gamerule keepInventory true
gamerule playersSleepingPercentage 0
gamerule doDaylightCycle true
gamerule doMobSpawning true
gamerule doPatrolSpawning false
gamerule doTraderSpawning false
gamerule doInsomnia false
gamerule doWardenSpawning false
everest weather clear
time set 23500
```

`time set 23500` starts the day at dawn; run it when people are joining. A whitelist (`whitelist on`,
`whitelist add <name>`) keeps it to your group.

## A pack for your players

The route markers are Xaero's waypoints, which Xaero files under the server address players join with and the world's
level id. Build the pack with both, so your players get the markers and your server in their list:

```
python pack/build_pack.py --server-address play.example.com --xaeromap "<world folder>/xaeromap.txt"
```

Keep `xaeromap.txt` in the world: deleting it gives the world a new id, and the markers in everyone's pack stop
showing. A new server address also needs the pack rebuilt.

Player progress is plain advancements:

```
/advancement revoke <name> from everest:root              reset someone's route
/advancement grant <name> until everest:base_camp
```

## Other maps

`route/` has the tools that made the route: `route_paths.py` finds walkable paths (1-block step-ups) between the stops
in `route_stages.json` on a map's region files, and `build_route_pack.py` turns them into the route datapack and
waypoints. Everest Expedition's altitudes and oxygen camps are still set for Everest 8000 in its code.
