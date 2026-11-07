import sys, os
import numpy as np
from PIL import Image

src, res = sys.argv[1], sys.argv[2]
im = np.asarray(Image.open(src).convert('RGB')).astype(np.float32) / 255.0
bg = np.median(im[:200, :200].reshape(-1, 3), axis=0)

# Colour-difference key: alpha from distance to the background colour,
# then un-premultiply so glows keep their colour over any background.
d = np.abs(im - bg).max(axis=2)
alpha = np.clip((d - 0.045) / 0.35, 0, 1)
fg = np.where(alpha[..., None] > 1e-3,
              (im - bg * (1 - alpha[..., None])) / np.maximum(alpha[..., None], 1e-3), 0)
rgba = np.dstack([np.clip(fg, 0, 1), alpha])
rgba = (rgba * 255 + 0.5).astype(np.uint8)

ys, xs = np.where(alpha > 0.02)
pad = 30
crop = Image.fromarray(rgba).crop((xs.min() - pad, ys.min() - pad, xs.max() + pad, ys.max() + pad))
print('logo crop', crop.size)

# In-app header logo
os.makedirs(f'{res}/drawable-nodpi', exist_ok=True)
w = 900
crop.resize((w, round(crop.height * w / crop.width)), Image.LANCZOS).save(f'{res}/drawable-nodpi/logo.png', optimize=True)

# Adaptive icon foreground (108dp canvas). Logo is 74dp wide: only the flat
# ends of the waveform reach the edge of a circular mask; all the detail stays visible.
for name, scale in [('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)]:
    size = round(108 * scale)
    lw = round(74 * scale)
    lh = round(crop.height * lw / crop.width)
    logo = crop.resize((lw, lh), Image.LANCZOS)
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    canvas.paste(logo, ((size - lw) // 2, (size - lh) // 2), logo)
    d = f'{res}/mipmap-{name}'
    os.makedirs(d, exist_ok=True)
    canvas.save(f'{d}/ic_launcher_foreground.png', optimize=True)
print('bg colour #%02X%02X%02X' % tuple(int(round(c * 255)) for c in bg))
