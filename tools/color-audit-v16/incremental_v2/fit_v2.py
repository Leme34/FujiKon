"""通透胶片 v2：用 20 张作品的聚合亮度分布拟合「保色影调 LUT」。

与 1.6.32 / 1.6.33 的根本区别
--------------------------------
* 1.6.32 用逐通道 curve（redLut/greenLut/blueLut 各查一次非线性表），
  会拉伸彩色像素的 RGB 比例 -> 色相漂移 -> 1.6.33 严重偏色。
* v2 用「亮度耦合 LUT」：按 luma 查表，三通道同 scale + lift 缩放，
  RGB 比例严格不变，色相/饱和度零漂移。与 ImageRenderer 的
  adjustToneLut 路径 1:1 对应。

LUT 口径：输入/输出都是 0..255 的加权亮度
    luma = 0.299R + 0.587G + 0.114B
渲染器：mapped = lut[round(luma)]; scale = mapped/luma (luma>8 时);
        lift = mapped - luma*scale; out_c = c*scale + lift

拟合：目标 = 20 张作品聚合 luma 分布；源 = 13 张中性源图聚合 luma 分布。
CDF 匹配反演后向恒等收缩 alpha，savgol 平滑，强制单调，并施加
黑位下限 / 白位上限（防缩略图全黑 / 高光溢出）。
"""
import numpy as np
import json, os, glob, sys
from PIL import Image
from scipy.signal import savgol_filter

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET_DIR = os.path.dirname(HERE)                      # works_round2 (20 张 jpg)
NEUTRAL_DIR = ('/root/.codebuddy/artifact/2f8a7d30-9c21-4f0b-1d66-6dafb848b000'
               '/fit16/neutral')

# 每张图统一到这个像素数，保证「每张等权」，大图不占优。
SAMPLES_PER_IMAGE = 60000
SEED = 20261111


def luma_of(rgb_u8):
    a = rgb_u8.astype(np.float64)
    return a[..., 0] * 0.299 + a[..., 1] * 0.587 + a[..., 2] * 0.114


def sample_luma(path, rng):
    im = Image.open(path).convert('RGB')
    im.thumbnail((520, 520))
    a = np.asarray(im).reshape(-1, 3)
    if len(a) > SAMPLES_PER_IMAGE:
        idx = rng.choice(len(a), SAMPLES_PER_IMAGE, replace=False)
        a = a[idx]
    return luma_of(a)


def aggregate(paths, label):
    """返回每张等权的聚合 luma 样本 + 逐图分位表。"""
    rng = np.random.default_rng(SEED)
    per_image, all_samples = {}, []
    for p in paths:
        s = sample_luma(p, rng)
        name = os.path.basename(p)
        q = [1, 5, 10, 25, 50, 75, 90, 95, 99]
        per_image[name] = {f'p{v:02d}': round(float(np.percentile(s, v)), 2) for v in q}
        all_samples.append(s)
    pooled = np.concatenate(all_samples)
    print(f'[{label}] {len(paths)} 张 / {len(pooled):,} 采样像素')
    return pooled, per_image


def cdf_lut(src, tgt, alpha, black_floor=6, white_cap=250):
    """CDF 匹配：src 分位 -> tgt 同分位值，得到 luma_in -> luma_out LUT。"""
    grid = np.arange(256, dtype=float)
    # 目标/源的分位函数（在 0..255 上取 256 个分位点）
    qs = np.linspace(0, 1, 256)
    src_q = np.quantile(src, qs)
    tgt_q = np.quantile(tgt, qs)
    # 对每个输入亮度 v，找它在源分布的分位，再取目标分布同分位的值
    # 用 src_q 作为横坐标做反查（src_q 单调）
    v_out = np.interp(grid, src_q, tgt_q)
    # 向恒等收缩（防过拟合场景亮度构成）
    out = (1 - alpha) * grid + alpha * v_out
    # 单调 + 平滑 + 再单调
    out = np.maximum.accumulate(out)
    out = savgol_filter(out, 21, 2)
    out = np.maximum.accumulate(out)
    # 黑位下限 / 白位上限
    out = np.clip(out, black_floor, white_cap)
    # 端点固定：黑位抬起但不死黑，白点柔和收敛不到纯白
    out[0] = black_floor
    out[-1] = white_cap
    return np.round(out).astype(int)


def render_luma_coupled(luma, lut):
    """1:1 复刻 ImageRenderer 的亮度耦合路径（对 luma 标量）。"""
    idx = np.round(luma).astype(int).clip(0, 255)
    mapped = lut[idx].astype(float)
    scale = np.where(luma > 8.0, mapped / np.maximum(luma, 1e-6), 1.0)
    lift = mapped - luma * scale
    return scale, lift


def apply_to_source(src_rgb, lut):
    """对中性源图 RGB 施加亮度耦合 LUT，返回输出 RGB。"""
    a = src_rgb.astype(np.float64)
    luma = a[..., 0] * 0.299 + a[..., 1] * 0.587 + a[..., 2] * 0.114
    scale, lift = render_luma_coupled(luma, lut)
    out = a * scale[..., None] + lift[..., None]
    return np.clip(out, 0, 255)


def lab_of(rgb_u8):
    x = np.asarray(rgb_u8, np.float64) / 255.0
    lin = np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)
    M = np.array([[0.4124564, 0.3575761, 0.1804375],
                  [0.2126729, 0.7151522, 0.0721750],
                  [0.0193339, 0.1191920, 0.9503041]])
    xyz = lin @ M.T / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    return 116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])


def hue_of(rgb_u8):
    x = np.asarray(rgb_u8, np.float64) / 255.0
    mx, mn = x.max(-1), x.min(-1)
    d = mx - mn
    h = np.zeros_like(mx)
    m = d > 1e-6
    r, g, b = x[..., 0], x[..., 1], x[..., 2]
    idx = (mx == r) & m; h[idx] = ((g - b)[idx] / d[idx]) % 6
    idx = (mx == g) & m; h[idx] = ((b - r)[idx] / d[idx]) + 2
    idx = (mx == b) & m; h[idx] = ((r - g)[idx] / d[idx]) + 4
    return h * 60, d / (mx + 1e-9)


def probe_color_safety(lut):
    """灰阶 + 彩色探针：验证 LUT 不引入任何色相/彩度漂移。"""
    # 灰阶探针
    gray = np.stack([np.arange(256)] * 3, axis=-1).astype(np.uint8)
    g_out = apply_to_source(gray, lut)
    L, A, B = lab_of(g_out)
    gray_ab = float(np.max(np.abs(A)) + np.max(np.abs(B)))
    # 彩色探针：固定色相、不同亮度与彩度
    hues = np.arange(0, 360, 15, dtype=float)
    rng = np.random.default_rng(SEED)
    cols = []
    for hd in hues:
        for v in [40, 90, 150, 210]:
            for s in [0.25, 0.55, 0.85]:
                # HSV -> RGB
                c = v * s; hp = hd / 60.0
                xx = c * (1 - abs(hp % 2 - 1))
                m = v - c / 2
                if hp < 1: rr, gg, bb = c, xx, 0
                elif hp < 2: rr, gg, bb = xx, c, 0
                elif hp < 3: rr, gg, bb = 0, c, xx
                elif hp < 4: rr, gg, bb = 0, xx, c
                elif hp < 5: rr, gg, bb = xx, 0, c
                else: rr, gg, bb = c, 0, xx
                cols.append([rr + m, gg + m, bb + m])
    cols = np.clip(np.array(cols), 0, 255).astype(np.uint8)
    h0, s0 = hue_of(cols)
    out = apply_to_source(cols, lut)
    h1, s1 = hue_of(np.clip(out, 0, 255).astype(np.uint8))
    # 只统计输入有彩度的像素
    m = s0 > 0.15
    dh = np.abs(((h1 - h0 + 180) % 360) - 180)[m]
    # 饱和像素的比例变化（RGB 比例应严格保持，scale 为正）
    return dict(gray_ab_max=round(gray_ab, 4),
                hue_shift_mean=round(float(dh.mean()), 4),
                hue_shift_p95=round(float(np.percentile(dh, 95)), 4),
                hue_shift_max=round(float(dh.max()), 4))


def evaluate_lut(lut, neutral_paths, target_luma, label):
    """把 LUT 套到中性源图，测输出分位与目标的差距 + 光感/裁剪指标。"""
    rng = np.random.default_rng(SEED)
    outs = []
    for p in neutral_paths:
        im = Image.open(p).convert('RGB')
        im.thumbnail((520, 520))
        a = np.asarray(im).reshape(-1, 3)
        if len(a) > SAMPLES_PER_IMAGE:
            a = a[rng.choice(len(a), SAMPLES_PER_IMAGE, replace=False)]
        o = apply_to_source(a, lut)
        outs.append(luma_of(np.clip(o, 0, 255).astype(np.uint8)))
    pooled = np.concatenate(outs)
    qs = [1, 5, 10, 25, 50, 75, 90, 95, 99]
    tp = np.percentile(target_luma, qs)
    op = np.percentile(pooled, qs)
    err = float(np.mean(np.abs(op - tp)))
    q = {f'p{v:02d}': (round(float(t), 2), round(float(o), 2))
         for v, t, o in zip(qs, tp, op)}
    return dict(label=label, alpha=None, quantile_err=round(err, 3), quantiles=q,
                L_std=round(float(np.std(pooled)), 2),
                clip_black=round(float((pooled < 5).mean()), 4),
                clip_white=round(float((pooled > 250).mean()), 4))


def main():
    tgt_paths = sorted(glob.glob(os.path.join(TARGET_DIR, '*.jpg')))
    src_paths = sorted(glob.glob(os.path.join(NEUTRAL_DIR, '*')))
    assert len(tgt_paths) == 20, f'目标应 20 张，实得 {len(tgt_paths)}'
    assert len(src_paths) == 13, f'中性源应 13 张，实得 {len(src_paths)}'

    tgt, tgt_per = aggregate(tgt_paths, '20 张作品')
    src, src_per = aggregate(src_paths, '13 张中性源图')

    print('\n=== 源 vs 目标 luma 分位 ===')
    for k in ['p01', 'p10', 'p50', 'p90', 'p95', 'p99']:
        sv = float(np.percentile(src, int(k[1:])))
        tv = float(np.percentile(tgt, int(k[1:])))
        print(f'  {k}: 源 {sv:6.2f}  目标 {tv:6.2f}  Δ={tv-sv:+6.2f}')

    # 多 alpha 网格搜索 + 闭环评分（分位误差 + 平滑 + 裁剪惩罚）
    results = []
    for alpha in [0.45, 0.55, 0.65, 0.75, 0.85, 0.95, 1.0]:
        lut = cdf_lut(src, tgt, alpha)
        ev = evaluate_lut(lut, src_paths, tgt, f'a{int(alpha*100)}')
        ev['alpha'] = alpha
        safety = probe_color_safety(lut)
        ev['safety'] = safety
        # 平滑度（二阶差分）
        ev['curvature'] = round(float(np.mean(np.abs(np.diff(lut, 2)))), 3)
        # 单调性
        ev['monotonic'] = bool(np.all(np.diff(lut) >= 0))
        score = ev['quantile_err'] + abs(ev['L_std'] - float(np.std(tgt))) * 0.15
        score += ev['clip_white'] * 100 + ev['curvature'] * 0.5
        ev['score'] = round(score, 3)
        results.append((ev, lut))
        print(f'  a{int(alpha*100):3d}: 分位误差 {ev["quantile_err"]:6.3f} '
              f'L_std {ev["L_std"]:5.2f} clipW {ev["clip_white"]:.4f} '
              f'曲率 {ev["curvature"]:5.3f} 色相漂移 {safety["hue_shift_mean"]:.4f}° '
              f'score {ev["score"]:.3f}')

    best_ev, best_lut = min(results, key=lambda x: x[1] if False else x[0]['score'])
    print(f'\n=== 最优 alpha={best_ev["alpha"]} ===')
    for k, (t, o) in best_ev['quantiles'].items():
        print(f'  {k}: 目标 {t:6.2f}  输出 {o:6.2f}  Δ={o-t:+.2f}')
    print(f'  保色探针: {best_ev["safety"]}')
    print(f'  单调: {best_ev["monotonic"]}  L_std: {best_ev["L_std"]}  '
          f'clipW: {best_ev["clip_white"]}')

    print('\n=== LUT 关键节点 ===')
    for x in [0, 8, 16, 32, 64, 96, 128, 160, 192, 224, 240, 255]:
        print(f'  in {x:3d} -> out {best_lut[x]:3d}  (Δ{best_lut[x]-x:+d})')

    out = dict(
        method='20 张作品聚合 luma 分布 vs 13 张中性源图 CDF 反演；亮度耦合保色 LUT',
        n_target=20, n_neutral=13, samples_per_image=SAMPLES_PER_IMAGE,
        chosen_alpha=best_ev['alpha'],
        lumaToneLut=best_lut.tolist(),
        evaluation=best_ev,
        all_alphas=[e for e, _ in results],
        source_quantiles={k: round(float(np.percentile(src, int(k[1:]))), 2)
                          for k in ['p01', 'p10', 'p50', 'p90', 'p95', 'p99']},
        target_quantiles={k: round(float(np.percentile(tgt, int(k[1:]))), 2)
                          for k in ['p01', 'p10', 'p50', 'p90', 'p95', 'p99']},
        per_image_target=tgt_per,
    )
    with open(os.path.join(HERE, 'v2_fit_result.json'), 'w') as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    with open(os.path.join(HERE, 'luma_tone_lut.json'), 'w') as f:
        json.dump(dict(lumaToneLut=best_lut.tolist()), f, indent=1)
    print('\n已写 v2_fit_result.json / luma_tone_lut.json')


if __name__ == '__main__':
    main()
