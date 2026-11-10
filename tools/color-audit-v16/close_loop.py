"""闭环量测：把候选 LUT 套回 13 张中性源图，比较渲染后聚合统计与 16 张目标，
据此选定 alpha 与饱和度补偿，避免过冲。"""
import numpy as np, json, os, glob, sys
from PIL import Image
from fit16 import load_pixels, stats, HERE, V4
sys.path.insert(0, V4)
from _render_pipeline import render, base_cfg

P = json.load(open(os.path.join(HERE, 'fit16_params.json')))
TGT = P['target_quant']; TZ = P['target_zones']; TG = P['target_global']
SRC_IMGS = [load_pixels(p) for p in sorted(glob.glob(os.path.join(HERE, 'neutral', '*')))]


def render_all(lut, sat=0):
    cfg = base_cfg(); cfg['saturation'] = sat
    outs = []
    for a in SRC_IMGS:
        px = np.clip(np.round(a * 255), 0, 255).astype(np.int64)
        outs.append(render(px, cfg, np.asarray(lut, dtype=int)) / 255.0)
    return outs


def summarize(imgs):
    s = stats(imgs)
    return dict(quant=s['quant'], zones=s['zones'],
                ga=s['global_a'], gb=s['global_b'], gs=s['global_sat'])


print('=== 各 alpha：套用后亮度分位 vs 目标 ===')
print(f"{'alpha':>6} {'p25':>6} {'p50':>6} {'p75':>6} {'p95':>6} | {'sat':>6} "
      f"{'mid_b':>6} {'skin-friendly':>14}")
for name, lut in P['luts'].items():
    s = summarize(render_all(lut))
    q = s['quant']
    print(f"{name:>6} {q['p25']:6.1f} {q['p50']:6.1f} {q['p75']:6.1f} {q['p95']:6.1f} | "
          f"{s['gs']:6.3f} {s['zones']['mid']['b']:6.2f}")
q = TGT
print(f"{'目标':>6} {q['p25']:6.1f} {q['p50']:6.1f} {q['p75']:6.1f} {q['p95']:6.1f} | "
      f"{TG['sat']:6.3f} {TZ['mid']['b']:6.2f}")

# 选定 alpha：让 p50 最接近目标且不过冲
best = None
for name, lut in P['luts'].items():
    s = summarize(render_all(lut))
    err = sum((s['quant'][k] - TGT[k]) ** 2 for k in ['p05', 'p25', 'p50', 'p75', 'p90', 'p95'])
    if best is None or err < best[0]: best = (err, name, s)
print(f"\n最接近目标的 alpha = {best[1]} (分位误差平方和={best[0]:.1f})")

# 饱和度补偿：LUT 提亮会压 HSV sat，若明显低于目标，补一点 saturation
chosen_lut = P['luts'][best[1]]
print('\n=== 饱和度补偿扫描（基于', best[1], '）===')
for sat in [0, 3, 5, 8, 10, 12]:
    s = summarize(render_all(chosen_lut, sat))
    print(f"  sat={sat:2d}: global_sat={s['gs']:.3f} (目标 {TG['sat']:.3f})  "
          f"dark_sat={s['zones']['dark']['sat']:.3f} mid_sat={s['zones']['mid']['sat']:.3f} "
          f"high_sat={s['zones']['high']['sat']:.3f}")

json.dump(dict(chosen_alpha=best[1], chosen_lut=chosen_lut),
          open(os.path.join(HERE, 'fit16_chosen.json'), 'w'))
print('\n已写 fit16_chosen.json')
