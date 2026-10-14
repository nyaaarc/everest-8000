"""Regenerate every Everest Expedition texture and preview.

    python build_all.py

Writes into src/client/resources/assets/everest_expedition/textures/ (plus the
everest-radio mod's radio.png) and art/previews/.
"""
import hud
import hypoxia
import items
import armor
import preview_armor
import suit3d_textures

if __name__ == "__main__":
    print("items:")
    items.main()
    print("armor (flat fallback layers, unused while the 3D suit renderer is registered):")
    armor.main()
    preview_armor.main()
    print("3D suit:")
    suit3d_textures.main()
    print("hud:")
    hud.main()
    print("hypoxia:")
    hypoxia.main()
