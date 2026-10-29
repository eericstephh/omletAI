# video

The video processing layer: importing/reading media, project/clip state,
and (later) the editing pipeline and playback support built on Media3.

**Implemented:**
- `VideoMetadataExtractor` — reads file name, duration, resolution, and
  size from a picked video URI via `MediaMetadataRetriever`.
- `ProjectRepository` / `InMemoryProjectRepository` — holds projects and
  their imported clips. In-memory for now; the interface is the seam a
  persistent implementation (Room, DataStore, etc.) plugs into later
  without changing any calling code.

**Not implemented yet (by design — out of scope until later tasks):**
- `editor/` — timeline and clip editing/trimming logic
- `export/` — Media3 Transformer-based rendering/export
- `player/` — ExoPlayer wrapper for in-app preview
- Persistent storage for projects (currently lost on process death)
