"""Writes the published JSON Schemas (draft 2020-12) for shot specs and batch shot files.

The Java side (dev.everest8000.cinema.spec) is the authoritative validator; keep this in step with it. Run:
    python generate.py      -> shot.schema.json, shots.schema.json, render.schema.json, preview.schema.json
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))


def num(lo, hi, desc=None, default=None):
    d = {"type": "number", "minimum": lo, "maximum": hi}
    if desc:
        d["description"] = desc
    if default is not None:
        d["default"] = default
    return d


def integer(lo, hi, desc=None, default=None):
    d = {"type": "integer", "minimum": lo, "maximum": hi}
    if desc:
        d["description"] = desc
    if default is not None:
        d["default"] = default
    return d


def boolean(desc=None, default=None):
    d = {"type": "boolean"}
    if desc:
        d["description"] = desc
    if default is not None:
        d["default"] = default
    return d


def enum(values, desc=None, default=None):
    d = {"enum": list(values)}
    if desc:
        d["description"] = desc
    if default is not None:
        d["default"] = default
    return d


def obj(props, required=(), desc=None):
    d = {"type": "object", "additionalProperties": False, "properties": props}
    if required:
        d["required"] = list(required)
    if desc:
        d["description"] = desc
    return d


def ref(name):
    return {"$ref": f"#/$defs/{name}"}


def typed(type_name, props, required=(), desc=None):
    p = {"type": {"const": type_name}}
    p.update(props)
    return obj(p, ["type", *required], desc)


ACTOR_ID = "^[A-Za-z0-9_-]{1,32}$"
ITEM_SPEC = r"^[a-z0-9_.-]+:[a-z0-9_./-]+(\[.*\])?$"
EASING = ["linear", "easeIn", "easeOut", "easeInOut", "sineInOut", "easeInOutQuint"]
ANCHOR = ["feet", "center", "eyes"]
SLOTS = ["head", "chest", "legs", "feet", "mainhand", "offhand"] + [f"hotbar.{i}" for i in range(9)] + \
        [f"inventory.{i}" for i in range(27)]
ID = "^[a-z0-9_.-]+:[a-z0-9_./-]+$"
YAW = num(-36000, 36000, "Minecraft yaw in degrees: 0 faces south (+Z), 90 west, 180 north, -90 east.")
PITCH = num(-90, 90, "Degrees; positive looks down.")
ROLL = num(-180, 180, "Degrees; positive banks the camera to the left (the horizon turns clockwise).", 0)
FOV = num(1, 170, "Vertical field of view in degrees.", 70)
DUR = {"type": "number", "exclusiveMinimum": 0, "maximum": 3600,
       "description": "Seconds. Optional: without it the move lasts the whole shot (or runs until stopped via the API)."}

DEFS = {
    "vec3": {"type": "array", "items": {"type": "number"}, "minItems": 3, "maxItems": 3,
             "description": "[x, y, z] in blocks."},
    "easing": enum(EASING, "Time easing over the whole move.", "linear"),
    "target": {"oneOf": [
        typed("point", {"pos": ref("vec3"), "offset": ref("vec3")}, ["pos"], "A fixed point."),
        typed("player", {"anchor": enum(ANCHOR, default="eyes"), "offset": ref("vec3")}, (), "The local player."),
        typed("entity", {"uuid": {"type": "string", "pattern": "^[0-9a-fA-F-]{36}$"}, "anchor": enum(ANCHOR, default="eyes"),
                         "offset": ref("vec3")}, ["uuid"], "An entity by UUID (see world.findEntities)."),
        typed("nearestEntity", {"entityType": {"type": "string", "pattern": ID}, "maxDistance": num(1, 512, default=64),
                                "anchor": enum(ANCHOR, default="eyes"), "offset": ref("vec3")}, ["entityType"],
              "The nearest entity of a type to the player, locked on for the rest of the move."),
        typed("actor", {"id": {"type": "string", "pattern": ACTOR_ID}, "anchor": enum(ANCHOR, default="eyes"), "offset": ref("vec3")},
              ["id"], 'A client-side actor by id ("player" when the player is a directed actor).'),
    ]},
    "shake": obj({"amplitudeDeg": num(0, 10), "frequencyHz": num(0.01, 10, default=0.5)}, ["amplitudeDeg"],
                 "Handheld/wind shake added to yaw, pitch and roll."),
    "keyframe": obj({
        "t": num(0, 3600, "Seconds from the start of the move. Give it on every keyframe or on none."),
        "pos": ref("vec3"), "yaw": YAW, "pitch": PITCH,
        "lookAt": ref("target"), "roll": ROLL, "fov": num(1, 170, "Overrides the move's fov at this key."),
        "relativeTo": {**ref("target"), "description": "pos is an offset from this target's current position."},
    }, ["pos"], "Orientation: yaw+pitch, or lookAt, or neither when the move has lookAt."),
    "move": {"oneOf": [
        typed("static", {"pos": ref("vec3"), "yaw": YAW, "pitch": PITCH, "lookAt": ref("target"),
                         "lookSmoothSec": num(0, 10, default=0), "roll": ROLL, "fov": FOV, "durationSec": DUR,
                         "shake": ref("shake")}, ["pos"], "Holds one pose (or keeps aiming at a moving target)."),
        typed("keyframes", {"keyframes": {"type": "array", "items": ref("keyframe"), "minItems": 1, "maxItems": 1000},
                            "interpolation": enum(["catmullRom", "linear"], default="catmullRom"),
                            "easing": ref("easing"), "constantSpeed": boolean("Re-time the path by arc length.", False),
                            "lookAt": ref("target"), "lookSmoothSec": num(0, 10, default=0), "fov": FOV,
                            "durationSec": DUR, "shake": ref("shake")}, ["keyframes"],
              "A path through keyframes (Catmull-Rom with key times as knots, or linear)."),
        typed("orbit", {"center": ref("target"), "radius": num(0.1, 100000), "radiusEnd": num(0.1, 100000),
                        "height": num(-10000, 10000, "Camera height above the center.", 0), "heightEnd": num(-10000, 10000),
                        "startDeg": num(-36000, 36000, "Bearing from the center to the camera, as a Minecraft yaw."),
                        "endDeg": num(-36000, 36000), "easing": ref("easing"), "lookAt": ref("target"),
                        "lookSmoothSec": num(0, 10, default=0), "roll": ROLL, "fov": FOV, "fovEnd": num(1, 170),
                        "durationSec": DUR, "shake": ref("shake")},
              ["center", "radius", "startDeg", "endDeg"], "Circles a target."),
        typed("dolly", {"target": ref("target"), "azimuthDeg": num(-36000, 36000), "elevationDeg": num(-89, 89, default=0),
                        "distanceStart": num(0.1, 100000), "distanceEnd": num(0.1, 100000), "easing": ref("easing"),
                        "roll": ROLL, "fov": FOV, "fovEnd": num(1, 170), "durationSec": DUR, "shake": ref("shake")},
              ["target", "azimuthDeg", "distanceStart", "distanceEnd"], "Moves along the line of sight, aiming at the target."),
        typed("truck", {"target": ref("target"), "azimuthDeg": num(-36000, 36000), "elevationDeg": num(-89, 89, default=0),
                        "distance": num(0.1, 100000), "slideStart": num(-100000, 100000), "slideEnd": num(-100000, 100000),
                        "track": boolean("Keep aiming at the target; otherwise the heading is fixed.", False),
                        "easing": ref("easing"), "roll": ROLL, "fov": FOV, "fovEnd": num(1, 170), "durationSec": DUR,
                        "shake": ref("shake")},
              ["target", "azimuthDeg", "distance", "slideStart", "slideEnd"],
              "Slides sideways past a target; positive slide is to the camera's right."),
        typed("follow", {"target": ref("target"), "offset": ref("vec3"),
                         "space": enum(["world", "targetYaw"], "targetYaw: offset is [left, up, forward] of the target's facing.", "world"),
                         "smoothSec": num(0, 10, default=0.25), "lookAt": ref("target"), "lookSmoothSec": num(0, 10, default=0),
                         "roll": ROLL, "fov": FOV, "durationSec": DUR, "shake": ref("shake")},
              ["target", "offset"], "Rides along with a target, aiming at it."),
        typed("firstPerson", {"fov": num(1, 170, "Omit to keep the game's FOV setting."), "durationSec": DUR,
                              "shake": ref("shake")}, (), "The player's own first-person view (HUD shots)."),
    ]},
    "action": {"oneOf": [
        typed("command", {"command": {"type": "string", "minLength": 1, "maxLength": 32000, "pattern": "^[^\\n\\r]+$"}},
              ["command"], "Runs a command as the player (leading slash optional)."),
        typed("wait", {"seconds": num(0, 3600)}, ["seconds"], "setup/teardown only."),
        typed("waitLoaded", {"timeoutSec": num(1, 3600, default=60), "includeLods": boolean(default=True),
                             "lodSettleSec": num(0, 600, default=4)}, (), "setup/teardown only."),
        typed("teleport", {"pos": ref("vec3"), "yaw": YAW, "pitch": PITCH}, ["pos"], "Server-side /tp of the player."),
        typed("gamemode", {"mode": enum(["survival", "creative", "adventure", "spectator"])}, ["mode"]),
        typed("time", {"dayTime": integer(0, 24000), "freeze": boolean("Stop the daylight cycle.", True)}, ["dayTime"]),
        typed("weather", {"weather": enum(["clear", "rain", "thunder"])}, ["weather"]),
        typed("tickRate", {"rate": num(1, 10000)}, ["rate"]),
        typed("equip", {"slot": enum(SLOTS), "item": {"type": "string", "pattern": ID}, "count": integer(1, 99, default=1)},
              ["slot", "item"], "/item replace on the player (restored by state.restore)."),
        typed("clearSlot", {"slot": enum(SLOTS)}, ["slot"]),
        typed("selectSlot", {"slot": integer(0, 8)}, ["slot"]),
        typed("walk", {"points": {"type": "array", "items": ref("vec3"), "minItems": 1, "maxItems": 10000},
                       "speed": enum(["walk", "sprint", "sneak"], default="walk"), "arriveRadius": num(0.1, 16, default=0.6)},
              ["points"], "Walks the player through ground points with physics (jumps over blocks)."),
        typed("look", {"yaw": YAW, "pitch": PITCH, "durationSec": num(0, 600, default=0),
                       "easing": enum(EASING, default="sineInOut")}, ["yaw", "pitch"]),
        typed("lookAt", {"target": ref("target"), "durationSec": num(0, 600, default=0),
                         "easing": enum(EASING, default="sineInOut")}, ["target"]),
        typed("input", {"forward": num(-1, 1, default=0), "strafe": num(-1, 1, "Positive = left.", 0),
                        "jump": boolean(default=False), "sneak": boolean(default=False), "sprint": boolean(default=False),
                        "use": boolean(default=False)}, (), "Raw movement input, held until clearInput/stop."),
        typed("clearInput", {}), typed("jump", {}), typed("swing", {"hand": enum(["main", "off"], default="main")}),
        typed("stop", {}, (), "Stops walking, looking and held input."),
        typed("frozen", {"fraction": num(0, 1), "durationSec": num(0, 600, default=0)}, ["fraction"],
              "Client-side freezing meter (frost overlays), eased."),
        typed("hud", {"visible": boolean(), "chat": boolean(default=False), "crosshair": boolean(default=False),
                      "hand": boolean(default=True)}, ["visible"]),
        typed("shaders", {"enabled": boolean()}, ["enabled"], "Iris shaders on/off (reload takes a few seconds)."),
        typed("playerVisible", {"visible": boolean()}, ["visible"]),
    ]},
    "gait": obj({
        "pace": num(0.1, 1.3, "Forward input at full stride on the flat (1 = normal walk).", 0.75),
        "turnRateDegPerSec": num(20, 720, default=150), "accelSec": num(0.05, 5, default=0.6),
        "wander": num(0, 3, "Sideways meander amplitude in blocks (route-level, kept on walkable ground).", 1.2),
        "wanderWavelength": num(2, 200, "Blocks of travel per meander swing.", 18),
        "lookahead": num(0.8, 8, "Pure-pursuit lookahead, blocks.", 2.2),
        "uphillSlowdown": num(0, 0.95, default=0.45), "snowSlowdown": num(0, 0.95, default=0.35),
        "breathers": obj({"everyMinSec": num(1, 600, "Effort between breathers, in seconds of walking at rate 1 (randomised "
                                                     "between min and max). Altitude and slope multiply the rate."),
                          "everyMaxSec": num(1, 600), "minSec": num(0.2, 60, "Shortest stop (excluding the 0.6 s ease in and out).", 2.0),
                          "maxSec": num(0.2, 60, default=5.0),
                          "slopeFactor": num(0, 20, "Extra breather rate per unit of uphill grade (rate x (1 + k x grade)).", 1.0),
                          "altitude": {"type": "array", "minItems": 1, "maxItems": 64,
                                       "description": "Breather rate by height, piecewise linear, increasing y (1 if omitted).",
                                       "items": obj({"y": num(-4096, 4096), "rate": num(0.05, 50)}, ["y", "rate"])}},
                         ["everyMinSec", "everyMaxSec"],
                         "Ease to a stop, catch breath (head down, a look back at a teammate, or a slight crouch), carry on. "
                         "Effort-based: more often on climbs and higher up, rare low down. Omit for no breathers."),
        "paceAltitude": {"type": "array", "minItems": 1, "maxItems": 64,
                         "description": "Pace multiplier by height, piecewise linear, increasing y (a slower trudge higher up).",
                         "items": obj({"y": num(-4096, 4096), "factor": num(0.1, 2)}, ["y", "factor"])},
        "idleLook": boolean("Occasional glances while walking and standing.", True),
    }),
    "actorAction": {"oneOf": [
        typed("walkTo", {"to": ref("vec3"), "points": {"type": "array", "items": ref("vec3"), "minItems": 1, "maxItems": 10000},
                         "speed": enum(["walk", "stroll", "sprint", "sneak"], default="walk"),
                         "path": enum(["astar", "direct"], "astar: route over the blocks (step-ups, ladders, no drops > 3).", "astar"),
                         "arriveRadius": num(0.2, 16, default=0.6), "maxNodes": integer(100, 1000000, default=60000)}, (),
              "Walk with real physics; give to or points. Finishes on arrival (fails if stuck 5 s)."),
        typed("lookAt", {"target": ref("target"), "mode": enum(["track", "glance"], default="track"),
                         "durationSec": num(0, 600, "Glance length.", 1.5)}, ["target"]),
        typed("look", {"yaw": YAW, "pitch": PITCH, "mode": enum(["track", "glance"], default="track"),
                       "durationSec": num(0, 600, default=1.5)}, ["yaw", "pitch"]),
        typed("lookAhead", {}, (), "Back to looking ahead with idle glances."),
        typed("face", {"yaw": YAW}, ["yaw"], "Turn the body while standing."),
        typed("stop", {}), typed("breather", {"durationSec": num(0.2, 60, default=3)}),
        typed("jump", {}), typed("crouch", {"on": boolean()}, ["on"]),
        typed("brace", {"on": boolean(), "windYaw": YAW}, ["on"], "Crouch into the wind, head down."),
        typed("swing", {"hand": enum(["main", "off"], default="main")}),
        typed("use", {"hand": enum(["main", "off"], default="main"), "durationSec": num(0, 600, "0 = until release.", 0)}, (),
              "Hold use (draw a bow...)."),
        typed("release", {"arrow": boolean("Loose a client-side arrow (bows).", False), "power": num(0.1, 3, default=2.5)}),
        typed("pose", {"hand": enum(["main", "off"], "Which arm (ignored by raise with Everest Radio).", "main"),
                       "pose": enum(["none", "raise", "spyglass", "bow", "crossbow", "block", "brush"]),
                       "amount": num(0, 1, "raise only: pin how far up the radio is (0 down .. 1 at the mouth) instead "
                                           "of the radio mod's own eased raise.")}, ["pose"],
              "Arm pose. raise: Everest Radio's raise-to-face on the arm holding everest_radio:radio (main hand first), "
              "eased in client ticks (about 175 ms up, 300 ms down); any other pose, none included, lowers it. Without "
              "the radio mod, raise falls back to the vanilla horn pose. spyglass = to the eye."),
        typed("equip", {"slot": enum(["head", "chest", "legs", "feet", "mainhand", "offhand"]),
                        "item": {"type": "string", "pattern": ITEM_SPEC}}, ["slot", "item"], "Actors only."),
        typed("hood", {"down": boolean()}, ["down"], "Actors only."),
        typed("mask", {"on": boolean()}, ["on"], "Actors only."),
        typed("gravity", {"on": boolean()}, ["on"], "Off holds the walker at its height while it walks (a span its "
                                                     "physics won't hold up); on puts gravity back."),
        typed("teleport", {"pos": ref("vec3"), "yaw": YAW}, ["pos"]),
    ]},
    "trigger": {"oneOf": [
        typed("time", {"sec": num(0, 3600)}, ["sec"], "Seconds after the timeline starts (the take's first frame in shots)."),
        typed("after", {"cue": {"type": "string", "pattern": r"^([A-Za-z0-9_-]{1,32}\.)?[A-Za-z0-9_-]{1,32}$"},
                        "delaySec": num(0, 3600, default=0)}, ["cue"], "When a cue finishes (own id, or actorId.cueId)."),
        typed("near", {"pos": ref("vec3"), "radius": num(0.2, 1000, default=2)}, ["pos"], "When the actor comes within radius."),
    ]},
    "cue": obj({"id": {"type": "string", "pattern": ACTOR_ID}, "at": ref("trigger"), "action": ref("actorAction")}, ["action"]),
    "actor": obj({
        "id": {"type": "string", "pattern": ACTOR_ID, "description": '"player" directs the real player (no skin, pos or outfit).'},
        "skin": {"oneOf": [typed("player", {}, (), "The local player's skin."),
                           typed("file", {"path": {"type": "string"}, "model": enum(["wide", "slim"], default="wide")}, ["path"],
                                 "A 64x64 skin PNG.")]},
        "pos": ref("vec3"), "yaw": YAW, "pitch": PITCH,
        "outfit": obj({"suit": boolean(default=True),
                       "hoodStyle": {"type": "string", "default": "expedition",
                                     "pattern": "^((expedition|parka|forehead|goggles_down)|(expedition|parka|low|low_fur|sherpa"
                                                "|helmet)/(none|hood|forehead|eyes|neck))$",
                                     "description": "The look: \"shape/goggles\" (Everest Expedition 0.3.5+; shapes "
                                                    "expedition, parka, low, low_fur, sherpa, helmet; goggles none, hood, "
                                                    "forehead, eyes, neck) or an old name: expedition, parka, forehead, "
                                                    "goggles_down."},
                       "hoodDown": boolean(default=False), "mask": boolean(default=False),
                       "jacketRgb": integer(-1, 16777215, "Jacket dye colour (the hood takes it too); -1 for the undyed "
                                                          "gold suit with its black hood.", -1),
                       "pantsRgb": integer(-1, 16777215, "Pants dye colour; -1 undyed. Boots are never dyed.", -1),
                       "packRgb": integer(-1, 16777215, "Traveler's Backpack dye colour; -1 for no pack.", 13383222),
                       "mainhand": {"type": "string", "pattern": ITEM_SPEC}, "offhand": {"type": "string", "pattern": ITEM_SPEC}}),
        "gait": ref("gait"), "seed": integer(0, 2 ** 53, "Noise seed (wander, glances, breathers)."),
        "timeline": {"type": "array", "items": ref("cue"), "maxItems": 10000},
    }, ["id"]),
    "lodGate": obj({
        "enabled": boolean(default=True),
        "quietSec": num(0.5, 120, "Distant Horizons must have no work in flight (executors, retrieval and network request "
                                  "queues, render uploads, sections building) for this long.", 5),
        "stallSec": num(1, 600, "Sections still missing data or drawn coarser than wanted count as done once their number "
                                "has not changed for this long while DH is idle (nothing more to get, e.g. past the map edge).", 10),
        "minSec": num(0, 600, "Shortest settle at the start pose.", 3),
        "timeoutSec": num(1, 3600, "Give up after this long in total (warm pass included).", 300),
        "onTimeout": enum(["fail", "continue"], "fail: the render fails with TIMEOUT and the gate report in error.data; "
                                                 "continue: film anyway with a warning. Previews always continue.", "fail"),
        "warm": boolean("Visit points along the camera path first (the spectator rides along).", True),
        "warmSpacing": num(16, 5000, default=160), "warmPointMaxSec": num(1, 600, default=60),
        "warmMaxPoints": integer(1, 64, default=10),
        "warmQuietSec": num(0.5, 60, "How long DH must be idle before moving to the next warm point.", 2),
        "frameStability": boolean("Cross-check: the picture must hold still.", True),
        "frameThreshold": num(0.05, 20, "Mean block-brightness change (0..255) that counts as still.", 0.6),
        "frameQuietSec": num(0.5, 120, default=3),
        "stills": boolean("Write <shot>_gate_start.png and <shot>_gate_done.png next to the video, to check by eye that "
                          "done means detailed.", False),
        "nearRadius": num(100, 100000, "Missing or queued LOD positions within this many blocks of the camera count as "
                                       "still to come (farther ones never block: DH requests out to its whole radius).", 6000),
        "waitForSync": boolean("Also wait for the server sync-on-load queue (re-checks every cached LOD on arrival).", False),
        "frameMaxWaitSec": num(0, 600, "Once DH is ready, how long the picture gets to hold still (moving clouds and "
                                       "weather may never let it).", 8)}),
    "shot": obj({
        "name": {"type": "string", "pattern": "^[A-Za-z0-9_.-]{1,64}$"},
        "durationSec": {"type": "number", "minimum": 0.05, "maximum": 3600},
        "camera": ref("move"),
        "player": obj({"visible": boolean("Render the player model (default: not riding)."),
                       "ride": boolean("Move the spectator player along with the camera so chunks load around it.", False),
                       "gamemode": enum(["survival", "creative", "adventure", "spectator"]),
                       "pos": ref("vec3"), "yaw": YAW, "pitch": PITCH}),
        "hud": obj({"visible": boolean(), "chat": boolean(default=False), "crosshair": boolean(default=False),
                    "hand": boolean(default=True)}, ["visible"]),
        "shaders": boolean("Switch shaders on/off for this shot (omit to leave as is)."),
        "setup": {"type": "array", "items": ref("action"), "maxItems": 10000},
        "warmup": obj({"chunksTimeoutSec": num(0, 3600, default=45), "lodSettleSec": num(0, 600, default=4),
                       "minSec": num(0, 600, default=1), "scout": boolean(default=False),
                       "scoutSpeed": num(0.1, 20, default=1), "lod": ref("lodGate")}),
        "events": {"type": "array", "maxItems": 10000,
                   "items": obj({"atSec": num(0, 3600), "action": ref("action")}, ["atSec", "action"])},
        "teardown": {"type": "array", "items": ref("action"), "maxItems": 10000},
        "actors": {"type": "array", "items": ref("actor"), "maxItems": 64,
                   "description": "Spawned before warm-up; timelines start on the first frame; despawned after the take."},
        "keepActors": boolean("Leave the actors in the world after the take.", False),
        "particles": obj({"block": {"type": "array", "maxItems": 64, "items": {"type": "string", "minLength": 1},
                                    "description": "Particle class-name prefixes not drawn during the shot (runtime names: "
                                                   "intermediary for vanilla, e.g. net.minecraft.class_5685 = snowflake)."},
                          "clear": boolean("Clear live particles as the take starts.", True)},
                         desc="Keep particles out of a take (e.g. stray weather snow in a calm vista); world.particles "
                                     "lists what is live by class."),
    }, ["name", "durationSec", "camera"]),
    "render": obj({"width": integer(16, 7680, default=1920), "height": integer(16, 4320, default=1080),
                   "fps": integer(1, 240, default=60), "codec": enum(["hevc_nvenc", "h264_nvenc", "libx264", "libx265", "png"],
                                                                      default="hevc_nvenc"),
                   "quality": integer(0, 51, "Constant QP (NVENC) or CRF (x264/x265).", 18),
                   "tenBit": boolean("HEVC Main10 (hevc_nvenc, libx265 only)."),
                   "serverTickSync": boolean("Slow the server to the render speed so mobs and weather keep pace.", False),
                   "sheetFrames": integer(0, 24, default=6),
                   "offscreen": enum(["auto", "always", "never"], "auto: resize the window, and render offscreen at the "
                                     "requested size if the screen cannot hold it (e.g. 3840x2160 on a smaller screen); the "
                                     "window shows the frame scaled. always: offscreen. never: record at the window size.",
                                     "auto")}),
    "preview": obj({"scale": num(0.1, 1, default=0.5), "stills": integer(1, 64, default=4), "video": boolean(default=False),
                    "fps": integer(1, 60, default=20)}),
}


def schema(root, title):
    s = {"$schema": "https://json-schema.org/draft/2020-12/schema", "title": title, "$defs": DEFS}
    s.update(root)
    return s


def main():
    files = {
        "shot.schema.json": schema(ref("shot"), "Cinema shot"),
        "shots.schema.json": schema(obj({
            "$schema": {"type": "string"}, "version": {"const": 1}, "render": ref("render"),
            "setup": {"type": "array", "items": ref("action")}, "shots": {"type": "array", "items": ref("shot"), "minItems": 1},
            "teardown": {"type": "array", "items": ref("action")}}, ["version", "shots"]), "Cinema batch shots file"),
        "move.schema.json": schema(ref("move"), "Cinema camera move"),
        "actor.schema.json": schema(ref("actor"), "Cinema actor"),
        "lodGate.schema.json": schema(ref("lodGate"), "Cinema LOD-ready gate"),
        "render.schema.json": schema(ref("render"), "Cinema render settings"),
        "preview.schema.json": schema(ref("preview"), "Cinema preview settings"),
    }
    for name, s in files.items():
        with open(os.path.join(HERE, name), "w", encoding="utf-8") as f:
            json.dump(s, f, indent=2)
            f.write("\n")
        print("wrote", name)


if __name__ == "__main__":
    main()
