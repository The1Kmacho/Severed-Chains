# Severed Chains Visual Remaster Mod

This is the content-mod half of the Visual Remaster project. It is built as a normal Severed Chains mod JAR and packaged at:

`mods/visual-remaster.jar`

## Background authoring workflow

When a retail submap environment loads, the mod creates:

`mods/visual_remaster/backgrounds/drgn2X/cut_YYYY/`

The first time that scene is visited, the mod reconstructs the retail stitched environment from the user's locally extracted game data and writes:

- `source_background.png`
- `source_foreground_0.png`, `source_foreground_1.png`, etc.
- `scene.txt`

These source PNGs are authoring references only. They are generated locally and are not shipped in the repository.

To replace that scene, add:

- `background.png`
- `foreground_0.png`, `foreground_1.png`, etc. when needed

Replacement foregrounds must be full-canvas RGBA PNGs with transparency outside the foreground cutout. All replacement layers should share the source background's canvas/aspect ratio. Missing replacement layers fall back to retail.

## First Seles test

Run the game from the opening until the first battle area in Seles. The mod will create the exact scene folder and export its source layers. Send those source PNGs back into the project workflow and they become the reference for the first HD remaster.
