任务：根据全部章节摘要生成一个 StoryBible。

输出要求：
- 只返回一个 JSON 对象
- 必须包含 characters、locations、mainPlot、themes、continuityRules
- characters 每项结构为 { "id", "name", "aliases", "role", "goal", "traits", "voice" }
- locations 每项结构为 { "id", "name", "type", "description" }
- 角色 ID 统一使用 char_001 这类格式
- 地点 ID 统一使用 loc_001 这类格式
- mainPlot 不能为空

输入内容：
%s