"""真实照片对照：原图 / 本滤镜(纯影调LUT) / 目标作品参照，并叠加指纹量化。"""
import numpy as np, json, os, glob, sys
from PIL import Image, ImageDraw, ImageFont
from fit16 import load_pixels, HERE
sys.path.insert(0, HERE)
from _render_pipeline import render, base_cfg, srgb_to_lab, rgb_to_hsv

P = json.load(open(os.path.join(HERE, 'fit16_params.json')))
C = json.load(open(os.path.join(HERE, 'fit16_chosen.json')))
lut = np.asarray(C['chosen_lut'], dtype=int)


def apply(im, sat=0):
    cfg = base_cfg(); cfg['saturation'] = sat
    a = np.clip(np.round(np.asarray(im.convert('RGB'))), 0, 255).astype(np.int64)
    return Image.fromarray(render(a, cfg, lut))


def fp(im):
    a = np.asarray(im.convert('RGB')).astype(float) / 255.0
    L, A, B = srgb_to_lab(a)
    h, s, v = rgb_to_hsv(a)
    Ln = L / 100.0
    z = {'dark': Ln < .33, 'mid': (Ln >= .33) & (Ln < .66), 'high': Ln >= .66}
    out = {}
    for k, m in z.items():
        out[f'{k}_a'] = round(float(A[m].mean()), 1) if m.sum() > 20 else 0
        out[f'{k}_b'] = round(float(B[m].mean()), 1) if m.sum() > 20 else 0
    out['L50'] = round(float(np.median(L)), 1)
    out['sat'] = round(float(s.mean()), 3)
    out['p95'] = round(float(np.percentile(L, 95)), 1)
    return out


# 选 6 张代表性中性照片
names = ['portrait', 'forest', 'outdoor', 'street', 'pic_1015', 'pic_1043', 'pic_1080']
srcs = []
for n in names:
    f = os.path.join(HERE, 'neutral', n + '.jpg')
    if os.path.exists(f):
        srcs.append((n, Image.open(f).convert('RGB')))

# 目标参照 4 张（青蓝 / 暖橙 / 逆光近景 / 暖橙特写）
ref_files = ['01_38946.jpg', '07_38931.jpg', '11_38923.jpg', '16_38913.jpg']
refs = []
for f in ref_files:
    p = os.path.join('/workspace/待分析照片', f)
    if os.path.exists(p):
        refs.append((f[:2], Image.open(p).convert('RGB')))

PANEL_W, PANEL_H = 250, 333
THUMB = (PANEL_W - 16, PANEL_H - 40)


def thumb(im):
    t = im.copy(); t.thumbnail(THUMB)
    return t


# ---------- 图 1：原图 vs 本滤镜（每行一张）----------
rows = len(srcs)
W = PANEL_W * 2
H = 40 + rows * (PANEL_H + 8)
canvas = Image.new('RGB', (W, H), (247, 247, 247))
d = ImageDraw.Draw(canvas)
d.text((10, 12), '左：原图（中性）   |   右：本滤镜（16张作品影调拟合：抬黑/提亮中间调/压高光）', fill=(20, 20, 20))
print('=== 逐图指纹（原图 -> 本滤镜）===')
for i, (n, im) in enumerate(srcs):
    a = im; b = apply(im)
    fa, fb = fp(a), fp(b)
    print(f"[{n}] 原图 L50={fa['L50']} p95={fa['p95']} sat={fa['sat']} "
          f"dark_a={fa['dark_a']} mid_b={fa['mid_b']} -> "
          f"滤镜 L50={fb['L50']} p95={fb['p95']} sat={fb['sat']} "
          f"dark_a={fb['dark_a']} mid_b={fb['mid_b']}")
    y = 40 + i * (PANEL_H + 8)
    for j, pic in enumerate([a, b]):
        t = thumb(pic)
        x = j * PANEL_W
        canvas.paste(t, (x + (PANEL_W - t.width) // 2, y))
canvas.save(os.path.join(HERE, 'compare16_effect.png'))
print('saved compare16_effect.png', canvas.size)

# ---------- 图 2：本滤镜效果 + 目标作品参照 ----------
# 上排：4 张目标参照；下排：4 张套用后的中性照片（挑最贴近人像/暖色的）
W2 = PANEL_W * 4
H2 = 40 + 2 * (PANEL_H + 8)
c2 = Image.new('RGB', (W2, H2), (247, 247, 247))
d2 = ImageDraw.Draw(c2)
d2.text((10, 12), '上排：16张中的目标作品（参照）   |   下排：本滤镜套用到中性照片的效果（对比风格）', fill=(20, 20, 20))
for i, (lbl, im) in enumerate(refs[:4]):
    t = thumb(im)
    c2.paste(t, (i * PANEL_W + (PANEL_W - t.width) // 2, 40))
    d2.text((i * PANEL_W + 8, 40 + PANEL_H - 34), f'目标 {lbl}', fill=(20, 20, 20))
eff = [apply(im) for _, im in srcs[:4]]
for i, im in enumerate(eff):
    t = thumb(im)
    y = 40 + (PANEL_H + 8)
    c2.paste(t, (i * PANEL_W + (PANEL_W - t.width) // 2, y))
    d2.text((i * PANEL_W + 8, y + PANEL_H - 34), f'滤镜效果 {i+1}', fill=(20, 20, 20))
c2.save(os.path.join(HERE, 'compare16_ref.png'))
print('saved compare16_ref.png', c2.size)

# ---------- 图 3：tone 曲线可视化（理解滤镜做了什么）----------
cw, ch = 520, 520
c3 = Image.new('RGB', (cw, ch), (255, 255, 255))
d3 = ImageDraw.Draw(c3)
pad = 50
def xy(vin, vout):
    return (pad + vin / 255.0 * (cw - 2 * pad), ch - pad - vout / 255.0 * (ch - 2 * pad))
# 恒等线
d3.line([xy(0, 0), xy(255, 255)], fill=(210, 210, 210), width=2)
# 拟合曲线
pts = [xy(i, lut[i]) for i in range(256)]
d3.line(pts, fill=(220, 40, 40), width=3)
d3.text((10, 10), 'tone curve: red = fitted, gray = identity', fill=(20, 20, 20))
for v in [0, 64, 128, 192, 255]:
    d3.line([xy(v, 0), xy(v, 255)], fill=(235, 235, 235))
    d3.line([xy(0, v), xy(255, v)], fill=(235, 235, 235))
    d3.text((xy(v, 0)[0] - 6, ch - pad + 6), str(v), fill=(60, 60, 60))
    d3.text((6, xy(0, v)[1] - 6), str(v), fill=(60, 60, 60))
c3.save(os.path.join(HERE, 'compare16_curve.png'))
print('saved compare16_curve.png')

# 导出 LUT 为 Kotlin 可用（0..32767 的 256 点）
curve32767 = [int(round(v / 255.0 * 32767)) for v in lut]
json.dump({'lut255': lut.tolist(), 'curve32767': curve32767,
           'chosen_alpha': C['chosen_alpha']},
          open(os.path.join(HERE, 'fit16_final_lut.json'), 'w'), indent=1)
print('LUT 单调?', bool(np.all(np.diff(lut) >= 0)),
      '端值', lut[0], lut[-1], 'curve[0]=', curve32767[0], 'curve[255]=', curve32767[255])
print('saved fit16_final_lut.json')
