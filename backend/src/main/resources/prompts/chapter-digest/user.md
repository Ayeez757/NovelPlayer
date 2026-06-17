任务：根据单章小说内容生成一个 ChapterDigest。
输出要求：
- 只返回一个 JSON 对象
- chapterIndex 必须与输入 chapterIndex 完全一致
- title 和 summary 不能为空
- majorEvents、characters、locations、conflicts、openThreads、adaptationHints 必须全部存在且为数组
- characters 的每一项使用 { "name": "...", "aliases": [], "roleHint": "...", "goalHint": "..." } 结构
- locations 的每一项使用 { "name": "...", "type": "...", "description": "..." } 结构
- 拿不准时保留空数组，不要省略字段
输入内容：
%s