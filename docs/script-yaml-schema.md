# 剧本 YAML Schema

本文档定义 NovelPlayer 生成的剧本 YAML 格式，当前版本为 `1.0`。

## 设计目标

- 可编辑：作者可以直接修改场景、对白、动作和备注。
- 可追溯：每场戏都能标记来源章节，方便作者核对 AI 改编依据。
- 可校验：角色、地点、场景使用稳定 ID，后端可以检查引用是否有效。
- 可扩展：后续可以增加分镜、拍摄预算、情绪曲线等字段，不破坏核心结构。

## 顶层结构

```yaml
schema_version: "1.0"
metadata: {}
adaptation: {}
characters: []
locations: []
scenes: []
revision_notes: []
```

## 字段说明

### `schema_version`

Schema 版本号。

设计原因：剧本格式后续可能迭代，版本号可以帮助系统判断如何解析、校验或迁移旧数据。

### `metadata`

记录作品和生成任务的基础信息。

必填字段：

- `title`：作品标题。
- `language`：剧本文本语言，例如 `zh-CN`。
- `source_chapter_count`：来源小说章节数量，必须不少于 3。
- `generated_at`：生成时间。

设计原因：这些字段用于演示、下载、归档和问题排查，也方便作者区分不同生成版本。

### `adaptation`

记录本次改编的创作意图。

必填字段：

- `format`：剧本类型，例如 `web_drama`、`screenplay`、`stage_play`。
- `tone`：整体风格，例如 `suspense`、`realistic`、`comedy`。
- `logline`：一句话故事梗概。
- `themes`：主题关键词列表。

设计原因：同一部小说可以被改编成不同类型的剧本。把改编参数写入 YAML，可以让剧本初稿保留生成时的创作约束。

### `characters`

角色表。对白块通过 `speaker_id` 引用角色，而不是重复写角色名。

必填字段：

- `id`：角色 ID，例如 `char_001`。
- `name`：角色名称。
- `role`：角色功能，例如 `protagonist`、`antagonist`、`supporting`。

可选字段：

- `aliases`：别名、昵称或原文中的不同称呼。
- `goal`：角色目标。
- `traits`：性格特征。
- `voice`：对白风格说明。

设计原因：小说中人物称呼常常不一致。独立角色表可以减少歧义，并支持后续做人物弧光、对白风格一致性检查。

### `locations`

地点表。场景通过 `location_id` 引用地点。

必填字段：

- `id`：地点 ID，例如 `loc_001`。
- `name`：地点名称。
- `type`：地点类型，例如 `interior`、`exterior`。

可选字段：

- `description`：地点描述。

设计原因：剧本创作和影视制作都很关注场景地点。把地点抽出来，可以支持后续生成拍摄计划、场景预算或分镜表。

### `scenes`

剧本主体。每个元素代表一场戏，是作者最主要的编辑单元。

必填字段：

- `id`：场景 ID，例如 `scene_001`。
- `title`：场景标题。
- `source_chapters`：来源章节编号列表。
- `location_id`：地点 ID。
- `time_of_day`：时间，例如 `day`、`night`、`dawn`。
- `characters`：本场出现的角色 ID 列表。
- `dramatic_purpose`：本场戏的戏剧目的。
- `summary`：场景摘要。
- `blocks`：场景内容块。

设计原因：小说章节不一定等于剧本场景。以场景作为核心结构，更贴近剧本创作流程，也便于作者逐场修改。

### `blocks`

场景内容块。当前支持以下类型：

- `action`：动作或场面描写。
- `dialogue`：对白。
- `transition`：转场。
- `note`：创作备注。

通用字段：

- `type`：内容块类型。
- `text`：内容文本。

对白块额外字段：

- `speaker_id`：说话角色 ID。

设计原因：把场景内容拆成块，可以同时兼顾可读性和可编辑性。后续前端可以按块提供更细粒度的编辑体验。

### `revision_notes`

记录 AI 在改编时做出的取舍。

设计原因：小说改剧本一定会发生删减、合并、转化。把这些说明暴露给作者，可以降低 AI 黑箱感。

## 完整示例

```yaml
schema_version: "1.0"
metadata:
  title: "雨夜的信"
  language: "zh-CN"
  source_chapter_count: 3
  generated_at: "2026-06-05T12:00:00+08:00"
adaptation:
  format: "web_drama"
  tone: "suspense"
  logline: "年轻作者追踪一封旧信，逐步揭开父亲失踪的真相。"
  themes:
    - 真相
    - 记忆
    - 选择
characters:
  - id: "char_001"
    name: "林安"
    aliases:
      - "安安"
    role: "protagonist"
    goal: "找到父亲失踪的真相"
    traits:
      - "敏锐"
      - "克制"
    voice: "句子偏短，情绪通常压在动作里。"
locations:
  - id: "loc_001"
    name: "旧书店"
    type: "interior"
    description: "狭窄、潮湿，书架间有昏黄灯光。"
scenes:
  - id: "scene_001"
    title: "雨夜的信"
    source_chapters:
      - 1
    location_id: "loc_001"
    time_of_day: "night"
    characters:
      - "char_001"
    dramatic_purpose: "建立主角目标，并引出父亲失踪的悬念。"
    summary: "林安在旧书店发现父亲留下的信。"
    blocks:
      - type: "action"
        text: "雨水敲着玻璃。林安推开旧书店的门，风铃轻轻一响。"
      - type: "dialogue"
        speaker_id: "char_001"
        text: "这封信，为什么会在这里？"
      - type: "transition"
        text: "CUT TO:"
revision_notes:
  - "第 1 章中的心理描写被转化为动作和对白。"
  - "原文中较长的背景叙述被压缩为场景摘要。"
```

