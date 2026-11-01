#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.3 launcher icon: white rounded card + Material tonal ball + music note."""
from PIL import Image, ImageDraw, ImageFont

W = H = 192
BG = (255, 255, 255, 255)          # card
OUTLINE = (218, 220, 224, 255)     # #DADCE0 hairline
BALL = (211, 227, 253, 255)        # #D3E3FD tonal
NOTE = (11, 87, 208, 255)          # #0B57D0 Google blue

img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle([1, 1, W - 2, H - 2], radius=42, fill=BG, outline=OUTLINE, width=2)

cx = cy = 96
r = 55
d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=BALL)

font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 78)
d.text((cx, cy + 2), '♪', font=font, fill=NOTE, anchor='mm')

img.save('res/drawable/ic_launcher.png')
print('icon written:', img.size)
