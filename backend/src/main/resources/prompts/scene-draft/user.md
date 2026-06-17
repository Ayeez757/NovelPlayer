任务：根据场景写作上下文生成一个 SceneDraft。

输出要求：
- 只返回一个 JSON 对象
- id、sourceChapters、locationId、characters 必须与输入 plannedScene 保持一致
- title、dramaticPurpose、summary 不能为空
- blocks 必须是非空数组
- 每个 block 必须包含 type 和 text
- 如果 block.type 是“对白”，speakerId 必须引用输入 characters 中已有的角色 id

输入内容：
%s