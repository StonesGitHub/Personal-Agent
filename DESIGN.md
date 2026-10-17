# 0.4.0：四栏与问答发现

- **墨聊**：保持留白、小墨团和输入框。回答下方只放赞、踩、复制、分享、更多；不常用操作进入更多菜单。
- **发现**：内容单位是一问一答。问题为18sp粗体，回答为15sp正文，卡片内17dp留白；长回答列表最多5行，点开阅读全文。昵称/时间弱化，点赞/评论只用图标与数字。
- **决定**：延续自动整理，用户可回看与更正。
- **我**：我的背景、我的分享、设置。底部导航约66dp，选中项用浅杏色背景，不使用投影或夸张胶囊。
- **分享**：编辑问题与回答 → 阅读实际发布预览 → 发布；支持存草稿。未配置线上社区时只允许存草稿，不伪装成发布成功。
- **阅读详情**：问题标题、完整 Markdown 回答、轻量互动区、评论。评论输入框固定底部，键盘由系统调整布局。
- **风格**：奶油白底、棕色强调、灰棕辅助文字、细描边与圆角；不堆叠横幅、标签或大块操作按钮。
- **数据边界**：私聊与记忆继续本机保存；只有用户主动发布的问答与互动进入社区。

---

# 墨知视觉设计 0.2.0

方向：温暖、可爱、可信赖的个人决策搭子。用柔软的色彩与圆角降低距离感，正文和操作保持清晰。产品仍是独立的「墨知」，与其他应用的数据隔离。

## 墨团

Q 版陪伴玩偶暂名「墨团」：暖墨色豆形身体，一撮弯弯的墨滴，奶油色心形脸，杏色围巾，小小的星芒。表情平静、亲切；没有机器人装甲和复杂装饰。主图为透明背景 PNG，直接用于首页和空状态。

图标由同一张脸提炼为 Android 原生矢量：不对称墨滴、心形脸、桃色腮红与围巾，缩小后仍保留轮廓。提供自适应桌面图标与单色主题图标。

## 色彩与组件

| 用途 | 色值 |
| --- | --- |
| 页面奶油底色 | #FFF9F0 |
| 卡片暖白 | #FFFDF9 |
| 正文暖墨色 | #433833 |
| 主按钮栗棕色 | #956047 |
| 次级文字 | #79685D |
| 首页杏桃色 | #F8E8D7 |
| 选中导航 | #F3DFCC |
| 玩偶围巾 | #E6A077 |

卡片圆角 24–26dp，按钮圆角 16–18dp，触摸反馈采用 Ripple。主要触摸目标至少 48dp；底部导航为 58dp 图标与文字组合。四个导航项始终显示文字，避免只靠图形辨认。段落保留宽松行距，主要信息不依赖颜色表达。

首页：「有点犹豫？一起想想。」配合墨团和三种可点击的提问起点。关于我：先确认再记住，以空状态示例帮助输入。我的决定：强调记录理由和复盘，避免游戏化评分。设置页沿用同一套配色与卡片。

本版没有加入动画、深色主题或独立的大字号布局。现有原生表单功能保持，后续可在真机反馈基础上调整。

## 资产与生成记录

- 工具：内置 imagegen；生成模式：新图，透明背景。
- 生成资产：`app/src/main/res/drawable-nodpi/motuan.png`，原图直接集成，未用代码修改图像。
- 通用 SVG 标志与图标稿：`design/mozhi-logo.svg`、`design/mozhi-app-icon.svg`，可用于设计工具。
- 矢量标志及导航图标：`app/src/main/res/drawable/`，用 Android VectorDrawable 编写。
- 玩偶是 AI 生成的概念形象；矢量 Logo 为可编辑的实现稿。

最终生成提示词：

```text
Use case: stylized-concept. Asset type: transparent mascot asset for a warm Chinese personal decision assistant Android app called 墨知 (do not render any text). Design one original highly recognizable chibi plush character, nickname 墨团: a round soft charcoal-ink colored bean/droplet body with a single tiny curved teardrop tuft leaning to one side, a large warm ivory face patch shaped like a rounded heart, two simple dark dot eyes and a tiny gently smiling mouth, soft peach cheek blush, very short stubby arms and feet, and a small apricot scarf with just one rounded tail. Small warm golden four-point sparkle stitched on its lower belly. Calm thoughtful companion, not childish baby, no robot, no owl, no animal ears, no existing IP likeness. Style: premium designer plush toy, softly felted wool texture, restrained 3D clay/plush rendering, inviting tactile materials, soft warm studio light. Full body front three-quarter pose, one small hand waving, relaxed seated posture. Single character centered with generous empty transparent margin, face readable at small app-avatar sizes, entire silhouette visible. Restrained palette charcoal brown #403936, ivory #FFF8EE, apricot #E6A077, sage accent optional. Truly transparent alpha background, no ground plane or scene, no wordmark, no text, no watermark, no collage, no accessory objects.
```

## 0.2.0 可读性与细节修复

正文采用原生 Markdown 渲染。按钮取消系统默认的凸起阴影与 Z 轴动画，仅保留受圆角约束的 Ripple。消息操作按钮各占半行，中间留12dp；上下文操作与输入行之间留8dp。列表隐藏默认滚动条和边缘光晕，避免截图中右侧及按钮下方的虚影。

## 0.2.2 聊天导航

- 顶栏右侧依次为加号（新建对话）与回转时钟（历史）。不展示“对话记录”等大文字按钮；24dp线性图标、1.8dp线宽、48dp点击区域。
- 沿用奶油白与棕杏色；图标没有常驻底色和阴影，只有按压涟漪。墨团标志40dp，标题24sp，副标题单行，窄屏省略。
- 历史默认收起，由右侧弹出，宽度不超过340dp或窗口的88%，使用浅色圆角面板和轻遮罩。打开和关闭动画180/140ms。
- 点击历史条目直接切换并收起；关闭、返回键和点击外部均可收起。当前对话浅杏色标记。长按管理，删除仍需确认。
- 图标保留无障碍名称及长按提示。新建入口在模型请求中暂时停用；历史可浏览，切换和删除遵守现有请求保护。

## 0.3.0 精简原则

以用户提供的 DeepSeek / 豆包截图为密度参考，保留墨知自己的暖白、棕色与墨团标志。

- 首页只留小标志、问候与输入框，不展示大欢迎卡片或推荐问题列表。
- 聊天无底部标签栏、无大回复卡片、无常驻记忆勾选行。设置、关于我、决定在右侧历史抽屉中。
- 用户消息右对齐轻气泡；助手16sp正文直接排版，行间留白。回复操作用复制与更多图标。
- 输入框内提供选项、位置与发送/停止图标，48dp触摸区域；开启位置后只显示一行状态。
- 添加背景只有右上角加号入口，复杂字段按需展开。
- 设置用普通列表；备份一级页仅两行，详细说明在首次开启和恢复时出现。
- 地名点击与文字选择分开处理：点击走链接；需要选择文本时，从回复更多菜单打开。

## 0.3.1 减少手动操作

- 长期记忆默认开启，唯一开关在设置；聊天输入区只保留背景预览、位置和发送。
- 回答下方为 20dp 线性图标，保留 48dp 点击范围和无障碍说明，不常驻按钮文字。复制、选择文字直接触达，有执行记录时显示更多图标。
- 决定页为紧凑列表，无新建大按钮；从聊天明确选择与已成功打开的地图动作自动整理，点击记录才进入更正。地图查看状态与明确决定分别呈现。
