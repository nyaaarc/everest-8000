"""Sky flicker regression check: a tick-locked pulse in the sky band of frame-stepped renders.

  python sky_check.py analyze <video>... [--sky 0,0,1,0.24] [--fps 60] [--max-pulse 1.5]
  python sky_check.py run [--instance DIR] [--out DIR] [--max-pulse 1.5]

The bug this guards against: during a riding shot the server rejected the spectator's moves ("moved too quickly") and
snapped it back once per tick, so the sky (and anything else drawn relative to the player) changed on the one frame
in three where a game tick ran. At 60 fps the client tick is every 3rd frame, so the check folds the sky band's mean
colour by tick phase and compares that 20 Hz component with the frame-to-frame noise. Clean renders sit well under
1 (the v1 player shots: 0.09-0.44); the v1 riding scenery shots that flickered measured 3.7-10.5.

run renders two short shots through the Cinema API with the game up: a fast riding flight (the case that broke) and a
static riding shot (baseline), both 1280x720 h264, then analyses them and checks the render results for server
position corrections. Exit status 1 if any video fails.
"""
import argparse
import json
import os
import subprocess
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))


def probe(video):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                          "stream=width,height,r_frame_rate", "-of", "csv=p=0", video],
                         capture_output=True, text=True, check=True).stdout.strip().split(",")
    num, den = out[2].split("/")
    return int(out[0]), int(out[1]), round(int(num) / int(den))


def sky_series(video, crop):
    """Mean RGB of the crop (x, y, w, h in pixels) per frame."""
    x, y, w, h = crop
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", video, "-vf", f"crop={w}:{h}:{x}:{y},scale=32:8",
                          "-pix_fmt", "rgb24", "-f", "rawvideo", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.uint8).reshape(-1, 8, 32, 3).astype(np.float64).mean(axis=(1, 2))


def tick_pulse(s, period):
    """(amplitude of the component locked to the tick period, frame-to-frame noise, per-phase means)."""
    k = period
    pad = np.pad(s, ((k, k), (0, 0)), mode="edge")
    trend = np.stack([np.convolve(pad[:, c], np.ones(k) / k, mode="same")[k:-k] for c in range(3)], -1)
    r = s - trend
    phases = np.array([r[p::k].mean(axis=0) for p in range(k)])
    amp = float(np.abs(phases).max())
    noise = float(np.median(np.abs(np.diff(r, axis=0)))) + 1e-6
    return amp, noise, phases


def analyze(video, sky=(0.0, 0.0, 1.0, 0.24), fps=None, max_pulse=1.5):
    w, h, vfps = probe(video)
    fps = fps or vfps
    crop = (int(sky[0] * w), int(sky[1] * h), max(32, int(sky[2] * w)), max(8, int(sky[3] * h)))
    s = sky_series(video, crop)
    period = max(2, round(fps / 20))
    amp, noise, phases = tick_pulse(s, period)
    # A perfectly still sky has ~0 frame-to-frame noise; measure against a floor of 0.05 levels so that doesn't blow up.
    ratio = amp / max(noise, 0.05)
    jumps = np.abs(np.diff(s.mean(axis=1)))
    return {"video": video, "frames": len(s), "fps": fps, "crop": list(crop), "pulseAmp": round(amp, 3),
            "noise": round(noise, 3), "pulseRatio": round(ratio, 2), "p99LumaJump": round(float(np.percentile(jumps, 99)), 2),
            "phaseMeans": np.round(phases.mean(axis=1), 3).tolist(), "ok": bool(ratio <= max_pulse)}


def run(instance, out_dir, max_pulse):
    sys.path.insert(0, os.path.join(HERE, "..", "client"))
    from cinema import Cinema, a_command, a_time, key, keyframes, lod_gate, point, render_settings, shot, static

    summit = point(20706.5, 2032.0, 7774.5)
    hidden = {"visible": False, "ride": True}
    setup = [a_command("weather clear"), a_time(6000)]
    warm = {"lod": lod_gate(min_sec=3, timeout_sec=90, warm_max_points=4), "chunksTimeoutSec": 30, "minSec": 1}
    # The v1 khumbu_glacier move: ~110 blocks/s over the valley, low pitch so the top quarter of the frame is sky.
    # (The valley floor there is around y -110; the camera flies 90 blocks above it.)
    flight = shot("sky_check_flight", 7,
                  keyframes([key([15350.0, -18.0, 9800.0], t=0, look_at=summit), key([15750.0, 2.0, 9150.0], t=7, look_at=summit)],
                            easing="linear", fov=40),
                  player=hidden, setup=setup, warmup=warm)
    still = shot("sky_check_static", 3, static([15350.0, -18.0, 9800.0], look_at=summit, fov=40),
                 player=hidden, setup=setup, warmup=warm)
    rs = render_settings(1280, 720, 60, codec="h264_nvenc", quality=20)
    c = Cinema.from_instance(instance)
    c.wait_ready()
    c.snapshot()
    results = []
    try:
        for s in (flight, still):
            r = c.render(s, out_dir, render=rs)
            a = analyze(r["video"], max_pulse=max_pulse)
            corr = [w for w in r.get("warnings", []) if "server moved the player" in w]
            a["serverCorrections"] = corr
            a["ok"] = a["ok"] and not corr
            results.append(a)
    finally:
        c.restore()
    return results


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    an = sub.add_parser("analyze")
    an.add_argument("videos", nargs="+")
    an.add_argument("--sky", default="0,0,1,0.24", help="sky band as fractions x,y,w,h of the frame")
    an.add_argument("--fps", type=int, default=None)
    an.add_argument("--max-pulse", type=float, default=1.5)
    rn = sub.add_parser("run")
    rn.add_argument("--instance", default=os.path.join(os.environ.get("APPDATA", ""), "PrismLauncher", "instances",
                                                       os.environ.get("CINEMA_INSTANCE", "Everest Cinema")))
    rn.add_argument("--out", default=os.path.join(HERE, "..", "build", "sky_check"))
    rn.add_argument("--max-pulse", type=float, default=1.5)
    a = ap.parse_args()
    if a.cmd == "analyze":
        sky = tuple(float(v) for v in a.sky.split(","))
        results = [analyze(v, sky, a.fps, a.max_pulse) for v in a.videos]
    else:
        os.makedirs(a.out, exist_ok=True)
        results = run(a.instance, os.path.abspath(a.out), a.max_pulse)
    print(json.dumps(results, indent=1))
    bad = [r["video"] for r in results if not r["ok"]]
    if bad:
        print("FAIL: tick-locked sky pulse (or server corrections) in " + ", ".join(os.path.basename(b) for b in bad),
              file=sys.stderr)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
