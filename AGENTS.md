# colorful-mgmt 协作入口

使用中文交流。用户提供本地路径时直接读取，读取报错前不要求用户粘贴内容。

接手读取 [.claude/CLAUDE.md](.claude/CLAUDE.md)，按其路由加载当前任务需要的文件；所有工具共用这一份工程规则。

当用户输入 `/graphify` 时先加载 graphify 技能；技能不存在时说明情况，不编造执行结果。
