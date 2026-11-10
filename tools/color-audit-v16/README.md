# color-audit-v16 · 16 张作品影调拟合

从用户提供的 **16 张风格一致的目标成片** 反演一个可泛化的滤镜，落地为 App 内置
预设 `WorkStyle16Preset`（「16 张作品 · 通透胶片」）。本目录沉淀全部拟合脚本、中间
数据与口径说明，便于复现与复核。

> 目标成片本身（`/workspace/待分析照片/`，16 张）含用户作品，出于版权不随仓库分发；
> 中性源图（Unsplash / picsum 公共素材）同理不分发。复现时自备即可，见下方「复现」。

## 为什么这一版和 1.6.32（被用户判定「效果很差」并回退）完全不同

1.6.32 依据尼康官方 brief 的文字「加强中暗部的青色」，往滤镜里**加青 + 加色彩层**。
本轮用 16 张真实成片做定量分析后发现那个方向是错的：

| 观测（`fingerprint16.py` / `semantic.py`） | 结论 |
|---|---|
| 全局 `mean_a` 跨 4 批次从 −2.0（夏日青蓝）波动到 +7.4（暖橙） | 全局色偏几乎全是**场景色**，不能学进滤镜 |
| 肤色 `hue` 26.6~29.9°、暖橙高光 `hue` 31.4~32.0°（±0.3°）跨批次稳定 | 滤镜**不扭曲色相**，色彩层应保持恒等 |
| 绿 109.8~127.3°、青蓝 197.2~215.4°（波动 17~18°） | 语义色块差异来自场景，非滤镜级色相偏移 |
| 影调 `L p01≈5 / p50≈58 / p95≈94 / p99≈97`、死黑 0、死白≈0.4%、`L_std≈24` 跨批次稳定 | **影调是唯一跨场景稳定的可泛化滤镜特征** |

因此本预设 **只做影调、色彩层严格恒等**（`saturation/hue/colorBlender/colorGrading`
全零），杜绝把场景暖色或青色学进滤镜导致套用到其它照片泛青/发脏。

## 拟合方法

1. `fingerprint16.py`：16 张成片在 sRGB→Lab / HSV 双空间做指纹，按 4 批次
   （A 夏日青蓝 01-05 / B 秋冬暖橙 06-10 / C 逆光近景 11-15 / D 暖橙特写 16）
   分组，做「跨批次一致性检验」，区分滤镜特征与场景色。
2. `fit16.py`：取 16 张成片与 13 张多样化中性源图各自的**聚合亮度 CDF**，做
   CDF 反演得到单调 tone LUT；向恒等收缩（alpha）+ savgol 平滑，正则化避免把场景
   亮度构成硬套到任意照片。输出多档 alpha 的 LUT。
3. `close_loop.py`：把候选 LUT 套回中性源图，比较渲染后分位与目标分位，选强度、
   并扫描饱和度补偿是否需要（结论：**不需要**，套用后 `sat=0.364` 对目标 `0.357`）。
4. `semantic.py`：语义色块（肤色/绿植/青蓝/暖橙高光）目标 vs 源对比，确认无稳定
   色相偏移；并生成三档强度对照。
5. `compare16.py`：真实中性照片的「原图 / 套用效果 / 目标作品参照」三联对照 +
   tone 曲线可视化，导出最终 LUT（`curve32767`, 0..32767 的 256 点）。
6. `_render_pipeline.py`：1:1 复刻 `ImageRenderer` 的色彩管线（tone LUT /
   saturation / hueMatrix / colorBlender / colorGrading），保证 Python 拟合结果可
   无失真写回 Kotlin。

**选定参数**：`alpha=0.85`（分位贴近目标但高光不过曝，a100 在天空/日落略发白）。
LUT 单调、抬黑（输入 0→约 11/255）、压白（输入 255→约 254/255），灰阶探针证明
**完全不引入 a/b 色偏**。`fit16_final_lut.json` 为最终 256 点，逐点写入
`WorkStyle16Preset.CURVE`（往返误差 0，见 `WorkStyle16PresetTest`）。

## 诚实边界

- 目标只有**成片（after）**，没有 before/after 配对，也没有尼康机内真实 gamma/LUT。
  因此这是「统计风格拟合 / 算子辨识」，**不是像素级 ΔE00 复刻**。
- 作品里的逆光光感、发丝轮廓光、暖橙主体、胶片颗粒来自**拍摄与场景**，滤镜无法
  凭空生成；本预设复现的是跨 16 张稳定的影调与「色彩不被扭曲」这一特性。
- 若提供同场景 before/after 配对原图（哪怕 2~3 张），可升级为有监督像素级拟合。

## 复现

```bash
# 自备：/workspace/待分析照片/ 放 16 张目标成片；tools/color-audit-v16/neutral/ 放中性源图
cd tools/color-audit-v16
python3 fingerprint16.py          # -> fingerprints16.json
python3 fit16.py                   # -> fit16_params.json（多档 LUT）
python3 close_loop.py              # -> fit16_chosen.json（选强度/饱和补偿）
python3 semantic.py                # -> semantic_bands.json, compare16_alpha.png
python3 compare16.py               # -> 三联对照图, fit16_final_lut.json
```

依赖：`numpy / scipy / Pillow`。最终交付以 App 内置预设 `WorkStyle16Preset` 为准，
本目录脚本仅作可复现与复核用途。
