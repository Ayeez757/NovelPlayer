必须返回如下 JSON 结构：
{
  "schemaVersion": "1.0",
  "metadata": {"title": "...", "language": "zh-CN", "sourceChapterCount": 3, "generatedAt": "2026-06-05T12:00:00+08:00"},
  "adaptation": {"format": "web_drama", "tone": "suspense", "logline": "...", "themes": ["..."]},
  "characters": [{"id": "char_001", "name": "...", "aliases": [], "role": "protagonist", "goal": "...", "traits": [], "voice": "..."}],
  "locations": [{"id": "loc_001", "name": "...", "type": "interior", "description": "..."}],
  "scenes": [{"id": "scene_001", "title": "...", "sourceChapters": [1], "locationId": "loc_001", "timeOfDay": "night", "characters": ["char_001"], "dramaticPurpose": "...", "summary": "...", "blocks": [{"type": "action", "text": "..."}, {"type": "dialogue", "speakerId": "char_001", "text": "..."}]}],
  "revisionNotes": ["..."]
}

生成约束：
- 人物、地点和场景必须使用稳定编号。
- 每个场景都必须填写 sourceChapters。
- 每个对白块都必须填写 speakerId。
- 尽量把内心独白转化为可表演的动作或对白。
- 必须保留所有章节中的主要冲突。
