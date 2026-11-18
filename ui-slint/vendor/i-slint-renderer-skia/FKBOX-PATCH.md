# i-slint-renderer-skia 1.19.0, patched for FK-box

Unmodified crates.io source of `i-slint-renderer-skia` 1.19.0 (GPL-3.0-only OR
the Slint licenses, see LICENSES/; `Cargo.toml.orig` and the registry's
`.cargo_vcs_info.json`/`Cargo.lock` left out) with two changes, used through
`[patch.crates-io]` in ui-slint/Cargo.toml. Search for "FK-box patch" to find
every hunk.

1. `opengl_surface.rs`: `set_resource_cache_limit(32 MB)` on the GrDirectContext
   (Skia's default budget is 256 MB).
2. `lib.rs`: `fkbox_request_purge()` (thread-local flag). The next frame's
   `render_components_to_canvas` first clears the renderer's image, layer,
   path, box-shadow and text-layout caches, calls `free_gpu_resources()` on the
   GrDirectContext and `skia_safe::graphics::purge_all_caches()`. The UI calls
   it when a page with many images closes (the app lists), so memory returns
   without the app going to the background.

Retired with 1.19: the 1.18.1 copy also patched `font_cache.rs`, whose
`load_typeface_internal` created every typeface from `Data::new_copy` (a heap
copy of the whole font file per face index and variation, 100+ MB with a
variable CJK system font at 4-5 weights). Upstream 1.19.0 creates it with
`Data::new_with_owner(font.data.clone())`, which references the fontique blob's
bytes (FK-box's memory-mapped system fonts) without a copy and keeps the blob
alive with the typeface, so `font_cache.rs` is upstream again.

1.19 also brings `backdrop-blur` (Rectangle), which this renderer draws as a
Skia backdrop image filter over the GL surface; FK-box's glass surfaces
(ui/kit.slint `Glass`) rely on it.

To update Slint: re-copy the new crate version (without `Cargo.toml.orig`,
`.cargo_vcs_info.json`, `Cargo.lock`), keep this file and re-apply the hunks
marked "FK-box patch" (a three-way merge against the previous upstream crate
does it: `git merge-file NEW OLD_UPSTREAM THIS_COPY` per file).
