# No minimap and no world map, only the in-world route markers: getting lost is part of the climb. These effects are
# Xaero's own server-side switches; hidden particles also hide the effect icon. Re-applied every second, because milk
# and dying clear effects.
effect give @a xaerominimap:no_minimap infinite 0 true
effect give @a xaeroworldmap:no_world_map infinite 0 true
schedule function everest_social:no_maps 20t
