"""Example: three roped-up climbers on the summit ridge, directed by the character director.

Three client-side actors (the local player's skin, a friend's skin, and the player's skin in a parka hood with the mask
on) start a few blocks apart on the route above the South Col and climb toward the summit with the Everest gait: A*
routes over the blocks with a little meander each, a slow trudge, eased breathers. The middle climber glances back at
the third, and the lead takes a breather when it reaches a marked spot. The camera trucks slowly alongside the middle
climber from the south. The LOD gate runs before the take (the shot default).

  python team_on_the_ridge.py                 write team_on_the_ridge.json (validated) and exit
  python team_on_the_ridge.py <instance> <out> [--preview]   render it with the game up
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "client"))
from cinema import (Cinema, a_command, a_time, act_breather, act_look_at, act_walk_to, actor, actor_target, cue,  # noqa: E402
                    everest_gait, lod_gate, outfit, preview_settings, render_settings, shot, skin_file, skin_player,
                    truck, validate)

FRIEND_SKIN = "skins/friend.png"  # any 64x64 skin PNG
WEST = 90.0  # yaw facing -x, up the ridge here

team = [
    actor("lead", pos=(20937.5, 1791, 7880.5), yaw=WEST, skin=skin_player(),
          outfit_spec=outfit(hood_style="goggles_down"), gait_spec=everest_gait(), seed=7,
          timeline=[cue(act_walk_to((20869.5, 1853, 7880.5)), cue_id="climb"),
                    cue(act_breather(4.0), near=(20905.5, 1819, 7880.5), radius=2.5)]),
    actor("second", pos=(20943.5, 1786, 7880.5), yaw=WEST, skin=skin_file(FRIEND_SKIN),
          outfit_spec=outfit(hood_style="expedition", pack_rgb=0x2E5FA8), gait_spec=everest_gait(), seed=99,
          timeline=[cue(act_walk_to((20875.5, 1847, 7880.5)), cue_id="climb"),
                    cue(act_look_at(actor_target("third"), mode="glance", duration_sec=1.8), at_sec=5.5)]),
    actor("third", pos=(20949.5, 1781, 7880.5), yaw=WEST, skin=skin_player(),
          outfit_spec=outfit(hood_style="parka", mask=True, pack_rgb=0xD9A21B),
          gait_spec=everest_gait(pace=0.66), seed=4242,
          timeline=[cue(act_walk_to((20881.5, 1841, 7880.5)), at_sec=0.4)]),
]

SHOT = shot("team_on_the_ridge", 10,
            truck(actor_target("second", anchor="center"), azimuth_deg=0, distance=14, slide_start=3, slide_end=-3,
                  elevation_deg=8, track=True, easing="sineInOut", fov=40),
            player={"visible": False, "ride": True},
            hud={"visible": False},
            setup=[a_command("everest weather clear"), a_command("everest weather jet 25"), a_time(1500)],
            warmup={"lod": lod_gate(min_sec=8)},
            actors=team)

BATCH = {"$schema": "../schema/shots.schema.json", "version": 1,
         "render": render_settings(2560, 1440, 60, codec="hevc_nvenc", quality=16), "shots": [SHOT]}
validate(BATCH, "shots.schema.json")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        with open(os.path.join(HERE, "team_on_the_ridge.json"), "w", encoding="utf-8") as f:
            json.dump(BATCH, f, indent=2)
            f.write("\n")
        print("wrote team_on_the_ridge.json")
        sys.exit(0)
    c = Cinema.from_instance(sys.argv[1])
    c.wait_ready()
    c.snapshot()
    try:
        preview = preview_settings(0.5, 6) if "--preview" in sys.argv else None
        print(json.dumps(c.render(SHOT, sys.argv[2], render=BATCH["render"], preview=preview), indent=2))
    finally:
        print(c.restore())
