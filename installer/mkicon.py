# -*- coding: utf-8 -*-
"""生成 SuperAutoStudy 程序图标（多尺寸 .ico）。"""
from PIL import Image, ImageDraw

SIZE = 256
BG = (17, 24, 39, 255)        # #111827
ACCENT = (34, 211, 238, 255)  # #22d3ee
TRACK = (55, 65, 81, 255)     # #374151

img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# 圆角底
d.rounded_rectangle([0, 0, SIZE - 1, SIZE - 1], radius=52, fill=BG)

# 播放三角
d.polygon([(104, 68), (104, 150), (172, 109)], fill=ACCENT)

# 进度条：底轨 + 已播部分
d.rounded_rectangle([52, 178, 204, 196], radius=9, fill=TRACK)
d.rounded_rectangle([52, 178, 156, 196], radius=9, fill=ACCENT)

import os
HERE = os.path.dirname(os.path.abspath(__file__))
out = os.path.join(HERE, os.pardir, "dist-portable", "SuperAutoStudy", "app.ico")
img.save(out, format="ICO",
         sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print("saved:", out)
