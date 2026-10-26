#!/usr/bin/env python3
"""Report focusable nodes that a screen reader cannot name.

A node is nameable when it, or something inside it, has non-empty text or a content description.
TalkBack announces the focusable node, not the TextView sitting beside it, so a `Switch` without its
own label is announced as "switch, off" with no subject - a real defect that no unit test can see on
the device and no screenshot shows.

Usage, on a device or emulator:

    adb shell uiautomator dump /sdcard/ui.xml
    adb shell cat /sdcard/ui.xml > ui.xml
    python3 tools/a11y-audit.py ui.xml

Exit code 1 when an unlabelled focusable node is found, so the check can gate a release walk.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Containers are focusable on some Android versions and are named by their children; a bare
# scroll view or list is not something a reader has to announce on its own.
CONTAINER_CLASSES = (
    "android.widget.FrameLayout",
    "android.widget.LinearLayout",
    "android.widget.RelativeLayout",
    "android.widget.ScrollView",
    "androidx.recyclerview.widget.RecyclerView",
    "android.widget.HorizontalScrollView",
)


def label_of(node: ET.Element) -> str:
    text = (node.get("text") or "").strip()
    description = (node.get("content-desc") or "").strip()
    return text or description


def has_labelled_child(node: ET.Element) -> bool:
    for child in node.iter():
        if child is node:
            continue
        if label_of(child):
            return True
    return False


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print(__doc__)
        return 2
    tree = ET.parse(Path(argv[0]))
    unlabelled = []
    total = 0
    for node in tree.iter("node"):
        if node.get("focusable") != "true":
            continue
        total += 1
        if label_of(node):
            continue
        if node.get("class") in CONTAINER_CLASSES and has_labelled_child(node):
            continue
        unlabelled.append(node)
    for node in unlabelled:
        print(
            "unlabelled:",
            node.get("class"),
            node.get("bounds"),
            "text=" + repr(node.get("text")),
            "desc=" + repr(node.get("content-desc")),
        )
    print(f"{total} focusable nodes, {len(unlabelled)} without a name")
    return 1 if unlabelled else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
