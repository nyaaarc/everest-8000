# Playing Everest 8000

You don't need to download the map. Distant terrain streams from the server through Distant Horizons.

## Setup

1. Install [Prism Launcher](https://prismlauncher.org) and sign in: top right > Accounts > Add Microsoft, then pick that
   account. Prism only downloads the mod loader while you're signed in.
2. Add Instance > Import > pick the `.mrpack` > OK. Prism downloads the mods itself. The Modrinth App reads `.mrpack`
   too.
3. Right-click the new instance > Edit > Settings > Java:
   - tick Memory and set the maximum to 6144 MB;
   - tick Java arguments and paste `-XX:+UseZGC -XX:+ZGenerational` (a smoother garbage collector, so far terrain
     loads with fewer stutters).
4. Launch it, go to Multiplayer and join your host's server. If the server is whitelisted, send the host your exact
   Minecraft Java username first.

Don't update Distant Horizons: its version has to match the server's.

## Finding your way

- There's no minimap and no map screen on purpose: you find your way by the mountain. If your host built the pack for
  their server, every stop from Lukla to the summit shows as a labeled marker in the world, with its distance, so you
  always know which way the next camp is. Join from the server list: the markers are tied to that address, so typing a
  different one in Direct Connect makes them disappear.
- Pick your own path between stops. Reaching a stop pops an advancement, and L (Advancements) shows how far up the
  route you are. If you die, you come back at the last stop you reached.

## Day and night

A day lasts an hour: about 40 minutes of daylight and 20 of night, with a few minutes of sunset and sunrise between.
One player sleeping in a bed skips the night for everyone.

## Weather

- The higher you go, the harder the wind blows. Crouch to brace against it, and use ridges and rocks as windbreaks.
- Storms roll over the upper mountain. Out in one you slowly freeze (frost at the screen edges), then start losing
  health and can't heal. Get into a tent or hut, or next to a lit campfire, to thaw out.

## Survival

- You start at Lukla with a Traveler's Backpack and some food. Right-click it to open it, then press Equip so you wear
  it. Food has to last: hunt deer, boar and birds in the valleys. Animals only live below the Khumbu lodge; nothing
  spawns higher up, and there are no monsters anywhere.
- Base Camp hands you a down suit and two oxygen cylinders. Above Camp 1 it's cold even on a clear day, and colder at
  night; the full suit keeps that out and blunts storms.
- Above Camp 3 the air is too thin. Your cylinders (two at most in your inventory) last about 8 minutes at a walk,
  longer resting, shorter sprinting or climbing. The gauge over your hunger bar shows them and your blood oxygen.
  Without oxygen you black out, collapse, then start dying; a teammate can save you by throwing you a cylinder.
- Base Camp, Camp 2, Camp 3 and Camp 4 refill cylinders while you stand in camp.

## Talking and finding each other

- There are no name tags and no maps, so it's easy to get lost.
- Voice chat is proximity-based: you hear people near you. Press V on first launch to set up your mic.
- Your starter kit has an Expedition Radio. Hold it in your hand and talk, and everyone carrying one hears you, anywhere
  on the mountain. It's clear up close with a clear line of sight; distance and ridges break it up into static. Press
  F3 to see your coordinates (the XYZ line) and read them out so others can find you.
- Type `/hood` to pick your hood, goggles, and suit and backpack colours, so friends can tell you apart from a distance.

## Keys

| Key | Does |
|---|---|
| U | route stops, with distances |
| B | backpack |
| J | hood up/down |
| V | voice chat menu |
| N | mute mic |
| K | shaders on/off |
| H | hide HUD |
| R | peek HUD |
| C | zoom (hold it, and scroll to zoom in further, e.g. to scout the route) |

Shaders: Complementary Reimagined (by EminGT, [complementary.dev](https://www.complementary.dev/)) comes with the pack
but starts off. Press K in game to turn it on, if your graphics card can take it.
