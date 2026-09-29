# Severed Chains Visual Remaster Mod

This is the content-mod half of the Visual Remaster project.

## Background replacements

When a submap environment loads, the mod creates a folder under:

`mods/visual_remaster/backgrounds/drgn2X/cut_YYYY/`

The folder contains `scene.txt` with the location name, archive identifier, cut number, and foreground-layer count.

To replace that scene, add:

- `background.png`
- `foreground_0.png`, `foreground_1.png`, etc. when needed

Foreground replacements must be full-canvas RGBA images with transparent pixels outside the foreground cutout. Keep all layers aligned to the same canvas/aspect ratio.

The mod falls back to the retail background or individual retail foreground layers when an override file is absent.
