"""Vitality Film v4 色彩标定拟合。

1:1 复刻 ImageRenderer 的色彩管线（buildToneLut / saturation / hueMatrix /
colorBlender / colorGrading），用「灰阶探针 + 色相色卡」做算子级标定：

目标（来自 12 张官方/官方合作实拍的聚合指纹，见 fingerprints.json）：
  - 按亮度分区的 a 梯度：dark -5.3 / mid -8.8 / high -0.8
    → 中暗部显著偏青绿、亮部接近中性（这是滤镜的稳定特征，与场景无关）
  - 绿植 hue 127→137（向青偏约 15°）、青蓝 hue≈193
  - 肤色 hue≈23.6 / a≈+7 / b≈+12.9：温暖红润，必须保持不变
  - 「色彩浓郁」：整体彩度不降、略增

由于官方只有 after 单张、没有 before-after 配对，本拟合是「统计风格 / 算子辨识」，
不是像素级 ΔE00 复刻。灰阶探针输入 a=b=0，任何色偏都 100% 来自滤镜，因此用它
标定「滤镜对中暗部注入的青色量」，再叠加色相带变换，最后在真实照片上目视复核。
"""
import numpy as np
import re, json, os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
KT = "/workspace/FujiKon/app/src/main/java/com/fujikon/app/nic/CloudLookCalibration.kt"

# ---------------------------------------------------------------- 色彩空间
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


# ------------------------------------------------- Kotlin rgbToHsl / hslToRgb
def kt_rgb_to_hsl(r, g, b):
    """r,g,b 为 0..255 float 数组；返回 H(0..360), S(0..1), L(0..1)。"""
    rf, gf, bf = r / 255.0, g / 255.0, b / 255.0
    mx = np.maximum(np.maximum(rf, gf), bf)
    mn = np.minimum(np.minimum(rf, gf), bf)
    l = (mx + mn) * 0.5
    d = mx - mn
    s = np.where(d < 1e-6, 0.0, d / np.maximum(1.0 - np.abs(2 * l - 1), 1e-6))
    h = np.zeros_like(mx)
    mR = (mx == rf) & (d >= 1e-6)
    mG = (mx == gf) & (d >= 1e-6) & ~mR
    mB = (mx == bf) & (d >= 1e-6) & ~mR & ~mG
    h[mR] = ((gf - bf)[mR] / d[mR] + np.where(gf[mR] < bf[mR], 6.0, 0.0)) * 60
    h[mG] = ((bf - rf)[mG] / d[mG] + 2) * 60
    h[mB] = ((rf - gf)[mB] / d[mB] + 4) * 60
    h = np.where(h < 0, h + 360, h)
    return h, np.clip(s, 0, 1), l


def kt_hsl_to_rgb(h, s, l):
    """返回 0..255 float 三通道数组。"""
    c = (1 - np.abs(2 * l - 1)) * s
    hp = ((h % 360) + 360) % 360 / 60
    x = c * (1 - np.abs(hp % 2 - 1))
    r1 = np.zeros_like(h); g1 = np.zeros_like(h); b1 = np.zeros_like(h)
    m0 = hp < 1; m1 = (hp >= 1) & (hp < 2); m2 = (hp >= 2) & (hp < 3)
    m3 = (hp >= 3) & (hp < 4); m4 = (hp >= 4) & (hp < 5); m5 = hp >= 5
    r1[m0] = c[m0]; g1[m0] = x[m0]
    r1[m1] = x[m1]; g1[m1] = c[m1]
    g1[m2] = c[m2]; b1[m2] = x[m2]
    g1[m3] = x[m3]; b1[m3] = c[m3]
    r1[m4] = x[m4]; b1[m4] = c[m4]
    r1[m5] = c[m5]; b1[m5] = x[m5]
    m = l - c * 0.5
    return (np.stack([(r1 + m) * 255, (g1 + m) * 255, (b1 + m) * 255], -1))


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


BAND_CENTERS = [0., 30., 60., 120., 180., 240., 280., 320.]
BAND_WIDTH = 52.


# ------------------------------------------------------------- LUT & 渲染
def load_vitality_curve():
    txt = open(KT, encoding='utf-8').read()
    key = '"01JSBM30XWQ6GG4XK0KSZ3FQEK"'
    seg = txt[txt.index(key):txt.index(key) + 7000]
    body = re.search(r'curve = intArrayOf\((.*?)\)', seg, re.S).group(1)
    nums = [int(x) for x in re.findall(r'-?\d+', body)]
    assert len(nums) == 256, len(nums)
    return nums


def build_tone_lut(curve):
    return np.clip(np.round(np.asarray(curve, float) / 32767.0 * 255), 0, 255).astype(int)


def hsl_to_rgb_scalar(h, s, l):
    """Kotlin hslToRgb 单值版，用于 colorGrading tint。返回 (r,g,b) 0..255 int。"""
    c = (1 - abs(2 * l - 1)) * s
    hp = ((h % 360) + 360) % 360 / 60
    x = c * (1 - abs(hp % 2 - 1))
    if hp < 1:   r1, g1, b1 = c, x, 0
    elif hp < 2: r1, g1, b1 = x, c, 0
    elif hp < 3: r1, g1, b1 = 0, c, x
    elif hp < 4: r1, g1, b1 = 0, x, c
    elif hp < 5: r1, g1, b1 = x, 0, c
    else:        r1, g1, b1 = c, 0, x
    m = l - c * 0.5
    return (int(np.clip(round((r1 + m) * 255), 0, 255)),
            int(np.clip(round((g1 + m) * 255), 0, 255)),
            int(np.clip(round((b1 + m) * 255), 0, 255)))


def render(pixels, cfg, lut):
    """1:1 复刻 ImageRenderer.processRows 的预设色彩部分。"""
    p = pixels.astype(np.int64)
    r = lut[p[..., 0]].astype(float)
    g = lut[p[..., 1]].astype(float)
    b = lut[p[..., 2]].astype(float)

    sat = 1.0 + cfg['saturation'] / 100.0
    luma = r * 0.299 + g * 0.587 + b * 0.114
    r = np.round(luma + (r - luma) * sat)
    g = np.round(luma + (g - luma) * sat)
    b = np.round(luma + (b - luma) * sat)

    # colorBlender
    bl = cfg['blender']  # 8 x 3, 已是 -100..100 整数
    if any(v != 0 for row in bl for v in row):
        h, s, l = kt_rgb_to_hsl(r, g, b)
        hueShift = np.zeros_like(h); chromaAdd = np.zeros_like(h); brightAdd = np.zeros_like(h)
        for band, center in enumerate(BAND_CENTERS):
            diff = np.abs(h - center)
            diff = np.minimum(diff, 360 - diff)
            w = np.maximum(0, 1 - diff / BAND_WIDTH)
            hueShift += bl[band][0] / 100.0 * w
            chromaAdd += bl[band][1] / 100.0 * w
            brightAdd += bl[band][2] / 100.0 * w
        active = (np.abs(hueShift) + np.abs(chromaAdd) + np.abs(brightAdd)) > 1e-9
        if active.any():
            hn = (h + hueShift * 28.0 + 360) % 360
            sn = np.clip(s * (1 + chromaAdd * 0.75), 0, 1)
            ln = np.clip(l + brightAdd * 0.075, 0, 1)
            rgb = kt_hsl_to_rgb(hn, sn, ln)
            r = np.where(active, np.round(rgb[..., 0]), r)
            g = np.where(active, np.round(rgb[..., 1]), g)
            b = np.where(active, np.round(rgb[..., 2]), b)

    # colorGrading
    gr = cfg['grading']  # dict: highlights/midtones/shadows -> (hue,chroma,brightness)
    zones = [gr['highlights'], gr['midtones'], gr['shadows']]
    if any(c != 0 or br != 0 for _, c, br in zones):
        tint = np.array([hsl_to_rgb_scalar(hue, 0.78, 0.5) for hue, _, _ in zones], float) / 255.0
        chroma = np.array([c / 100.0 for _, c, _ in zones])
        bright = np.array([br / 100.0 for _, _, br in zones])
        lum = (r * 0.299 + g * 0.587 + b * 0.114) / 255.0
        adjusted = np.clip(lum + cfg['grading_balance'] * 0.18, 0, 1)
        soft = 0.18 + cfg['grading_blend'] * 0.42
        w0 = smoothstep(0.58 - soft * 0.35, 1.0, adjusted)          # high
        w2 = 1 - smoothstep(0.0, 0.42 + soft * 0.35, adjusted)       # shadow
        w1 = np.maximum(0, 1 - w0 - w2)                              # mid
        ws = [w0, w1, w2]
        dr = np.zeros_like(r); dg = np.zeros_like(r); db = np.zeros_like(r)
        for z in range(3):
            s = chroma[z] * ws[z]
            dr += (tint[z][0] - 0.5) * s * 0.55
            dg += (tint[z][1] - 0.5) * s * 0.55
            db += (tint[z][2] - 0.5) * s * 0.55
        brw = bright[0] * w0 + bright[1] * w1 + bright[2] * w2
        dr += brw * 0.09; dg += brw * 0.09; db += brw * 0.09
        r = np.round(r + dr * 255); g = np.round(g + dg * 255); b = np.round(b + db * 255)

    return np.clip(np.stack([r, g, b], -1), 0, 255).astype(np.uint8)


# ------------------------------------------------------------------- 探针图
def make_probe():
    """上半：256 级灰阶（每行重复 12 次）；下半：色相 x 明度 x 彩度 色卡。"""
    W = 256
    gray = np.repeat(np.arange(256, dtype=np.uint8).reshape(1, W, 1).repeat(3, 2), 14, axis=0)
    hues = [0, 20, 30, 45, 60, 90, 120, 140, 160, 180, 195, 210, 240, 280, 320]
    ls = [0.30, 0.45, 0.60, 0.75]
    ss = [0.25, 0.50]
    rows = []
    for L in ls:
        for S in ss:
            row = []
            for H in hues:
                c = (1 - abs(2 * L - 1)) * S
                hp = H / 60.0
                x = c * (1 - abs(hp % 2 - 1))
                if hp < 1:   r1, g1, b1 = c, x, 0
                elif hp < 2: r1, g1, b1 = x, c, 0
                elif hp < 3: r1, g1, b1 = 0, c, x
                elif hp < 4: r1, g1, b1 = 0, x, c
                elif hp < 5: r1, g1, b1 = x, 0, c
                else:        r1, g1, b1 = c, 0, x
                m = L - c * 0.5
                rgb = (np.clip(np.array([r1 + m, g1 + m, b1 + m]) * 255, 0, 255)).astype(np.uint8)
                row.append(np.repeat(rgb.reshape(1, 1, 3), W // len(hues) + 1, axis=1))
            rows.append(np.concatenate(row, axis=1)[:, :W])
    sw = np.concatenate([np.repeat(rows[k], 12, axis=0) for k in range(len(rows))], axis=0)
    return gray, sw, hues, ls, ss


def measure(gray_out, sw_out, hues, ls, ss):
    """返回可比较的算子指标。"""
    L, A, B = srgb_to_lab(gray_out.astype(float) / 255.0)
    Ln = L / 100.0
    dark = Ln < 0.33; mid = (Ln >= 0.33) & (Ln < 0.66); high = Ln >= 0.66
    out = {}
    for name, m in [('dark', dark), ('mid', mid), ('high', high)]:
        out[f'{name}_a'] = float(A[m].mean()) if m.sum() > 10 else 0.0
        out[f'{name}_b'] = float(B[m].mean()) if m.sum() > 10 else 0.0

    # 色卡：按 (hue, l, s) 分块统计输出 hue(HSV)/sat
    H, S, V = rgb_to_hsv(sw_out.astype(float) / 255.0)
    block_h = 12; cw = 256 // len(hues) + 1
    # 每块取中心区域
    def block_stat(hi, li, si, arr_h, arr_s):
        idx = (li * len(ss) + si)
        y0 = idx * block_h + 3; y1 = y0 + block_h - 6
        x0 = hi * cw + 2; x1 = min(x0 + cw - 4, 256)
        hh = arr_h[y0:y1, x0:x1]; ss_ = arr_s[y0:y1, x0:x1]
        m = ss_ > 0.08
        return (float(np.mean(hh[m])) if m.sum() > 5 else 0.0,
                float(np.mean(ss_[m])) if m.sum() > 5 else 0.0)
    # 绿块 hue=120 -> 向青偏；青蓝块 195
    out['green_hue'] = block_stat(hues.index(120), 1, 1, H, S)[0]
    out['cyanblue_hue'] = block_stat(hues.index(195), 1, 1, H, S)[0]
    out['cyanblue_sat'] = block_stat(hues.index(195), 1, 1, H, S)[1]
    # 肤色块 hue=20/30 的 Lab a,b（取 L=0.75 高亮、S=0.5）
    Ls, As, Bs = srgb_to_lab(sw_out.astype(float) / 255.0)
    def skin_stat(hi):
        idx = 3 * len(ss) + 1
        y0 = idx * block_h + 3; y1 = y0 + block_h - 6
        x0 = hi * cw + 2; x1 = min(x0 + cw - 4, 256)
        return float(As[y0:y1, x0:x1].mean()), float(Bs[y0:y1, x0:x1].mean()), float(np.mean(H[y0:y1, x0:x1]))
    out['skin_a'], out['skin_b'], out['skin_hue'] = skin_stat(hues.index(20))
    out['skin_a2'], out['skin_b2'], out['skin_hue2'] = skin_stat(hues.index(30))
    out['mean_sat'] = float(S.mean())
    return out


def base_cfg():
    return dict(
        saturation=0,
        blender=[[0, 0, 0] for _ in range(8)],
        grading={'highlights': (0, 0, 0), 'midtones': (0, 0, 0), 'shadows': (0, 0, 0)},
        grading_blend=0.5, grading_balance=0.0,
    )


def fmt(cfg):
    return json.dumps({
        'saturation': cfg['saturation'],
        'blender': cfg['blender'],
        'grading': {
            'highlights': list(cfg['grading']['highlights']),
            'midtones': list(cfg['grading']['midtones']),
            'shadows': list(cfg['grading']['shadows']),
        },
        'blending': int(round(cfg['grading_blend'] * 100)),
        'balance': int(round(cfg['grading_balance'] * 100)),
    }, ensure_ascii=False, indent=1)


if __name__ == '__main__':
    from scipy.optimize import least_squares
    curve = load_vitality_curve()
    lut = build_tone_lut(curve)
    gray, sw, hues, ls, ss = make_probe()
    cur = base_cfg()
    cur_out = render(gray, cur, lut), render(sw, cur, lut)
    base = measure(*cur_out, hues, ls, ss)
    print('=== 当前实现（只有 LUT）探针基线 ===')
    print(json.dumps({k: round(v, 3) for k, v in base.items()}, ensure_ascii=False, indent=1))

    # 目标：算子级「中暗部青、亮部中性、肤色不变、绿向青偏、浓郁」
    T = dict(mid_a=-6.5, dark_a=-3.2, high_a=-0.4,
             mid_b=-2.2, dark_b=-1.0, high_b=0.0,
             green_hue=135.0, cyanblue_hue=193.0, cyanblue_sat=base['cyanblue_sat'] * 1.06,
             skin_a=base['skin_a'], skin_b=base['skin_b'], skin_hue=base['skin_hue'],
             skin_a2=base['skin_a2'], skin_b2=base['skin_b2'], skin_hue2=base['skin_hue2'],
             mean_sat=base['mean_sat'] * 1.04)
    W = dict(mid_a=1.0, dark_a=1.0, high_a=1.2,
             mid_b=0.35, dark_b=0.25, high_b=0.3,
             green_hue=1.4, cyanblue_hue=0.4, cyanblue_sat=0.5,
             skin_a=1.6, skin_b=1.6, skin_hue=1.2,
             skin_a2=1.6, skin_b2=1.6, skin_hue2=1.2,
             mean_sat=0.4)

    # 参数向量（连续）：
    # 0 grading hue(共用) 1 mid chroma 2 shadow chroma 3 high chroma
    # 4 saturation 5 green-band hue 6 green-band chroma 7 cyan-band chroma
    # 8 balance
    def cfg_from_x(x):
        hue = x[0]
        c = base_cfg()
        c['grading'] = {
            'highlights': (hue, x[3], 0),
            'midtones': (hue, x[1], 0),
            'shadows': (hue, x[2], 0),
        }
        c['saturation'] = x[4]
        bl = [[0, 0, 0] for _ in range(8)]
        bl[3] = [x[5], x[6], 0]   # 120 绿带
        bl[4] = [0, x[7], 0]      # 180 青带
        c['blender'] = bl
        c['grading_balance'] = x[8]
        return c

    cache = {}
    def evaluate(x):
        key = tuple(np.round(x, 3))
        if key in cache:
            return cache[key]
        c = cfg_from_x(x)
        m = measure(render(gray, c, lut), render(sw, c, lut), hues, ls, ss)
        cache[key] = m
        return m

    def resid(x):
        m = evaluate(x)
        return np.array([W[k] * (m[k] - T[k]) / max(1.0, abs(T[k]) ** 0.5) for k in T])

    x0 = np.array([190., 30., 18., 0., 8., 25., 10., 8., 0.])
    lo = np.array([150., 0., 0., -3., -5., -10., -10., -10., -0.3])
    hi = np.array([215., 90., 70., 6., 22., 70., 35., 30., 0.3])
    res = least_squares(resid, x0, bounds=(lo, hi), diff_step=0.02, max_nfev=120,
                        xtol=1e-6, ftol=1e-6)
    x = res.x
    print('\n=== 连续最优参数 ===')
    print(np.round(x, 2))

    # 取整（写回 Kotlin 需要整数）
    def quant(x):
        c = cfg_from_x(x)
        c['grading'] = {k: (int(round(v[0])), int(round(v[1])), int(round(v[2])))
                        for k, v in c['grading'].items()}
        c['saturation'] = int(round(c['saturation']))
        c['blender'] = [[int(round(v)) for v in row] for row in c['blender']]
        c['grading_balance'] = round(c['grading_balance'], 2)
        return c
    best = quant(x)
    m = measure(render(gray, best, lut), render(sw, best, lut), hues, ls, ss)
    print('\n=== 取整后参数 ===')
    print(fmt(best))
    print('\n=== 取整后探针指标 vs 目标 ===')
    for k in T:
        print(f"  {k:16s} base={base[k]:8.3f}  now={m[k]:8.3f}  target={T[k]:8.3f}")
    err = sum(W[k] * (m[k] - T[k]) ** 2 for k in T)
    print(f'\n加权残差平方和 = {err:.3f}')

    json.dump({'params': json.loads(fmt(best)), 'metrics': m, 'target': T, 'base': base},
              open(os.path.join(HERE, 'vitality_v4_params.json'), 'w'),
              ensure_ascii=False, indent=1)
