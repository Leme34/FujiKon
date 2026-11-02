# FujiKon

FujiKon 是一个 Android 端的尼康 Picture Control 预设工具：导入 `.NCP` / `.NP3` 文件，批量套用到手机相册照片，套用后可继续通过 Lightroom 风格调节面板和 RGB 色调曲线微调，长按对比原图、双指缩放查看细节，并尽量以原图尺寸导出到相册、保留相机 EXIF 信息。

所有解析与渲染都在本机完成。应用只在“从 nikonpc 下载”功能中访问 nikonpc.com 获取公开的 Picture Control 预设，不会上传照片或本地预设。

## 功能

- 导入尼康 `.NCP` / `.NP3` 预设（SAF 文件选择器，支持多选；也可以从文件管理器直接用 FujiKon 打开）
- 从 nikonpc.com 在线浏览全部社区 Picture Control，支持单个导入和一键全部导入，自动去重并缓存下载内容
- 预设管理：收藏、重命名、备注、删除、恢复内置示例
- 解析文件内的 256 点色调曲线、锐化/清晰度/中间锐度、对比度、亮度、饱和度、色相、高光/阴影/白色/黑色、单色滤镜与色调
- 解析 NP3 的 Color Blender（8 色相带）与 Color Grading（高光/中间调/阴影、混合、平衡）
- 从系统相册按相册筛选、批量勾选照片
- 实时预览：长按查看原图，双指缩放/拖动，双击快速放大
- 套用预设后可实时调节色温、色调、自然饱和度、曝光度、对比度、高光、阴影、白色色阶、黑色色阶、纹理、清晰度、去朦胧；按“光线 / 颜色 / 细节”分组，双击滑块归零
- 预设列表提供“无（保留原图）”选项：不套用任何 Picture Control，仍可继续裁剪、HSL 调色、影调、滤镜和手动调节
- 8 色带 HSL 混色器（红、橙、黄、绿、青、蓝、紫、洋红），每带独立色相、饱和度、明度；并在调节面板内提供“影调”曲线编辑
- 非破坏性裁剪：原图、1:1、4:3、3:2、16:9、9:16、3:4、2:3 比例，支持顺时针旋转（0/90/180/270°）与纠偏（±45°），导出时按相同区域裁切；裁剪预览直接在主图实时展示，裁剪面板只用于参数调节。
- 内置富士、理光、徕卡风光滤镜与人像系列滤镜，强度可调，并与预设、HSL、调节参数叠加
- 套用预设后可调整 Lightroom 风格 RGB 主通道色调曲线：点击空白加点、拖动控制点、双击删除；近乎透明的面板让照片保持可见，拖动过程中持续刷新预览
- 将当前调节、HSL、裁剪、滤镜、曲线与预设参数保存为预设列表中的新副本，不修改原始 NCP/NP3 文件
- 批量导出：JPEG（最高画质、保留 EXIF）或 PNG（无损、不写 EXIF）
- 显示RGB直方图，并根据当前RGB直方图分布情况，动态显示修图提示，以及增加一键自动AI修图按钮
- 导出到 `Pictures/<相册名>`，原图尺寸（受设备内存限制时自动降到可处理的最大尺寸）
- 深色界面，适配 Android 13/14/15 的照片权限（含“仅选择部分照片”）


---

## App 功能截图（v1.4.0）

### 完整流程图文版

**1 · 首页**
- 点击“从 nikonpc 下载”进入在线预设列表  
  <img src="docs/screenshots/step1_home.jpg" width="30%">


---

**2 · 在线预设列表**
- nikonpc.com 公开预设列表，可选择单个或全部导入  
  <img src="docs/screenshots/step2_nikonpc.jpg" width="30%">


---

**3 · 预设列表**
- 导入完成后显示在首页预设列表，曲线图展示 
  <div style="display: flex; justify-content: center; gap: 10px;">
    <img src="docs/screenshots/step3_preset_list1.jpg" width="25%">
    <img src="docs/screenshots/step3_preset_list2.jpg" width="25%">
  </div>


---

**4 · 相册选照片，选预设套用**
- 从系统相册选择多张照片（含相册下拉 + 批量勾选），选择完成后底部弹窗展示选定照片的预设缩略图预览  
  <img src="docs/screenshots/step4_gallery.jpg" width="30%">


---

**5 · 套用预设**
- 预设套用后的实时预览界面，可切换预设  
  <div style="display: flex; justify-content: center; gap: 10px;">
    <img src="docs/screenshots/step5_after_apply1.jpg" width="25%">
    <img src="docs/screenshots/step5_after_apply2.jpg" width="25%">
  </div>

---

**6 · 手动调整参数**
- 手动调节色彩参数面板，光线/颜色/细节分组，双击归零，可保存为新预设  
  <img src="docs/screenshots/step6_adjustments.jpg" width="30%">


---

**7 · RGB 曲线编辑**
- 半透明曲线编辑器，拖动控制点实时刷新预览  
  <img src="docs/screenshots/step7_curve.jpg" width="30%">


---

**8 · 导出**
- 批量导出JPG/PNG格式，保留EXIF  
  <img src="docs/screenshots/step8_export.jpg" width="30%">


---

**9 · RGB直方图，修图提示，一键修图**
- 显示RGB直方图，并根据当前RGB直方图分布情况，动态显示修图提示，以及增加一键自动AI修图按钮  
  <img src="docs/screenshots/step9_RGB_Histogram1.jpg" width="30%">
  <img src="docs/screenshots/step9_RGB_Histogram2.jpg" width="30%">

## 构建

环境：

- JDK 17
- Android SDK Platform 35、Build-Tools 35.0.0
- Gradle 8.9（项目含 wrapper）

```bat
gradlew.bat :app:assembleDebug
```

> wrapper 默认使用腾讯云 Gradle 镜像以加快国内下载；如需官方源，可把 `gradle/wrapper/gradle-wrapper.properties` 中的 `distributionUrl` 改回 `https://services.gradle.org/distributions/gradle-8.9-bin.zip`。

如果本机没有配置 `ANDROID_HOME`，在项目根目录创建 `local.properties`：

```properties
sdk.dir=D:/Android/Sdk
```

产物按应用版本号命名，不使用 `debug` 后缀：

```
app/build/outputs/apk/debug/FujiKon-1.6.0.apk
app/build/outputs/apk/release/FujiKon-1.6.0.apk
```

安装：

```bat
adb install -r app/build/outputs/apk/debug/FujiKon-1.6.0.apk
```

版本规范：

- `versionName` 使用语义化版本 `MAJOR.MINOR.PATCH`，`versionCode` 单调递增。
- APK 统一命名为 `FujiKon-<versionName>.apk`，例如 `FujiKon-1.6.0.apk`。
- 每次提交新版本代码时创建对应的 annotated Git tag，例如 `v1.6.0`。

## 使用

1. 首次启动会自动载入 5 个内置示例预设。
2. 首页点“导入预设”，选择一个或多个 `.NCP` / `.NP3` 文件；也可以点“从 nikonpc 下载”浏览在线预设，单个导入或全部导入。
3. 点“选照片套用”，在预设列表中选择预设；选择“无（保留原图）”则不套用任何 Picture Control，直接进入后续编辑。
4. 勾选照片，可切换相册、全选当前相册；点“下一步”。
5. 预览页：
   - 长按图片对比原图；
   - 双指缩放、拖动查看细节；
   - 双击快速放大/还原；
   - 底部缩略图切换照片；
   - “预设”即时切换并重新渲染，也可以随时切回“无（保留原图）”；
   - “调节”打开近乎透明的调节面板，可实时调整光线、颜色、细节、HSL 和滤镜；HSL 支持 8 个色带，双击某个滑块把它归零，“全部归零”把所有调节参数、HSL、裁剪和滤镜清零；
   - “影调”分组内可编辑影调曲线；单独的“曲线”按钮打开半透明 RGB 主通道曲线编辑器，点击空白添加控制点、拖动调整、双击删除；拖动时照片会以节流方式实时刷新，曲线叠加在当前预设之上，并用于当前预览和批量导出；
   - "裁剪"打开裁剪面板，选择比例、旋转与纠偏后实时预览；预览图直接反映裁剪结果，面板只负责参数调节。导出使用同一裁切区域。
   - 在“调节”或“曲线”面板中点“保存为新预设”，当前调节、HSL、裁剪、滤镜、曲线与预设参数会作为新副本加入预设列表。
6. 点“导出”，选择 JPEG/PNG、是否保留 EXIF、输出相册名，开始批量导出。

## 在线预设（nikonpc.com）

首页的“从 nikonpc 下载”会读取 [nikonpc.com](https://nikonpc.com/) 的公开预设列表，目前约 166 个。单个导入会下载并生成本地 `.NCP`，全部导入使用 4 路并发并显示进度，可随时取消。

- 导入前会用文件内容哈希去重；已经存在的相同预设不会重复添加。
- 下载的 NCP 会缓存到应用缓存目录，重复导入时不再请求下载接口。
- 在线功能需要 `INTERNET` 权限；照片、相册和本地预设不会上传。
- 预设名称和版权归 nikonpc.com 及其作者所有。

## NCP / NP3 支持

容器是 big-endian TLV：

```
"NCP\0" [id:u32][size:u32][body...] ... [0x00000000]
```

| 格式            | 识别方式                          | 主要记录                                                                                                                                                         |
| ------------- | ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| NCP 0100      | 文件头 `NCP\0`，版本 `01xx`         | `1` 设置、`2` 578 字节曲线体                                                                                                                                         |
| NP2 0200      | 文件头 `NP2\0` 或版本 `02xx`        | 尽力按 NCP 记录 `1` 解析                                                                                                                                            |
| NP3 0300/0310 | 文件头 `NCP\0`/`NP3\0`，版本 `03xx` | `0x0100` 版本、`0x0200` 名称、`0x0300` 基准、`0x0600` 锐化、`0x0700` 清晰度、`0x1600` 中间锐度、`0x1900`~`0x1E00` 影调/色彩、`0x1F00` Color Blender、`0x2000` Color Grading、可选记录 `2` 曲线 |

曲线体：`body[8]` 为控制点数量，`body[66]` 起为 256 个 big-endian `u16` LUT（0..32767）。完全水平的 LUT 视为“无曲线”。

基准 Picture Control ID：`0x0020` 灵活色彩、`0x0001` 标准、`0x00C3` 鲜艳、`0x03C2` 自然、`0x0486` 人像、`0x04C7` 风景、`0x064D` 单色。

解析器有 JVM 单元测试，使用真实 NCP/NP3 文件覆盖 NCP 曲线、NP3 Color Blender/Grading、NP3 曲线记录与损坏文件拒绝：

```bat
gradlew.bat :app:testDebugUnitTest
```

测试还包含色调曲线插值/归一化/边界测试、调节参数 JSON 与预设持久化、HSL/裁剪/滤镜 JSON 与边界测试、曲线 JSON 持久化与预设副本删除保护、渲染器曲线叠加与调节效果测试、无预设恒等渲染、裁剪尺寸、滤镜混合与 HSL 渲染测试、Compose 曲线编辑器加点/拖动/双击手势测试、调节滑块拖动/双击归零测试、预设面板“无”选项测试，以及 Robolectric 启动/仓库/图像 I/O/渲染冒烟测试、Compose 首页/预设面板 UI 测试和长按对比手势测试；nikonpc 在线导入还覆盖目录解析、详情/form 映射、本地 HTTP 下载与缓存、异常 NCP 修复。Robolectric 首次运行会下载 Android 运行时（默认走阿里云镜像）；可覆盖：

```bat
gradlew.bat :app:testDebugUnitTest -ProbolectricRepo=https://repo1.maven.org/maven2
gradlew.bat :app:testDebugUnitTest -ProbolectricDir=C:/path/to/android-all-instrumented.jar
```

静态检查：

```bat
gradlew.bat :app:lintDebug
```

## 渲染管线

CPU 渲染，按行分块并行：

1. 文件 256 点 LUT（若有）；“无预设”使用恒等 LUT
2. 基准色彩（标准/鲜艳/自然/人像/风景/单色）的对比度与饱和度倾向
3. 对比度、亮度、高光、阴影、白色、黑色
4. 用户 RGB 主通道色调曲线（叠加在预设之上；单调三次插值，端点固定）
5. 饱和度、色相矩阵
6. Color Blender（按色相带加权）
7. Color Grading（高光/中间调/阴影加权，含混合与平衡）
8. 单色滤镜与色调
9. 用户调节：色温/色调、曝光、对比度、高光/阴影/白色/黑色、自然饱和度、去朦胧
10. 用户 8 色带 HSL
11. 内置风光/人像创意滤镜（按强度与当前结果混合）
12. 用户纹理/清晰度 + 细锐化（近似 unsharp mask）
13. 非破坏性裁剪（按比例、缩放和偏移计算输出区域）

这是对尼康色彩科学的**近似实现**，不是尼康官方算法。渲染结果可能与相机直出或尼康软件存在差异。

## 导出与“无损”

严格意义上的无损只在“不修改像素”时成立。FujiKon 需要重新渲染像素，因此：

- **JPEG**：尽量保持原图尺寸 + quality 100 重新编码，并复制机型、镜头、曝光、GPS、拍摄时间等 EXIF。视觉上接近无损，但不是二进制层面的无损。
- **PNG**：真正无损保存渲染后的像素，但 PNG 不写入相机 EXIF（界面会提示）。

内存策略：

- 预览解码到最长边 1800 px。
- 导出按设备堆上限动态限制最大像素数（约 `maxMemory / 10`，上限 3600 万像素），在内存允许时保持原图分辨率。
- 导出时先释放源 Bitmap，再分配输出 Bitmap，降低峰值内存。
- 超大照片可能因内存不足被跳过，导出结果会列出失败项。

## 项目结构

```
app/src/main/java/com/fujikon/app/
├── MainActivity.kt / FujiKonApp.kt / AppContainer.kt
├── ncp/                 NCP/NP3 TLV 解析与模型
├── nikonpc/             在线目录、下载表单、NCP 修复
├── data/                预设仓库、相册仓库、设置、选择状态
├── render/              CPU 渲染、Bitmap 解码、EXIF、MediaStore 保存、批量导出
└── ui/
    ├── AppNav.kt
    ├── theme/
    ├── components/      曲线图/曲线编辑器、调节滑块/面板、预设卡片、缩放手势、选择/导出面板
    ├── screens/         首页、预设详情、相册、预览导出、设置
    └── viewmodel/
```

## 已知限制

- 色彩为近似模拟；不同相机、固件、拍摄参数下观感会不同。
- 调节面板为全局参数，不包含局部蒙版、画笔或径向滤镜。
- 内置创意滤镜为风格化近似，不支持导入第三方 LUT。
- 裁剪为矩形比例裁剪，暂不支持旋转、翻转或透视校正。
- 曲线编辑器目前只提供 RGB 主通道，不包含 Lightroom 的红/绿/蓝分通道曲线。
- 曲线副本是应用内预设（不再对应 NCP/NP3 文件）；删除副本不会删除原始预设或源文件。
- NP2 仅做尽力解析，部分文件可能不支持。
- 导出不保留原图的 MakerNote 之外的私有块、XMP、ICC 配置（EXIF 主要字段会保留）。
- 不处理 RAW（NEF）；只处理系统相册能解码的 JPEG/PNG/WebP/HEIF 等。
- 批量导出为纯 CPU 处理，张数多或像素高时需要等待。

## 许可

本项目仅用于个人学习与摄影工作流。FujiKon 与尼康（Nikon）无关联；NCP/NP3 是尼康相机系统使用的文件格式。
