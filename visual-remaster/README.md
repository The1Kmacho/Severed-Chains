# Severed Chains Visual Remaster Mod

This is the content-mod half of the Visual Remaster project. It is built as a normal Severed Chains mod JAR and packaged at:

`mods/visual-remaster.jar`

## Modern battle-character replacement pipeline

The first vertical slice targets **human Dart in battle**.

Replacement assets are loaded from:

`mods/visual_remaster/models/battle/dart/combat/`

Supported files:

- `albedo.png` — shared high-resolution texture atlas
- `part_00.obj`
- `part_01.obj`
- etc.

The replacement system is modular. If only one `part_XX.obj` exists, only that rigid model part is replaced and every other part remains retail.

### Why rigid parts

Legend of Dragoon battle animation is already expressed as transforms on separate model parts. The remaster pipeline preserves those retail transforms/animations and swaps the GPU mesh for each part. This lets much higher-detail geometry use the existing animation library without introducing a new skinned-animation system for the first pass.

### OBJ requirements

The initial loader supports:

- positions (`v`)
- UV coordinates (`vt`)
- normals (`vn`)
- triangles and polygon faces (`f`, fan-triangulated)
- positive and negative OBJ indices

OBJ parts should be authored around the same local origin/pivot as the matching retail part. UVs use `albedo.png`.

### Local retail reference assets

Severed Chains extracts Dart's normal battle source assets from the user's discs to:

- `files/characters/dart/models/combat`
- `files/characters/dart/textures/combat`

These retail assets are not committed to the source repository.

## Background replacements

Background replacement support remains available under:

`mods/visual_remaster/backgrounds/drgn2X/cut_YYYY/`

with `background.png` and optional `foreground_*.png` overrides, but background remastering is not the current project focus.
