"""Build the mod's wind loop (wind_loop.ogg) from a CC0 recording. Needs numpy and ffmpeg on PATH.

Source: "Icy Heights" wind.ogg by Ecrivain on OpenGameArt, CC0
(https://opengameart.org/content/icy-heights). The recording's end is crossfaded into its start with an
equal-power fade, so it loops without a seam, and it's normalized so the game's volume curve has room.

Usage: make_wind_loop.py <source wind.ogg> <out.ogg> [preview.wav]
"""
import os, subprocess, sys, tempfile, wave

import numpy as np

RATE = 48000
FADE_SECONDS = 3.0

src, out = sys.argv[1], sys.argv[2]
raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", src, "-ac", "2", "-ar", str(RATE), "-f", "f32le", "-"],
                     capture_output=True, check=True).stdout
x = np.frombuffer(raw, dtype=np.float32).reshape(-1, 2).astype(np.float64)
n = len(x)
fade = int(FADE_SECONDS * RATE)
t = np.linspace(0, np.pi / 2, fade)[:, None]
loop = x[:n - fade].copy()
loop[:fade] = x[:fade] * np.sin(t) + x[n - fade:] * np.cos(t)

loop /= np.percentile(np.abs(loop), 99.95)
loop = np.tanh(loop * 0.85) * 0.9

with tempfile.TemporaryDirectory() as tmp:
    wav = sys.argv[3] if len(sys.argv) > 3 else os.path.join(tmp, "wind.wav")
    with wave.open(wav, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes((loop * 32767).astype("<i2").tobytes())
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "6", out], check=True)
print(out, f"{len(loop) / RATE:.1f} s,", os.path.getsize(out), "bytes")
