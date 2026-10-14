"""Generate the everest_route datapack (Minecraft 1.21.1) and the matching Xaero's Minimap waypoints.

Each stop is a vanilla advancement granted by walking into a box around it, so arrival shows the
normal advancement toast and chat line, and the advancements screen (L) doubles as the route log.
Two of them hand out kits as loot-table rewards: the root (first join, at Lukla) a backpack and food,
Base Camp the down suit and two oxygen cylinders. The kits need Traveler's Backpack and our mod.

Usage: build_route_pack.py <route_paths.json> <out pack dir> <out waypoints.txt>
"""
import json, os, shutil, sys

paths_file, out_dir, waypoints_out = sys.argv[1:4]
legs = json.load(open(paths_file))

# name -> (advancement id, real altitude, description, icon, frame, box radius, Xaero initials, Xaero color index)
# Xaero colors: 6 gold, 10 green, 11 aqua, 12 red.
STOPS = {
    "Lukla": ("lukla", "2,860 m", "Tenzing-Hillary Airport. The trek starts here", "minecraft:map", "task", 48, "L", 10),
    "Namche Bazaar": ("namche_bazaar", "3,440 m", "Sherpa capital and the first acclimatization stop", "minecraft:emerald", "task", 64, "N", 10),
    "Tengboche": ("tengboche", "3,867 m", "Monastery with Everest on the skyline", "minecraft:bell", "task", 40, "T", 10),
    "Pangboche": ("pangboche", "3,985 m", "Monastery village above the tree line", "minecraft:candle", "task", 32, "P", 10),
    "Khumbu valley camp": ("khumbu_valley_camp", "4,540 m", "Last lodge below the glacier", "minecraft:campfire", "task", 24, "K", 10),
    "Everest Base Camp": ("base_camp", "5,364 m", "Tents on the glacier. The Khumbu Icefall starts here", "minecraft:white_banner", "goal", 64, "EB", 6),
    "Camp 1": ("camp_1", "6,065 m", "Top of the Khumbu Icefall", "minecraft:ladder", "task", 24, "C1", 11),
    "Camp 2": ("camp_2", "6,400 m", "Advanced base camp in the Western Cwm", "minecraft:lantern", "task", 24, "C2", 11),
    "Camp 3": ("camp_3", "7,162 m", "A ledge on the Lhotse Face", "minecraft:lead", "task", 24, "C3", 11),
    "Camp 4 (South Col)": ("camp_4", "7,906 m", "The South Col, at the edge of the death zone", "minecraft:glass_bottle", "goal", 24, "C4", 6),
    "Summit": ("summit", "8,849 m", "The top of the world", "minecraft:nether_star", "challenge", 10, "S", 12),
}

# Kits as loot tables (1.21 folder name loot_table). Cylinders and the backpack don't stack, so each is its own entry.
def item(name, count=1, components=None):
    entry = {"type": "minecraft:item", "name": name}
    functions = []
    if count > 1:
        functions.append({"function": "minecraft:set_count", "count": count})
    if components:
        functions.append({"function": "minecraft:set_components", "components": components})
    if functions:
        entry["functions"] = functions
    return {"rolls": 1, "entries": [entry]}


# Expedition red (#CC3636): stands out in a whiteout and against the black hood; tints the worn
# pack, its icon and its screen alike. Players can re-dye to tell each other apart.
EXPEDITION_PACK = {"minecraft:dyed_color": {"rgb": 13383222}}

KITS = {
    "lukla": [item("travelersbackpack:standard", components=EXPEDITION_PACK), item("everest_radio:radio"),
              item("minecraft:bread", 8),
              item("minecraft:cooked_mutton", 4),
              item("minecraft:torch", 6)],
    "base_camp": [item("everest_expedition:down_hood"), item("everest_expedition:down_jacket"),
                  item("everest_expedition:down_pants"), item("everest_expedition:down_boots"),
                  item("everest_expedition:oxygen_cylinder"), item("everest_expedition:oxygen_cylinder")],
}
KIT_REWARDS = {"base_camp": "everest:kits/base_camp"}

stops = [("Lukla", legs[0]["path"][0])] + [(leg["to"], leg["path"][-1]) for leg in legs]
for leg in legs:
    assert leg["walkable"], leg


def location(x, z, r, y_min=None):
    pos = {"x": {"min": x - r, "max": x + r + 1}, "z": {"min": z - r, "max": z + r + 1}}
    if y_min is not None:
        pos["y"] = {"min": y_min}
    return {"trigger": "minecraft:location",
            "conditions": {"player": {"location": {"dimension": "minecraft:overworld", "position": pos}}}}


advancements = {
    "root": {
        "display": {
            "icon": {"id": "minecraft:compass"},
            "title": "Everest Expedition",
            "description": "Walk from Lukla to the summit. Your stops are on the map (M)",
            "background": "minecraft:textures/block/packed_ice.png",
            "frame": "task", "show_toast": False, "announce_to_chat": False,
        },
        "criteria": {"joined": {"trigger": "minecraft:tick"}},
        "rewards": {"loot": ["everest:kits/lukla"]},
    }
}
parent = "everest:root"
respawns = {}
for name, (x, y, z) in stops[1:]:
    adv_id, alt, desc, icon, frame, radius, _, _ = STOPS[name]
    advancements[adv_id] = {
        "parent": parent,
        "display": {
            "icon": {"id": icon},
            "title": name,
            "description": f"{alt} · {desc}",
            "frame": frame, "show_toast": True, "announce_to_chat": True,
        },
        # The summit box is tiny and needs real height, so standing below it on the ridge doesn't count.
        "criteria": {"arrive": location(x, z, radius, y_min=y - 4 if name == "Summit" else None)},
        # Dying sends you back to the last stop you reached, not all the way to Lukla.
        "rewards": {"function": f"everest:respawn/{adv_id}", **({"loot": [KIT_REWARDS[adv_id]]} if adv_id in KIT_REWARDS else {})},
    }
    respawns[adv_id] = f"spawnpoint @s {x} {y + 1} {z}\n"
    parent = f"everest:{adv_id}"

# The first version of this pack drew a breadcrumb HUD from these; clear them out of existing worlds.
LOAD = """scoreboard objectives remove everest.wp
scoreboard objectives remove everest.calc
scoreboard objectives remove route
kill @e[type=minecraft:marker,tag=everest_dir]
data remove storage everest:route markers
data remove storage everest:tmp m
data remove storage everest:tmp n
"""

if os.path.exists(out_dir):
    shutil.rmtree(out_dir)
adv_dir = os.path.join(out_dir, "data", "everest", "advancement")
os.makedirs(adv_dir)
for adv_id, body in advancements.items():
    with open(os.path.join(adv_dir, f"{adv_id}.json"), "w", encoding="utf-8") as f:
        json.dump(body, f, indent=2, ensure_ascii=False)
fdir = os.path.join(out_dir, "data", "everest", "function")
os.makedirs(fdir)
with open(os.path.join(fdir, "load.mcfunction"), "w", encoding="utf-8", newline="\n") as f:
    f.write(LOAD)
os.makedirs(os.path.join(fdir, "respawn"))
for adv_id, body in respawns.items():
    with open(os.path.join(fdir, "respawn", f"{adv_id}.mcfunction"), "w", encoding="utf-8", newline="\n") as f:
        f.write(body)
kit_dir = os.path.join(out_dir, "data", "everest", "loot_table", "kits")
os.makedirs(kit_dir)
for kit, pools in KITS.items():
    with open(os.path.join(kit_dir, f"{kit}.json"), "w", encoding="utf-8") as f:
        json.dump({"type": "minecraft:advancement_reward", "pools": pools}, f, indent=2)
tags = os.path.join(out_dir, "data", "minecraft", "tags", "function")
os.makedirs(tags)
json.dump({"values": ["everest:load"]}, open(os.path.join(tags, "load.json"), "w"))
json.dump({"pack": {"pack_format": 48, "description": "Everest expedition: Lukla to the summit"}},
          open(os.path.join(out_dir, "pack.mcmeta"), "w"), indent=2)

lines = ["#", "#waypoint:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination", "#"]
for name, (x, y, z) in stops:
    *_, initials, color = STOPS[name]
    lines.append(f"waypoint:{name}:{initials}:{x}:{y + 1}:{z}:{color}:false:0:gui.xaero_default:false:0:LOCAL:false")
with open(waypoints_out, "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(lines) + "\n")

print(f"{len(advancements)} advancements, {len(stops)} waypoints:")
for name, (x, y, z) in stops:
    print(f"  {name:20s} {x:6d} {y + 1:5d} {z:6d}  {STOPS[name][1]}")
