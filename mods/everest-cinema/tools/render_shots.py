"""Batch renderer: renders every shot of a shots file (schema/shots.schema.json) through a running game's Cinema API.

  python render_shots.py SHOTS.json --instance "<Prism instance dir>" [--out DIR] [--shots a,b] [--preview]
                         [--stills N] [--scale S] [--keep-open] [--no-restore] [--validate-only]

Steps: validate the file against the schema, wait for the game, take a state snapshot, run the file's setup actions,
render each shot (capture.render), run the teardown actions, restore the snapshot, quit the game (unless --keep-open).
Writes <out>/summary.json with every shot's result or error. Exit code 0 only if every shot rendered.
"""
import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "client"))
import cinema  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("spec")
    ap.add_argument("--instance", required=False, help="Prism instance folder (reads config/everest-cinema.json)")
    ap.add_argument("--port", type=int, default=None)
    ap.add_argument("--token", default=None)
    ap.add_argument("--out", default=None, help="output folder (default: <spec folder>/renders)")
    ap.add_argument("--shots", default=None, help="comma-separated shot names to (re-)render")
    ap.add_argument("--preview", action="store_true", help="low-res stills + contact sheets instead of videos")
    ap.add_argument("--stills", type=int, default=4)
    ap.add_argument("--scale", type=float, default=0.5)
    ap.add_argument("--keep-open", action="store_true")
    ap.add_argument("--no-restore", action="store_true")
    ap.add_argument("--validate-only", action="store_true")
    ap.add_argument("--ready-timeout", type=float, default=600)
    a = ap.parse_args()

    with open(a.spec, encoding="utf-8") as f:
        spec = json.load(f)
    try:
        cinema.validate(spec, "shots.schema.json")
    except ValueError as e:
        print(f"INVALID {a.spec}: {e}")
        return 2
    names = [s["name"] for s in spec["shots"]]
    if len(set(names)) != len(names):
        print("INVALID: duplicate shot names")
        return 2
    wanted = a.shots.split(",") if a.shots else names
    missing = [n for n in wanted if n not in names]
    if missing:
        print(f"INVALID: no shot(s) named {missing}; have {names}")
        return 2
    print(f"spec ok: {len(names)} shots, rendering {len(wanted)}")
    if a.validate_only:
        return 0

    out = os.path.abspath(a.out or os.path.join(os.path.dirname(os.path.abspath(a.spec)), "renders"))
    os.makedirs(out, exist_ok=True)
    if a.instance:
        c = cinema.Cinema.from_instance(a.instance)
    else:
        c = cinema.Cinema(port=a.port or 25580, token=a.token)
    st = c.wait_ready(timeout=a.ready_timeout)
    print(f"game ready ({st['window']['width']}x{st['window']['height']}, shaders {st.get('shaders')})")

    summary = {"spec": os.path.abspath(a.spec), "out": out, "preview": a.preview, "shots": {}, "started": time.time()}
    ok = True
    if not a.no_restore:
        summary["snapshot"] = c.snapshot()
    try:
        if spec.get("setup"):
            c.run_actions(spec["setup"])
        render = spec.get("render")
        preview = {"scale": a.scale, "stills": a.stills, "video": False, "fps": 20} if a.preview else None
        for shot in spec["shots"]:
            if shot["name"] not in wanted:
                continue
            t0 = time.time()
            try:
                r = c.render(shot, out, render=render, preview=preview)
                summary["shots"][shot["name"]] = r
                print(f"{shot['name']}: ok in {time.time() - t0:.0f}s -> {r.get('video') or r.get('contactSheet')}"
                      f"{'  WARN ' + '; '.join(r['warnings']) if r.get('warnings') else ''}", flush=True)
            except cinema.CinemaError as e:
                ok = False
                summary["shots"][shot["name"]] = {"error": {"code": e.code, "message": e.message, "data": e.data}}
                print(f"{shot['name']}: FAILED {e}", flush=True)
                if e.code is None:
                    raise
        if spec.get("teardown"):
            c.run_actions(spec["teardown"])
    finally:
        try:
            if not a.no_restore:
                summary["restore"] = c.restore()
                print("restored:", summary["restore"].get("restored"), "warnings:", summary["restore"].get("warnings"))
        except cinema.CinemaError as e:
            ok = False
            summary["restore"] = {"error": str(e)}
            print("RESTORE FAILED:", e)
        with open(os.path.join(out, "summary.json"), "w", encoding="utf-8") as f:
            json.dump(summary, f, indent=2)
        if not a.keep_open:
            try:
                c.quit()
            except cinema.CinemaError:
                pass
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
