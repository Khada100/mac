#!/usr/bin/env python3
"""에뮬레이터 테스트 도우미 (uiautomator 덤프 파싱 / 템플릿 이미지 생성 / 스크린샷 로그 출력)."""
import base64
import io
import random
import re
import sys
import xml.etree.ElementTree as ET


def nodes(path):
    return list(ET.parse(path).getroot().iter("node"))


def bounds(n):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
    return tuple(map(int, m.groups())) if m else None


def cmd_find(path, attr, value):
    """attr == value 인 노드의 중심 좌표 'x y' 출력"""
    for n in nodes(path):
        if n.get(attr) == value:
            b = bounds(n)
            if b:
                print((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
                return 0
    return 1


def cmd_icons(path):
    """홈 화면에서 앱 아이콘 2개를 골라 'x1 y1 x2 y2' 두 줄 출력"""
    picked = []
    for n in nodes(path):
        pkg = n.get("package", "")
        desc = (n.get("content-desc") or n.get("text") or "").strip()
        b = bounds(n)
        if not b or "launcher" not in pkg or not desc or n.get("clickable") != "true":
            continue
        w, h = b[2] - b[0], b[3] - b[1]
        if 80 <= w <= 400 and 80 <= h <= 400 and b[1] > 400:
            picked.append((desc, b))
    for desc, b in picked[:2]:
        print(*b, desc.replace(" ", "_"))
    return 0 if len(picked) >= 2 else 1


def cmd_crop(src, x1, y1, x2, y2, out):
    from PIL import Image
    Image.open(src).convert("RGB").crop((int(x1), int(y1), int(x2), int(y2))).save(out)
    return 0


def cmd_noise(out, seed):
    """화면에 절대 없을 무작위 무늬 템플릿"""
    from PIL import Image
    rnd = random.Random(int(seed))
    img = Image.new("RGB", (64, 32))
    img.putdata([(rnd.randrange(256), rnd.randrange(256), rnd.randrange(256)) for _ in range(64 * 32)])
    img.save(out)
    return 0


def cmd_shot(src, name):
    """스크린샷을 작게 줄여 base64 로 로그에 출력 (아티팩트를 못 받는 환경에서 확인용)"""
    from PIL import Image
    img = Image.open(src).convert("RGB")
    img.thumbnail((540, 1200))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=60)
    print(f"=====SHOT {name}=====")
    data = base64.b64encode(buf.getvalue()).decode()
    for i in range(0, len(data), 4000):
        print(data[i:i + 4000])
    print("=====END=====")
    return 0


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    sys.exit(globals()["cmd_" + cmd](*args))
