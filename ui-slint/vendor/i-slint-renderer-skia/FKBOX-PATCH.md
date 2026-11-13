# i-slint-renderer-skia 1.18.1, patched for FK-box

Unmodified crates.io source of `i-slint-renderer-skia` 1.18.1 (GPL-3.0-only OR
the Slint licenses, see LICENSES/) with two changes, used through
`[patch.crates-io]` in ui-slint/Cargo.toml:

1. `font_cache.rs`: `load_typeface_internal` created every Skia typeface from
   `Data::new_copy(font.data)`, a heap copy of the whole font file for each
   (file, face index, variation) cache key. With a variable CJK system font
   (20-30 MB) used at 4-5 weights this alone held 100+ MB of anonymous memory.
   Now one `skia_safe::Data` per blob id references the blob's bytes without a
   copy (`Data::new_bytes`); the blob (a memory map of the system font file) is
   retained for the life of the cache so the bytes outlive every typeface.
2. `opengl_surface.rs`: `set_resource_cache_limit(32 MB)` on the GrDirectContext
   (Skia's default budget is 256 MB).

To update Slint: re-copy the new crate version and re-apply both hunks
(search for "FK-box patch").
