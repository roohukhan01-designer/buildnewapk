# Roohu ClipForge 2.0 (Android)

Turns long videos into vertical 9:16 clips, **autonomously**. Package `com.roohu.clipforge`.

## What changed in 2.0
- **Auto Create button**: one tap finds the best moments, writes the spoken words as captions, titles each clip, styles it by mood, adds music and saves everything to the Gallery.
- **Offline auto-captions** (Vosk) with real per-word timing. The spoken word pops and lights up. Styles: Pop, Karaoke, Bar, Outlined, or Auto (matches the clip mood).
- **Styled RoohuXKhan badge**: gradient pill, play icon, glowing X, shimmer sweep, slide-in. Position, size and opacity stay adjustable in Settings.
- **No more lag**: export now runs on the phone's hardware encoder (Media3 Transformer) at a constant 30 fps, H.264 + AAC MP4 with a proper index. Clips save to **Movies/RoohuClipForge**, so they show in the Gallery.
- **Built-in music**: if you add no tracks, a small original beat is generated per mood (calm, upbeat, intense). No copyright worries.
- Animations: zoom punch-in, beat pulse, fade in/out, vignette, animated progress bar.

## Build the APK on GitHub (no Android Studio)
1. Create an empty GitHub repository and upload **everything** in this folder, including the hidden `.github` folder.
2. Open **Actions**. The build starts on every push to `main` (or press **Run workflow**). The workflow downloads the two speech models (about 80 MB) by itself, so this takes a few minutes longer than before.
3. When it is green, download **Roohu-ClipForge-debug-apk** under Artifacts and install `app-debug.apk`.

If the build fails, open the failed step and send the first line that starts with `error:` or `What went wrong:`.

## Honest limits
- Offline captions support **English** and **Hindi/Urdu speech** (Urdu speech comes out in Devanagari script, because there is no small Urdu model). Other languages: import an .srt file.
- "Fit with blurred background" is exported as a clean letterbox. Fill mode (default) follows the motion.
- Music ducking under speech is not in the native export yet; music plays at the volume set in Settings.
- Claude title/rating needs claude.ai and is unavailable here; titles come from the first spoken words instead.
- YouTube links are not downloaded (needs a server).
- Debug-signed APK. A Play Store release needs your own signing key.

## 3.0 Milestone 2: reaction-edit look (new)
- Settings > **Edit look**: black frame with two glowing white dividers, a **3D hook title** in the top bar (red + yellow, extruded), and a **zoom punch + light flash + shake on every loud moment** found automatically from the clip's audio (`Energy.java`). Divider glow pulses with the hits.
- Applies to the phone export. The in-app preview stays plain.
- Next: M3 3D captions with bundled free fonts, M4 on-device scene scoring + mood music, M5 optional Gemini key.

## 3.1 Milestone 3: languages, real fonts, 3D look (new)
- **Any spoken language**: Settings > Spoken language is now *Auto-detect*. Paste a free Groq key (console.groq.com, no card) and Whisper detects the language itself (Urdu, Hindi, Arabic, English and 90+ more) with exact word timing. Without a key the app picks between its offline English and Hindi/Urdu models automatically. Right-to-left languages are laid out right-to-left.
- **Real fonts**: the GitHub build now downloads Anton, Bungee, Bangers, Luckiest Guy, Archivo Black, Poppins Black, Bebas Neue, Russo One, Black Ops One and Tajawal (Arabic) into `assets/fonts`. If a download fails the app falls back to a system font and the build still succeeds.
- **3D captions**: extruded letters with outline, gradient face, shine and a glow on the spoken word. Styles: 3D pop, Fire, Gold, Ice, Neon (auto by clip mood). Each caption page springs in.
- **Cinematic GPU effects** (`FxEffect.java`): chromatic aberration, glitch slices, bloom, grade, vignette and film grain, kicking on every loud moment.
- **Name credit**: the tiny logo pill is gone. "ROOHU X KHAN" is now big 3D chrome text (gold / red X / ice) with a moving shine, centred in the bottom bar.

## 3.2 Hardening
- `shared/core/validate.js` is strict now (null/empty/boolean timestamps rejected, bad metadata never throws, text cleaned, style and scores whitelisted, export-settings and file-name sanitising). 28 tests: `node shared/test/validate.test.js` (also runs in CI before the build).
- Cloud captions retry with exponential backoff (`Net.java`) and cache transcripts locally per clip and language (`cacheDir/asr`), so the same clip is never sent twice.
- The Groq key is stored encrypted with an Android Keystore AES-256-GCM key (`SecureStore.java`), not in localStorage.
- Optional release signing through CI secrets `RELEASE_KEYSTORE`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` (debug builds need none).

## 3.3 Step 1: real export settings
Shape (9:16, 16:9, 1:1, 4:5, 3:4), resolution (480p/720p/1080p), frame rate (24/30/60), bitrate with size estimates and a file-name template. Settings are validated by `validate.js` in the page and again in Java, and are applied to the actual render (`ClipExporter`). The reaction frame is used for portrait shapes only.

## 3.4 Steps 2 and 3
- **Projects**: autosave every 3 s and when the app is closed, a backup copy, versioned records (newer-version projects are kept, never deleted), Continue / Open / Rename / Duplicate / Delete (delete needs a second tap). Reopen a project by picking the same video again; a different video is refused and nothing is overwritten.
- **Caption editor** (new *Captions* tab): edit text and times, split at the playhead, merge, add, delete, shift all lines, import and export `.srt`. Word timings are kept in step so animated captions stay in sync, and edits flow into the exported clips.
- Tests: `node shared/test/validate.test.js`, `projects.test.js`, `subtitles.test.js` (49 in total, run in CI before the build).

## 3.5 Step 4: AI director (optional Gemini)
- Settings > **Free Gemini key**. Tap *Title and rate with AI* in Review, or run Auto Create with a key: each clip gets a title, a hook-strength rating (how strong the opening is, not a view prediction), a one-line reason, a mood (which picks the music) and a caption style.
- Official REST API with the key in the `x-goog-api-key` header, JSON schema output, answers re-validated locally (`validateDirector`), retries with backoff, answers cached on the phone. The newest stable *flash* model is chosen by asking Google which models the key can use, so no model name is hardcoded.
- Privacy: only the caption text of the clips is sent. No video or audio is uploaded. The key is stored encrypted (Android Keystore).
- The old "Claude" button only worked inside a preview window, never in the real APK; it is replaced.
- On-device scoring (audio loudness, motion, punch moments) works with no key and no internet.

## 3.6 Key Save button + Neon story look
- Settings > Groq key and Gemini key now have **Save key** and **Clear** buttons. A status line shows "Saved on this phone ✓ (ends …xxxx)" after a read-back check. Pasting also saves by itself.
- Settings > **Edit look** is now a list: Reaction, **Neon story** (new), Plain.
- Neon story (from the Qatar reference video): full screen, richer colours, the clip title typed letter by letter (yellow first line, glowing green second line), zoom + flash + glitch on loud moments, spoken captions moved lower.
- Not tested on a phone. Not compiled here (no Android SDK). If the GitHub build fails, send the first line with `error:`.

## 3.7 Face cam cutaways (new)
- Settings > **Face cam cutaways**: choose a second video (for example a face reaction). The exported clip opens on the face cam, then cuts between your main video and the face cam. Settings: how many seconds each one shows.
- Sound stays continuous from the main video (face cam sound is not used). Every cut gets a zoom, flash and glitch.
- Phone export only. The editor preview stays plain.
- Not compiled or tested on a phone. It uses `Composition.Builder.setEffects` (Media3 1.4.1). If the build says it cannot find that method, send me the line.

## 3.8 Highlight captions + zoom blur (new)
- Settings > Captions > Style > **Highlight**: all words white, the spoken word lit green, yellow or red with a glow. Sits at the top of the screen (in the Reaction look it stays in the lower area).
- **Zoom blur** now kicks on every loud moment and every face cam cut (`FxEffect.java`), together with the flash and glitch.
- Not compiled or tested on a phone. If the GitHub build fails, send me the first line that starts with `error:`.

## 4.0 New design: three steps (M22)
- The app is now three steps: **1 Import**, **2 AI works**, **3 Review and post**. A step bar at the top shows where you are. The bottom bar has Create, Library and Settings.
- **Import**: one big "Make my clips with AI" button. Captions file and auto-caption moved into "More options". A note shows if the Groq or Gemini key is missing.
- **AI works**: big percent, 4 live steps (listen, high points, pick clips, plan the edit of each clip), and the loudness curve.
- **Review and post**: tabs Edit / Captions / Post. Clip cards scroll sideways. A new "AI edit plan" card shows the hook, zooms, sound effects, transition and beat pulse. Trim, Look, Captions and Music are folded sections. Export and YouTube upload are on the Post tab.
- All buttons and ids from before are kept, so export, captions and upload work the same way.
- Tested in a desktop browser (layout, navigation, no console errors, no sideways scroll at 360, 390 and 1100 px). Not built or run on a phone.
- TikTok and Instagram: no automatic posting (they do not allow it for personal apps). Use Library > Share.

## 4.1 Settings and Library design (M23)
- **Settings** now starts with an **AI keys** card (Groq and Gemini, with Save key). The other settings are folded sections: Clips, Captions, Look of the clip, Face cam cutaways, Music, YouTube upload, Your name on the clips. All settings keep their old names.
- **Library** is now "Your clips": rounded cards with a thumbnail, Play, Share and Delete.
- Privacy text fixed: the AI editor sends 4 small pictures per clip to Gemini (plus the caption text). The full video and the audio are not uploaded.

## 4.2 Step B: face tracking and speed ramps (M24)
- **Face tracking**: the phone looks at the clip about once a second (on the device, using Android's own face finder: no internet, no Gemini limit, nothing uploaded). The crop then **pans to keep the speaker in frame** instead of sitting on one fixed spot. A dead zone and a speed limit keep the camera calm. If no face is found in enough pictures, the old single AI crop is used. Files: `FaceTrack.java`, `shared/core/motion.js` (`buildTrack`), pan in `ClipExporter.reframe()`.
- **Speed ramps**: the AI editor may add up to 3 speed changes per clip: slow motion on a punchline or reveal, and a fast part over boring stretches. Picture and voice change speed together (voice pitch stays). Captions, zoom punches, sound effects, music length and the progress bar are moved onto the new timeline. Files: `SpeedRamp.java`, `shared/core/motion.js` (`cleanRamps`, `mapTime`).
- Settings > Clips: two switches, **Follow the face** and **Speed ramps** (both on by default).
- Limits: speed ramps are skipped while face cam cutaways are on. The editor preview does not play ramps (the exported clip does). The speed change is a straight step, not a gradual ease.
- Tests: `node shared/test/motion.test.js` (15 tests, run in CI). The Java is **not compiled or run here** (no Android SDK). The ramp code uses `SpeedChangeEffect` and `SonicAudioProcessor` from Media3 1.4.1. If the GitHub build fails, send the first line that starts with `error:`.


## 4.2.1 Gemini limit fix (M25)
- Free Gemini limits are counted **per model**. The app now keeps a ranked list of the models your key can use (newest "flash" first, "flash-lite" as fallbacks, which have much bigger free limits) and switches to the next model when one answers 429 (limit), 404 or 5xx. The model that works is remembered.
- A **daily** limit is now recognised. The app stops at once and says so ("The free Gemini daily limit is used up…") instead of waiting 65 s five times. Per-minute limits still wait, at most 3 times.
- Not compiled here. If the GitHub build fails, send the first line that starts with `error:`.

## 4.3 OpenRouter free models (M26)
- Settings > AI keys: new **OpenRouter key**, **Which AI decides the edits** (Gemini / OpenRouter free models / Gemini first, OpenRouter when Gemini is out) and **OpenRouter model** (Auto, or one model from the list; the button *Load free models* fills the list, 👁 = can see pictures).
- All AI steps go through the same bridge call, so the choice applies everywhere: finding moments, ranking, the edit plan, titles, music picks. `OpenRouterClient.java` is the new client; `MainActivity.gemini()` routes by provider.
- Free OpenRouter limits are shared by every `:free` model: 20 requests a minute, 50 a day (1,000 a day after buying credits). So the client never retries a limit answer, tries at most 3 models per request, caches answers, and stops with a clear "daily limit" message.
- Models that cannot see pictures get the caption text only (the page then simply has no subjectX; face tracking still works because it runs on the phone).
- Privacy note is in Settings: free models are run by outside companies and some may keep or train on prompts.
- Not compiled here. If the GitHub build fails, send the first line that starts with `error:`.

## 4.3.1 Scan fix and clean captions (M27)
- **Fixed "The AI could not plan usable clips".** Free AI models often return times as text ("12:30"), windows under 8 s, or windows that overlap. The app used to silently drop every such pick. Now `shared/core/clips.js` reads times in any format, stretches short windows to at least 15 s, trims long ones, removes overlaps, and tops up from the best candidates when the AI picks too few. 9 tests (`node shared/test/clips.test.js`, run in CI).
- **New default caption style "Clean"** (Settings > Captions > Style; Auto now means Clean): Poppins ExtraBold, white words, the spoken word lit yellow, thick black outline, soft shadow, a small 7% pop, no glow, no tilt, no gradients. Same look in the editor preview (`drawCap`) and in the export (`Painter.caption`). Older styles are still in the list. A one-time migration resets caption style and font to Auto.
- Captions come from Groq Whisper large-v3 with word timing (unchanged). For best accuracy set the spoken language in Settings instead of Auto, and fix any word in the "Caption lines" editor.
- Not compiled here. If the GitHub build fails, send the first line that starts with `error:`.

## 4.3.2 Stability and security pass (M28)
**The "AI scan failed: java.lang.IllegalStateException" at 0%**
- The phone's audio reader and decoder answer with a bare `IllegalStateException` (no text) when they cannot read a picked video or the decoder is in a bad state. The old code had one way to read the audio and no fallback. New `AudioDecode.java` tries: the picked link with the standard decoder, then the phone's software decoder, then a private copy of the video with both decoders. It also reads float audio, cannot hang (a decoder silent for 20 s counts as failed) and drops partial data between tries.
- Captions and the loudness curve now share one decode (`Energy.profileFromPcm`), so a long video is decoded once instead of twice. A part with no speech still returns its loudness, so loud moments are not lost in music/gameplay stretches.
- One unreadable part no longer kills a 3-4 hour scan: each part gets two tries, then it is skipped and the scan continues.
- `Diag.java`: every native error now says what failed and where (for example `IllegalStateException (…) (AudioDecode.java:123)`). If an error still appears, send me that line.
- `MediaCache.java`: private copies are made only when needed, after a free-space check, keyed by link and size, and deleted when a new video is picked. Frames and face tracking also fall back to the copy.

**Bugs fixed**
- Cancelling an export left the page waiting forever and Export disabled until restart. The cancel now answers the page.
- Decoder loops could spin forever (no stop condition). Fixed in `AudioDecode`.
- Music file names like `..` could point outside the music folder. Names are cleaned.

**Security**
- The JavaScript bridge (which hands out API keys) only answers while the page shown is this app's own page.
- The page can only open, share, thumbnail, upload or delete clips that ClipForge itself saved (Gallery/MediaStore or the app's Movies folders), not arbitrary files.
- External links open only for `http(s)`; `intent:`, `file:` and `javascript:` links are ignored.

Not compiled with the real Android SDK here. The new files were compile-checked against API stubs and the page script was syntax-checked; all JS tests pass. If the GitHub build fails, send the first line that starts with `error:`.
