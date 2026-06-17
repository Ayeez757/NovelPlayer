任务：结合章节摘要与 StoryBible 生成一个 ScenePlan。

输出要求：
- 只返回一个 JSON 对象
- 必须包含 scenes 数组
- scenes 每一项结构为 { "id", "title", "sourceChapters", "locationId", "timeOfDay", "characters", "dramaticPurpose", "summary", "requiredBeats" }
- 每个 scene 的 id、title、locationId、timeOfDay、dramaticPurpose、summary 不能为空
- sourceChapters、characters、requiredBeats 必须是数组
- locationId 只能引用 StoryBible.locations 中已有的 id
- characters 只能引用 StoryBible.characters 中已有的 id

输入内容：
%s