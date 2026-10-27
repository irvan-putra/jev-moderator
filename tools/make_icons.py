#!/usr/bin/env python3
"""Regenerate launcher icons with the app version drawn on them.
Reads versionName from app/build.gradle so the badge always matches the build.
Usage: python3 tools/make_icons.py [path/to/sticker.(webp|png)] [--preview out.png]
"""
import os, re, sys
from PIL import Image, ImageDraw, ImageFont
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
argv = sys.argv[1:]
if '--preview' in argv:
    i = argv.index('--preview'); del argv[i:i + 2]
args = argv
sticker_path = args[0] if args else '/tmp/jev-assets/sticker.webp'
preview = sys.argv[sys.argv.index('--preview') + 1] if '--preview' in sys.argv else None
ver = re.search(r'versionName\s+"([^"]+)"', open(os.path.join(root, 'app/build.gradle')).read()).group(1)
label = 'v' + ver
BG = (234, 242, 251, 255)
FONTS = ['/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
         '/usr/local/lib/python3.10/dist-packages/matplotlib/mpl-data/fonts/ttf/DejaVuSans-Bold.ttf']
fontfile = next(f for f in FONTS if os.path.exists(f))
s = Image.open(sticker_path).convert('RGBA'); s = s.crop(s.getbbox())

def fit(img, size, frac, cy):
    w, h = img.size; k = size * frac / max(w, h)
    r = img.resize((max(1, int(w * k)), max(1, int(h * k))), Image.LANCZOS)
    c = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    c.paste(r, ((size - r.width) // 2, int(size * cy - r.height / 2)), r); return c

def badge(canvas, size, cx, cy, h):
    """Dark pill with white version text; h = pill height in px of this canvas."""
    d = ImageDraw.Draw(canvas)
    fs = int(h * 0.72)
    f = ImageFont.truetype(fontfile, fs)
    tw = d.textlength(label, font=f); w = int(tw + h * 0.7)
    box = (cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    d.rounded_rectangle(box, radius=h / 2, fill=(20, 33, 61, 255))
    d.text((cx, cy), label, font=f, fill=(255, 255, 255, 255), anchor='mm')

def foreground(size):
    c = fit(s, size, 0.42, 0.42); k = size / 432
    badge(c, size, size // 2, int(312 * k), max(8, int(50 * k))); return c

def legacy(px):
    b = Image.new('RGBA', (px, px), BG); b.alpha_composite(fit(s, px, 0.60, 0.40))
    badge(b, px, px // 2, int(px * 0.80), max(8, int(px * 0.26)))
    m = Image.new('L', (px, px), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, px - 1, px - 1), radius=px // 5, fill=255)
    o = Image.new('RGBA', (px, px), (0, 0, 0, 0)); o.paste(b, (0, 0), m); return o

if not preview:
    for d, px in [('mdpi', 108), ('hdpi', 162), ('xhdpi', 216), ('xxhdpi', 324), ('xxxhdpi', 432)]:
        foreground(px).save(os.path.join(root, f'app/src/main/res/mipmap-{d}/ic_launcher_foreground.png'))
    for d, px in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
        legacy(px).save(os.path.join(root, f'app/src/main/res/mipmap-{d}/ic_launcher.png'))
    print('icons written for', label)
else:
    base = Image.new('RGBA', (432, 432), BG); base.alpha_composite(foreground(432))
    P = Image.new('RGBA', (1500, 470), (120, 120, 120, 255)); a = 72
    for i, shape in enumerate(['circle', 'round']):
        m = Image.new('L', (432, 432), 0); dr = ImageDraw.Draw(m); box = (a, a, 432 - a, 432 - a)
        dr.ellipse(box, fill=255) if shape == 'circle' else dr.rounded_rectangle(box, radius=70, fill=255)
        c = Image.new('RGBA', (432, 432), (0, 0, 0, 0)); c.paste(base, (0, 0), m); P.alpha_composite(c, (10 + i * 440, 20))
    P.alpha_composite(legacy(192).resize((288, 288)), (890, 20))
    # small, launcher-like sizes
    sm = base.resize((144, 144), Image.LANCZOS); m = Image.new('L', (144, 144), 0)
    ImageDraw.Draw(m).ellipse((24, 24, 120, 120), fill=255); c = Image.new('RGBA', (144, 144), (0, 0, 0, 0)); c.paste(sm, (0, 0), m)
    P.alpha_composite(c, (1200, 20)); P.alpha_composite(legacy(96), (1200, 190)); P.alpha_composite(legacy(48), (1320, 190))
    P.save(preview); print('preview', label)
