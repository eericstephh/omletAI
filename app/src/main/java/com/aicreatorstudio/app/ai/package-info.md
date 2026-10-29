# ai

Reserved for future AI-powered features (e.g. auto-captioning, smart
trimming, style/effect suggestions, script-to-video, voice cleanup).

Nothing is implemented here yet. Keeping AI code in its own package keeps
it decoupled from `video` (raw editing) and `ui` (presentation), so it can
be swapped, mocked, or backed by an on-device or remote model without
touching the rest of the app.

Planned shape (not implemented yet):
- `AiRepository` — single entry point the ViewModels call into
- `remote/` — API clients for cloud AI services
- `ondevice/` — on-device model inference, if/when adopted
