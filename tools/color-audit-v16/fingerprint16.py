"""16 张目标成片 -> 定量色彩指纹 + 跨批次一致性检验。

核心目的：区分「滤镜特征」与「场景色」。
只有跨 4 个批次（冷暖两端 + 逆光近景）都稳定成立的量，
才能作为后续分布匹配拟合的目标；否则会把场景色学进滤镜。

批次：
  A 01-05 夏日青蓝（蓝天 / 青绿裙 / 蓝绣球）
  B 06-10 秋冬暖橙（橙枫 / 落叶 / 木栏）
  C 11-15 逆光近景（暖橙虚化 / 深绿围巾 / 侧逆光）
  D 16    暖橙特写
"""
import numpy as np
from PIL import Image
import sys, os, json, glob

M = np.array([[0.4124564, 0.3575761, 0.1804375],
              [0.2126729, 0.7151522, 0.0721750],
              [0.0193339, 0.1191920, 0.9503041]])
WHITE = np.array([0.95047, 1.0, 1.08883])


def srgb_to_lab(a):
    lin = np.where(a <= 0.04045, a / 12.92, ((a + 0.055) / 1.055) ** 2.4)
    xyz = lin @ M.T
    t = xyz / WHITE
    f = np.where(t > 0.008856, np.cbrt(t), 7.787 * t + 16.0 / 116.0)
    return 116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])


def rgb_to_hsv(a):
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    mx = a.max(-1); mn = a.min(-1); d = mx - mn
    h = np.zeros_like(mx)
    mask = d > 1e-6
    idx = (mx == r) & mask; h[idx] = ((g - b)[idx] / d[idx]) % 6
    idx = (mx == g) & mask; h[idx] = ((b - r)[idx] / d[idx]) + 2
    idx = (mx == b) & mask; h[idx] = ((r - g)[idx] / d[idx]) + 4
    h = h * 60
    s = np.where(mx > 1e-6, d / np.maximum(mx, 1e-6), 0)
    return h, s, mx


def fingerprint(path):
    im = Image.open(path).convert('RGB')
    im.thumbnail((400, 600))
    a = np.asarray(im).astype(np.float64) / 255.0
    L, A, B = srgb_to_lab(a)
    h, s, v = rgb_to_hsv(a)
    flat = lambda x: x.ravel()
    Lf, Af, Bf, hf, sf, vf = map(flat, (L, A, B, h, s, v))
    chroma = np.sqrt(Af ** 2 + Bf ** 2)
    r, g, b = a[..., 0].ravel(), a[..., 1].ravel(), a[..., 2].ravel()
    Ln = Lf / 100.0

    def zone_stat(mask):
        if mask.sum() < 50:
            return dict(a=0.0, b=0.0, L=0.0, sat=0.0, n=int(mask.sum()))
        return dict(a=float(Af[mask].mean()), b=float(Bf[mask].mean()),
                    L=float(Lf[mask].mean()), sat=float(sf[mask].mean()), n=int(mask.sum()))

    dark = Ln < 0.33; mid = (Ln >= 0.33) & (Ln < 0.66); high = Ln >= 0.66

    # 8 档亮度 -> a/b 曲线（用于拟合 tone-dependent 色偏）
    octiles = []
    for k in range(8):
        m = (Ln >= k / 8) & (Ln < (k + 1) / 8)
        octiles.append(round(float(Af[m].mean()), 2) if m.sum() > 30 else 0.0)

    # 肤色（经典规则）
    skin = (r > 0.30) & (g > 0.20) & (b > 0.15) & (r > g) & (g > b) & \
           (r - g > 0.02) & ((hf < 50) | (hf > 345)) & (Lf > 30) & (Lf < 92)
    # 暖橙高光区（橙枫/落叶/暖阳，亮部暖色）——验证「不被染青」
    warm_hi = (hf >= 10) & (hf <= 60) & (sf > 0.20) & (Ln >= 0.55)
    green = (hf >= 70) & (hf <= 170) & (sf > 0.12) & (vf > 0.12)
    cyanblue = (hf >= 170) & (hf <= 250) & (sf > 0.10) & (vf > 0.12)

    def band(mask):
        if mask.sum() < 50:
            return dict(n=int(mask.sum()), hue=0.0, sat=0.0, a=0.0, b=0.0, L=0.0)
        return dict(n=int(mask.sum()), hue=float(hf[mask].mean()),
                    sat=float(sf[mask].mean()), a=float(Af[mask].mean()),
                    b=float(Bf[mask].mean()), L=float(Lf[mask].mean()))

    return dict(
        file=os.path.basename(path),
        global_L=float(Lf.mean()), L_std=float(Lf.std()),
        L_p01=float(np.percentile(Lf, 1)), L_p05=float(np.percentile(Lf, 5)),
        L_p25=float(np.percentile(Lf, 25)), L_p50=float(np.median(Lf)),
        L_p75=float(np.percentile(Lf, 75)), L_p95=float(np.percentile(Lf, 95)),
        L_p99=float(np.percentile(Lf, 99)),
        clip_white=float((vf > 0.985).mean()), clip_black=float((vf < 0.02).mean()),
        mean_a=float(Af.mean()), mean_b=float(Bf.mean()),
        mean_sat=float(sf.mean()), mean_chroma=float(chroma.mean()),
        dark=zone_stat(dark), mid=zone_stat(mid), high=zone_stat(high),
        a_octiles=octiles,
        skin=band(skin), warm_hi=band(warm_hi), green=band(green), cyanblue=band(cyanblue),
    )


def aggregate(fps, label):
    keys = ['global_L', 'L_std', 'L_p01', 'L_p05', 'L_p25', 'L_p50', 'L_p75', 'L_p95',
            'L_p99', 'clip_white', 'clip_black', 'mean_a', 'mean_b', 'mean_sat', 'mean_chroma']
    out = {'label': label, 'n': len(fps)}
    for k in keys:
        out[k] = round(float(np.mean([f[k] for f in fps])), 3)
    for z in ['dark', 'mid', 'high']:
        out[z] = {kk: round(float(np.mean([f[z][kk] for f in fps])), 3)
                  for kk in ['a', 'b', 'L', 'sat']}
    for bn in ['skin', 'warm_hi', 'green', 'cyanblue']:
        valid = [f for f in fps if f[bn]['n'] > 100]
        out[bn] = {kk: round(float(np.mean([f[bn][kk] for f in valid])), 3)
                   for kk in ['hue', 'sat', 'a', 'b', 'L']} if valid else None
    out['a_octiles'] = [round(float(np.mean([f['a_octiles'][k] for f in fps])), 2)
                        for k in range(8)]
    # 暗-亮 a 梯度（负 = 暗部相对更偏青/绿）
    out['dark_minus_high_a'] = round(out['dark']['a'] - out['high']['a'], 3)
    return out


BATCH = {}
for i in range(1, 17):
    if i <= 5:   BATCH[i] = 'A'
    elif i <= 10: BATCH[i] = 'B'
    elif i <= 15: BATCH[i] = 'C'
    else:         BATCH[i] = 'D'

if __name__ == '__main__':
    paths = sys.argv[1:] or sorted(glob.glob('/workspace/待分析照片/[0-9][0-9]_*.jpg'))
    fps = [fingerprint(p) for p in paths]
    groups = {'A_夏日青蓝': [], 'B_秋冬暖橙': [], 'C_逆光近景': [], 'D_暖橙特写': []}
    for f in fps:
        idx = int(f['file'][:2])
        groups[list(groups.keys())[ord(BATCH[idx]) - ord('A')]].append(f)
    result = {
        'all': aggregate(fps, '全部 16 张'),
        'by_batch': {k: aggregate(v, k) for k, v in groups.items() if v},
        'per_image': fps,
    }
    json.dump(result, open(os.path.join(os.path.dirname(__file__), 'fingerprints16.json'), 'w'),
              ensure_ascii=False, indent=1)
    a = result['all']
    print('===== 全部 16 张聚合 =====')
    print(f"影调: L均值={a['global_L']} std={a['L_std']} p01={a['L_p01']} p05={a['L_p05']} "
          f"p50={a['L_p50']} p95={a['L_p95']} p99={a['L_p99']} 死白={a['clip_white']} 死黑={a['clip_black']}")
    print(f"全局: a={a['mean_a']} b={a['mean_b']} sat={a['mean_sat']} chroma={a['mean_chroma']}")
    print(f"分区 a: dark={a['dark']['a']} mid={a['mid']['a']} high={a['high']['a']} "
          f"(暗-亮梯度={a['dark_minus_high_a']})")
    print(f"分区 b: dark={a['dark']['b']} mid={a['mid']['b']} high={a['high']['b']}")
    print(f"8档亮度 a 曲线: {a['a_octiles']}")
    print(f"肤色: {a['skin']}")
    print(f"暖橙高光: {a['warm_hi']}")
    print(f"绿植: {a['green']}")
    print(f"青蓝: {a['cyanblue']}")
    print('\n===== 跨批次一致性（关键）=====')
    for k, g in result['by_batch'].items():
        print(f"[{k}] n={g['n']} L={g['global_L']} sat={g['mean_sat']} | "
              f"dark_a={g['dark']['a']:.2f} mid_a={g['mid']['a']:.2f} high_a={g['high']['a']:.2f} "
              f"暗-亮梯度={g['dark_minus_high_a']:.2f} | "
              f"肤色a={g['skin']['a'] if g['skin'] else 'NA'} "
              f"暖橙高光a={g['warm_hi']['a'] if g['warm_hi'] else 'NA'} "
              f"暖橙hue={g['warm_hi']['hue'] if g['warm_hi'] else 'NA'}")
