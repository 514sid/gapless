#!/usr/bin/env python3
"""Generates the test media for the sample app.

Every clip shows its codec and format, a running clip timer, a frame counter, a strip that
lights one cell per frame and a bar that sweeps the width once a second. The first frame has
a green border and the last a red one, so a camera pointed at the screen shows whether a cut
drops or repeats frames. Audio, where a clip has it, chimes at the start (a different note
per clip) and gives a soft tick on every whole second.

Needs python3 with Pillow (see requirements.txt) and ffmpeg with libx264, libx265,
libvpx-vp9, libsvtav1, libopus and libmp3lame. Frames are drawn with Pillow's bundled font
and piped to ffmpeg raw, so ffmpeg doesn't need drawtext and the output doesn't depend on
the fonts installed on the machine.

    python3 -m venv .venv && .venv/bin/pip install -r samples/requirements.txt
    .venv/bin/python samples/generate.py                    # everything
    .venv/bin/python samples/generate.py --only h264_1080p30
"""

import argparse
import json
import random
import struct
import zlib
import os
import shutil
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_OUT = os.path.join(ROOT, "app", "src", "main", "assets", "samples")
ASSET_URI_PREFIX = "file:///android_asset/samples/"

WHITE = (255, 255, 255)
BLACK = (0, 0, 0)
GREY = (225, 225, 225)
GREEN = (0, 220, 0)
RED = (230, 0, 0)

# One background per clip, picked so neighbours in a playlist look different.
PALETTE = [
    (31, 78, 160), (196, 84, 20), (30, 130, 70), (128, 40, 140), (160, 30, 50),
    (20, 120, 130), (120, 100, 20), (70, 70, 80), (180, 50, 110), (40, 90, 40),
    (90, 50, 30), (50, 60, 150), (150, 110, 10), (10, 100, 160),
]

# Start chimes: C major pentatonic from C4 to C5, so any two clips sound fine back to back.
CHIME_NOTES = [261.63, 293.66, 329.63, 392.00, 440.00, 523.25]
TICK_NOTE = 329.63  # E4

_fonts = {}


def font(size):
    size = max(8, int(size))
    if size not in _fonts:
        _fonts[size] = ImageFont.load_default(size=size)
    return _fonts[size]


def fitted(text, max_width, size):
    """The largest font no bigger than size that fits text in max_width."""
    while size > 8 and font(size).getlength(text) > max_width:
        size *= 0.95
    return font(size)


def label(draw, xy, text, f, fill=WHITE, anchor="la"):
    """Text with a black outline, so it reads on any background."""
    stroke = max(2, f.size // 14)
    draw.text(xy, text, font=f, fill=fill, anchor=anchor, stroke_width=stroke, stroke_fill=BLACK)


def border(draw, w, h, thickness, color):
    draw.rectangle([0, 0, w - 1, thickness - 1], fill=color)
    draw.rectangle([0, h - thickness, w - 1, h - 1], fill=color)
    draw.rectangle([0, 0, thickness - 1, h - 1], fill=color)
    draw.rectangle([w - thickness, 0, w - 1, h - 1], fill=color)


# Videos

VIDEOS = [
    dict(id="h264_1080p30", w=1920, h=1080, fps=30, vcodec="h264", audio="aac48"),
    dict(id="h264_1080p60", w=1920, h=1080, fps=60, vcodec="h264", audio="aac48"),
    dict(id="h264_1080p25", w=1920, h=1080, fps=25, vcodec="h264", audio="aac48"),
    dict(id="h264_720p30_noaudio", w=1280, h=720, fps=30, vcodec="h264", audio=None),
    dict(id="h264_720p30", w=1280, h=720, fps=30, vcodec="h264", audio="aac48"),
    dict(id="hevc_720p30", w=1280, h=720, fps=30, vcodec="hevc", audio="aac48"),
    dict(id="vp9_720p30", w=1280, h=720, fps=30, vcodec="vp9", audio="opus", container="webm"),
    dict(id="h264_1080p30_mp3", w=1920, h=1080, fps=30, vcodec="h264", audio="mp3_44"),
    dict(id="h264_1080x1920p30_portrait", w=1080, h=1920, fps=30, vcodec="h264", audio="aac48"),
    dict(id="h264_2160p30", w=3840, h=2160, fps=30, vcodec="h264", audio="aac48"),
    dict(id="hevc_1080p30", w=1920, h=1080, fps=30, vcodec="hevc", audio="aac48"),
    dict(id="hevc_2160p30_10bit", w=3840, h=2160, fps=30, vcodec="hevc10", audio="aac48"),
    dict(id="vp9_1080p30", w=1920, h=1080, fps=30, vcodec="vp9", audio="opus", container="webm"),
    dict(id="av1_1080p30", w=1920, h=1080, fps=30, vcodec="av1", audio="aac48"),
    dict(id="short_a_1s", w=1920, h=1080, fps=30, vcodec="h264", audio="aac48", seconds=1),
    dict(id="short_b_1s", w=1920, h=1080, fps=30, vcodec="h264", audio="aac48", seconds=1),
    # Valid files that most devices have no decoder for.
    dict(id="broken_hevc_444", w=1920, h=1080, fps=30, vcodec="hevc444", audio="aac48",
         seconds=3, broken=True),
    dict(id="broken_hevc_4320p", w=7680, h=4320, fps=30, vcodec="hevc", audio="aac48",
         seconds=2, broken=True),
]

CODEC_LABEL = {"h264": "H.264", "hevc": "HEVC", "hevc10": "HEVC 10-bit", "hevc444": "HEVC 4:4:4", "vp9": "VP9", "av1": "AV1"}
AUDIO_LABEL = {None: "No audio", "aac48": "AAC 48 kHz", "mp3_44": "MP3 44.1 kHz",
               "opus": "Opus 48 kHz"}
DEFAULT_SECONDS = 6
IMAGE_SECONDS = 4
WEB_SECONDS = 6


def video_encoder_args(v):
    gop = str(v["fps"])  # A keyframe every second.
    c = v["vcodec"]
    if c == "h264":
        level = "5.1" if v["w"] * v["h"] > 1920 * 1088 else "4.1" if v["fps"] <= 30 else "4.2"
        return ["-c:v", "libx264", "-profile:v", "high", "-level:v", level, "-preset", "slow",
                "-crf", "20", "-g", gop, "-pix_fmt", "yuv420p", "-x264-params", "log-level=error"]
    if c in ("hevc", "hevc10", "hevc444"):
        pix_fmt, profile = {"hevc": ("yuv420p", "main"), "hevc10": ("yuv420p10le", "main10"),
                            "hevc444": ("yuv444p", "main444-8")}[c]
        preset = "ultrafast" if v["w"] > 3840 else "medium"
        return ["-c:v", "libx265", "-preset", preset, "-crf", "22", "-tag:v", "hvc1",
                "-pix_fmt", pix_fmt, "-profile:v", profile,
                "-x265-params", f"keyint={gop}:min-keyint={gop}:log-level=error"]
    if c == "vp9":
        return ["-c:v", "libvpx-vp9", "-crf", "32", "-b:v", "0", "-row-mt", "1",
                "-deadline", "good", "-cpu-used", "4", "-g", gop, "-pix_fmt", "yuv420p"]
    if c == "av1":
        return ["-c:v", "libsvtav1", "-crf", "35", "-preset", "8", "-g", gop, "-pix_fmt", "yuv420p",
                "-svtav1-params", "tune=0:svt-log-level=1"]
    raise ValueError(c)


def audio_args(audio, seconds, chime):
    """lavfi input and encoder args for the chime-and-tick track, or ([], ["-an"]) for none."""
    if audio is None:
        return [], ["-an"]
    rate = 44100 if audio == "mp3_44" else 48000
    # Both sounds fade in over 5 ms (no click) and then decay, like a soft mallet.
    chime_expr = (f"0.35*sin(2*PI*{chime}*t)*min(1\\,t*200)*exp(-t*6)"
                  f"+0.12*sin(4*PI*{chime}*t)*min(1\\,t*200)*exp(-t*9)")
    x = "mod(t\\,1)"
    tick_expr = f"0.12*sin(2*PI*{TICK_NOTE}*t)*min(1\\,{x}*200)*exp(-{x}*25)*gte(t\\,0.5)"
    expr = f"{chime_expr}+{tick_expr}"
    src = ["-f", "lavfi", "-i", f"aevalsrc={expr}|{expr}:s={rate}:d={seconds}"]
    enc = {
        "aac48": ["-c:a", "aac", "-b:a", "128k"],
        "mp3_44": ["-c:a", "libmp3lame", "-b:a", "128k"],
        "opus": ["-c:a", "libopus", "-b:a", "96k"],
    }[audio]
    return src, enc


def render_video(v, index, out_dir):
    seconds = v.get("seconds", DEFAULT_SECONDS)
    container = v.get("container", "mp4")
    fps, w, h = v["fps"], v["w"], v["h"]
    frames = fps * seconds
    color = PALETTE[index % len(PALETTE)]
    chime = CHIME_NOTES[index % len(CHIME_NOTES)]
    u = min(w, h) / 1080  # 1.0 at 1080p

    # Static layer: labels that don't change between frames.
    margin = int(64 * u)  # Leaves room for the first and last frame's border.
    inner = w - 2 * margin
    base = Image.new("RGB", (w, h), color)
    d = ImageDraw.Draw(base)
    title = f"{CODEC_LABEL[v['vcodec']]}  {w} x {h}  {fps} fps"
    subtitle = f"{AUDIO_LABEL[v['audio']]}  ·  {container.upper()}  ·  {seconds} s"
    f_title = fitted(title, inner * 0.75, 84 * u)
    f_sub = fitted(subtitle, inner * 0.75, 52 * u)
    f_id = fitted(v["id"], inner * 0.75, 40 * u)
    y = margin
    label(d, (margin, y), title, f_title)
    y += int(f_title.size * 1.3)
    label(d, (margin, y), subtitle, f_sub)
    y += int(f_sub.size * 1.3)
    label(d, (margin, y), v["id"], f_id, GREY)
    labels_bottom = y + int(f_id.size * 1.5)

    strip_h = int(64 * u)
    strip_y = h - margin - strip_h
    cell_w = inner / fps
    for c in range(fps):
        d.rectangle([margin + c * cell_w + 2, strip_y, margin + (c + 1) * cell_w - 3, strip_y + strip_h], fill=BLACK)
    bar_w = max(4, int(14 * u))
    bar_bottom = strip_y - int(16 * u)

    f_timer = fitted("0:00.000", inner * 0.9, 340 * u)
    f_frame = fitted("Frame 0000 / 0000", inner * 0.8, 96 * u)
    f_mark = fitted("FIRST", inner * 0.2, 110 * u)
    timer_cy = labels_bottom + (bar_bottom - labels_bottom) * 0.45
    frame_cy = timer_cy + f_timer.size * 0.5 + f_frame.size * 0.9

    path = os.path.join(out_dir, f"{v['id']}.{container}")
    a_src, a_enc = audio_args(v["audio"], seconds, chime)
    cmd = (
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", str(fps), "-i", "-"]
        + a_src
        + ["-vf", "scale=out_color_matrix=bt709:out_range=tv",
           "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
           "-color_range", "tv"]
        + video_encoder_args(v)
        + a_enc
        + (["-movflags", "+faststart"] if container == "mp4" else [])
        + ["-t", str(seconds), path]
    )
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    for n in range(frames):
        f = base.copy()
        d = ImageDraw.Draw(f)
        # Sweep bar: crosses the width once a second, behind the timer.
        x = (n % fps) * w / fps
        d.rectangle([x, labels_bottom, x + bar_w - 1, bar_bottom], fill=BLACK)
        ms = round(n * 1000 / fps)  # when this frame starts
        label(d, (w / 2, timer_cy), f"{ms // 60000}:{ms // 1000 % 60:02d}.{ms % 1000:03d}",
              f_timer, anchor="mm")
        label(d, (w / 2, frame_cy), f"Frame {n + 1:04d} / {frames:04d}", f_frame, anchor="mm")
        c = n % fps
        d.rectangle([margin + c * cell_w + 2, strip_y, margin + (c + 1) * cell_w - 3, strip_y + strip_h], fill=WHITE)
        if n == 0 or n == frames - 1:
            mark, text = (GREEN, "FIRST") if n == 0 else (RED, "LAST")
            border(d, w, h, int(36 * u), mark)
            label(d, (w - margin, margin), text, f_mark, mark, anchor="ra")
        proc.stdin.write(f.tobytes())
    proc.stdin.close()
    if proc.wait() != 0:
        sys.exit(f"ffmpeg failed for {v['id']}")


# Images

IMAGES = [
    dict(id="image_1920x1080_jpg", w=1920, h=1080, fmt="jpg"),
    dict(id="image_1920x1080_png", w=1920, h=1080, fmt="png"),
    dict(id="image_1920x1080_webp", w=1920, h=1080, fmt="webp"),
    dict(id="image_1080x1920_portrait_jpg", w=1080, h=1920, fmt="jpg"),
    dict(id="image_3840x2160_jpg", w=3840, h=2160, fmt="jpg"),
    dict(id="image_8000x6000_huge_jpg", w=8000, h=6000, fmt="jpg"),
    dict(id="image_640x360_small_png", w=640, h=360, fmt="png"),
    dict(id="image_1920x1080_alpha_png", w=1920, h=1080, fmt="png", alpha=True),
]

IMAGE_MIME = {"jpg": "image/jpeg", "png": "image/png", "webp": "image/webp"}


def render_image(img, index, out_dir):
    w, h = img["w"], img["h"]
    alpha = img.get("alpha", False)
    color = PALETTE[(index + 5) % len(PALETTE)]
    im = Image.new("RGBA" if alpha else "RGB", (w, h), color + ((255,) if alpha else ()))
    d = ImageDraw.Draw(im)
    u = min(w, h) / 1080
    if alpha:
        # The left half is see-through, so whatever is behind the player shows there.
        d.rectangle([0, 0, w // 2 - 1, h - 1], fill=(0, 0, 0, 0))
    # Corner squares and a border show whether the image is cropped or letterboxed.
    border(d, w, h, max(3, int(12 * u)), WHITE)
    corner = max(12, int(120 * u))
    for x, y in ((0, 0), (w - corner, 0), (0, h - corner), (w - corner, h - corner)):
        d.rectangle([x, y, x + corner - 1, y + corner - 1], fill=WHITE)
    inner = w - 2 * corner - int(80 * u)
    title = f"Image  {img['fmt'].upper()}  {w} x {h}"
    f_title = fitted(title, inner, 96 * u)
    f_id = fitted(img["id"], inner, 52 * u)
    y = corner + int(40 * u)
    label(d, (w / 2, y), title, f_title, anchor="ma")
    label(d, (w / 2, y + f_title.size * 1.3), img["id"], f_id, GREY, anchor="ma")
    big = "Transparent left half" if alpha else "Still image"
    label(d, (w / 2, h / 2 + 60 * u), big, fitted(big, inner, 200 * u), anchor="mm")

    path = os.path.join(out_dir, f"{img['id']}.{img['fmt']}")
    if img["fmt"] == "jpg":
        im.save(path, "JPEG", quality=90, optimize=True)
    elif img["fmt"] == "webp":
        im.save(path, "WEBP", quality=85, method=6)
    else:
        im.save(path, "PNG", optimize=True)


# Animated images and vector graphics. Each animation frame shows its number, so a cut that
# starts the animation part way through, or a loop count that isn't kept, shows on screen.

ANIM_FRAMES = 12
ANIM_FRAME_MS = 100
ANIMATIONS = [
    dict(id="image_animated_gif", fmt="gif", w=640, h=360),
    dict(id="image_animated_webp", fmt="webp", w=640, h=360),
]
ANIM_MIME = {"gif": "image/gif", "webp": "image/webp"}


def render_animation(anim, index, out_dir):
    w, h = anim["w"], anim["h"]
    color = PALETTE[(index + 2) % len(PALETTE)]
    u = min(w, h) / 1080
    frames = []
    for n in range(1, ANIM_FRAMES + 1):
        im = Image.new("RGB", (w, h), color)
        d = ImageDraw.Draw(im)
        border(d, w, h, max(3, int(12 * u)), WHITE)
        title = f"Animated {anim['fmt'].upper()}  {w} x {h}"
        label(d, (w / 2, 40 * u), title, fitted(title, w * 0.9, 96 * u), anchor="ma")
        big = f"{n} / {ANIM_FRAMES}"
        label(d, (w / 2, h / 2 + 40 * u), big, fitted(big, w * 0.8, 360 * u), anchor="mm")
        # A bar that fills over the loop, so a stopped animation shows where it stopped.
        bar_y = h - int(120 * u)
        d.rectangle([int(60 * u), bar_y, int(60 * u + (w - 120 * u) * n / ANIM_FRAMES), bar_y + int(40 * u)], fill=WHITE)
        frames.append(im)
    path = os.path.join(out_dir, f"{anim['id']}.{anim['fmt']}")
    if anim["fmt"] == "gif":
        frames[0].save(path, "GIF", save_all=True, append_images=frames[1:], duration=ANIM_FRAME_MS,
                       loop=0, optimize=True)
    else:
        frames[0].save(path, "WEBP", save_all=True, append_images=frames[1:], duration=ANIM_FRAME_MS,
                       loop=0, quality=80, method=6)


SVG_IMAGE = """<svg xmlns="http://www.w3.org/2000/svg" width="1920" height="1080" viewBox="0 0 1920 1080">
  <rect width="1920" height="1080" fill="#2a6f97"/>
  <rect x="6" y="6" width="1908" height="1068" fill="none" stroke="#fff" stroke-width="12"/>
  <circle cx="480" cy="540" r="260" fill="#f4a261"/>
  <polygon points="1440,280 1700,800 1180,800" fill="#e9c46a"/>
  <text x="960" y="200" font-family="sans-serif" font-size="96" fill="#fff" text-anchor="middle">Image  SVG  1920 x 1080</text>
  <text x="960" y="980" font-family="sans-serif" font-size="140" fill="#fff" text-anchor="middle">Vector image</text>
</svg>
"""


# Broken assets. Each should end in an error event, and the player should carry on with the
# next asset instead of crashing or stalling.

def write_garbage(path, size, seed):
    rng = random.Random(seed)
    with open(path, "wb") as fh:
        fh.write(bytes(rng.getrandbits(8) for _ in range(size)))


def write_truncated(src, path, fraction):
    with open(src, "rb") as fh:
        data = fh.read()
    with open(path, "wb") as fh:
        fh.write(data[: int(len(data) * fraction)])


def write_png_bomb(path, w, h):
    """A one-colour PNG that is tiny on disk but w*h*4 bytes once decoded."""
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    comp = zlib.compressobj(9)
    row = b"\x00" + b"\x00\x80\xff" * w  # filter byte, then one RGB colour
    body = b"".join(comp.compress(row) for _ in range(h)) + comp.flush()
    with open(path, "wb") as fh:
        fh.write(b"\x89PNG\r\n\x1a\n")
        fh.write(chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)))
        fh.write(chunk(b"IDAT", body))
        fh.write(chunk(b"IEND", b""))


# Blocks the renderer's main thread before the first paint, as a hung renderer does.
HUNG_PAGE = """<!doctype html>
<html><body style="background:#400"><script>for (;;) {}</script></body></html>
"""


def render_broken(out_dir, wanted):
    """Writes the damaged files and returns their manifest entries."""
    good_video = os.path.join(out_dir, "h264_1080p30.mp4")
    good_image = os.path.join(out_dir, "image_3840x2160_jpg.jpg")
    for need in (good_video, good_image):
        if not os.path.exists(need):
            sys.exit(f"{os.path.basename(need)} is needed to make the broken assets")

    def video(id, file, note, mime="video/mp4", duration=6000):
        return dict(id=id, file=file, mimeType=mime, width=1920, height=1080,
                    durationMs=duration, note=note)

    def image(id, file, note, mime, w=1920, h=1080):
        return dict(id=id, file=file, mimeType=mime, width=w, height=h,
                    durationMs=IMAGE_SECONDS * 1000, note=note)

    entries = [
        video("broken_truncated_mp4", "broken_truncated_mp4.mp4",
              "First 40% of h264_1080p30: plays, then fails partway through"),
        video("broken_corrupt_bitstream_mp4", "broken_corrupt_bitstream_mp4.mp4",
              "Valid container, H.264 packets with random bytes flipped"),
        video("broken_garbage_mp4", "broken_garbage_mp4.mp4", "Random bytes named .mp4"),
        video("broken_empty_mp4", "broken_empty_mp4.mp4", "Zero-byte file"),
        video("broken_missing_mp4", "broken_missing_mp4.mp4", "No such file in the APK"),
        video("broken_image_as_video", "image_1920x1080_jpg.jpg",
              "A JPEG with a video MIME type", duration=4000),
        image("broken_garbage_jpg", "broken_garbage_jpg.jpg", "Random bytes named .jpg",
              "image/jpeg"),
        image("broken_truncated_jpg", "broken_truncated_jpg.jpg",
              "First half of a 4K JPEG; some decoders show the top half, some fail",
              "image/jpeg", 3840, 2160),
        image("broken_empty_png", "broken_empty_png.png", "Zero-byte file", "image/png"),
        image("broken_missing_png", "broken_missing_png.png", "No such file in the APK",
              "image/png"),
        image("broken_png_bomb", "broken_png_bomb.png",
              "20000x20000 PNG, 1.6 GB once decoded at full size", "image/png", 20000, 20000),
        image("broken_video_as_image", "h264_1080p30.mp4", "An MP4 with an image MIME type",
              "image/jpeg"),
        dict(id="broken_missing_web", file="broken_missing_web.html", mimeType="text/html",
             durationMs=WEB_SECONDS * 1000, note="No such page in the APK"),
        dict(id="broken_unreachable_web", uri="https://unreachable.invalid/", mimeType="text/html",
             durationMs=WEB_SECONDS * 1000, note="A host that never resolves"),
        dict(id="broken_hung_web", file="broken_hung_web.html", mimeType="text/html",
             durationMs=WEB_SECONDS * 1000,
             note="A script that never returns, so the WebView renderer hangs before it paints"),
    ]

    files = {e["id"]: e.get("file") for e in entries}

    def make(id, fn):
        if wanted(id):
            print(f"broken {id}", flush=True)
            fn(os.path.join(out_dir, files[id]))

    make("broken_truncated_mp4", lambda p: write_truncated(good_video, p, 0.4))
    make("broken_corrupt_bitstream_mp4", lambda p: subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", good_video, "-c", "copy",
         "-bsf:v", "noise=amount=2000", p], check=True))
    make("broken_garbage_mp4", lambda p: write_garbage(p, 256 * 1024, 1))
    make("broken_empty_mp4", lambda p: open(p, "wb").close())
    make("broken_garbage_jpg", lambda p: write_garbage(p, 64 * 1024, 2))
    make("broken_truncated_jpg", lambda p: write_truncated(good_image, p, 0.5))
    make("broken_empty_png", lambda p: open(p, "wb").close())
    make("broken_png_bomb", lambda p: write_png_bomb(p, 20000, 20000))
    make("broken_hung_web", lambda p: open(p, "w").write(HUNG_PAGE))
    return entries


# Real websites, loaded live, to see how the web player copes with what people actually put on
# screens: news with ads and consent banners, WebGL maps and 3D, dashboards, an embedded YouTube
# video that needs a decoder of its own, and light pages for contrast. They need a network and
# change without notice, so they test behaviour, not exact output.
WEBSITE_SECONDS = 15
WEBSITES = [
    ("web_wikipedia", "https://en.wikipedia.org/wiki/Television", "Plain article, light"),
    ("web_hacker_news", "https://news.ycombinator.com/", "Plain HTML, very light"),
    ("web_bbc_news", "https://www.bbc.com/news", "News front page with a consent banner"),
    ("web_guardian", "https://www.theguardian.com/international", "News front page, many images"),
    ("web_cnn", "https://edition.cnn.com/", "News front page, heavy scripts and ads"),
    ("web_accuweather", "https://www.accuweather.com/", "Weather, ad-heavy"),
    ("web_time_is", "https://time.is/", "Live clock, updates every second"),
    ("web_windy", "https://www.windy.com/", "WebGL weather map with animated wind"),
    ("web_osm", "https://www.openstreetmap.org/#map=12/51.5074/-0.1278", "Tiled map"),
    ("web_google_maps", "https://www.google.com/maps/@51.5074,-0.1278,13z", "Heavy map app"),
    ("web_threejs", "https://threejs.org/examples/webgl_animation_keyframes.html",
     "WebGL 3D animation"),
    ("web_grafana", "https://play.grafana.org/", "Dashboard with live charts"),
    ("web_github", "https://github.com/514sid/gapless", "Repository page"),
    ("web_reddit", "https://www.reddit.com/r/television/", "Infinite feed, heavy scripts"),
    ("web_amazon", "https://www.amazon.com/", "Shop front page, heavy"),
    ("web_youtube_embed", "https://www.youtube.com/embed/aqz-KE-bpKQ?autoplay=1&mute=1",
     "Embedded video: the page needs a video decoder next to ExoPlayer's"),
]


# Scripts run in web pages through GaplessAsset.javaScript, at document start.

# Hides the common cookie consent banners and the scroll lock they put on the page, before the
# first paint. Container names of the consent platforms the news sites above use.
HIDE_CONSENT_JS = """(function () {
  var css = '[id^="sp_message_container"], .fc-consent-root, #onetrust-consent-sdk,' +
    ' #CybotCookiebotDialog, .qc-cmp2-container, #didomi-host, #usercentrics-root, #cmpbox,' +
    ' #cmpbox2, .bbccookies-banner { display: none !important }' +
    ' html, body { overflow: auto !important }';
  var style = document.createElement('style');
  style.textContent = css;
  (document.head || document.documentElement).appendChild(style);
})();"""

# Shows the page only once it has fully loaded, then scrolls it slowly.
READY_AND_SCROLL_JS = """window.addEventListener('load', function () {
  if (window.gapless) gapless.ready();
  setInterval(function () { window.scrollBy(0, 1); }, 20);
});"""

# For web_script_check: marks that it ran, and hides the page's fake banner.
SCRIPT_CHECK_JS = """window.gaplessScriptRan = true;
var style = document.createElement('style');
style.textContent = '#banner { display: none !important }';
(document.head || document.documentElement).appendChild(style);"""

WEB_PAGE_STYLE = """<style>
  html, body { margin: 0; height: 100%; color: #fff; font-family: sans-serif; overflow: hidden; }
  .big { position: absolute; top: 30vh; width: 100%; text-align: center; font-size: 14vh; font-weight: bold; }
  .small { position: absolute; top: 55vh; width: 100%; text-align: center; font-size: 4vh; }
  .label { position: absolute; top: 3vh; left: 3vw; font-size: 4vh; }
</style>"""

# Reports whether the injected script ran before the page's own scripts; a fake cookie banner
# shows unless the script hid it.
SCRIPT_CHECK_PAGE = f"""<!doctype html>
<html><head><meta charset="utf-8">{WEB_PAGE_STYLE}</head>
<body style="background:#333">
<div class="label">SCRIPT CHECK<br>web_script_check</div>
<div class="big" id="result">?</div>
<div class="small">the red cookie banner below should not appear</div>
<div id="banner" style="position:absolute;bottom:0;width:100%;height:22vh;background:#c00;font-size:6vh;text-align:center;line-height:22vh">FAKE COOKIE BANNER</div>
<script>
  var ran = window.gaplessScriptRan === true;
  document.body.style.background = ran ? '#063' : '#600';
  document.getElementById('result').textContent = ran ? 'SCRIPT RAN FIRST' : 'NO SCRIPT';
</script>
</body></html>
"""

def ready_page(delay_s, calls):
    title = "READY AFTER %d s" % delay_s if calls else "NEVER READY"
    name = "web_ready_%ds" % delay_s if calls else "web_ready_never"
    then = "if (window.gapless) gapless.ready();" if calls else ""
    return f"""<!doctype html>
<html><head><meta charset="utf-8">{WEB_PAGE_STYLE}</head>
<body style="background:#900">
<div class="label">{title}<br>{name}</div>
<div class="big" id="state">LOADING</div>
<div class="small">red LOADING should never reach the screen; green READY should</div>
<script>
  setTimeout(function () {{
    {'document.body.style.background = "#063"; document.getElementById("state").textContent = "READY";' if calls else ''}
    {then}
  }}, {delay_s * 1000});
</script>
</body></html>
"""


# Playlists. The sample app plays one in a loop.

PLAYLISTS = {
    "codecs": ["h264_1080p30", "hevc_1080p30", "vp9_1080p30", "av1_1080p30", "h264_2160p30",
               "hevc_2160p30_10bit", "h264_1080p30_mp3", "h264_720p30_noaudio"],
    "framerates": ["h264_1080p25", "h264_1080p30", "h264_1080p60"],
    "fast": ["short_a_1s", "short_b_1s"],
    "audio": ["h264_1080p30", "h264_1080p30_mp3", "vp9_1080p30", "h264_720p30_noaudio"],
    "images": [i["id"] for i in IMAGES],
    "mixed": ["h264_1080p30", "image_1920x1080_jpg", "web_timer", "hevc_1080p30",
              "image_8000x6000_huge_jpg", "vp9_1080p30", "image_1920x1080_alpha_png"],
    "portrait": ["h264_1080x1920p30_portrait", "image_1080x1920_portrait_jpg"],
    # Animations start from their first frame at each cut; the 3-loop one stops on its last frame.
    "animated": ["image_animated_gif", "h264_1080p30", "image_animated_gif_3_loops",
                 "image_animated_webp", "image_svg", "image_1920x1080_jpg"],
    # A day-long run: every codec, frame rate and image type, a web page, short clips back to
    # back, and a few broken assets spread through, so each loop exercises the failure paths too.
    # About two minutes a loop; 4K clips drop out on Full HD devices.
    "soak": [
        "h264_1080p30", "image_1920x1080_jpg", "hevc_1080p30", "web_timer", "vp9_1080p30",
        "image_8000x6000_huge_jpg", "av1_1080p30", "h264_1080p60", "image_1920x1080_alpha_png",
        "h264_1080p25", "broken_truncated_mp4", "h264_1080p30_mp3", "image_1080x1920_portrait_jpg",
        "h264_720p30_noaudio", "broken_garbage_jpg", "short_a_1s", "short_b_1s", "short_a_1s",
        "image_1920x1080_webp", "h264_1080x1920p30_portrait", "h264_2160p30", "broken_missing_web",
        "hevc_720p30", "image_640x360_small_png", "vp9_720p30", "image_3840x2160_jpg",
    ],
    # For two players side by side: each half of a 1080p screen is under 720p anyway.
    "split_720p": ["h264_720p30", "hevc_720p30", "vp9_720p30"],
    # Every website once, with a video or image between them so each cut also switches players.
    "websites": [x for i, (site, _, _) in enumerate(WEBSITES)
                 for x in (site, ["h264_1080p30", "image_1920x1080_jpg", "hevc_1080p30",
                                  "vp9_1080p30"][i % 4])],
    "websites_only": [site for site, _, _ in WEBSITES],
    # Scripts injected into pages, and pages shown only once they call gapless.ready().
    "web_scripts": ["web_script_check", "h264_1080p30", "web_ready_8s", "hevc_1080p30",
                    "web_ready_never", "h264_720p30", "web_bbc_news", "vp9_1080p30",
                    "web_guardian", "image_1920x1080_jpg", "web_wikipedia", "h264_1080p30"],
}


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--only", nargs="*", help="ids to (re)generate; the manifest is always rewritten")
    args = ap.parse_args()
    if shutil.which("ffmpeg") is None:
        sys.exit("ffmpeg not found")
    os.makedirs(args.out, exist_ok=True)
    wanted = lambda i: not args.only or i in args.only

    assets = []
    for i, v in enumerate(VIDEOS):
        container = v.get("container", "mp4")
        if wanted(v["id"]):
            print(f"video {v['id']}", flush=True)
            render_video(v, i, args.out)
        assets.append(dict(id=v["id"], file=f"{v['id']}.{container}",
                           mimeType=f"video/{container}", width=v["w"], height=v["h"],
                           durationMs=v.get("seconds", DEFAULT_SECONDS) * 1000))
    for i, img in enumerate(IMAGES):
        if wanted(img["id"]):
            print(f"image {img['id']}", flush=True)
            render_image(img, i, args.out)
        assets.append(dict(id=img["id"], file=f"{img['id']}.{img['fmt']}",
                           mimeType=IMAGE_MIME[img["fmt"]], width=img["w"], height=img["h"],
                           durationMs=IMAGE_SECONDS * 1000))
    for i, anim in enumerate(ANIMATIONS):
        if wanted(anim["id"]):
            print(f"animation {anim['id']}", flush=True)
            render_animation(anim, i, args.out)
        assets.append(dict(id=anim["id"], file=f"{anim['id']}.{anim['fmt']}",
                           mimeType=ANIM_MIME[anim["fmt"]], width=anim["w"], height=anim["h"],
                           durationMs=IMAGE_SECONDS * 1000))
    # The same GIF played three times, held on its last frame for the rest of a 6 second slot.
    assets.append(dict(id="image_animated_gif_3_loops", file="image_animated_gif.gif",
                       mimeType="image/gif", width=640, height=360, durationMs=6000,
                       animationLoops=3))
    with open(os.path.join(args.out, "image_svg.svg"), "w") as fh:
        fh.write(SVG_IMAGE)
    assets.append(dict(id="image_svg", file="image_svg.svg", mimeType="image/svg+xml",
                       width=1920, height=1080, durationMs=IMAGE_SECONDS * 1000))
    # The web page is hand-written.
    shutil.copy(os.path.join(ROOT, "samples", "web_timer.html"), args.out)
    assets.append(dict(id="web_timer", file="web_timer.html", mimeType="text/html",
                       durationMs=WEB_SECONDS * 1000))
    site_extras = {
        "web_bbc_news": dict(javaScript=HIDE_CONSENT_JS),
        "web_guardian": dict(javaScript=HIDE_CONSENT_JS),
        "web_cnn": dict(javaScript=HIDE_CONSENT_JS),
        "web_accuweather": dict(javaScript=HIDE_CONSENT_JS),
        "web_wikipedia": dict(javaScript=READY_AND_SCROLL_JS, waitForPageReady=True),
    }
    for site, url, note in WEBSITES:
        assets.append(dict(id=site, uri=url, mimeType="text/html",
                           durationMs=WEBSITE_SECONDS * 1000, note=note, **site_extras.get(site, {})))
    # Local pages for the script and readiness features.
    for name, page, extra in [
        ("web_script_check", SCRIPT_CHECK_PAGE, dict(javaScript=SCRIPT_CHECK_JS)),
        ("web_ready_8s", ready_page(8, True), dict(waitForPageReady=True)),
        ("web_ready_never", ready_page(0, False), dict(waitForPageReady=True)),
    ]:
        with open(os.path.join(args.out, name + ".html"), "w") as fh:
            fh.write(page)
        assets.append(dict(id=name, file=name + ".html", mimeType="text/html",
                           durationMs=WEB_SECONDS * 1000, **extra))
    broken = render_broken(args.out, wanted)
    broken_ids = [b["id"] for b in broken] + [v["id"] for v in VIDEOS if v.get("broken")]
    assets += broken

    ids = {a["id"] for a in assets}
    # "broken" puts a good clip after every bad asset, so the recovery is visible.
    broken_mix = []
    for bad in broken_ids:
        broken_mix += [bad, "h264_1080p30"]
    playlists = dict(PLAYLISTS, all=[a["id"] for a in assets if a["id"] not in broken_ids],
                     broken=broken_mix, broken_only=broken_ids)
    for name, items in playlists.items():
        missing = [i for i in items if i not in ids]
        if missing:
            sys.exit(f"playlist {name} names unknown ids {missing}")
    for a in assets:
        if a["id"] in broken_ids:
            a["broken"] = True
        if "file" in a:
            a["uri"] = ASSET_URI_PREFIX + a.pop("file")
    with open(os.path.join(args.out, "samples.json"), "w") as fh:
        json.dump(dict(assets=assets, playlists=playlists), fh, indent=2)
        fh.write("\n")
    print("wrote", os.path.relpath(args.out, ROOT))


if __name__ == "__main__":
    main()
