"""语义色块对比 + 三档强度对照：判断色彩层是否真有可学的稳定差异，并选不过曝的强度。"""
import numpy as np, json, os, glob, sys
from PIL import Image, ImageDraw
from fit16 import load_pixels, HERE
sys.path.insert(0, HERE)
from _render_pipeline import render, base_cfg, srgb_to_lab, rgb_to_hsv

P = json.load(open(os.path.join(HERE, 'fit16_params.json')))
C = json.load(open(os.path.join(HERE, 'fit16_chosen.json')))


def bands(imgs):
    """语义色块：肤色 / 绿植 / 青蓝 / 暖橙高光，返回 (hue,sat,a,b,L) 均值。"""
    acc = {k: [[], [], [], [], []] for k in ['skin', 'green', 'cyanblue', 'warm_hi']}
    for a0 in imgs:
        L, A, B = srgb_to_lab(a0)
        h, s, v = rgb_to_hsv(a0)
        r, g, b = a0[..., 0], a0[..., 1], a0[..., 2]
        Ln = L / 100.0
        masks = {
            'skin': (r > .30) & (g > .20) & (b > .15) & (r > g) & (g > b) &
                    (r - g > .02) & ((h < 50) | (h > 345)) & (L > 30) & (L < 92),
            'green': (h >= 70) & (h <= 170) & (s > .12) & (v > .12),
            'cyanblue': (h >= 170) & (h <= 250) & (s > .10) & (v > .12),
            'warm_hi': (h >= 10) & (h <= 60) & (s > .20) & (Ln >= .55),
        }
        for k, m in masks.items():
            if m.sum() < 200:
                continue
            for j, arr in enumerate([h, s, A, B, L]):
                acc[k][j].append(float(arr[m].mean()))
    out = {}
    for k, cols in acc.items():
        out[k] = dict(hue=round(float(np.mean(cols[0])), 2), sat=round(float(np.mean(cols[1])), 3),
                      a=round(float(np.mean(cols[2])), 2), b=round(float(np.mean(cols[3])), 2),
                      L=round(float(np.mean(cols[4])), 2),
                      n_imgs=len(cols[0])) if cols[0] else None
    return out


tgt = [load_pixels(p) for p in sorted(glob.glob('/workspace/待分析照片/[0-9][0-9]_*.jpg'))]
src = [load_pixels(p) for p in sorted(glob.glob(os.path.join(HERE, 'neutral', '*')))]
BT, BS = bands(tgt), bands(src)
print('=== 语义色块：16 张成片 vs 13 张中性源图 ===')
for k in ['skin', 'green', 'cyanblue', 'warm_hi']:
    t, s = BT[k], BS[k]
    print(f"[{k}] 成片 hue={t['hue']:6.2f} sat={t['sat']:.3f} a={t['a']:+6.2f} b={t['b']:+6.2f} L={t['L']:5.1f} | "
          f"源 hue={s['hue']:6.2f} sat={s['sat']:.3f} a={s['a']:+6.2f} b={s['b']:+6.2f} L={s['L']:5.1f} | "
          f"Δhue={t['hue']-s['hue']:+.1f} Δsat={t['sat']-s['sat']:+.3f} Δb={t['b']-s['b']:+.1f}")

# 按批次看语义色块是否稳定（区分滤镜特征 vs 场景色）
print('\n=== 语义色块按批次（检验稳定性）===')
batch_files = {}
for p in sorted(glob.glob('/workspace/待分析照片/[0-9][0-9]_*.jpg')):
    idx = int(os.path.basename(p)[:2])
    key = 'A_夏日青蓝' if idx <= 5 else 'B_秋冬暖橙' if idx <= 10 else 'C_逆光近景' if idx <= 15 else 'D_暖橙特写'
    batch_files.setdefault(key, []).append(load_pixels(p))
for key, imgs in batch_files.items():
    b = bands(imgs)
    print(f"[{key}] 肤色hue={b['skin']['hue']:.1f} 绿hue={b['green']['hue']:.1f} "
          f"青蓝hue={b['cyanblue']['hue']:.1f} 暖橙hue={b['warm_hi']['hue']:.1f} "
          f"绿sat={b['green']['sat']:.3f} 青蓝sat={b['cyanblue']['sat']:.3f} 暖橙sat={b['warm_hi']['sat']:.3f}")

# ---------- 三档强度对照图 ----------
print('\n=== 生成三档强度对照 ===')
names = ['portrait', 'forest', 'pic_1015', 'street', 'outdoor']
imgs = [(n, Image.open(os.path.join(HERE, 'neutral', n + '.jpg')).convert('RGB'))
        for n in names if os.path.exists(os.path.join(HERE, 'neutral', n + '.jpg'))]
luts = {a: np.asarray(P['luts'][a], dtype=int) for a in ['a50', 'a70', 'a100']}


def apply(im, lut):
    cfg = base_cfg()
    arr = np.clip(np.round(np.asarray(im)), 0, 255).astype(np.int64)
    return Image.fromarray(render(arr, cfg, lut))


PW, PH = 210, 280
cols = 4  # 原图 + 三档
H = 34 + len(imgs) * (PH + 6)
canvas = Image.new('RGB', (PW * cols, H), (247, 247, 247))
d = ImageDraw.Draw(canvas)
d.text((8, 10), '原图 | a50(弱) | a70(中) | a100(强=最接近目标分位)', fill=(20, 20, 20))
for i, (n, im) in enumerate(imgs):
    y = 34 + i * (PH + 6)
    panels = [im] + [apply(im, luts[a]) for a in ['a50', 'a70', 'a100']]
    for j, pic in enumerate(panels):
        t = pic.copy(); t.thumbnail((PW - 12, PH - 30))
        canvas.paste(t, (j * PW + (PW - t.width) // 2, y))
    d.text((6, y + PH - 22), n, fill=(30, 30, 30))
canvas.save(os.path.join(HERE, 'compare16_alpha.png'))
print('saved compare16_alpha.png', canvas.size)

json.dump(dict(target_bands=BT, source_bands=BS),
           open(os.path.join(HERE, 'semantic_bands.json'), 'w'), ensure_ascii=False, indent=1)
print('saved semantic_bands.json')
