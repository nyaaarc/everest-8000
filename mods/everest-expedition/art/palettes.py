"""Material palettes shared by the item icons and the armor layers.

Each material has 4-6 shades, darkest (outline) to brightest (highlight).
Light comes from the top-left, like vanilla.
"""
from common import rgb

# Expedition red/orange down (classic Himalayan summit suit).
RED = {
    "1": rgb("4a0f0a"),  # outline
    "2": rgb("861b12"),  # baffle seam / deep shadow
    "3": rgb("b02818"),  # shadow
    "4": rgb("d8401f"),  # base
    "5": rgb("f06830"),  # light
    "6": rgb("ff9a5c"),  # highlight
}

# Black nylon accents: shoulder yoke, collar, cuffs, zipper, goggle frames.
BLACK = {
    "k": rgb("17161a"),
    "K": rgb("2b2a30"),
    "J": rgb("44434b"),
    "j": rgb("66656f"),
}

# Fur ruff around the hood opening.
FUR = {
    "f": rgb("5e4c3a"),
    "F": rgb("8f7b62"),
    "g": rgb("c2b293"),
    "G": rgb("e8dfca"),
}

# Amber goggle lenses.
AMBER = {
    "a": rgb("7a3a06"),
    "A": rgb("d27a12"),
    "y": rgb("ffb935"),
    "Y": rgb("ffeaa0"),
}

# 8000 m boot yellow.
YELLOW = {
    "o": rgb("5a3f06"),
    "d": rgb("9a6c0a"),
    "s": rgb("cc9614"),
    "b": rgb("f2c428"),
    "l": rgb("ffdf5e"),
    "h": rgb("fff3a8"),
}

# Poisk-style oxygen bottle orange (keys chosen not to clash with others).
ORANGE = {
    "O": rgb("5a2206"),
    "D": rgb("9c420c"),
    "S": rgb("d0641a"),
    "B": rgb("f08a24"),
    "L": rgb("ffb050"),
    "H": rgb("ffdca0"),
}

# Valve / regulator metal.
SILVER = {
    "x": rgb("2f3238"),
    "e": rgb("6d727b"),
    "E": rgb("a3a8b0"),
    "W": rgb("d6dae0"),
    "w": rgb("f6f8fa"),
}


def merge(*pals):
    out = {}
    for p in pals:
        for k, v in p.items():
            if k in out and out[k] != v:
                raise ValueError(f"palette key clash: {k!r}")
            out[k] = v
    return out
