"""Build the Everest 8000 client pack (.mrpack) for Minecraft 1.21.1.

Usage:
  build_pack.py [--out FILE]
  build_pack.py --server-address ADDR --xaeromap <world>/xaeromap.txt [--server-name NAME] [--out FILE]

The first form is the generic pack. The second is a host's pack for their own server: it adds the server to the
multiplayer list and preloads the route stops as Xaero's waypoints. Xaero's files those waypoints under the address
players join with and the world's level id (in the world's xaeromap.txt), so they only show for players who join
from the server list at that address.

The pack downloads every third-party file from Modrinth when it's imported; nothing third-party is bundled
(Complementary's license forbids passing its file around directly, and the other mods expect the same). Only our own
mods, built from mods/ in this repo, and our settings files ride along in the overrides. Build the mods first.
"""
import argparse, json, os, struct, zipfile

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
VERSION = "2.6.3"
NAME = "Everest 8000"

p = argparse.ArgumentParser()
p.add_argument("--server-address")
p.add_argument("--xaeromap", help="the server world's xaeromap.txt")
p.add_argument("--server-name", default=NAME)
p.add_argument("--waypoints", default=os.path.join(REPO, "pack", "waypoints", "Everest.txt"))
p.add_argument("--out", default=os.path.join(REPO, "dist", f"Everest-8000-{VERSION}.mrpack"))
args = p.parse_args()
if bool(args.server_address) != bool(args.xaeromap):
    p.error("--server-address and --xaeromap go together")


def built_jar(mod):
    props = dict(line.split("=", 1) for line in open(os.path.join(REPO, "mods", mod, "gradle.properties"))
                 if "=" in line and not line.startswith("#"))
    jar = os.path.join(REPO, "mods", mod, "build", "libs", f"{mod}-{props['mod_version'].strip()}.jar")
    if not os.path.exists(jar):
        raise SystemExit(f"{jar} is missing: build {mod} first (see the README)")
    return jar


EXPEDITION_JAR = built_jar("everest-expedition")
RADIO_JAR = built_jar("everest-radio")

# (path in .minecraft, url, sha1, sha512, size)
FILES = [
    ("mods/DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar",
     "https://cdn.modrinth.com/data/uCdwusMi/versions/9w34y8ai/DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar",
     "4357b4d19fab818780c94f0e43dad1cc931634b2",
     "d14b32d3fd143a06514d6ad388eee11e9d657c84e1a937aaf8d033b1137fb43ef8b05cff1011a994cc42f3106823c51234f8bc18d72bb05bc317a20ea83c50ed",
     28289212),
    ("mods/sodium-fabric-0.8.13+mc1.21.1.jar",
     "https://cdn.modrinth.com/data/AANobbMI/versions/SMxNOGZ6/sodium-fabric-0.8.13%2Bmc1.21.1.jar",
     "003c114c85ca88ef3362e018deb6aca0c682d6a1",
     "8614a374593698069a80ac74de8cd9c28f3928d16819a0e0d6320b538e823f7e54e8be06d5ea5a9c268beb0e02073209d1f831a02dede851588b972367680853",
     1574609),
    ("mods/iris-fabric-1.8.14-beta.1+mc1.21.1.jar",
     "https://cdn.modrinth.com/data/YL57xq9U/versions/bAo1Qhte/iris-fabric-1.8.14-beta.1%2Bmc1.21.1.jar",
     "6776c0340845887477bfa463acc736ce7fcb6de5",
     "a7fbb629793c52f0be8b049f787cb598879239b1ad8e1de62e103c8b9efff140e3232b93ef1f14e505d262897d8cf9505b1126396429ad4056bff969c8674e52",
     2791343),
    ("mods/fabric-api-0.116.17+1.21.1.jar",
     "https://cdn.modrinth.com/data/P7dR8mSH/versions/Mys3P7lK/fabric-api-0.116.17%2B1.21.1.jar",
     "810b2b0195371a012906241d8b85a32a1d6de53c",
     "98c478217da19181f0e4df3ea6c2da6bdbda271c7544a4aea048513a49b444f1ff5b71dc0df6303017c48e8ff69b7bb6af6179bdf0d0e09ab3c1ee08411857a8",
     2452735),
    ("mods/xaerominimap-fabric-1.21.1-26.6.0.jar",
     "https://cdn.modrinth.com/data/1bokaNcj/versions/W4dcLcUs/xaerominimap-fabric-1.21.1-26.6.0.jar",
     "5fbb03dd3f22c0c3d5466d1366222d7d277a8df3",
     "bad6dfbdfa4fa83c10155380e44e7c7a181c86144cbb377548a3df94e1fdadb88a27dbf18dca89bf8748a49a1c485a03b458e1f74a7881029763297106ed26b7",
     2234556),
    ("mods/xaeroworldmap-fabric-1.21.1-1.47.0.jar",
     "https://cdn.modrinth.com/data/NcUtCpym/versions/XIR7GzC3/xaeroworldmap-fabric-1.21.1-1.47.0.jar",
     "db7ad23fe512dbc13a8286844fd9277c08a7ef5a",
     "7bdf96a45ab729ad5b8c56b1de421ab8c94006a65e62f21e45a4733a38a7a1655b8be03ef3bdfcdc52401c66894b9f4f80fc28b41d47a787a9fa45094629f5c7",
     1456162),
    ("mods/particlerain-4.0.2+1.21.1-fabric.jar",
     "https://cdn.modrinth.com/data/nrikgvxm/versions/jquizTC2/particlerain-4.0.2%2B1.21.1-fabric.jar",
     "b83113c0fec92573c5b676ea0b594f6cd9f8d2d0",
     "b80fbfc16e3ed3726e5ff9981daf0ebf7ced477ad74a8bb34864306a828e38582ca0bc982a0d2343470d968258801d9c51cc0a801558f671c4379f44a60dbed5",
     626772),
    # Survival: AutoHUD (client only); Traveler's Backpack with its Cardinal Components and Cloth Config; Naturalist.
    # All but AutoHUD are on the server too.
    ("mods/autohud-8.13+1.21.1-fabric.jar",
     "https://cdn.modrinth.com/data/temczoTQ/versions/NMpWksgf/autohud-8.13%2B1.21.1-fabric.jar",
     "d1e47c9999cf7afc498121cf88093f7408fb33c7",
     "92836ca6119f8701cfa3507ba5156483cd7d2e66aeae5c00244be7e66dc0028dc32ade3d38b4125330db2b603c4017e09a6ea1e6d3ca6fe1750b2f93e0dfc1ac",
     420144),
    ("mods/travelersbackpack-fabric-1.21.1-10.1.39.jar",
     "https://cdn.modrinth.com/data/rlloIFEV/versions/npKsvQvE/travelersbackpack-fabric-1.21.1-10.1.39.jar",
     "d74374cfe5ef7d4afb4a6a3c2c9f48e41f334a8d",
     "ad2f52f675a61f81cde69304aadeafe800450eddb21a0860c821ab4c98646e7015bcdec7221bd5b9bc2baafc800feece0c605b6c46188babb49f426d13dec841",
     1508904),
    ("mods/cardinal-components-api-6.1.3.jar",
     "https://cdn.modrinth.com/data/K01OU20C/versions/nLsCe2VD/cardinal-components-api-6.1.3.jar",
     "e762f5205c0fd0f9bed1c04432bf9b51ec832882",
     "db52fc8c4f14dda723b69eec5a52a693fcb1db72e97114cb530ac8a306d95c13a4234ea54bc6e632134038cb05ba551b5240b9187562fb775a4c7bacb681eff1",
     229645),
    ("mods/cloth-config-15.0.140-fabric.jar",
     "https://cdn.modrinth.com/data/9s6osm5g/versions/HpMb5wGb/cloth-config-15.0.140-fabric.jar",
     "5d82342a2df53fd6df712bfdd583a811572918a4",
     "1b3f5db4fc1d481704053db9837d530919374bf7518d7cede607360f0348c04fc6347a3a72ccfef355559e1f4aef0b650cd58e5ee79c73b12ff0fc2746797a00",
     1157166),
    ("mods/naturalist-2.0.5-fabric-1.21.1.jar",
     "https://cdn.modrinth.com/data/F8BQNPWX/versions/GkSvbUbs/naturalist-2.0.5-fabric-1.21.1.jar",
     "1601db1e4ad2d54484f59c8410d813debcb629b2",
     "9ee6c79bfabac43e27b95586fa962642659a2256682927f614897dda1db19dceb99cd435a7ec0637f39ee42f6bce59828d772aac4b356b236a7b284508a82871",
     11256494),
    # Proximity voice chat; also on the server, where Everest Radio plugs into it.
    ("mods/voicechat-fabric-1.21.1-2.6.22.jar",
     "https://cdn.modrinth.com/data/9eGKb6K1/versions/VS5X7jZd/voicechat-fabric-1.21.1-2.6.22.jar",
     "2b3f229ed746955b756807a92e09e9a32d636776",
     "c44e69400ac7349ad6d4699263bde4d87359e03233c31a872eb02542658cfc73a185b46e4361c54cb1ac029a2991bd8eb4d4ea59b1c3e3b43f234087520b325a",
     5369475),
    # Sky and light (client only): held torches light the ground (LambDynamicLights), twinkling stars and night nebulae
    # in the vanilla sky (Nicer Skies; Complementary draws its own sky, so it only shows with shaders off), soft
    # volumetric clouds (Better Clouds, off under shaders where Complementary's clouds take over), and a zoom key with
    # deep scroll zoom for scouting the route (Zoomify, on C). YACL and Fabric Language Kotlin are their libraries.
    ("mods/lambdynamiclights-4.8.11+1.21.1.jar",
     "https://cdn.modrinth.com/data/yBW8D80W/versions/ksaGCvSu/lambdynamiclights-4.8.11%2B1.21.1.jar",
     "7b5afc75a094b4e8a7118f9856eec959b10f2422",
     "0286076e8b2922dfbe90cd4544a0d16e6d07d7249c732a37479ea4281e9cf96c86dd2e1ce5261489bd5553ceba33044bc550a04b9eb582645eb97f15c9c6a64c",
     2023375),
    ("mods/nicer-skies-1.4.2+1.21.1.jar",
     "https://cdn.modrinth.com/data/gsJ6q45e/versions/YMGPhcbn/nicer-skies-1.4.2%2B1.21.1.jar",
     "0bc3e48c6115ce2cde631046cc422ecf5b175013",
     "29cb18bfd5725451caa014bc0f621f9ac60818744e16fec77d808d409321314711f223baed5ccdcd1386d4c40187fe53e18f2ddc29ab2dcd7ef49c652bed1ab0",
     187170),
    ("mods/better-clouds-1.13.11+1.21.1-fabric.jar",
     "https://cdn.modrinth.com/data/5srFLIaK/versions/IURyim1H/better-clouds-1.13.11%2B1.21.1-fabric.jar",
     "5550fb04a96e733d6016792088ff7c9526d1ea15",
     "4fd93b1ba5c12727460fdf260002f37fc887cda2971917a913728d6fa1831eeea3184923314f87440ec98f992b4a52c470dbcc1cbb94902da0150da414d2298d",
     816134),
    ("mods/zoomify-2.15.2+1.21.1.jar",
     "https://cdn.modrinth.com/data/w7ThoJFB/versions/6jrMC48Z/zoomify-2.15.2%2B1.21.1.jar",
     "9ec9c47e1d3465ea0cbd595b6d9d12227239c9a6",
     "ce8db36e77f089f091986b6e8bea1c33433bbcffc7a47fa18d04dea5ecd4910286943600a85b4773fdf4ed5405f52f3c0dc08fea8b1551d7725a26e1f6ef2879",
     872937),
    ("mods/yet_another_config_lib_v3-3.8.2+1.21.1-fabric.jar",
     "https://cdn.modrinth.com/data/1eAoo2KR/versions/o3cDn8Vp/yet_another_config_lib_v3-3.8.2%2B1.21.1-fabric.jar",
     "e075503382f70715ab9fc6f66d603397a86eb3e0",
     "353169f85fa63e73ce9bdd0d4ddce54e3cbc4d71153a93c982f74c68fd93325986663f94fc2f2b10f4427e8b15a0485c608c5587b3840d933204b50ea4cb6d4c",
     1131265),
    ("mods/fabric-language-kotlin-1.14.1+kotlin.2.4.20.jar",
     "https://cdn.modrinth.com/data/Ha28R6CL/versions/eRRZzGMc/fabric-language-kotlin-1.14.1%2Bkotlin.2.4.20.jar",
     "62ae40a31b689b52fde399f2143d547a631e942c",
     "91404f87774466ce8604aafea791d8cc97b603bc310dce17c8188f94f7783cca6dc14fc726ce871ca317bfe9033c199d8d858d51284106a00754e952147703b8",
     8142858),
    ("shaderpacks/ComplementaryReimagined_r5.9.3.zip",
     "https://cdn.modrinth.com/data/HVnmMxH1/versions/Bqen1mJX/ComplementaryReimagined_r5.9.3.zip",
     "838139b54cddb56b2e83cd260d8efd960ac536d6",
     "45304b1d7862afdb2177b7e6226fc9c6737911d17a4ad46cc9c519ca4deb935f05072cd337cd8b19a15bb8062b57b54eceec7bdee4cd572d94858e2946d37d85",
     553397),
]

DH_CONFIG = """_version = 5

[client]
	[client.advanced.autoUpdater]
		enableAutoUpdater = false
	[client.advanced.graphics]
		overrideVanillaGraphicsSettings = false
	[client.advanced.graphics.quality]
		lodChunkRenderDistanceRadius = 1024
	[common.logging]
		[common.logging.warning]
			showGarbageCollectorWarning = false
"""
# tutorialStep:none skips vanilla's "use WASD to move" hints for first-time players.
# Key bindings: the mods' defaults collide, so these move or unbind the less important side of each clash.
# Kept as is: M world map, B backpack, Z enlarge minimap, H AutoHUD toggle, R AutoHUD peek, J hood, V/G voice menus.
# Every letter is taken, so Zoomify gets C (its own default, and OptiFine's zoom key) from vanilla's "save hotbar"
# activator, which only works in creative.
KEY_BINDINGS = {
    "key.mute_microphone": "key.keyboard.n",           # Simple Voice Chat mute, off M (world map); N is next to it
    "key.disable_voice_chat": "key.keyboard.unknown",  # rarely used and in the V menu; frees N for mute
    "key.hide_icons": "key.keyboard.unknown",          # Simple Voice Chat, off H (AutoHUD toggle); rarely used
    "gui.xaero_new_waypoint": "key.keyboard.i",        # Xaero, off B (open backpack); beside U, the waypoint menu
    "key.travelersbackpack.cycle_tool": "key.keyboard.unknown",  # off Z (enlarge minimap); niche
    "iris.keybind.reload": "key.keyboard.unknown",     # off R (AutoHUD peek); only for editing shader packs
    "key.saveToolbarActivator": "key.keyboard.unknown",  # creative-only hotbar saving, off C
    "zoomify.key.zoom": "key.keyboard.c",              # hold to zoom, scroll while holding to zoom further
}
# version: is Minecraft 1.21.1's data version. Without it the game runs every options upgrade from the oldest
# versions, one of which reads key bindings as old numeric key codes, fails on "key.keyboard.n" and throws the whole
# file away: players got all the defaults (render distance 12, every key clash back).
OPTIONS = ("version:3955\nrenderDistance:8\nsimulationDistance:6\ntutorialStep:none\n"
           + "".join(f"key_{name}:{key}\n" for name, key in KEY_BINDINGS.items()))
# Particle Rain's defaults, with room for the mod's blizzard snow and haze particles.
PARTICLE_RAIN_CONFIG = {
    "configVersion": 7,
    "perf": {"maxParticleAmount": 4000, "particleDensity": 75, "particleStormDensity": 160,
             "particleDistance": 18, "surfaceRange": 100},
    "sound": {"rainVolume": 0.2, "snowVolume": 0.1, "windVolume": 0.1},
    "wind": {"strength": 0.03, "gustFrequency": 0.15, "modulationSpeed": 0.1, "yLevelAdjustment": True},
    "compat": {"renderDefaultWeather": False, "doDefaultSplashing": False, "waterTint": True, "tintMix": 0.6,
               "shaderpackTint": True, "syncRegistries": True, "crossBiomeBorder": False, "useHeightmapTemp": True,
               "weatherIgnoreBlocks": {"entries": ["#minecraft:fences", "#minecraft:all_signs", "minecraft:barrier"],
                                       "isWhitelist": True},
               "doSpawnHeightLimit": False, "spawnHeightLimit": 0, "rippleRes": 16, "rippleUsePackRes": True},
    "streak": {"opacity": 0.9, "size": 0.5},
}
# Better Clouds draws the clouds with shaders off. With shaders on it stands down and Complementary's own clouds take
# over; both sit at the cloud height Everest Expedition sets from the weather. Better Clouds fills in its other keys.
BETTER_CLOUDS_CONFIG = {"irisSupport": False}
# Zoomify: hold C for 4x, scroll while holding for up to 12 more steps of 1.5x (about 500x) to scout the route; no
# spyglass overlay or sound, and no first-launch settings screen.
ZOOMIFY_CONFIG = {
    "initialZoom": 4, "zoomInTime": 1.0, "zoomOutTime": 0.5, "zoomInTransition": "ease_out_exp",
    "zoomOutTransition": "ease_out_exp", "affectHandFov": True, "retainZoomSteps": False, "scrollZoom": True,
    "scrollStepCount": 12, "zoomPerStep": 150, "scrollZoomSmoothness": 70, "zoomKeyBehaviour": "hold",
    "_keybindScrolling": False, "relativeSensitivity": 100, "relativeViewBobbing": True, "cinematicCamera": 0,
    "spyglassBehaviour": "combine", "spyglassOverlayVisibility": "never", "spyglassSoundBehaviour": "with_overlay",
    "secondaryZoomAmount": 4, "secondaryZoomInTime": 10.0, "secondaryZoomOutTime": 1.0, "secondaryHideHUDOnZoom": True,
    "_firstLaunch": False,
}
# Nicer Skies (shaders off): its defaults, but a darker sky behind the nebula; the default tints the whole night sky
# maroon toward the south. Its nebula only draws with shaders off (Everest Expedition hides it under Complementary).
NICER_SKIES_CONFIG = {
    "lightmapTweaked": False, "twinklingStars": True, "renderNebulas": True, "nebulasInOtherDimensions": False,
    "nebulaConfig": {"nebulaStrength": 1.0, "nebulaNoiseAmount": 0.5, "nebulaNoiseScale": 1.0, "baseColourAmount": 64,
                     "renderDuringDay": False},
}
# Complementary (only the keys we change; Iris keeps the rest at their defaults): night nebulae on, more stars (the air
# is thin up here), and the aurora on every night in snowy country or under a full moon. Its default (full moon AND
# snowy biome) would show it once every eight hours of play with the hour-long day. Soft volumetric clouds and a round
# sun and moon: the stock blocky clouds read as floating slabs at the realistic cloud-deck height.
COMPLEMENTARY_SETTINGS = ("NIGHT_NEBULAE=1\nAURORA_CONDITION=4\nNIGHT_STAR_AMOUNT=3\n"
                          "CLOUD_STYLE_DEFINE=3\nSUN_MOON_STYLE_DEFINE=2\n")
SHADER_SETTINGS_REL = "shaderpacks/ComplementaryReimagined_r5.9.3.zip.txt"
# Shaders start off; players turn them on with K if their graphics card can take it.
IRIS_PROPERTIES = "shaderPack=ComplementaryReimagined_r5.9.3.zip\nenableShaders=false\n"


def nbt_string(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def servers_dat(name, ip):
    entry = (b"\x08" + nbt_string("name") + nbt_string(name)
             + b"\x08" + nbt_string("ip") + nbt_string(ip) + b"\x00")
    servers = b"\x09" + nbt_string("servers") + b"\x0a" + struct.pack(">i", 1) + entry
    return b"\x0a" + nbt_string("") + servers + b"\x00"


index = {
    "formatVersion": 1, "game": "minecraft", "versionId": VERSION, "name": NAME,
    "summary": "A pack for climbing mountains with friends (Minecraft 1.21.1).",
    "files": [{"path": rel, "hashes": {"sha1": sha1, "sha512": sha512},
               "env": {"client": "required", "server": "unsupported"}, "downloads": [url], "fileSize": size}
              for rel, url, sha1, sha512, size in FILES],
    "dependencies": {"minecraft": "1.21.1", "fabric-loader": "0.19.5"},
}
os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("modrinth.index.json", json.dumps(index, indent=2))
    z.writestr("overrides/config/DistantHorizons.toml", DH_CONFIG)
    z.writestr("overrides/config/iris.properties", IRIS_PROPERTIES)
    z.writestr("overrides/options.txt", OPTIONS)
    z.writestr("overrides/config/particlerain/config.json", json.dumps(PARTICLE_RAIN_CONFIG, indent=2))
    z.writestr("overrides/config/betterclouds-v1.json", json.dumps(BETTER_CLOUDS_CONFIG, indent=2))
    z.writestr("overrides/config/zoomify.json", json.dumps(ZOOMIFY_CONFIG, indent=2))
    z.writestr("overrides/config/nicerskies.json", json.dumps(NICER_SKIES_CONFIG))
    z.writestr(f"overrides/{SHADER_SETTINGS_REL}", COMPLEMENTARY_SETTINGS)
    z.write(EXPEDITION_JAR, f"overrides/mods/{os.path.basename(EXPEDITION_JAR)}")
    z.write(RADIO_JAR, f"overrides/mods/{os.path.basename(RADIO_JAR)}")
    if args.server_address:
        level_id = open(args.xaeromap).read().strip().split(":")[1]
        z.writestr("overrides/servers.dat", servers_dat(args.server_name, args.server_address))
        z.write(args.waypoints,
                f"overrides/xaero/minimap/Multiplayer_{args.server_address}/dim%0/mw${level_id}_Everest.txt")

print(args.out, os.path.getsize(args.out), "bytes")
