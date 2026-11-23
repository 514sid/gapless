#!/usr/bin/env python3
"""Compares synced playback on two or more devices from their logs.

    adb -s <device> shell setprop log.tag.GaplessTimeline DEBUG     # on each device, optional
    adb -s <leader> logcat -v time GaplessSync:V GaplessTimeline:D '*:S' > leader.txt
    adb -s <follower> logcat -v time GaplessSync:V GaplessTimeline:D '*:S' > follower.txt
    python3 samples/sync/compare.py leader.txt follower.txt

Each device logs, for every slot of the shared schedule, how late its asset appeared against the
shared clock. This lines the slots up across devices and prints how far apart the devices were
(the second file and later against the first), and how late each device was on its own.

With GaplessTimeline debug logging on, the library also logs once a second how far each playing
video is from its timeline. Lined up by shared clock second, that shows how far apart the devices'
pictures are during clips, not only at the cuts.
"""

import re
import statistics
import sys
from collections import defaultdict

# Older builds formatted decimals in the device's locale, so a comma may stand in for the point.
SLOT = re.compile(r"slot=(\d+) asset=(\S+) late=(-?\d+)ms offset=(-?[\d.,]+)ms rtt=([\d.,]+)ms")


def read(path):
    slots = {}
    for line in open(path, errors="replace"):
        m = SLOT.search(line)
        if m:
            slots[int(m.group(1))] = dict(asset=m.group(2), late=int(m.group(3)),
                                          offset=float(m.group(4).replace(",", ".")),
                                          rtt=float(m.group(5).replace(",", ".")))
    return slots


TIMELINE = re.compile(r"GaplessTimeline.*asset=(\S+) clock=(\d+) error=(-?\d+)ms speed=([\d.]+)")


def read_timeline(path):
    """(shared second, asset) -> (error ms, speed)."""
    points = {}
    for line in open(path, errors="replace"):
        m = TIMELINE.search(line)
        if m:
            points[(int(m.group(2)) // 1000, m.group(1))] = (int(m.group(3)), float(m.group(4)))
    return points


def kind(asset):
    if asset.startswith("web_"):
        return "web"
    if asset.startswith("image_"):
        return "image"
    return "video"


def summary(values):
    values = sorted(values)
    q = lambda f: values[min(len(values) - 1, int(f * len(values)))]
    return (f"median {statistics.median(values):.0f} ms, 95th percentile {q(0.95):.0f} ms, "
            f"worst {max(values, key=abs):.0f} ms")


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    devices = [(path, read(path)) for path in sys.argv[1:]]
    for path, slots in devices:
        if not slots:
            sys.exit(f"{path}: no slot lines; was it logged with GaplessSync:V?")

    print("How late each device showed its assets against the shared schedule:")
    for path, slots in devices:
        lates = [s["late"] for s in slots.values()]
        rtts = [s["rtt"] for s in slots.values()]
        print(f"- {path}: {len(slots)} slots, {summary(lates)}; "
              f"clock sync round trip median {statistics.median(rtts):.1f} ms")
    print()

    ref_path, ref = devices[0]
    for path, slots in devices[1:]:
        common = sorted(set(ref) & set(slots))
        if not common:
            print(f"{path} shares no slots with {ref_path}.")
            continue
        mismatched = [n for n in common if ref[n]["asset"] != slots[n]["asset"]]
        if mismatched:
            print(f"{path}: {len(mismatched)} slots show a different asset than {ref_path}, "
                  f"first slot {mismatched[0]}. The devices aren't on the same playlist.")
        diffs = [slots[n]["late"] - ref[n]["late"] for n in common]
        print(f"{path} against {ref_path}, {len(common)} slots in common:")
        print(f"- apart by {summary([abs(d) for d in diffs])}")
        print(f"- within one frame at 30 fps (33 ms): {sum(1 for d in diffs if abs(d) <= 33)} of {len(diffs)}")
        by_kind = defaultdict(list)
        for n, d in zip(common, diffs):
            by_kind[kind(ref[n]["asset"])].append(abs(d))
        for k, v in sorted(by_kind.items()):
            print(f"- {k}: {len(v)} slots, {summary(v)}")
        worst = sorted(common, key=lambda n: -abs(slots[n]["late"] - ref[n]["late"]))[:5]
        print("- worst slots: " + ", ".join(
            f"{n} {ref[n]['asset']} ({slots[n]['late'] - ref[n]['late']:+d} ms)" for n in worst))
        print()

    timelines = [(path, read_timeline(path)) for path, _ in devices]
    if all(points for _, points in timelines):
        print("During clips, from each device's distance to the timeline, once a second:")
        for path, points in timelines:
            errors = [e for e, _ in points.values()]
            sped = sum(1 for _, sp in points.values() if sp != 1.0)
            print(f"- {path}: {len(points)} readings, off its timeline by {summary([abs(e) for e in errors])}; "
                  f"speed corrected in {sped}")
        ref_path, ref_points = timelines[0]
        for path, points in timelines[1:]:
            common = sorted(set(ref_points) & set(points))
            if common:
                apart = [abs(points[k][0] - ref_points[k][0]) for k in common]
                print(f"- {path} against {ref_path}, {len(common)} seconds in common: pictures apart by {summary(apart)}; "
                      f"within one frame (33 ms): {sum(1 for a in apart if a <= 33)} of {len(apart)}")
        print()


if __name__ == "__main__":
    main()
