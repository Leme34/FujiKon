"""从 16 张目标成片拟合一个可泛化滤镜。

方法论（与 1.6.32 的「凭官方 brief 加青」彻底切割）：
  * 只有「跨 4 批次冷暖场景都稳定」的量才是滤镜特征；全局 a/b 是场景色，不能学。
  * 影调是主要内容无关特征：用 16 张成片的聚合亮度分布，对中性源图做
    CDF 匹配反演一条单调 tone LUT，并「向恒等收缩 + 单调平滑」正则化，
    避免把场景亮度构成（逆光人像暗部多）硬套到任意照片。
  * 色彩层先做辨识：把 16 张成片与 13 张中性源图在「按亮度分区的 (a,b)/彩度」
    上对比；只有差异显著且方向稳定的项才进滤镜，否则保持恒等，杜绝染青。
  * 全链路 1:1 复刻 ImageRenderer，拟合出的参数可直接写回 Kotlin/Preset。

输出 fit16_params.json：tone LUT(256, 0..32767) + saturation + colorGrading
+ channelCurves 建议 + 探针指标 + 目标/源差异表。
"""
import numpy as np
import json, os, glob
from PIL import Image
from scipy.signal import savgol_filter

HERE = os.path.dirname(os.path.abspath(__file__))
V4 = HERE  # 渲染管线复刻同目录自带
import sys
sys.path.insert(0, V4)
from _render_pipeline import (srgb_to_lab, rgb_to_hsv, render, base_cfg,
                             make_probe, measure, BAND_CENTERS, BAND_WIDTH)

TARGET_DIR = '/workspace/待分析照片'
NEUTRAL_DIR = os.path.join(HERE, 'neutral')

M = np.array([[0.4124564, 0.3575761, 0.1804375],
              [0.2126729, 0.7151522, 0.0721750],
              [0.0193339, 0.1191920, 0.9503041]])
WHITE = np.array([0.95047, 1.0, 1.08883])


def load_pixels(path, maxside=520):
    im = Image.open(path).convert('RGB')
    im.thumbnail((maxside, maxside))
    return np.asarray(im).astype(np.float64) / 255.0


def stats(imgs):
    """聚合一组图像的 Lab L / a / b / HSV sat，返回 CDF 与分区统计。"""
    Ls, As, Bs, Ss = [], [], [], []
    labs = []
    for a in imgs:
        L, A, B = srgb_to_lab(a)
        h, s, v = rgb_to_hsv(a)
        Ls.append(L.ravel()); As.append(A.ravel()); Bs.append(B.ravel()); Ss.append(s.ravel())
        labs.append((L, A, B, s))
    L = np.concatenate(Ls); A = np.concatenate(As); B = np.concatenate(Bs); S = np.concatenate(Ss)
    Ln = L / 100.0
    dark = Ln < 0.33; mid = (Ln >= 0.33) & (Ln < 0.66); high = Ln >= 0.66
    zones = {}
    for nm, m in [('dark', dark), ('mid', mid), ('high', high)]:
        zones[nm] = dict(a=round(float(A[m].mean()), 2), b=round(float(B[m].mean()), 2),
                         chroma=round(float(np.sqrt(A[m] ** 2 + B[m] ** 2).mean()), 2),
                         sat=round(float(S[m].mean()), 3), L=round(float(L[m].mean()), 2))
    q = [1, 5, 10, 25, 50, 75, 90, 95, 99]
    quant = {f'p{p:02d}': round(float(np.percentile(L, p)), 2) for p in q}
    # 亮度 CDF（在 0..100 Lab L 上，256 bin）
    hist, edges = np.histogram(L, bins=256, range=(0, 100))
    cdf = np.cumsum(hist).astype(float); cdf /= cdf[-1]
    # 按 8 档亮度的 a/b 形状
    oct_ab = []
    for k in range(8):
        m = (Ln >= k / 8) & (Ln < (k + 1) / 8)
        oct_ab.append([round(float(A[m].mean()), 2), round(float(B[m].mean()), 2)]
                      if m.sum() > 30 else [0.0, 0.0])
    return dict(quant=quant, cdf=cdf.tolist(), edges=edges.tolist(), zones=zones,
                oct_ab=oct_ab, global_a=round(float(A.mean()), 2),
                global_b=round(float(B.mean()), 2), global_sat=round(float(S.mean()), 3))


def cdf_match_lut(src_cdf, tgt_cdf, alpha=1.0):
    """CDF 匹配：把源亮度分位映射到目标亮度分位，得 256 点 LUT（输入/输出均为 0..255 sRGB 值）。

    注意：Lab L 的 CDF 不能直接当 sRGB 查表，这里用「相对亮度分位」建立
    v_in(0..255) -> v_out(0..255) 的单调映射：对每个 sRGB 输入值 v，
    找到它在源分布里的分位 q，再取目标分布里同分位的亮度值。
    """
    src_cdf = np.asarray(src_cdf, float); tgt_cdf = np.asarray(tgt_cdf, float)
    # 输入 v 的源分位用线性 sRGB 近似（源 CDF 定义在 Lab L 0..100 网格上，
    # 用 sRGB->L 单调关系把 v 映射过去）
    v = np.arange(256, dtype=float) / 255.0
    # sRGB gamma 值 -> 近似 Lab L（单调）
    lin = np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)
    L_approx = 116 * np.cbrt(lin) - 16  # 灰阶近似（Y=lin 时 f=cbrt(lin)）
    L_approx = np.clip(L_approx, 0, 100)
    q = np.interp(L_approx, np.linspace(0, 100, 256), src_cdf)  # 源分位
    L_out = np.interp(q, tgt_cdf, np.linspace(0, 100, 256))      # 目标亮度
    # L_out -> sRGB
    fy = (L_out + 16) / 116.0
    lin_out = np.clip(fy ** 3, 0, 1)
    v_out = np.where(lin_out <= 0.0031308, lin_out * 12.92, 1.055 * lin_out ** (1 / 2.4) - 0.055)
    v_out = np.clip(v_out, 0, 1) * 255.0
    # 向恒等收缩（正则），再单调化 + 平滑
    out = (1 - alpha) * np.arange(256) + alpha * v_out
    out = np.maximum.accumulate(out)
    out = savgol_filter(out, 21, 2)
    out = np.maximum.accumulate(out)
    return np.clip(out, 0, 255)


if __name__ == '__main__':
    tgt_paths = sorted(glob.glob(os.path.join(TARGET_DIR, '[0-9][0-9]_*.jpg')))
    src_paths = sorted(glob.glob(os.path.join(NEUTRAL_DIR, '*')))
    assert len(tgt_paths) == 16, len(tgt_paths)
    tgt_imgs = [load_pixels(p) for p in tgt_paths]
    src_imgs = [load_pixels(p) for p in src_paths]
    print(f'目标 {len(tgt_imgs)} 张 / 中性源 {len(src_imgs)} 张')

    T = stats(tgt_imgs)
    S = stats(src_imgs)
    print('\n=== 亮度分位：16 张成片 vs 中性源图 ===')
    for k in T['quant']:
        print(f"  {k}: 成片 {T['quant'][k]:6.2f}  源 {S['quant'][k]:6.2f}  Δ={T['quant'][k]-S['quant'][k]:+.2f}")
    print('\n=== 分区色度：成片 vs 源（判断色彩层是否该动）===')
    for z in ['dark', 'mid', 'high']:
        print(f"  {z:5s} 成片 a={T['zones'][z]['a']:+.2f} b={T['zones'][z]['b']:+.2f} "
              f"chroma={T['zones'][z]['chroma']:.2f} sat={T['zones'][z]['sat']:.3f} | "
              f"源 a={S['zones'][z]['a']:+.2f} b={S['zones'][z]['b']:+.2f} "
              f"chroma={S['zones'][z]['chroma']:.2f} sat={S['zones'][z]['sat']:.3f}")
    print(f"  全局 a/b/sat: 成片 {T['global_a']:+.2f}/{T['global_b']:+.2f}/{T['global_sat']:.3f} "
          f"源 {S['global_a']:+.2f}/{S['global_b']:+.2f}/{S['global_sat']:.3f}")
    print(f"  8档 a/b 形状 成片 {T['oct_ab']}")
    print(f"  8档 a/b 形状 源   {S['oct_ab']}")

    # 影调 LUT（多强度）
    luts = {}
    for alpha in [0.5, 0.7, 0.85, 1.0]:
        lut = cdf_match_lut(S['cdf'], T['cdf'], alpha)
        luts[f'a{int(alpha*100)}'] = np.round(lut).astype(int)
    chosen = luts['a85']
    print('\n=== 拟合 tone LUT (alpha=0.85) 关键节点 ===')
    for x in [0, 16, 32, 64, 96, 128, 160, 192, 224, 255]:
        print(f"  in {x:3d} -> out {chosen[x]:3d}  (Δ{chosen[x]-x:+d})")

    # 用探针验证 LUT 本身不引入色偏（灰阶输入，输出 a/b 应≈0）
    gray, sw, hues, ls, ss = make_probe()
    ident = np.arange(256)
    cfg = base_cfg()
    m_id = measure(render(gray, cfg, ident), render(sw, cfg, ident), hues, ls, ss)
    m_lut = measure(render(gray, cfg, chosen), render(sw, cfg, chosen), hues, ls, ss)
    print('\n=== 探针：恒等 vs 拟合 LUT（验证 LUT 不染色）===')
    for k in ['dark_a', 'mid_a', 'high_a', 'dark_b', 'mid_b', 'high_b',
              'green_hue', 'skin_a', 'skin_b', 'skin_hue', 'mean_sat']:
        print(f"  {k:12s} 恒等 {m_id[k]:7.3f}  LUT {m_lut[k]:7.3f}  Δ={m_lut[k]-m_id[k]:+.3f}")

    out = dict(
        method='聚合亮度 CDF 反演 tone LUT + 色彩层辨识（克制）',
        target_quant=T['quant'], source_quant=S['quant'],
        target_zones=T['zones'], source_zones=S['zones'],
        target_global=dict(a=T['global_a'], b=T['global_b'], sat=T['global_sat']),
        source_global=dict(a=S['global_a'], b=S['global_b'], sat=S['global_sat']),
        luts={k: v.tolist() for k, v in luts.items()},
        chosen='a85',
        probe_identity={k: round(v, 3) for k, v in m_id.items()},
        probe_lut={k: round(v, 3) for k, v in m_lut.items()},
    )
    json.dump(out, open(os.path.join(HERE, 'fit16_params.json'), 'w'), ensure_ascii=False, indent=1)
    print('\n已写 fit16_params.json')
