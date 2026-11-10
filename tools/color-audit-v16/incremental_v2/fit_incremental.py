"""通透胶片 v2 增量拟合（最终版）。

定位：「提升」而非「重做」。以 1.6.32 已验证的 tone LUT 为基线，
用 20 张新作品相对 16 张旧作品的**风格差**做温和增量，受硬约束保护。

两处关键修正（相对初版）
------------------------
1. 保色溢出保护：亮度耦合 LUT 若逐通道 coerceIn(0,255)，高光溢出时
   会破坏 RGB 比例 -> 色相漂移。改为「最大通道触顶时三通道等比回缩」，
   RGB 比例严格不变，色相恒为 0。渲染器与此处 1:1 对应。
2. 目标函数改为「温和增量方向」：不硬套绝对分位（会把曝光差学进去、
   压低高光导致发灰），而是把新旧形状差按 gain 缩小后作为软目标，
   并禁止压低高光区。

方法
----
1. 基线 v1 = 1.6.32 的 lut255（0..255 luma 域，单调，最大 +37）。
2. 增量 Δ(i) 参数化为 5 控制点的平滑修正，叠加到 v1，偏移上限 20。
3. 目标：输出相对基线的分位增量 ≈ 新旧形状差 * gain；L_std 向目标靠拢；
   灰阶 a/b=0、彩色像素色相漂移=0、无裁剪、曲线平滑单调。
4. 输出 lumaToneLut（256 点整数）+ 完整验证数据。
"""
import numpy as np
import json, os, glob
from PIL import Image
from scipy.signal import savgol_filter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OLD_DIR = '/workspace/待分析照片'
NEUTRAL_DIR = ('/root/.codebuddy/artifact/2f8a7d30-9c21-4f0b-1d66-6dafb848b000'
               '/fit16/neutral')
V1_LUT = np.array(json.load(open('/workspace/FujiKon/tools/color-audit-v16/'
                                 'fit16_final_lut.json'))['lut255'], dtype=float)

SAMPLES = 60000
SEED = 20261111
QS = np.array([1, 5, 10, 25, 50, 75, 90, 95, 99], dtype=float)
GAIN = 0.30          # 新旧形状差缩放到「温和增量」


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
    """1:1 复刻 ImageRenderer 的【保色亮度耦合】路径。

    mapped = lut[round(luma)]; scale = mapped/luma (luma>8);
    lift = mapped - luma*scale (=0 当 luma>8)
    溢出保护：若 max(r,g,b)*scale + lift > 255，则把 scale 重算为
    (255-lift)/maxChannel，三通道等比回缩 -> RGB 比例不变 -> 色相不变。
    """
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


def make_delta(ctrl):
    knots = np.array([0, 32, 96, 176, 255], dtype=float)
    d = np.interp(np.arange(256), knots, np.array(ctrl, float))
    return savgol_filter(d, 31, 2)


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


def main():
    tgt = pool(glob.glob(os.path.join(ROOT, '*.jpg')))
    old = pool(glob.glob(os.path.join(OLD_DIR, '*.jpg')))
    neu = pool(glob.glob(os.path.join(NEUTRAL_DIR, '*')))
    print('目标 20 张 / 旧 16 张 / 中性源 13 张')

    tL = np.concatenate([luma(a) for a in tgt])
    oL = np.concatenate([luma(a) for a in old])
    tQ, oQ = np.percentile(tL, QS), np.percentile(oL, QS)
    tStd = float(tL.std())
    print('\n=== 新 20 张 vs 旧 16 张 luma 分位（风格差）===')
    for q, tv, ov in zip(QS, tQ, oQ):
        print(f'  p{int(q):02d}: 新 {tv:6.2f}  旧 {ov:6.2f}  Δ={tv-ov:+6.2f}')
    print(f'  L_std: 新 {tStd:.2f}  旧 {float(oL.std()):.2f}')

    base_out = np.concatenate([luma(apply_lut(a, V1_LUT)) for a in neu])
    bQ = np.percentile(base_out, QS)
    print('\n=== 1.6.32 基线套到中性源图的输出分位 ===')
    print('  ' + '  '.join(f'p{int(q):02d}={v:.1f}' for q, v in zip(QS, bQ)))

    # 温和增量软目标：新旧形状差扣除整体平移，再按 GAIN 缩小
    raw_delta = tQ - oQ
    shift = float(np.median(raw_delta))
    shape_delta = raw_delta - shift
    desired = np.clip(shape_delta * GAIN, -8, 14)
    # 高光区禁止压低（保持通透、不死白，也不发灰）
    desired[6:] = np.clip(desired[6:], 0, 6)
    print(f'\n  扣除整体平移 {shift:+.2f}、gain={GAIN} 后的期望增量:')
    print('  ' + '  '.join(f'p{int(q):02d}={v:+.1f}' for q, v in zip(QS, desired)))

    rng = np.random.default_rng(SEED)
    best = None
    for trial in range(520):
        ctrl = [rng.uniform(-3, 12),     # 0   黑位（柔化，不抬死黑）
                rng.uniform(-2, 16),     # 32  暗部
                rng.uniform(-4, 16),     # 96  中间调
                rng.uniform(-3, 10),     # 176 高光（不再允许大幅压低）
                rng.uniform(-2, 4)]      # 255 白点
        delta = make_delta(ctrl)
        cand = np.maximum.accumulate(V1_LUT + delta)
        cand = np.clip(cand, 6, 250)
        cand[0] = 6; cand[-1] = 250
        if np.max(np.abs(cand - V1_LUT)) > 20:
            continue
        if not np.all(np.diff(cand) >= 0):
            continue
        curv = float(np.mean(np.abs(np.diff(cand, 2))))
        out = np.concatenate([luma(apply_lut(a, cand)) for a in neu])
        oq = np.percentile(out, QS)
        d_out = oq - bQ
        err = float(np.mean(np.abs(d_out - desired) * np.array(
            [0.5, 0.8, 1.0, 1.3, 1.3, 1.0, 0.9, 0.7, 0.5])))
        cp = color_probe(cand)
        clip_w = float((out > 250).mean())
        clip_b = float((out < 5).mean())
        score = (err * 1.0
                 + cp['hue_mean'] * 12.0 + cp['gray_ab'] * 8.0
                 + clip_w * 80 + clip_b * 80
                 + curv * 0.8
                 + abs(float(out.std()) - tStd) * 0.04)
        if best is None or score < best[0]:
            best = (score, cand, dict(err=round(err, 3), curvature=round(curv, 3),
                                      color=cp, clip_white=round(clip_w, 4),
                                      clip_black=round(clip_b, 4),
                                      L_std=round(float(out.std()), 2),
                                      out_q=oq.round(2).tolist(),
                                      d_out=d_out.round(2).tolist(),
                                      desired=desired.round(2).tolist(),
                                      ctrl=[round(c, 2) for c in ctrl]))
    score, lut_f, info = best
    lut = np.round(lut_f).astype(int)
    lut = np.maximum.accumulate(np.clip(lut, 6, 250))
    lut[0] = 6; lut[-1] = 250

    print(f'\n=== 最优增量解 score={score:.3f} ===')
    print(f'  控制点: {info["ctrl"]}')
    print(f'  增量误差 {info["err"]}  曲率 {info["curvature"]}  '
          f'L_std {info["L_std"]}  clipW {info["clip_white"]}')
    print(f'  保色探针: {info["color"]}')
    print('\n  增量对照（期望 / 实际）:')
    for i, q in enumerate(QS):
        print(f'    p{int(q):02d}: 期望 {info["desired"][i]:+6.2f}  '
              f'实际 {info["d_out"][i]:+6.2f}  误差 {info["d_out"][i]-info["desired"][i]:+6.2f}')

    print('\n=== v2 LUT 关键节点（vs 1.6.32 基线）===')
    for x in [0, 8, 16, 32, 64, 96, 128, 160, 192, 224, 240, 255]:
        print(f'  in {x:3d} -> 基线 {int(V1_LUT[x]):3d}  v2 {lut[x]:3d}  '
              f'(增量 {lut[x]-int(V1_LUT[x]):+d}, 总偏移 {lut[x]-x:+d})')

    final_probe = color_probe(lut.astype(float))
    print(f'\n=== 量化后最终保色复核 ===\n  {final_probe}')
    assert final_probe['gray_ab'] < 0.01, '灰阶出现色偏'
    assert final_probe['hue_mean'] < 0.5, '色相漂移超标'
    assert np.all(np.diff(lut) >= 0), 'LUT 非单调'

    out = dict(
        method='1.6.32 基线 + 20 张作品风格差的温和增量；保色亮度耦合 LUT（含溢出等比回缩）',
        n_target=20, n_old=16, n_neutral=13,
        gain=GAIN, exposure_shift_removed=round(shift, 3),
        baseline_source='tools/color-audit-v16/fit16_final_lut.json::lut255',
        lumaToneLut=lut.tolist(),
        info=info, final_probe=final_probe,
        desired_delta=desired.round(2).tolist(),
        baseline_output_quantiles=bQ.round(2).tolist(),
        v2_output_quantiles=info['out_q'],
        target_quantiles=tQ.round(2).tolist(),
        old_quantiles=oQ.round(2).tolist(),
        baseline_lut=V1_LUT.astype(int).tolist(),
    )
    with open(os.path.join(HERE, 'v2_final_result.json'), 'w') as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    with open(os.path.join(HERE, 'luma_tone_lut.json'), 'w') as f:
        json.dump(dict(lumaToneLut=lut.tolist()), f, indent=1)
    print('\n已写 v2_final_result.json / luma_tone_lut.json')


if __name__ == '__main__':
    main()
