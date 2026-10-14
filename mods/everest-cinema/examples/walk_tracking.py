"""Example: a walking-player tracking shot, rendered through the Cinema API from Python.

The player walks two waypoints in creative mode; the camera rides behind and to the left of them, rotating with their
facing and smoothing out the steps; at 6 s the player turns their head toward the view.
Run with the game up:  python walk_tracking.py "<Prism instance dir>" <out dir> [--preview]
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "client"))
from cinema import (Cinema, a_look, a_walk, at, follow, player_target, preview_settings, render_settings, shot,  # noqa: E402
                    validate)

s = shot("ridge_walk", 10,
         follow(player_target(anchor="center"), offset=[3, 1.5, -6], space="targetYaw", smooth_sec=0.6, fov=55),
         player={"visible": True, "gamemode": "creative", "pos": [21000.5, 1548, 8500.5], "yaw": 160, "pitch": 5},
         hud={"visible": False},
         events=[at(0.0, a_walk([[20950, 0, 8400], [20900, 0, 8300]], speed="walk")),
                 at(6.0, a_look(-20, -10, duration_sec=1.5))])
validate(s, "shot.schema.json")

if __name__ == "__main__":
    c = Cinema.from_instance(sys.argv[1])
    c.wait_ready()
    c.snapshot()
    try:
        preview = preview_settings(0.5, 4) if "--preview" in sys.argv else None
        print(c.render(s, sys.argv[2], render=render_settings(1920, 1080, 60), preview=preview))
    finally:
        print(c.restore())
