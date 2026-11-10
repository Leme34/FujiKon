"""works_round2 作品指纹：sRGB->Lab，影调/色调/光感/肤色/质感五组量化指标。

用法: python3 fingerprint.py [输出json]
输入: 当前目录所有 *.jpg
"""
import numpy as np, glob, sys, json
from PIL import Image


def srgb_lab(rgb):
    x = rgb.astype(np.float64) / 255.0
    lin = np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)
    M = np.array([[0.4124564, 0.3575761, 0.1804375],
                  [0.2126729, 0.7151522, 0.0721750],
                  [0.0193339, 0.1191920, 0.9503041]])
    xyz = lin @ M.T
    xyz /= np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    return 116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])


def hsv_hue(rgb):
    x = rgb.astype(np.float64) / 255.0
    mx, mn = x.max(-1), x.min(-1)
    d = mx - mn
    h = np.zeros_like(mx)
    m = d > 1e-6
    r, g, b = x[..., 0], x[..., 1], x[..., 2]
    idx = (mx == r) & m; h[idx] = ((g - b)[idx] / d[idx]) % 6
    idx = (mx == g) & m; h[idx] = ((b - r)[idx] / d[idx]) + 2
    idx = (mx == b) & m; h[idx] = ((r - g)[idx] / d[idx]) + 4
    return h * 60, d / (mx + 1e-9)


def analyze(path):
    im = Image.open(path).convert("RGB")
    W, H = im.size
    im.thumbnail((360, 480))
    a = np.asarray(im)
    L, A, B = srgb_lab(a)
    h, s = hsv_hue(a)
    lum = a.astype(float) @ np.array([0.2126, 0.7152, 0.0722])
    cm = (s > 0.12) & (lum > 20) & (lum < 245)          # 彩色像素
    sm = (h >= 5) & (h <= 45) & (s > 0.08) & (s < 0.72) & (L > 35)  # 肤色近似
    q = np.percentile(L, [1, 10, 50, 90, 95, 99])
    g = lum
    lap = (-4 * g + np.roll(g, 1, 0) + np.roll(g, -1, 0)
           + np.roll(g, 1, 1) + np.roll(g, -1, 1))[1:-1, 1:-1]
    return dict(
        file=path.split("/")[-1], size=f"{W}x{H}",
        L_p01=round(float(q[0]), 1), L_p10=round(float(q[1]), 1),
        L_p50=round(float(q[2]), 1), L_p90=round(float(q[3]), 1),
        L_p95=round(float(q[4]), 1), L_p99=round(float(q[5]), 1),
        L_std=round(float(L.std()), 1),
        clip_black=round(float((lum < 5).mean()), 4),
        clip_white=round(float((lum > 250).mean()), 4),
        mean_a=round(float(A[cm].mean()), 1) if cm.sum() > 100 else None,
        mean_b=round(float(B[cm].mean()), 1) if cm.sum() > 100 else None,
        sat_mean=round(float(s[cm].mean()), 3) if cm.sum() > 100 else None,
        chroma_p90=round(float(np.percentile(np.hypot(A[cm], B[cm]), 90)), 1) if cm.sum() > 100 else None,
        skin_hue=round(float(np.median(h[sm])), 1) if sm.sum() > 200 else None,
        skin_L=round(float(np.median(L[sm])), 1) if sm.sum() > 200 else None,
        skin_ratio=round(float(sm.mean()), 3),
        texture_lap=round(float(lap.std()), 2),
    )


if __name__ == "__main__":
    rows = [analyze(p) for p in sorted(glob.glob("*.jpg"))]
    out = sys.argv[1] if len(sys.argv) > 1 else "fingerprints.json"
    json.dump(rows, open(out, "w"), ensure_ascii=False, indent=2)
    keys = ["file", "L_p01", "L_p50", "L_p95", "L_p99", "L_std",
            "clip_black", "clip_white", "mean_a", "mean_b", "sat_mean",
            "chroma_p90", "skin_hue", "skin_L", "skin_ratio", "texture_lap"]
    print(" | ".join(f"{k:>11}" for k in keys))
    for r in rows:
        print(" | ".join(f"{str(r[k]):>11}" for k in keys))
    print(f"\n共 {len(rows)} 张")
    for k in keys[1:]:
        v = [r[k] for r in rows if r[k] is not None]
        print(f"  {k:>12}: mean={np.mean(v):.2f}  min={np.min(v):.2f}  max={np.max(v):.2f}")
