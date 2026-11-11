"""通透胶片 v2.1 —— 日系影调增强 + 高光控制（回测版）。

在 1.6.35「通透胶片 v2」已验证的保色亮度耦合 LUT 基础上做增量：
  ① 高光控制：当前 v2 高光段（输入 ~248 起）被压成 250 死白平台，4+ 级
     输入塌陷为 1 级，丢失高光层次（即「高光失控」）。改为平滑肩部，到 254
     渐次收敛、全程保留层次，绝不塌成平台。
  ② 日系影调：抬黑位（雾感/空气感）、提亮并压低中间调对比（低对比、明亮、
     通透），属纯影调操作，色彩层保持恒等，不引入任何色偏。

约束（与 v2 一致）：单调、平滑、保色（灰阶 a/b=0、彩色像素色相漂移≈0）、
无逐通道裁剪。方法：以控制点定义日系传递函数，样条平滑 + 累积最大保证单调，
再对中性源图做回测，量化高光层次与日系亮度分布。
"""
import numpy as np
import json, os, glob
from scipy.signal import savgol_filter
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
NEUTRAL_DIR = ('/root/.codebuddy/artifact/2f8a7d30-9c21-4f0b-1d66-6dafb848b000'
               '/fit16/neutral')
V2_LUT = np.array(json.load(open(os.path.join(HERE, 'v2_final_result.json')))['lumaToneLut'], dtype=float)

SAMPLES = 60000
SEED = 20261111
QS = np.array([1, 5, 10, 25, 50, 75, 90, 95, 99], dtype=float)


def luma(a):
    a = a.astype(np.float64)
    return a[..., 0] * 0.299 + a[..., 1] * 0.587 + a[..., 2] * 0.114


def pool(paths, seed=SEED):
    rng = np.random.default_rng(seed)
    xs = []
    for p in sorted(paths):
        im = Image.open(p).convert('RGB')
        im.thumbnail((520, 520))
        a = np.asarray(im).reshape(-1, 3)
        if len(a) > SAMPLES:
            a = a[rng.choice(len(a), SAMPLES, replace=False)]
        xs.append(a)
    return xs


def apply_lut(rgb, lut):
    """1:1 复刻 ImageRenderer 的【保色亮度耦合】路径（含溢出等比回缩）。"""
    a = rgb.astype(np.float64)
    l = luma(a)
    idx = np.round(l).astype(int).clip(0, 255)
    mapped = lut[idx]
    scale = np.where(l > 8.0, mapped / np.maximum(l, 1e-6), 1.0)
    lift = mapped - l * scale
    maxc = a.max(-1)
    top = maxc * scale + lift
    over = top > 255.0
    if np.any(over):
        safe = np.where(over, (255.0 - lift) / np.maximum(maxc, 1e-6), scale)
        scale = safe
    out = a * scale[..., None] + lift[..., None]
    return np.clip(out, 0, 255)


def make_from_knots(kx, ky):
    d = np.interp(np.arange(256), np.array(kx, float), np.array(ky, float))
    d = savgol_filter(d, 31, 2)
    d = np.maximum.accumulate(d)          # 单调不减
    d = np.clip(d, 12, 254)
    d[0] = 12
    d[-1] = 254
    d = np.maximum.accumulate(d)
    return np.round(d).astype(int)


def lab(rgb):
    x = np.asarray(rgb, np.float64) / 255.0
    lin = np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)
    M = np.array([[0.4124564, 0.3575761, 0.1804375],
                  [0.2126729, 0.7151522, 0.0721750],
                  [0.0193339, 0.1191920, 0.9503041]])
    xyz = lin @ M.T / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    return 116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])


def hue(rgb):
    x = np.asarray(rgb, np.float64) / 255.0
    mx, mn = x.max(-1), x.min(-1)
    d = mx - mn
    h = np.zeros_like(mx); m = d > 1e-6
    r, g, b = x[..., 0], x[..., 1], x[..., 2]
    i = (mx == r) & m; h[i] = ((g - b)[i] / d[i]) % 6
    i = (mx == g) & m; h[i] = ((b - r)[i] / d[i]) + 2
    i = (mx == b) & m; h[i] = ((r - g)[i] / d[i]) + 4
    return h * 60, d / (mx + 1e-9)


def color_probe(lut):
    gray = np.stack([np.arange(256)] * 3, -1).astype(np.uint8)
    go = apply_lut(gray, lut)
    L, A, B = lab(go)
    gray_ab = float(np.max(np.abs(A)) + np.max(np.abs(B)))
    cols = []
    for hd in range(0, 360, 12):
        for v in [35, 70, 120, 170, 215, 250]:
            for s in [0.25, 0.5, 0.8, 1.0]:
                c = v * s; hp = hd / 60.0
                xx = c * (1 - abs(hp % 2 - 1)); m = v - c / 2
                if hp < 1: r, g, b = c, xx, 0
                elif hp < 2: r, g, b = xx, c, 0
                elif hp < 3: r, g, b = 0, c, xx
                elif hp < 4: r, g, b = 0, xx, c
                elif hp < 5: r, g, b = xx, 0, c
                else: r, g, b = c, 0, xx
                cols.append([r + m, g + m, b + m])
    cols = np.clip(np.array(cols), 0, 255)
    h0, s0 = hue(cols)
    out = apply_lut(cols, lut)
    h1, _ = hue(out)
    m = s0 > 0.15
    dh = np.abs(((h1 - h0 + 180) % 360) - 180)[m]
    return dict(gray_ab=round(gray_ab, 4),
                hue_mean=round(float(dh.mean()), 4),
                hue_p95=round(float(np.percentile(dh, 95)), 4),
                hue_max=round(float(dh.max()), 4))


def highlight_gradation(lut):
    """高光层次指标：顶部 [200,255] 最长平台、[220,255] 平均斜率、[248,255] 斜率。"""
    top = lut[200:256]
    # 最长相等连续段
    max_run = 1; run = 1
    for i in range(1, len(top)):
        if top[i] == top[i - 1]:
            run += 1; max_run = max(max_run, run)
        else:
            run = 1
    slope_hi = float(np.mean(np.diff(lut[220:256])))
    slope_top = float(np.mean(np.diff(lut[248:256])))
    return dict(max_flat_run=int(max_run), slope_220_255=round(slope_hi, 3),
                slope_248_255=round(slope_top, 3))


def main():
    neu = pool(glob.glob(os.path.join(NEUTRAL_DIR, '*')))
    print('中性源图 %d 张' % len(neu))

    # —— 日系传递函数：抬黑位 + 明亮低对比中间调 + 高光平滑肩部 ——
    # 控制点（输入 -> 输出）。设计目标：
    #   · 黑位 14（日系雾感/空气感，原本 6）
    #   · 低中段轻微提亮、中段以上不压暗（明亮通透、低对比但不是发灰）
    #   · 高光到 254 渐次收敛、全程保留层次，不塌成死白平台
    KX = [0, 32, 64, 96, 128, 160, 192, 216, 236, 248, 255]
    KY = [14, 50, 108, 145, 172, 196, 216, 230, 244, 252, 254]
    jp = make_from_knots(KX, KY)

    assert np.all(np.diff(jp) >= 0), 'LUT 非单调'
    assert abs(jp[0] - 14) <= 2 and jp[-1] == 254

    # 回测：套到中性源图
    out_jp = np.concatenate([luma(apply_lut(a, jp)) for a in neu])
    out_v2 = np.concatenate([luma(apply_lut(a, V2_LUT)) for a in neu])
    q_jp = np.percentile(out_jp, QS)
    q_v2 = np.percentile(out_v2, QS)

    cp = color_probe(jp)
    hg = highlight_gradation(jp)
    hg_v2 = highlight_gradation(V2_LUT)

    print('\n=== 中性源图输出分位（v2.1 日系 vs v2）===')
    for q, a, b in zip(QS, q_jp, q_v2):
        print(f'  p{int(q):02d}: 日系 {a:6.2f}  v2 {b:6.2f}  Δ={a-b:+6.2f}')
    print(f'\n=== 保色探针 ===\n  {cp}')
    print('\n=== 高光层次（控制目标）===')
    print(f'  v2.1: {hg}')
    print(f'  v2  : {hg_v2}')
    print('\n=== v2.1 LUT 关键节点 ===')
    for x in [0, 16, 32, 64, 96, 128, 160, 192, 216, 236, 248, 255]:
        print(f'  in {x:3d} -> v2 {int(V2_LUT[x]):3d}  日系 {jp[x]:3d}  '
              f'(Δ {jp[x]-int(V2_LUT[x]):+d})')

    assert cp['gray_ab'] < 0.01, '灰阶出现色偏'
    assert cp['hue_mean'] < 0.5, '色相漂移超标'
    assert hg['max_flat_run'] <= 3, '高光仍存在死白平台'
    assert hg['slope_248_255'] > 0.2, '高光顶端梯度不足'

    out = dict(method='v2 + 日系影调(抬黑位/低对比/明亮) + 高光平滑肩部控制',
               knots=dict(x=KX, y=KY),
               lumaToneLut=jp.tolist(),
               color_probe=cp,
               highlight_gradation=hg,
               highlight_gradation_v2=hg_v2,
               quantiles_jp=q_jp.round(2).tolist(),
               quantiles_v2=q_v2.round(2).tolist(),
               v2_lut=V2_LUT.astype(int).tolist())
    with open(os.path.join(HERE, 'v2_1_jp_result.json'), 'w') as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    with open(os.path.join(HERE, 'luma_tone_lut_jp.json'), 'w') as f:
        json.dump(dict(lumaToneLut=jp.tolist()), f, indent=1)
    print('\n已写 v2_1_jp_result.json / luma_tone_lut_jp.json')


if __name__ == '__main__':
    main()
