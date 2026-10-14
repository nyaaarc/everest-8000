"""Python client for the Cinema mod's JSON-RPC API (Minecraft 1.21.1, Fabric).

The mod listens on 127.0.0.1 (port and token in <instance>/minecraft/config/everest-cinema.json). Every method of the
API has a typed wrapper on :class:`Cinema`; the builder functions below make the JSON objects the API takes (targets,
keyframes, camera moves, actions, shots) so scripts never hand-write dicts. Errors raise :class:`CinemaError` with the
API's code, message and data.

    from cinema import Cinema, point, orbit, shot
    c = Cinema.from_instance(r"C:\\...\\instances\\Everest Cinema")
    c.wait_ready()
    c.snapshot()
    s = shot("summit_orbit", 8, orbit(point(20706, 2031, 7774), radius=60, height=25, start_deg=0, end_deg=90),
             player={"visible": False, "ride": True})
    print(c.render(s, r"D:\\renders", render={"width": 2560, "height": 1440}))
    c.restore()

Conventions: positions are [x, y, z] in blocks; yaw/pitch are Minecraft degrees (yaw 0 = facing south/+Z, 90 = west,
180 = north, -90 = east; pitch positive looks down); fov is the vertical field of view in degrees; times in seconds.
"""
from __future__ import annotations

import json
import os
import socket
import time
from typing import Any, Dict, Iterable, List, Optional, Sequence, Union

Vec3 = Sequence[float]
JSON = Dict[str, Any]

# JSON-RPC error codes (see README)
PARSE_ERROR = -32700
INVALID_REQUEST = -32600
METHOD_NOT_FOUND = -32601
INVALID_PARAMS = -32602
INTERNAL = -32603
NOT_IN_WORLD = -32000
UNAUTHORIZED = -32001
BUSY = -32002
TIMEOUT = -32003
COMMAND_FAILED = -32004
CAPTURE_FAILED = -32005
NO_SNAPSHOT = -32006
CANCELLED = -32007
UNSUPPORTED = -32008
NOT_FOUND = -32009
STUCK = -32010


class CinemaError(Exception):
    """An error returned by the API (or a connection problem, code None)."""

    def __init__(self, code: Optional[int], message: str, data: Any = None):
        super().__init__(f"[{code}] {message}" if code is not None else message)
        self.code = code
        self.message = message
        self.data = data


def _v3(v: Vec3) -> List[float]:
    if len(v) != 3:
        raise ValueError(f"expected [x, y, z], got {v!r}")
    return [float(v[0]), float(v[1]), float(v[2])]


def _clean(d: JSON) -> JSON:
    """Drops None values so optional parameters fall back to the API defaults."""
    return {k: v for k, v in d.items() if v is not None}


class Cinema:
    """A connection to the game. Calls are synchronous; long ones block until done (pass wait=False where offered)."""

    def __init__(self, port: int = 25580, token: Optional[str] = None, host: str = "127.0.0.1",
                 timeout: Optional[float] = None):
        self.host, self.port, self.token, self.timeout = host, port, token, timeout
        self._sock: Optional[socket.socket] = None
        self._file = None
        self._id = 0
        self.info: Optional[JSON] = None

    @classmethod
    def from_instance(cls, instance_dir: str, **kw) -> "Cinema":
        """Reads port and token from <instance_dir>/minecraft/config/everest-cinema.json (or <dir>/config/...)."""
        for p in (os.path.join(instance_dir, "minecraft", "config", "everest-cinema.json"),
                  os.path.join(instance_dir, "config", "everest-cinema.json")):
            if os.path.exists(p):
                with open(p, encoding="utf-8") as f:
                    cfg = json.load(f)
                return cls(port=cfg.get("port", 25580), token=cfg.get("token") or None, **kw)
        raise FileNotFoundError(f"no config/everest-cinema.json under {instance_dir}")

    # ---- transport

    def connect(self) -> JSON:
        """Opens the socket and authenticates (session.hello). Returns the API info (version, methods)."""
        self.close()
        self._sock = socket.create_connection((self.host, self.port), timeout=10)
        self._sock.settimeout(self.timeout)
        self._file = self._sock.makefile("rwb")
        self.info = self._call("session.hello", _clean({"token": self.token}))
        return self.info

    def close(self) -> None:
        if self._sock:
            try:
                self._sock.close()
            except OSError:
                pass
        self._sock = None
        self._file = None

    def _call(self, method: str, params: JSON) -> Any:
        self._id += 1
        req = {"jsonrpc": "2.0", "id": self._id, "method": method, "params": params}
        try:
            self._file.write((json.dumps(req) + "\n").encode("utf-8"))
            self._file.flush()
            line = self._file.readline()
        except OSError as e:
            self.close()
            raise CinemaError(None, f"connection lost during {method}: {e}") from e
        if not line:
            self.close()
            raise CinemaError(None, f"connection closed during {method} (did the game exit?)")
        resp = json.loads(line)
        if "error" in resp:
            e = resp["error"]
            raise CinemaError(e.get("code"), e.get("message", ""), e.get("data"))
        return resp.get("result")

    def call(self, method: str, **params: Any) -> Any:
        """Calls any method by name with keyword parameters (None values are omitted)."""
        if self._file is None:
            self.connect()
        return self._call(method, _clean(params))

    def wait_ready(self, timeout: float = 600.0, poll: float = 2.0) -> JSON:
        """Waits until the API answers and the player is in a world. Returns state.get."""
        deadline = time.time() + timeout
        last = None
        while time.time() < deadline:
            try:
                if self._file is None:
                    self.connect()
                st = self.state()
                if st.get("inWorld"):
                    return st
                last = f"screen {st.get('screen')}"
            except (OSError, CinemaError) as e:
                last = str(e)
                self.close()
            time.sleep(poll)
        raise CinemaError(TIMEOUT, f"game not ready after {timeout:.0f} s ({last})")

    # ---- state

    def state(self) -> JSON:
        """Camera, player, chunks, LOD, fps, render progress, window, world time and weather."""
        return self.call("state.get")

    def snapshot(self) -> JSON:
        """Records a restore point (player position/mode/inventory, time, weather, tick rate, client options)."""
        return self.call("state.snapshot")

    def restore(self) -> JSON:
        """Puts back everything from the last snapshot. Returns {restored: [...], warnings: [...]}."""
        return self.call("state.restore")

    def screen(self) -> JSON:
        """The open screen: class, GUI size and scale, and its widgets (message and bounds in GUI coordinates)."""
        return self.call("screen.get")

    def click(self, x: float, y: float, button: int = 0) -> JSON:
        """Clicks the open screen at GUI coordinates (window pixels / guiScale)."""
        return self.call("screen.click", x=x, y=y, button=button)

    def close_screen(self) -> JSON:
        """Closes whatever screen or menu is open; returns {closed: class name or None}."""
        return self.call("screen.close")

    def quit(self) -> JSON:
        """Closes the game (after replying)."""
        return self.call("game.quit")

    # ---- camera

    def set_pose(self, pos: Vec3, yaw: float, pitch: float, roll: float = 0.0, fov: float = 70.0) -> JSON:
        """Detaches the camera and holds it at this pose."""
        return self.call("camera.setPose", pos=_v3(pos), yaw=yaw, pitch=pitch, roll=roll, fov=fov)

    def get_pose(self) -> JSON:
        return self.call("camera.getPose")

    def look_at(self, target: JSON, fov: float = 70.0, look_smooth_sec: float = 0.0) -> JSON:
        """Keeps the camera where it is and aims it at a target (tracking it if it moves)."""
        return self.call("camera.lookAt", target=target, fov=fov, lookSmoothSec=look_smooth_sec)

    def play(self, move: JSON, wait: bool = False) -> JSON:
        """Plays any camera move (see the move builders). With wait, returns when it ends."""
        return self.call("camera.play", move=move, wait=wait)

    def orbit(self, center: JSON, radius: float, start_deg: float, end_deg: float, duration_sec: float,
              height: float = 0.0, wait: bool = False, **extra: Any) -> JSON:
        """Circles a target. Extra keys: radiusEnd, heightEnd, easing, lookAt, lookSmoothSec, roll, fov, fovEnd, shake."""
        return self.call("camera.orbit", center=center, radius=radius, startDeg=start_deg, endDeg=end_deg,
                         durationSec=duration_sec, height=height, wait=wait, **extra)

    def dolly(self, target: JSON, azimuth_deg: float, distance_start: float, distance_end: float, duration_sec: float,
              elevation_deg: float = 0.0, wait: bool = False, **extra: Any) -> JSON:
        return self.call("camera.dolly", target=target, azimuthDeg=azimuth_deg, distanceStart=distance_start,
                         distanceEnd=distance_end, durationSec=duration_sec, elevationDeg=elevation_deg, wait=wait, **extra)

    def truck(self, target: JSON, azimuth_deg: float, distance: float, slide_start: float, slide_end: float,
              duration_sec: float, elevation_deg: float = 0.0, track: bool = False, wait: bool = False, **extra: Any) -> JSON:
        return self.call("camera.truck", target=target, azimuthDeg=azimuth_deg, distance=distance, slideStart=slide_start,
                         slideEnd=slide_end, durationSec=duration_sec, elevationDeg=elevation_deg, track=track, wait=wait, **extra)

    def follow(self, target: JSON, offset: Vec3, space: str = "world", smooth_sec: float = 0.25,
               duration_sec: Optional[float] = None, wait: bool = False, **extra: Any) -> JSON:
        return self.call("camera.follow", target=target, offset=_v3(offset), space=space, smoothSec=smooth_sec,
                         durationSec=duration_sec, wait=wait, **extra)

    def first_person(self, fov: Optional[float] = None, duration_sec: Optional[float] = None) -> JSON:
        return self.call("camera.firstPerson", fov=fov, durationSec=duration_sec)

    def release(self) -> JSON:
        """Back to the normal player camera."""
        return self.call("camera.release")

    def path_play(self, keyframes: List[JSON], interpolation: str = "catmullRom", easing: str = "linear",
                  duration_sec: Optional[float] = None, constant_speed: bool = False, look_at: Optional[JSON] = None,
                  fov: Optional[float] = None, shake: Optional[JSON] = None, wait: bool = False) -> JSON:
        """Flies through keyframes (see key()). Times come from the keys' t, or spread over duration_sec by distance."""
        return self.call("path.play", keyframes=keyframes, interpolation=interpolation, easing=easing,
                         durationSec=duration_sec, constantSpeed=constant_speed, lookAt=look_at, fov=fov, shake=shake,
                         wait=wait)

    def path_stop(self) -> JSON:
        return self.call("path.stop")

    def path_status(self) -> JSON:
        return self.call("path.status")

    # ---- player (puppet)

    def player(self) -> JSON:
        return self.call("player.get")

    def move_to(self, pos: Vec3, speed: str = "walk", arrive_radius: float = 0.6, wait: bool = True) -> JSON:
        return self.call("player.moveTo", pos=_v3(pos), speed=speed, arriveRadius=arrive_radius, wait=wait)

    def walk_path(self, points: Iterable[Vec3], speed: str = "walk", arrive_radius: float = 0.6, wait: bool = True) -> JSON:
        return self.call("player.walkPath", points=[_v3(p) for p in points], speed=speed, arriveRadius=arrive_radius, wait=wait)

    def player_look(self, yaw: float, pitch: float, duration_sec: float = 0.0, easing: str = "sineInOut", wait: bool = True) -> JSON:
        return self.call("player.look", yaw=yaw, pitch=pitch, durationSec=duration_sec, easing=easing, wait=wait)

    def player_look_at(self, target: JSON, duration_sec: float = 0.0, easing: str = "sineInOut", wait: bool = True) -> JSON:
        return self.call("player.lookAt", target=target, durationSec=duration_sec, easing=easing, wait=wait)

    def set_input(self, forward: float = 0.0, strafe: float = 0.0, jump: bool = False, sneak: bool = False,
                  sprint: bool = False, use: bool = False) -> JSON:
        return self.call("player.setInput", forward=forward, strafe=strafe, jump=jump, sneak=sneak, sprint=sprint, use=use)

    def clear_input(self) -> JSON:
        return self.call("player.clearInput")

    def player_stop(self) -> JSON:
        return self.call("player.stop")

    def jump(self) -> JSON:
        return self.call("player.jump")

    def swing(self, hand: str = "main") -> JSON:
        return self.call("player.swing", hand=hand)

    def equip(self, slot: str, item: str, count: int = 1) -> JSON:
        """slot: head, chest, legs, feet, mainhand, offhand, hotbar.0-8, inventory.0-26."""
        return self.call("player.equip", slot=slot, item=item, count=count)

    def clear_slot(self, slot: str) -> JSON:
        return self.call("player.clearSlot", slot=slot)

    def select_slot(self, slot: int) -> JSON:
        return self.call("player.selectSlot", slot=slot)

    def teleport(self, pos: Vec3, yaw: Optional[float] = None, pitch: Optional[float] = None) -> JSON:
        return self.call("player.teleport", pos=_v3(pos), yaw=yaw, pitch=pitch)

    def gamemode(self, mode: str) -> JSON:
        return self.call("player.setGamemode", mode=mode)

    def set_visible(self, visible: bool) -> JSON:
        return self.call("player.setVisible", visible=visible)

    def ride(self, enabled: bool) -> JSON:
        """Moves the (spectator) player along with the camera so chunks load around the camera."""
        return self.call("player.ride", enabled=enabled)

    def set_frozen(self, fraction: float, duration_sec: float = 0.0) -> JSON:
        return self.call("player.setFrozen", fraction=fraction, durationSec=duration_sec)

    def perspective(self, mode: str) -> JSON:
        return self.call("player.perspective", mode=mode)

    # ---- world

    def command(self, command: str, timeout_sec: float = 2.0, quiet_ms: int = 250, fail_on_error: bool = False) -> JSON:
        """Runs a command as the player; returns {feedback: [{text, translationKey, args, error}], ok}."""
        return self.call("world.command", command=command, timeoutSec=timeout_sec, quietMs=quiet_ms, failOnError=fail_on_error)

    def time(self, day_time: Optional[int] = None, freeze: Optional[bool] = None) -> JSON:
        return self.call("world.time", dayTime=day_time, freeze=freeze)

    def weather(self, weather: str) -> JSON:
        return self.call("world.weather", weather=weather)

    def tick_rate(self, rate: float) -> JSON:
        return self.call("world.tickRate", rate=rate)

    def wait_loaded(self, timeout_sec: float = 60.0, include_lods: bool = True, lod_settle_sec: float = 4.0) -> JSON:
        return self.call("world.waitLoaded", timeoutSec=timeout_sec, includeLods=include_lods, lodSettleSec=lod_settle_sec)

    def find_entities(self, entity_type: Optional[str] = None, max_distance: float = 64.0, limit: int = 20) -> JSON:
        return self.call("world.findEntities", entityType=entity_type, maxDistance=max_distance, limit=limit)

    # ---- actions

    def run_actions(self, actions: List[JSON]) -> JSON:
        """Runs shot actions (see the a_* builders) in order, waiting for each; returns {results: [...]}."""
        return self.call("actions.run", actions=actions)

    # ---- director (client-side actors and the directed player)

    def spawn_actor(self, actor_spec: JSON, start: bool = True) -> JSON:
        """Spawns a client-side actor (see actor()); start False keeps it idle until run_actor. Returns its state + warnings."""
        return self.call("actor.spawn", actor=actor_spec, start=start)

    def despawn_actor(self, actor_id: str) -> JSON:
        return self.call("actor.despawn", id=actor_id)

    def clear_actors(self) -> JSON:
        return self.call("actor.clear")

    def actors(self) -> JSON:
        return self.call("actor.list")

    def actor_state(self, actor_id: str) -> JSON:
        """Pose, walking/breather state, cue states and a short log."""
        return self.call("actor.get", id=actor_id)

    def run_actor(self, actor_id: str, timeline: List[JSON], wait: bool = False, timeout_sec: float = 600) -> JSON:
        """Replaces an actor's timeline and starts it now. actor_id "player" directs the real player."""
        return self.call("actor.run", id=actor_id, timeline=timeline, wait=wait, timeoutSec=timeout_sec)

    def actor_do(self, actor_id: str, action: JSON, wait: bool = True, timeout_sec: float = 300) -> JSON:
        """Runs one actor action now (see the act_* builders); with wait, returns when it finishes."""
        return self.call("actor.do", id=actor_id, action=action, wait=wait, timeoutSec=timeout_sec)

    def path(self, from_pos: Vec3, to_pos: Vec3, max_nodes: int = 60000, max_drop: int = 3, simplify: bool = True) -> JSON:
        """A* walking route over the loaded blocks: {status: found|partial|no_start|no_path, points, expanded, ms}."""
        return self.call("nav.path", **{"from": _v3(from_pos)}, to=_v3(to_pos), maxNodes=max_nodes, maxDrop=max_drop,
                         simplify=simplify)

    # ---- LOD gate

    def await_lod_ready(self, path: Optional[Iterable[Vec3]] = None, ride: bool = True, fov: float = 70,
                        stills_base: Optional[str] = None, **gate: Any) -> JSON:
        """Warm pass along path (camera + riding spectator), then waits at path[0] until Distant Horizons reports nothing
        in flight and nothing more coming (see lod_gate() for the keys, camelCase here: quietSec, stallSec, timeoutSec,
        onTimeout, ...). stills_base (absolute path prefix) with stills=True writes <prefix>_gate_start/_done.png.
        Returns {status, elapsedSec, warmSec, settleSec, dhIdleAfterSec, waitedOn, warm, chunks, dh, frame, timeline};
        on timeout with onTimeout "fail" (default) raises CinemaError TIMEOUT with that report in .data."""
        return self.call("world.awaitLodReady", path=[_v3(p) for p in path] if path else None, ride=ride, fov=fov,
                         stillsBase=os.path.abspath(stills_base) if stills_base else None, **gate)

    def lod_status(self) -> JSON:
        """Distant Horizons' progress: {available, activity, outstanding, executors, retrieval, network, renderTasksQueued,
        quadtree: {sectionsEnabled, sectionsBuilding, sectionsCoarse, missingPositions, queuedPositions, sectionsToReload},
        f3, frameDiff, serverPositionCorrections}."""
        return self.call("world.lodStatus")

    # ---- display

    def hud(self, visible: bool, chat: bool = False, crosshair: bool = True, hand: bool = True) -> JSON:
        return self.call("hud.set", visible=visible, chat=chat, crosshair=crosshair, hand=hand)

    def shaders(self, enabled: bool) -> JSON:
        return self.call("shaders.set", enabled=enabled)

    def display(self, width: Optional[int] = None, height: Optional[int] = None, render_distance: Optional[int] = None,
                fov: Optional[int] = None, bob_view: Optional[bool] = None, gui_scale: Optional[int] = None) -> JSON:
        return self.call("display.set", width=width, height=height, renderDistance=render_distance, fov=fov,
                         bobView=bob_view, guiScale=gui_scale)

    # ---- capture

    def frame(self, path: str) -> JSON:
        """Saves the next rendered frame as a PNG (absolute path)."""
        return self.call("capture.frame", path=os.path.abspath(path))

    def capture_start(self, path: str, render: Optional[JSON] = None) -> JSON:
        """Starts frame-stepped recording of whatever happens next (absolute .mp4 path, or a folder for codec png)."""
        return self.call("capture.start", path=os.path.abspath(path), render=render)

    def capture_stop(self) -> JSON:
        return self.call("capture.stop")

    def capture_status(self) -> JSON:
        return self.call("capture.status")

    def render(self, shot_spec: JSON, out_dir: str, render: Optional[JSON] = None, preview: Optional[JSON] = None) -> JSON:
        """Renders one shot (blocking): warm-up, frame-stepped recording, events, teardown. Returns paths and stats."""
        return self.call("capture.render", shot=shot_spec, outDir=os.path.abspath(out_dir), render=render, preview=preview)

    def cancel(self) -> JSON:
        return self.call("capture.cancel")


# ---------------------------------------------------------------- builders: targets

def point(x: float, y: float, z: float, offset: Optional[Vec3] = None) -> JSON:
    return _clean({"type": "point", "pos": [x, y, z], "offset": _v3(offset) if offset else None})


def player_target(anchor: str = "eyes", offset: Optional[Vec3] = None) -> JSON:
    """The local player; anchor feet | center | eyes."""
    return _clean({"type": "player", "anchor": anchor, "offset": _v3(offset) if offset else None})


def entity(uuid: str, anchor: str = "eyes", offset: Optional[Vec3] = None) -> JSON:
    return _clean({"type": "entity", "uuid": uuid, "anchor": anchor, "offset": _v3(offset) if offset else None})


def nearest(entity_type: str, max_distance: float = 64.0, anchor: str = "eyes", offset: Optional[Vec3] = None) -> JSON:
    """The nearest entity of a type to the player (locked on for the rest of the move)."""
    return _clean({"type": "nearestEntity", "entityType": entity_type, "maxDistance": max_distance, "anchor": anchor,
                   "offset": _v3(offset) if offset else None})


# ---------------------------------------------------------------- builders: camera moves

def key(pos: Vec3, t: Optional[float] = None, yaw: Optional[float] = None, pitch: Optional[float] = None,
        look_at: Optional[JSON] = None, roll: float = 0.0, fov: Optional[float] = None,
        relative_to: Optional[JSON] = None) -> JSON:
    """A keyframe: give yaw+pitch or look_at (or neither if the move has its own look_at)."""
    return _clean({"t": t, "pos": _v3(pos), "yaw": yaw, "pitch": pitch, "lookAt": look_at, "roll": roll or None,
                   "fov": fov, "relativeTo": relative_to})


def keyframes(keys: List[JSON], interpolation: str = "catmullRom", easing: str = "linear",
              duration_sec: Optional[float] = None, constant_speed: bool = False, look_at: Optional[JSON] = None,
              look_smooth_sec: float = 0.0, fov: float = 70.0, shake: Optional[JSON] = None) -> JSON:
    return _clean({"type": "keyframes", "keyframes": keys, "interpolation": interpolation, "easing": easing,
                   "durationSec": duration_sec, "constantSpeed": constant_speed or None, "lookAt": look_at,
                   "lookSmoothSec": look_smooth_sec or None, "fov": fov, "shake": shake})


def static(pos: Vec3, yaw: Optional[float] = None, pitch: Optional[float] = None, look_at: Optional[JSON] = None,
           roll: float = 0.0, fov: float = 70.0, shake: Optional[JSON] = None) -> JSON:
    return _clean({"type": "static", "pos": _v3(pos), "yaw": yaw, "pitch": pitch, "lookAt": look_at,
                   "roll": roll or None, "fov": fov, "shake": shake})


def orbit(center: JSON, radius: float, start_deg: float, end_deg: float, height: float = 0.0,
          radius_end: Optional[float] = None, height_end: Optional[float] = None, easing: str = "linear",
          look_at: Optional[JSON] = None, fov: float = 70.0, fov_end: Optional[float] = None, roll: float = 0.0,
          duration_sec: Optional[float] = None, shake: Optional[JSON] = None) -> JSON:
    """Bearings are from the center to the camera, as Minecraft yaw (0 = camera south of the center)."""
    return _clean({"type": "orbit", "center": center, "radius": radius, "radiusEnd": radius_end, "height": height,
                   "heightEnd": height_end, "startDeg": start_deg, "endDeg": end_deg, "easing": easing,
                   "lookAt": look_at, "fov": fov, "fovEnd": fov_end, "roll": roll or None, "durationSec": duration_sec,
                   "shake": shake})


def dolly(target: JSON, azimuth_deg: float, distance_start: float, distance_end: float, elevation_deg: float = 0.0,
          easing: str = "linear", fov: float = 70.0, fov_end: Optional[float] = None, roll: float = 0.0,
          duration_sec: Optional[float] = None, shake: Optional[JSON] = None) -> JSON:
    return _clean({"type": "dolly", "target": target, "azimuthDeg": azimuth_deg, "elevationDeg": elevation_deg,
                   "distanceStart": distance_start, "distanceEnd": distance_end, "easing": easing, "fov": fov,
                   "fovEnd": fov_end, "roll": roll or None, "durationSec": duration_sec, "shake": shake})


def truck(target: JSON, azimuth_deg: float, distance: float, slide_start: float, slide_end: float,
          elevation_deg: float = 0.0, track: bool = False, easing: str = "linear", fov: float = 70.0,
          fov_end: Optional[float] = None, roll: float = 0.0, duration_sec: Optional[float] = None,
          shake: Optional[JSON] = None) -> JSON:
    """Slides past a target; positive slide is to the camera's right."""
    return _clean({"type": "truck", "target": target, "azimuthDeg": azimuth_deg, "elevationDeg": elevation_deg,
                   "distance": distance, "slideStart": slide_start, "slideEnd": slide_end, "track": track or None,
                   "easing": easing, "fov": fov, "fovEnd": fov_end, "roll": roll or None, "durationSec": duration_sec,
                   "shake": shake})


def follow(target: JSON, offset: Vec3, space: str = "world", smooth_sec: float = 0.25, look_at: Optional[JSON] = None,
           look_smooth_sec: float = 0.0, fov: float = 70.0, roll: float = 0.0, duration_sec: Optional[float] = None,
           shake: Optional[JSON] = None) -> JSON:
    """space targetYaw: offset is [left, up, forward] relative to the target's facing."""
    return _clean({"type": "follow", "target": target, "offset": _v3(offset), "space": space, "smoothSec": smooth_sec,
                   "lookAt": look_at, "lookSmoothSec": look_smooth_sec or None, "fov": fov, "roll": roll or None,
                   "durationSec": duration_sec, "shake": shake})


def first_person(fov: Optional[float] = None, duration_sec: Optional[float] = None, shake: Optional[JSON] = None) -> JSON:
    return _clean({"type": "firstPerson", "fov": fov, "durationSec": duration_sec, "shake": shake})


def shake(amplitude_deg: float, frequency_hz: float = 0.5) -> JSON:
    return {"amplitudeDeg": amplitude_deg, "frequencyHz": frequency_hz}


# ---------------------------------------------------------------- builders: actions

def a_command(command: str) -> JSON:
    return {"type": "command", "command": command}


def a_wait(seconds: float) -> JSON:
    return {"type": "wait", "seconds": seconds}


def a_wait_loaded(timeout_sec: float = 60, include_lods: bool = True, lod_settle_sec: float = 4) -> JSON:
    return {"type": "waitLoaded", "timeoutSec": timeout_sec, "includeLods": include_lods, "lodSettleSec": lod_settle_sec}


def a_teleport(pos: Vec3, yaw: Optional[float] = None, pitch: Optional[float] = None) -> JSON:
    return _clean({"type": "teleport", "pos": _v3(pos), "yaw": yaw, "pitch": pitch})


def a_gamemode(mode: str) -> JSON:
    return {"type": "gamemode", "mode": mode}


def a_time(day_time: int, freeze: bool = True) -> JSON:
    return {"type": "time", "dayTime": int(day_time), "freeze": freeze}


def a_weather(weather: str) -> JSON:
    return {"type": "weather", "weather": weather}


def a_tick_rate(rate: float) -> JSON:
    return {"type": "tickRate", "rate": rate}


def a_equip(slot: str, item: str, count: int = 1) -> JSON:
    return {"type": "equip", "slot": slot, "item": item, "count": count}


def a_clear_slot(slot: str) -> JSON:
    return {"type": "clearSlot", "slot": slot}


def a_select_slot(slot: int) -> JSON:
    return {"type": "selectSlot", "slot": slot}


def a_walk(points: Iterable[Vec3], speed: str = "walk", arrive_radius: float = 0.6) -> JSON:
    return {"type": "walk", "points": [_v3(p) for p in points], "speed": speed, "arriveRadius": arrive_radius}


def a_look(yaw: float, pitch: float, duration_sec: float = 0.0, easing: str = "sineInOut") -> JSON:
    return {"type": "look", "yaw": yaw, "pitch": pitch, "durationSec": duration_sec, "easing": easing}


def a_look_at(target: JSON, duration_sec: float = 0.0, easing: str = "sineInOut") -> JSON:
    return {"type": "lookAt", "target": target, "durationSec": duration_sec, "easing": easing}


def a_input(forward: float = 0.0, strafe: float = 0.0, jump: bool = False, sneak: bool = False, sprint: bool = False,
            use: bool = False) -> JSON:
    return {"type": "input", "forward": forward, "strafe": strafe, "jump": jump, "sneak": sneak, "sprint": sprint, "use": use}


def a_clear_input() -> JSON:
    return {"type": "clearInput"}


def a_jump() -> JSON:
    return {"type": "jump"}


def a_swing(hand: str = "main") -> JSON:
    return {"type": "swing", "hand": hand}


def a_stop() -> JSON:
    return {"type": "stop"}


def a_frozen(fraction: float, duration_sec: float = 0.0) -> JSON:
    return {"type": "frozen", "fraction": fraction, "durationSec": duration_sec}


def a_hud(visible: bool, chat: bool = False, crosshair: bool = False, hand: bool = True) -> JSON:
    return {"type": "hud", "visible": visible, "chat": chat, "crosshair": crosshair, "hand": hand}


def a_shaders(enabled: bool) -> JSON:
    return {"type": "shaders", "enabled": enabled}


def a_player_visible(visible: bool) -> JSON:
    return {"type": "playerVisible", "visible": visible}


def at(seconds: float, action: JSON) -> JSON:
    """A timed event for a shot."""
    return {"atSec": seconds, "action": action}


# ---------------------------------------------------------------- builders: director

def actor_target(actor_id: str, anchor: str = "eyes", offset: Optional[Vec3] = None) -> JSON:
    return _clean({"type": "actor", "id": actor_id, "anchor": anchor, "offset": _v3(offset) if offset else None})


def gait(pace: float = 0.75, turn_rate_deg_per_sec: float = 150, accel_sec: float = 0.6, wander: float = 1.2,
         wander_wavelength: float = 18, lookahead: float = 2.2, uphill_slowdown: float = 0.45, snow_slowdown: float = 0.35,
         breathers: Optional[Sequence[float]] = None, breather_slope: float = 1.0,
         breather_altitude: Optional[Sequence[Sequence[float]]] = None,
         pace_altitude: Optional[Sequence[Sequence[float]]] = None, idle_look: bool = True) -> JSON:
    """A gait (schema $defs/gait).

    breathers: (everyMinSec, everyMaxSec[, minSec, maxSec]) or None for none. The "every" values are effort between
    breathers in seconds of walking at rate 1; breather_altitude [(y, rate), ...] and the slope (rate x (1 + k x grade))
    multiply the rate, so stops come more often higher up and on climbs. pace_altitude [(y, factor), ...] slows the
    trudge with height. Both curves are piecewise linear by increasing y.
    """
    g = {"pace": pace, "turnRateDegPerSec": turn_rate_deg_per_sec, "accelSec": accel_sec, "wander": wander,
         "wanderWavelength": wander_wavelength, "lookahead": lookahead, "uphillSlowdown": uphill_slowdown,
         "snowSlowdown": snow_slowdown, "idleLook": idle_look}
    if breathers:
        b = {"everyMinSec": breathers[0], "everyMaxSec": breathers[1], "slopeFactor": breather_slope}
        if len(breathers) > 2:
            b["minSec"], b["maxSec"] = breathers[2], breathers[3]
        if breather_altitude:
            b["altitude"] = [{"y": y, "rate": r} for y, r in breather_altitude]
        g["breathers"] = b
    if pace_altitude:
        g["paceAltitude"] = [{"y": y, "factor": f} for y, f in pace_altitude]
    return g


def everest_gait(**overrides: Any) -> JSON:
    """The Mt. Everest 8000 climber: a steady trudge that slows with height, breathers every ~22-40 s of effort near
    Camp 4 and above (2-5 s each), rare below Base Camp. Heights are the map's: Base Camp ~1200, South Col ~1555,
    summit ~2000. The same curve the offline demo charts (build/demo/two_actors_two_seeds.png)."""
    kw: Dict[str, Any] = dict(pace=0.7, turn_rate_deg_per_sec=140, accel_sec=0.7, lookahead=2.4,
                              breathers=(22, 40, 2.0, 5.0), breather_slope=0.8,
                              breather_altitude=[(1200, 0.25), (1555, 1.0), (1800, 1.3), (1980, 1.8)],
                              pace_altitude=[(1200, 1.0), (1555, 0.8), (1800, 0.68), (2000, 0.58)])
    kw.update(overrides)
    return gait(**kw)


# Everest Expedition suit colourways: (jacket+hood / pants colour, matching pack colour). "gold" is the undyed suit.
COLOURWAYS = {"gold": (-1, 14262811), "red": (12855844, 13383222), "orange": (15755802, 15234090),
              "cobalt": (2841796, 3039144), "teal": (2071194, 2792086), "green": (4098618, 4885054),
              "violet": (6963120, 7358640), "black": (2895155, 3421500)}


def outfit(suit: bool = True, hood_style: str = "expedition", hood_down: bool = False, mask: bool = False,
           pack_rgb: int = 13383222, mainhand: Optional[str] = None, offhand: Optional[str] = None,
           jacket_rgb: int = -1, pants_rgb: int = -1, colourway: Optional[str] = None) -> JSON:
    """hood_style: "shape/goggles" (shapes expedition parka low low_fur sherpa helmet; goggles none hood forehead eyes
    neck) or expedition | parka | forehead | goggles_down. jacket_rgb / pants_rgb: dye colours, -1 undyed (the gold
    suit). colourway (a COLOURWAYS name) sets jacket, pants and pack together. pack_rgb -1 for no pack."""
    if colourway:
        suit_rgb, pack = COLOURWAYS[colourway]
        jacket_rgb = pants_rgb = suit_rgb
        pack_rgb = pack
    return _clean({"suit": suit, "hoodStyle": hood_style, "hoodDown": hood_down, "mask": mask, "packRgb": pack_rgb,
                   "jacketRgb": jacket_rgb if jacket_rgb >= 0 else None, "pantsRgb": pants_rgb if pants_rgb >= 0 else None,
                   "mainhand": mainhand, "offhand": offhand})


def skin_file(path: str, model: str = "wide") -> JSON:
    return {"type": "file", "path": os.path.abspath(path), "model": model}


def skin_player() -> JSON:
    return {"type": "player"}


def cue(action: JSON, at_sec: Optional[float] = None, after: Optional[str] = None, delay_sec: float = 0.0,
        near: Optional[Vec3] = None, radius: float = 2.0, cue_id: Optional[str] = None) -> JSON:
    """One timeline entry: start at a time, after another cue (own id or actorId.cueId), or when near a point."""
    trig = None
    if at_sec is not None:
        trig = {"type": "time", "sec": at_sec}
    elif after is not None:
        trig = {"type": "after", "cue": after, "delaySec": delay_sec}
    elif near is not None:
        trig = {"type": "near", "pos": _v3(near), "radius": radius}
    return _clean({"id": cue_id, "at": trig, "action": action})


def actor(actor_id: str, pos: Optional[Vec3] = None, yaw: float = 0.0, pitch: float = 0.0, skin: Optional[JSON] = None,
          outfit_spec: Optional[JSON] = None, gait_spec: Optional[JSON] = None, seed: Optional[int] = None,
          timeline: Optional[List[JSON]] = None) -> JSON:
    """An actor spec; actor_id "player" directs the real player (then pos/skin/outfit must be None)."""
    if actor_id == "player":
        return _clean({"id": actor_id, "gait": gait_spec, "seed": seed, "timeline": timeline or None})
    return _clean({"id": actor_id, "skin": skin, "pos": _v3(pos), "yaw": yaw, "pitch": pitch or None, "outfit": outfit_spec,
                   "gait": gait_spec, "seed": seed, "timeline": timeline or None})


def act_walk_to(to: Optional[Vec3] = None, points: Optional[Iterable[Vec3]] = None, speed: str = "walk", path: str = "astar",
                arrive_radius: float = 0.6, max_nodes: int = 60000) -> JSON:
    return _clean({"type": "walkTo", "to": _v3(to) if to is not None else None,
                   "points": [_v3(p) for p in points] if points is not None else None, "speed": speed, "path": path,
                   "arriveRadius": arrive_radius, "maxNodes": max_nodes})


def act_look_at(target: JSON, mode: str = "track", duration_sec: float = 1.5) -> JSON:
    return {"type": "lookAt", "target": target, "mode": mode, "durationSec": duration_sec}


def act_look(yaw: float, pitch: float, mode: str = "track", duration_sec: float = 1.5) -> JSON:
    return {"type": "look", "yaw": yaw, "pitch": pitch, "mode": mode, "durationSec": duration_sec}


def act_look_ahead() -> JSON:
    return {"type": "lookAhead"}


def act_face(yaw: float) -> JSON:
    return {"type": "face", "yaw": yaw}


def act_stop() -> JSON:
    return {"type": "stop"}


def act_breather(duration_sec: float = 3.0) -> JSON:
    return {"type": "breather", "durationSec": duration_sec}


def act_jump() -> JSON:
    return {"type": "jump"}


def act_crouch(on: bool = True) -> JSON:
    return {"type": "crouch", "on": on}


def act_brace(on: bool = True, wind_yaw: Optional[float] = None) -> JSON:
    return _clean({"type": "brace", "on": on, "windYaw": wind_yaw})


def act_swing(hand: str = "main") -> JSON:
    return {"type": "swing", "hand": hand}


def act_use(hand: str = "main", duration_sec: float = 0.0) -> JSON:
    return {"type": "use", "hand": hand, "durationSec": duration_sec}


def act_release(arrow: bool = False, power: float = 2.5) -> JSON:
    return {"type": "release", "arrow": arrow, "power": power}


def act_pose(pose: str, hand: str = "main", amount: Optional[float] = None) -> JSON:
    """pose: none | raise | spyglass | bow | crossbow | block | brush.

    raise keys Everest Radio's raise-to-face on whichever hand holds everest_radio:radio (main first; hand is then
    ignored), eased in client ticks; any other pose lowers it. amount (raise only, 0..1) pins the height instead.
    Without the radio mod, raise is the vanilla horn pose on hand.
    """
    return _clean({"type": "pose", "hand": hand, "pose": pose, "amount": amount})


def act_equip(slot: str, item: str) -> JSON:
    return {"type": "equip", "slot": slot, "item": item}


def act_hood(down: bool) -> JSON:
    return {"type": "hood", "down": down}


def act_gravity(on: bool) -> JSON:
    """Off holds the walker at its height while it walks (crossing a span its physics won't hold up)."""
    return {"type": "gravity", "on": on}


def act_mask(on: bool) -> JSON:
    return {"type": "mask", "on": on}


def act_teleport(pos: Vec3, yaw: Optional[float] = None) -> JSON:
    return _clean({"type": "teleport", "pos": _v3(pos), "yaw": yaw})


def lod_gate(enabled: bool = True, quiet_sec: float = 5, stall_sec: float = 10, min_sec: float = 3, timeout_sec: float = 300,
             on_timeout: str = "fail", warm: bool = True, warm_spacing: float = 160, warm_point_max_sec: float = 60,
             warm_max_points: int = 10, warm_quiet_sec: float = 2, frame_stability: bool = True,
             frame_threshold: float = 0.6, frame_quiet_sec: float = 3, stills: bool = False, near_radius: float = 6000,
             wait_for_sync: bool = False, frame_max_wait_sec: float = 8) -> JSON:
    """The LOD-ready gate (schema $defs/lodGate): Distant Horizons' own status decides; see the README."""
    return {"enabled": enabled, "quietSec": quiet_sec, "stallSec": stall_sec, "minSec": min_sec, "timeoutSec": timeout_sec,
            "onTimeout": on_timeout, "warm": warm, "warmSpacing": warm_spacing, "warmPointMaxSec": warm_point_max_sec,
            "warmMaxPoints": warm_max_points, "warmQuietSec": warm_quiet_sec, "frameStability": frame_stability,
            "frameThreshold": frame_threshold, "frameQuietSec": frame_quiet_sec, "stills": stills,
            "nearRadius": near_radius, "waitForSync": wait_for_sync, "frameMaxWaitSec": frame_max_wait_sec}


# ---------------------------------------------------------------- builders: shots

def shot(name: str, duration_sec: float, camera: JSON, player: Optional[JSON] = None, hud: Optional[JSON] = None,
         shaders: Optional[bool] = None, setup: Optional[List[JSON]] = None, warmup: Optional[JSON] = None,
         events: Optional[List[JSON]] = None, teardown: Optional[List[JSON]] = None, actors: Optional[List[JSON]] = None,
         keep_actors: bool = False, particles: Optional[JSON] = None) -> JSON:
    """A shot spec (schema/shot.schema.json).

    player: {visible, ride, gamemode, pos, yaw, pitch}; hud: {visible, chat, crosshair, hand};
    warmup: {chunksTimeoutSec, lodSettleSec, minSec, scout, scoutSpeed, lod: lod_gate(...)}; the LOD gate runs before
    every take unless warmup.lod.enabled is false. actors: actor(...) specs, spawned before the warm-up and started on
    the take's first frame. particles: {block: [class-name prefixes], clear: bool} keeps those particles out of the take.
    """
    return _clean({"name": name, "durationSec": duration_sec, "camera": camera, "player": player, "hud": hud,
                   "shaders": shaders, "setup": setup or None, "warmup": warmup, "events": events or None,
                   "teardown": teardown or None, "actors": actors or None, "keepActors": keep_actors or None,
                   "particles": particles})


def render_settings(width: int = 2560, height: int = 1440, fps: int = 60, codec: str = "hevc_nvenc", quality: int = 18,
                    ten_bit: Optional[bool] = None, server_tick_sync: bool = False, sheet_frames: int = 6,
                    offscreen: str = "auto") -> JSON:
    """16:9 by default; any even size works (2560x1072 for 2.39:1). offscreen "auto" renders offscreen when the screen
    can't hold the window (3840x2160 on a smaller screen); "always" or "never"."""
    return _clean({"width": width, "height": height, "fps": fps, "codec": codec, "quality": quality, "tenBit": ten_bit,
                   "serverTickSync": server_tick_sync, "sheetFrames": sheet_frames, "offscreen": offscreen})


def preview_settings(scale: float = 0.5, stills: int = 4, video: bool = False, fps: int = 20) -> JSON:
    return {"scale": scale, "stills": stills, "video": video, "fps": fps}


# ---------------------------------------------------------------- schema validation (no dependencies)

SCHEMA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "schema")


def validate(instance: Any, schema_file: str = "shots.schema.json") -> None:
    """Validates against a schema in ../schema (a subset of JSON Schema 2020-12: type, properties, required,
    additionalProperties, enum, const, minimum, maximum, minItems, maxItems, items, pattern, oneOf, $ref, $defs).
    Raises ValueError with the JSON path of the first problem."""
    path = schema_file if os.path.isabs(schema_file) else os.path.join(SCHEMA_DIR, schema_file)
    with open(path, encoding="utf-8") as f:
        root = json.load(f)
    _check(instance, root, root, "$")


def _resolve(schema: JSON, root: JSON) -> JSON:
    while "$ref" in schema:
        ref = schema["$ref"]
        if not ref.startswith("#/"):
            raise ValueError(f"unsupported $ref {ref}")
        node: Any = root
        for part in ref[2:].split("/"):
            node = node[part]
        extra = {k: v for k, v in schema.items() if k != "$ref"}
        schema = {**node, **extra}
    return schema


def _type_ok(v: Any, t: str) -> bool:
    return {"object": isinstance(v, dict), "array": isinstance(v, list), "string": isinstance(v, str),
            "boolean": isinstance(v, bool), "null": v is None,
            "integer": isinstance(v, int) and not isinstance(v, bool) or isinstance(v, float) and v.is_integer(),
            "number": isinstance(v, (int, float)) and not isinstance(v, bool)}[t]


def _check(v: Any, schema: JSON, root: JSON, path: str) -> None:
    import re
    schema = _resolve(schema, root)
    if "oneOf" in schema:
        errors = []
        matches = 0
        for sub in schema["oneOf"]:
            try:
                _check(v, sub, root, path)
                matches += 1
            except ValueError as e:
                errors.append(str(e))
        if matches != 1:
            # Prefer the branch whose discriminator matches, for a useful message.
            if isinstance(v, dict) and "type" in v:
                for sub, err in zip(schema["oneOf"], errors):
                    s = _resolve(sub, root)
                    const = s.get("properties", {}).get("type", {}).get("const")
                    if const == v.get("type"):
                        raise ValueError(err)
                raise ValueError(f"{path}: unknown type {v.get('type')!r}")
            raise ValueError(f"{path}: matches {matches} alternatives; first error: {errors[0] if errors else '?'}")
    t = schema.get("type")
    if t is not None:
        types = t if isinstance(t, list) else [t]
        if not any(_type_ok(v, x) for x in types):
            raise ValueError(f"{path}: expected {t}, got {type(v).__name__} {v!r}"[:300])
    if "const" in schema and v != schema["const"]:
        raise ValueError(f"{path}: must be {schema['const']!r}")
    if "enum" in schema and v not in schema["enum"]:
        raise ValueError(f"{path}: must be one of {schema['enum']}, got {v!r}")
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        if "minimum" in schema and v < schema["minimum"]:
            raise ValueError(f"{path}: must be >= {schema['minimum']}, got {v}")
        if "maximum" in schema and v > schema["maximum"]:
            raise ValueError(f"{path}: must be <= {schema['maximum']}, got {v}")
        if "exclusiveMinimum" in schema and v <= schema["exclusiveMinimum"]:
            raise ValueError(f"{path}: must be > {schema['exclusiveMinimum']}, got {v}")
    if isinstance(v, str) and len(v) < schema.get("minLength", 0):
        raise ValueError(f"{path}: must have at least {schema['minLength']} characters")
    if isinstance(v, str) and "maxLength" in schema and len(v) > schema["maxLength"]:
        raise ValueError(f"{path}: must have at most {schema['maxLength']} characters")
    if isinstance(v, str) and "pattern" in schema and not re.search(schema["pattern"], v):
        raise ValueError(f"{path}: {v!r} doesn't match {schema['pattern']}")
    if isinstance(v, list):
        if len(v) < schema.get("minItems", 0):
            raise ValueError(f"{path}: needs at least {schema['minItems']} items")
        if "maxItems" in schema and len(v) > schema["maxItems"]:
            raise ValueError(f"{path}: allows at most {schema['maxItems']} items")
        if "items" in schema:
            for i, item in enumerate(v):
                _check(item, schema["items"], root, f"{path}[{i}]")
    if isinstance(v, dict):
        props = schema.get("properties", {})
        for r in schema.get("required", []):
            if r not in v:
                raise ValueError(f"{path}.{r}: required")
        if schema.get("additionalProperties") is False:
            extra = [k for k in v if k not in props]
            if extra:
                raise ValueError(f"{path}: unknown field(s) {extra}; allowed: {sorted(props)}")
        for k, sub in props.items():
            if k in v:
                _check(v[k], sub, root, f"{path}.{k}")
