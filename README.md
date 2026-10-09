# Web Screen —— 我的世界 1.21.1 NeoForge「网页方块」

在 Minecraft 里放一块**真正能上网的屏幕**：每个单元宽 1 格、高 2 格（**严格 1:2**），
空手右键输入网址，网页（含视频）就会直接在方块表面播放。
**横竖多摆几块会自动拼成一整块大屏**，画面连续铺满。

底层用 [MCEF](https://modrinth.com/mod/mcef)（Minecraft Chromium Embedded Framework）
把 Chromium 浏览器内嵌进游戏，所以**不是**贴一张图片，是真的在跑网页。

---

## 1. 依赖

| 名称 | 版本 | 说明 |
| --- | --- | --- |
| Minecraft | 1.21.1 | |
| NeoForge | 21.1.x（推荐 21.1.257） | |
| **MCEF** | `2.1.6-1.21.1`（NeoForge 版） | **必装**，客户端必需 |

> MCEF 是客户端模组。服务端不需要装，但玩家客户端必须装。
> 下载：<https://modrinth.com/mod/mcef> 或 <https://www.curseforge.com/minecraft/mc-mods/mcef>
> 文件名形如 `mcef-neoforge-2.1.6-1.21.1.jar`

---

## 2. 安装

1. 用官方 NeoForge 安装器装好 **Minecraft 1.21.1 + NeoForge**。
2. 把下面两个 jar 丢进 `.minecraft/mods/`（本仓库的 `put-in-mods/` 里已经准备好了）：
   - `mcef-neoforge-2.1.6-1.21.1.jar`
   - `webscreen-1.0.0.jar`
3. 启动游戏。

> **首次启动会停留较久**：MCEF 需要联网从 `mcef-download.cinemamod.com`
> 下载一份 Chromium 运行时（约 100+ MB），请耐心等待，不要中途关游戏。

---

## 3. 使用

### 放置与操作

| 操作 | 效果 |
| --- | --- |
| 创造模式「网页方块」标签页 / 合成 | 拿到网页方块 |
| 对着地面右键放置 | 自动往上占两格，正面朝着你 |
| **Shift + 右键方块** | 弹出输入网址界面（空屏幕直接右键也行） |
| 输入网址 → 点「播放」 | 网页开始在方块上渲染 |
| 点「停止」 | 清空网址，屏幕变回待机黑屏 |
| **按 `F8`（准星指着屏幕）** | 进入「网页输入模式」，直接在里面打字/登录 |
| **Shift + 左键** | 挖掉方块 |
| 破坏上/下任意一格 | 该单元整块一起掉，只掉 1 个 |

### 贴着墙放

面板是**贴着方块背面**的（不是悬在方块中间），所以把方块放在墙边时，
屏幕看起来就严丝合缝地贴在墙上，而不是"浮在离墙半格的空气里"。

光标指向的方块上表面如果被占住（贴墙、贴天花板时很常见），
放置会自动**往下挪一格**再试一次，而不是直接失败。所以：

```
站远一点，准星指向墙面偏下的位置 → 右键 → 方块自动贴到墙根并往上占两格
```

方块模型、碰撞箱、渲染面板三者用的是同一套坐标（`tools/quad_check.py` 有静态校验），
方向朝哪边，面板就贴在反方向那一面。

### 从背面看不再是透明的

面板正面是单面绘制的，所以早期版本从背面看过去能"穿透"看到正面的画面。
现在每个单元的背面会额外补一块**和边框同色的实心背板**，
从任何角度看过去都是一块实心外壳，不会出现透视穿帮。

### 拼成大屏

把多个单元**并排**（左右）或**上下叠放**摆在一起，它们会自动合并成一块大屏。
注意每个单元本身就占 2 格高，所以往上叠的时候，上面那块的下半格要放在当前单元之上第 2 格：

```
        ┌────┬────┐
        │    │    │      ← 每个格子都是一个"单元"（1 格宽 x 2 格高）
        │    │    │
        ├────┼────┤
        │    │    │
        │    │    │
        └────┴────┘
         2x2 单元 = 2 格宽 × 4 格高
```

* 整块大屏**只跑一个浏览器**，画面被切分后连续贴在各个单元上，**内部接缝完全对齐**、
  不会拉伸；带边框的"显示器外壳"只留在**整块屏幕的最外圈**，所以拼起来看不到一道道黑边。
* **任意一格右键**输入的都是同一份网址。
* 新拼上去的单元会自动**继承**正在播放的网址，不会变黑屏。
* 上限：横向最多 **8 格**、纵向最多 **4 个单元**（= 8 格高）。超出的部分会被切成独立的屏幕。
* 形状不是矩形时（比如摆成 L 形），会**自动按面积最大化切成几块矩形屏**，尽量少浪费。
  例如 3 格的 L 形会切成「1x2 + 1x1」两块屏。

### 和网页交互

核心原则：**不按 Shift 时左右键完全交给网页，按住 Shift 才是"操作方块"。**

| 操作 | 效果 |
| --- | --- |
| 准星指着屏幕 | 鼠标位置实时同步给网页（hover 效果、视频进度条） |
| **左键 / 右键**（不按 Shift） | 网页上的左键 / 右键点击（点视频的「播放」就是左键） |
| **Shift + 左键** | 原版挖掘，把方块挖掉 |
| **Shift + 右键** | 打开「输入网址」界面 |
| 滚轮 | 网页上下滚动 |
| **按 `F8`** | 进入「网页输入模式」（见下） |

> 指向屏幕**不会**抢走系统鼠标指针——光标始终由游戏自己管。
> 想挖掉屏幕、或者改它播的网址，按住 Shift 再点。

屏幕**还没加载网页**（或者 Chromium 还在初始化）时，它不算"屏幕"，
这时左右键就是普通方块操作 —— 不按 Shift 的右键也会直接弹出输入网址界面，
免得一块空屏幕反而既点不动也挖不掉。

> 实现上要注意一点：**Shift + 右键必须由本模组自己拦下来开界面**，不能指望原版的方块交互。
> 因为玩家潜行、手上又拿着东西时，原版会跳过方块交互、改成"放置手里的方块"，
> 那样永远弹不出输入网址界面。

### 标签页方块（在多个网址之间切换）

屏幕顶上可以贴**标签页方块**（半格高），一个标签 = 一个网址。一块屏幕同一时刻
只显示其中被"激活"的那一个，于是就能像浏览器一样来回切：

```
   [ 标签A ][ 标签B ][ 标签C ]      <- 标签页方块，贴在屏幕顶上
   ┌─────────────────────────┐
   │                         │
   │         屏幕画面          │    <- 显示当前激活标签的网址
   │                         │
   └─────────────────────────┘
```

| 操作 | 效果 |
| --- | --- |
| 右键标签方块 | 把它的网址切到屏幕上（激活） |
| **Shift + 右键**标签方块 | 改这个标签的网址 |
| 挖掉标签方块 | 关掉这个标签 |

激活的标签是**亮蓝色**，其它是**深灰色**，各自压着一行域名。放置位置约定：
**紧贴屏幕最上沿的上方那一格**（中间隔着单元的上半格），朝向自动跟下方的屏幕对齐。

**它同时是"新窗口链接"的落脚点。** 像哔哩哔哩点用户头像 / 个人主页这类
`target="_blank"` 的链接，本来会请求弹一个新窗口 —— 而 OSR 模式下根本没地方弹，
表现就是**点了毫无反应**。有了标签页方块之后：

* 屏幕顶上**有空闲的**标签方块 → 自动占用它，开成新标签；
* 没有（没放标签方块，或者都占满了）→ **当前屏幕直接跳过去**（"关掉现在的窗口开新的"）。

> **底层原因**：MCEF 跑的是离屏（OSR）模式，没有真正的窗口可弹，网页要求开新窗口时
> CEF 无处可开，表现就是点了毫无反应。
>
> **修法（踩过坑，别再走弯路）**：一开始按常规做法注册了 `CefLifeSpanHandler`，
> 但实测日志显示 **OSR 模式下 CEF 根本不会回调 `onBeforePopup`** —— handler 注册成功了，
> 却一次都没触发。所以真正干活的是**页面内 JS 注入**：
> 注入的脚本拦下所有 `target="_blank"` 的点击、表单提交和 `window.open`，
> 通过 jcef 的 `cefQuery`（`CefMessageRouter`）把网址回传给 Java，再由 Java 决定
> 开新标签还是同窗口跳转。lifespan / request handler 仍然保留着作为兜底。
>
> 两个实现要点：`addMessageRouter` 必须在**任何浏览器创建之前**调用；
> `CefMessageRouter` 必须用静态字段**持有强引用**，否则被 GC 后页面里的函数就失效了。

### 网页输入模式（`F8`）

准星指着屏幕按 `F8`，会进入一个**不暂停游戏、锁定鼠标**的输入界面，
键盘上的所有按键（字母、数字、退格、回车、方向键、Tab、剪切粘贴快捷键……）
都会原样转发给网页里的 Chromium：

* 可以直接在屏幕里**搜索、登录、输入账号密码**；
* `Esc` 或再按一次 `F8` 退出；
* 因为游戏没暂停，退出时视角、准星、其它模组的状态都不会被打乱。

> 底层有个坑：MCEF 的 `sendKeyPress` 不做键码转换，直接把 GLFW 键码塞进
> `CefKeyEvent.windows_key_code`。所以本模组自己维护了一张 GLFW → Windows VK 的映射表，
> 否则退格、回车、方向键这些全都会失效。

网址会自动补全 `https://`；`javascript:` / `data:` / `file:` 之类的协议会被拒绝。

---

## 4. 登录态：把浏览器的登录信息导进来

内嵌的 Chromium 用的是**独立 profile**（缓存在 `.minecraft/mods/mcef-cache`），
和你本机 Chrome / Edge 的登录状态不互通。所以像 B 站这种"看一半弹登录"的情况，
需要在里面登录一次 —— 有两条路：

### 路线 A：在游戏里直接登录（推荐，最省事）

准星指着屏幕按 `F8` → 直接在里面打开登录页、输账号密码。
登录一次之后 Cookie 会持久化到 `mcef-cache`，以后就不用再登了。
网址输入界面里也内置了一个「B 站登录页」按钮，一点就把地址填好。

### 路线 B：从本机浏览器一键导入 Cookie

用仓库里的 `tools/import_cookies.py`，把你本机 Chrome / Edge 等浏览器的
Cookie 解出来、写成模组能读的 JSON，游戏里会自动注入：

```bash
# 先看有哪些浏览器 profile
python tools/import_cookies.py --list

# 导出默认几个视频站的 Cookie 到 .minecraft/config/webscreen-cookies.json
python tools/import_cookies.py --browser edge

# 指定域名 / 全部导出
python tools/import_cookies.py --browser chrome bilibili.com
python tools/import_cookies.py --browser chrome --all
```

脚本会自己找到 `.minecraft` 目录（找不到就用 `--out` 指定）。
在游戏里它每 2 秒检查一次这个文件，**改动后自动重新注入**，不用重启游戏。

支持的输入格式（任意一种都行）：

* 本模组导出的 `{"cookies": [...]}`；
* 顶层就是数组的 JSON；
* 浏览器扩展（如 Cookie-Editor / EditThisCookie）导出的 JSON，字段叫 `expirationDate`（秒）或 `expires`（毫秒）都可以。

> **注意（Windows）**：
> * `v10` / `v11` 前缀的 Cookie 靠 DPAPI + AES-256-GCM 解密，脚本能处理；
>   但 Chrome 127+ 起的 `v20` 前缀是 **App-Bound 加密**，离线**解不开**，脚本会自动跳过并提示。
> * 浏览器**正在运行**时，它的 `Cookies` 数据库被独占锁定，读不到。
>   请完全退出浏览器（关窗口后进程常驻后台，去任务管理器结束 `chrome.exe` / `msedge.exe`）。
> * 实在解不开也没关系 —— 走路线 A 在游戏里登录一次即可。

---

## 5. 合成配方

```
玻璃板    玻璃板      玻璃板
玻璃板    阳光传感器   玻璃板    ->  网页方块 x1
玻璃板    玻璃板      玻璃板

玻璃板    玻璃板      玻璃板
纸       网页方块     纸       ->  标签页方块 x8
```

---

## 6. 服务器 / 多人游戏

**可以用。** 已在专用服务端实测：`runServer` 正常加载模组，`Done (5.252s)! For help, type "help"`，无报错。

分工如下：

| | 需要装 `webscreen` | 需要装 `MCEF` |
| --- | --- | --- |
| 专用服务器 | **需要** | **不需要**（依赖标了 `side = "CLIENT"`） |
| 客户端 | 需要 | 需要 |

服务端只负责**存和同步网址**，不跑任何浏览器；真正的 Chromium 在每个玩家的客户端上。
所以**服务端开销可以忽略**，负担都在各自客户端（每块屏幕一个 Chromium 实例，
单个客户端同时存在的上限是 8 个，超出按最久未使用淘汰）。

多人时的行为：

* **网址是共享的**：一块屏幕 / 一个标签方块里存的是什么，所有人看到的就一样；
* **"当前激活哪个标签"是各人自己的**：你切标签不会影响别人；
* **登录态（Cookie）是各人自己的**，互不影响；
* 页面里的**新窗口链接**同样各人各自处理：有空的标签方块就开新标签，没有就当前屏幕跳转。

> 已知限制：两个人**同时**点新窗口链接时，可能抢到同一个空闲标签（后写入的覆盖先写入的）——
> 服务端目前只做"写入"，没有做"这个标签是否已被占用"的仲裁。自己人一起玩基本碰不到。

服务器侧的注意事项：

* 服务器装了 `webscreen` 之后，**客户端也必须装**（模组列表要一致，否则连不上）；
* 服务器**不要**装 MCEF —— 那个依赖声明在客户端侧，服务端装了也没用；
* 单人存档（"对局域网开放"）同理：开房的人既是服务端也是客户端，两份都要有（整合包里本来就有）。

---

## 7. 从源码构建

需要 **JDK 21**（NeoForge 1.21.1 的要求）。

```bash
cd webscreen
gradlew.bat build        # Windows
./gradlew build          # macOS / Linux
```

产物：`build/libs/webscreen-1.0.0.jar`

开发环境直接开客户端调试（会自动把 MCEF 放进运行时类路径）：

```bash
gradlew.bat runClient
```

### 如果 `runClient` 启动时随机崩掉

现象：游戏在 `Initializing game` 阶段直接崩，崩溃报告里是

```
java.lang.RuntimeException: java.util.concurrent.ExecutionException:
        java.nio.file.FileSystemNotFoundException
    at net.neoforged.fml.earlydisplay.DisplayWindow.setupMinecraftWindow(...)
    at cpw.mods.niofs.union.UnionFileSystemProvider.getFileSystem(...)
```

同一个工程、同样的参数，**有时能起来有时崩**——这是 NeoForge / securejarhandler
的一个上游竞态 bug（NeoForge 维护者 ChampionAsh5357 也复现过同一份崩溃报告），
跟本模组无关：崩的时候模组列表里 MCEF / Web Screen 都还没开始跑。

根因是早期加载窗口的渲染线程去读自己模块里的类时，union 文件系统里查不到对应的 jar：

```java
// cpw.mods.niofs.union.UnionFileSystemProvider
public FileSystem getFileSystem(URI uri) {
    String[] split = uri.getPath().split("!");
    if (!fileSystems.containsKey(split[0])) throw new FileSystemNotFoundException();
    return fileSystems.get(split[0]);
}
```

**本仓库已经默认规避**：`gradle.properties` 里的

```properties
neoforge.earlyWindowControl=false
```

会在跑游戏前同步到 `run/config/fml.toml`（NeoForge 只从这个文件读这个开关，
`FMLConfig` 没有系统属性入口，所以用了一个 `syncFmlConfig` Gradle 任务来同步）。
关掉之后 `ImmediateWindowHandler` 会改用 `DummyProvider`，完全不碰 `DisplayWindow`，
日志里 `EARLYDISPLAY` 一行都不会出现。

代价只是**看不到那个花哨的加载动画**。等上游修好后，把它改回 `true` 即可。

> 如果你就是想要加载动画：把那行改成 `true`，然后在遇到崩溃时手动重启一次——
> 因为它是竞态，重试通常就能起来。

---

## 8. 实现要点

```
src/main/java/com/webscreen/
├─ WebScreenMod.java              # 公共入口：注册方块/物品/方块实体/网络包
├─ ModBlocks.java                 # 方块 + 物品注册
├─ ModBlockEntities.java
├─ ModCreativeTabs.java
├─ util/UrlUtil.java              # 网址规范化 + 协议白名单
├─ network/
│  ├─ SetUrlPayload.java          # 客户端 -> 服务端：写入网址（整块屏幕的所有单元）
│  └─ SyncUrlPayload.java         # 服务端 -> 客户端：广播当前网址
├─ block/
│  ├─ WebScreenBlock.java         # HALF(上/下) + FACING(四向)，双格放置/破坏联动，贴墙放置
│  ├─ WebScreenBlockEntity.java   # 存 URL，随区块同步给客户端
│  └─ ScreenLayout.java           # 多格拼接的核心算法（无状态）
└─ client/
   ├─ WebScreenClientMod.java     # 客户端入口（Dist.CLIENT），注册方块实体渲染器 + F8 键位
   ├─ KeyMappings.java            # F8 = 网页输入模式
   ├─ ClientHooks.java            # 打开输入网址界面 / 轮询 F8
   ├─ CookieVault.java            # 读 config/webscreen-cookies.json 并注入 CEF 全局 Cookie
   ├─ gui/UrlInputScreen.java     # 网址输入 GUI（含"网页输入模式"和"B 站登录页"按钮）
   ├─ gui/RemoteControlScreen.java# 网页输入模式：不暂停 + 锁鼠标 + 全键盘转发
   ├─ ScreenBrowserManager.java   # 每块屏幕一个 Chromium 实例，含 LRU 回收
   ├─ WebScreenRenderer.java      # 把 Chromium 纹理按 UV 切分贴到各单元 + 背面实心背板
   └─ ClientInteractions.java     # 鼠标 hover / 点击 / 滚轮转发，准星拾取
tools/
├─ gen_textures.py                # 程序化生成本模组所有贴图
├─ import_cookies.py              # 从本机 Chrome/Edge 导出 Cookie 到 webscreen-cookies.json
├─ layout_sim.py                  # 布局算法的仿真测试
└─ quad_check.py                  # 拼接几何 + UV 接缝仿真（64 项）+ 静态一致性校验
```

### 面板贴背

面板（`web_screen.json` 里的元素 `[0,0,11.5] → [16,16,15.5]`）沿朝向轴只占最后 4/16，
背面再留 0.5/16 的缝，所以它**贴在方块背面那一侧**，而不是悬在中间。
`blockstates` 用 `y: 0/90/180/270` 把它转到四个朝向；渲染端和碰撞箱各自独立地
复算了同一套 `backPlane / frontPlane`，三边必须对齐：

```
facing=NORTH(y=0)    z ∈ [11.5, 15.5]
facing=SOUTH(y=180)  z ∈ [ 0.5,  4.5]
facing=EAST (y=90)   x ∈ [ 0.5,  4.5]
facing=WEST (y=270)  x ∈ [11.5, 15.5]
```

`tools/quad_check.py` 会直接解析模型 JSON、blockstates 的旋转角、`WebScreenBlock` 的
`Block.box(...)` 表达式以及 `WebScreenRenderer` 里的 `PANEL_THICKNESS / PANEL_BACK_GAP` 常量，
逐个朝向比对 —— 以后改了模型忘了改碰撞/渲染，跑一次就能抓到。

### 背面为什么不再透明

网页 quad 是单面绘制的（`disableCull`），从背面看会看到镜像画面。
所以每个单元在面板**背面的内侧**再画一块铺满整格、顶点逆序的实心背板
（颜色取边框同色 `rgb(26,29,35)`），顶点逆序保证背面观察者看到的是朝自己的正面。
渲染前显式摆正深度状态（`enableDepthTest` / `depthMask(true)` / `depthFunc(GL_LEQUAL)`），
结束时统一恢复 `enableCull` / `disableBlend`，避免异常路径下状态泄漏。


### 黑框只留在整块屏幕的最外圈

方块模型本身就带一圈「显示器边框」（`web_screen_bezel` 贴图）。如果每格各画各的，
拼起来就会在**每条内部接缝**上都露出一道黑边。所以渲染时：

* 浏览器画面覆盖本格的整个正面 —— 内部单元的边框被画面**完全盖住**；
* 只有落在**整块屏幕最外圈**的那几条边（`col == 0` / `col == width-1` /
  `row == 0` / `row == height-1`）才按 `MARGIN_X / MARGIN_Y` 向内缩，把外框让出来。

判断"是不是外边缘"只看本格在布局里的 `col / row`，跟邻居无关，
所以接缝两侧两个单元算出来的顶点坐标和 UV **完全相等**，画面严丝合缝。
`tools/quad_check.py` 对 4 个朝向 × 16 种形状逐条校验了这一点：
公共边世界坐标一致、UV 一致、UV 铺满 `[0,1]`、外框不外溢到内部单元。

### 为什么单格是严格 1:2

单元内左右各留 `m` 的边、上下各留 `2m` 的边，于是可见画面恒为
`(1-2m) : (2-4m) = 1 : 2`，默认 `m = 1/16`。
因为每个单元都是 1 格宽 × 2 格高，所以拼出来的大屏整体比例仍然是 `宽 : 2*高`；
浏览器视口也按同样比例创建（`blockPixels` 按屏幕格数缩放，整体不超过 2048 px，
单格 48~512 px），**不会有拉伸变形**。

> 注意：留白是**按整块屏幕**算的，不是按单格。所以 8x4 的大屏视口是
> `(8-2m) : (16-4m)`，同样是 `1:2` 量级且与可见区域严格等比。
> 鼠标坐标到网页像素的换算（`ClientInteractions`）用的是同一套公式，
> 保证点哪儿就是哪儿。

### 屏幕布局算法（ScreenLayout）

这是多格拼接的关键，设计上刻意做成**无状态**的：

1. 从任意一格出发，BFS 找出所有相邻的、朝向相同的完整单元（连通块）。
2. 把连通块映射成栅格（注意一个单元竖直方向占 **2 格**，行号要除以 2）。
3. 按「从上到下、从左到右」的顺序**贪心切矩形**：每次取以当前格为左上角、面积最大的矩形。
4. 每一格都独立走完这套流程，得到自己所属矩形的左上角坐标 + 自己的 (col, row)。

因为算法是确定性的，**同一连通块里每一格跑出来的结果必然一致**，
所以完全不需要同步任何布局数据——在线放置/破坏方块时布局立刻自然更新，
拆分和合并都不需要特殊处理。

`tools/layout_sim.py` 把这段算法照搬成 Python 做了仿真，
覆盖 4 个朝向 × 16 种形状（含 L 形、T 形、十字、超尺寸、不相连等），
校验往返坐标、无重叠、全覆盖，共 64 项全部通过。

### 其他取舍

- **服务端只存 URL**，浏览器实例只在客户端创建，服务端零开销。
- 同时存在的浏览器有上限（8 个），超出按最久未使用淘汰，防止爆显存；
  方块被破坏、区块卸载、或该格不再是屏幕左上角时立即回收。
- 方块实体只在下半格存在，上半格只是占位 + 让形状拼成 2 格高。
- 禁用了 MCEF 的网页光标接管（`setCursorChangeListener` 置空），
  否则网页里鼠标划过链接时会把游戏鼠标"解锁"出来、视角也转不动。
