# ClipForge 3.0 architecture (M1)

shared/core (plain JS, runs in Android WebView and Electron)
  validate.js  parse + locally validate AI highlight JSON (done, 10 tests)
  next: project.js (project file), providers.js (AI provider interface), decisions.js (auto-edit decisions)

Android (kept): MainActivity bridge, Transcriber (Vosk), ClipExporter (Media3), Painter, MusicSynth
Windows (M7): Electron shell + FFmpeg renderer, same shared/core and same editor UI
Pipeline: inspect -> audio -> transcribe -> sample frames -> AI understanding -> candidates -> validate (shared/core) -> decisions -> render -> verify
Secrets: Android Keystore / Windows DPAPI (safeStorage), never in the repo.
  motion.js  face-track smoothing (buildTrack/trackAt/previewX) and speed-ramp rules (cleanRamps/mapTime), 15 tests (M24)
