# Test media

`generate.py` writes the media the sample app plays into `app/src/main/assets/samples/`, along with `samples.json`, which lists every asset and the playlists below. The files are committed, so you only need to run it after changing it.

## What's on screen

Every clip shows:

- its codec, resolution, frame rate, audio and container at the top left
- a timer of the clip's own time, from 0:00.000, computed from the frame number
- `Frame n / total`
- a strip along the bottom that lights one cell per frame and wraps every second
- a black bar that sweeps the width once a second
- a green border and FIRST on its first frame, and a red border and LAST on its last

Film the screen with a phone's slow-motion camera and step through the cut. In a gapless cut, the LAST frame of one clip is followed directly by the FIRST frame of the next. A repeated or missing strip cell shows a dropped or doubled frame. A black frame between them is a gap.

Clips with audio play a chime at the start (a different note from C4 to C5 per clip) and a soft E4 tick on every whole second, so you can hear audio gaps and A/V drift as well. Images show corner squares and a border, so cropping and letterboxing show up. `web_timer.html` counts display frames in the browser instead of video frames.

Launch with `--ez clock true` to draw the device's uptime clock in the bottom right corner. A recording then shows real time next to the clip timer, so a gap can be measured in milliseconds.

## Playing a playlist

```sh
adb shell am start -S -n io.github.gapless.sample/.MainActivity --es playlist codecs
```

`-S` stops the running app first. `PlayerActivity` is single-instance, so the new playlist only takes effect on a fresh start. Other extras:

- `--ef volume 0` mutes the videos. The default is `1`.
- `--ez clock true` shows the device clock overlay.
- `--es ids a,b,c` plays just those assets, in that order, instead of a playlist.
- `--ei rotation 90` rotates the players clockwise (`90`, `180` or `270`), for a screen mounted on its side. With two players and `90` or `270`, they still sit side by side as the viewer sees them.
- `--ez fallback true` turns on `GaplessVideoConfig.enableDecoderFallback` (off by default), so a decoder that can't open falls back to a software one instead of failing.
- `--ei players 2` shows two players side by side, each with its own controller and schedule. The right one plays the same list shifted by one clip and is muted; `--es playlist2 <name>` or `--es ids2 a,b,c` give it its own list and `--ef volume2 1` unmutes it. Each player holds two decoders, so two players need four. On a TV with two hardware decoders, each player then switches to hard cuts, or with `--ez fallback true` the rest decode in software, which is why `split_720p` exists.
- `--ez uhd false` leaves out videos larger than 1920x1080 (the two 4K clips), for devices that only decode Full HD. `--ez uhd true` keeps them. Without the extra, the app keeps them only if the display has a mode above 1920x1080. Images and broken assets are never left out, so `broken_hevc_4320p` still tests a clip the decoder can't take.

With no extra, the app plays `mixed`. The playlist in use, and whether 4K is on, is logged under the `GaplessPlayer` tag.

| Playlist | What it tests |
| --- | --- |
| `codecs` | Cuts between H.264, HEVC, VP9, AV1, 4K H.264, 4K HEVC 10-bit, MP3 audio and no audio |
| `framerates` | 25, 30 and 60 fps back to back |
| `fast` | Two 1-second clips, so a cut every second |
| `audio` | AAC 48 kHz, MP3 44.1 kHz, Opus and no audio: sample rate and audio codec changes |
| `images` | Every image format and size |
| `mixed` | Video, image and web page in turn, including the 8000x6000 JPEG and the transparent PNG |
| `portrait` | A 1080x1920 clip and image |
| `split_720p` | H.264, HEVC and VP9 at 720p, for two players side by side (`--ei players 2`) |
| `soak` | A two-minute loop of every working codec, frame rate and image type, the web page, short clips back to back and three broken assets, for long runs |
| `all` | Every working asset |
| `broken` | Each broken asset followed by `h264_1080p30`, so you see the player recover |
| `broken_only` | Only the broken assets |

## Assets

### Videos

All are 6 seconds unless noted, with a keyframe every second.

| Id | Video | Audio | Container |
| --- | --- | --- | --- |
| `h264_1080p30` | H.264 High, 1920x1080, 30 fps | AAC 48 kHz | MP4 |
| `h264_1080p60` | H.264 High, 1920x1080, 60 fps | AAC 48 kHz | MP4 |
| `h264_1080p25` | H.264 High, 1920x1080, 25 fps | AAC 48 kHz | MP4 |
| `h264_720p30_noaudio` | H.264 High, 1280x720, 30 fps | none | MP4 |
| `h264_720p30` | H.264 High, 1280x720, 30 fps | AAC 48 kHz | MP4 |
| `hevc_720p30` | HEVC Main, 1280x720, 30 fps | AAC 48 kHz | MP4 |
| `vp9_720p30` | VP9 Profile 0, 1280x720, 30 fps | Opus 48 kHz | WebM |
| `h264_1080p30_mp3` | H.264 High, 1920x1080, 30 fps | MP3 44.1 kHz | MP4 |
| `h264_1080x1920p30_portrait` | H.264 High, 1080x1920, 30 fps | AAC 48 kHz | MP4 |
| `h264_2160p30` | H.264 High 5.1, 3840x2160, 30 fps | AAC 48 kHz | MP4 |
| `hevc_1080p30` | HEVC Main, 1920x1080, 30 fps | AAC 48 kHz | MP4 |
| `hevc_2160p30_10bit` | HEVC Main 10, 3840x2160, 30 fps | AAC 48 kHz | MP4 |
| `vp9_1080p30` | VP9 Profile 0, 1920x1080, 30 fps | Opus 48 kHz | WebM |
| `av1_1080p30` | AV1 Main, 1920x1080, 30 fps | AAC 48 kHz | MP4 |
| `short_a_1s`, `short_b_1s` | H.264 High, 1920x1080, 30 fps, 1 second | AAC 48 kHz | MP4 |

AV1 needs a hardware decoder or Media3's AV1 extension. On a device without one, `av1_1080p30` fails like the broken assets do.

### Images

Each is shown for 4 seconds: JPEG, PNG and WebP at 1920x1080, a 1080x1920 portrait JPEG, a 3840x2160 JPEG, an 8000x6000 JPEG that has to be downsampled, a 640x360 PNG that is scaled up, and a 1920x1080 PNG whose left half is transparent.

### Web

`web_timer` is a local page with a running timer, a frame strip and a sweeping bar, driven by `requestAnimationFrame`.

### Broken

Each of these should end in a `PlaybackError` or `PreloadError` (or, for some, play partly), and the player should carry on with the next asset. None should crash the app or leave the screen stuck.

| Id | What's wrong |
| --- | --- |
| `broken_hevc_444` | Valid HEVC 4:4:4 (Range Extensions), which almost no Android decoder supports |
| `broken_hevc_4320p` | Valid 7680x4320 HEVC, above most devices' decoder limits |
| `broken_truncated_mp4` | The first 40% of `h264_1080p30`: plays, then fails partway through |
| `broken_corrupt_bitstream_mp4` | A valid container whose H.264 packets have random bytes flipped |
| `broken_garbage_mp4` | Random bytes named `.mp4` |
| `broken_empty_mp4` | A zero-byte file |
| `broken_missing_mp4` | A file that isn't in the APK |
| `broken_image_as_video` | A JPEG with a `video/mp4` MIME type |
| `broken_garbage_jpg` | Random bytes named `.jpg` |
| `broken_truncated_jpg` | The first half of a 4K JPEG; some decoders draw the top half, others fail |
| `broken_empty_png` | A zero-byte file |
| `broken_missing_png` | A file that isn't in the APK |
| `broken_png_bomb` | A 1.2 MB PNG that is 20000x20000, 1.6 GB if decoded at full size |
| `broken_video_as_image` | An MP4 with an `image/jpeg` MIME type |
| `broken_missing_web` | A page that isn't in the APK |
| `broken_unreachable_web` | `https://unreachable.invalid/`, a host that never resolves |
| `broken_hung_web` | A script that never returns, so the WebView renderer hangs before it paints |

## Soak test

`soak/soak.sh` plays the `soak` playlist on a device for hours and records how it holds up:

```sh
samples/soak/soak.sh 24 300
```

The arguments are the length in hours (24 by default) and the seconds between samples (300). It keeps the computer awake with `caffeinate` and writes into `soak-<date>/` in the current directory:

- `logcat.txt`: the player's logs, reconnecting if adb drops.
- `samples.csv`: every interval, the device's uptime, the player's process id, its memory (total, Java, native, graphics), threads, open files and CPU time, system free memory and swap, load, and the codec processes' memory.
- `events.txt`: start, end, and anything the script did, such as relaunching the app after the player was gone for two samples.

When the run ends it writes `summary.md` with `soak/report.py`, which you can also run on a run in progress:

```sh
python3 samples/soak/report.py soak-20261122-2114
```

The summary covers reboots, restarts, crashes, ANRs and low-memory kills, starts and errors per asset (flagging errors on working assets), the hold at each video cut, and the first, last, highest and hourly trend of every resource. Set `ANDROID_SERIAL` to pick the device and `SOAK_EXTRAS` for launch extras, for example `SOAK_EXTRAS="--ef volume 1"`. The computer has to stay on and on the device's network for the whole run.

## Regenerating

You need ffmpeg with libx264, libx265, libvpx-vp9, libsvtav1, libopus and libmp3lame (Homebrew's `ffmpeg` has them all), and Pillow:

```sh
python3 -m venv .venv
```

```sh
.venv/bin/pip install -r samples/requirements.txt
```

```sh
.venv/bin/python samples/generate.py
```

`--only <id> ...` regenerates just those assets. The manifest is always rewritten. A full run takes about a minute. Text is drawn with the font bundled in Pillow, so the output doesn't depend on the fonts installed on the machine.
