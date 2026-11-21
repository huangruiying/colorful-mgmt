# colorful-mgmt 运行线索

以下为 2026-11-22 从仓库与旧环境记录整理的位置线索，本次没有启动服务、连接数据库或服务器。使用前核对路径、版本和目标，不能据此宣称环境可用。

## 仓库可核对的位置

- Java/Maven 约束查 `pom.xml`；服务与数据源查 `src/main/resources/application.yml`；IDE 配置在 `.idea/`，可能未入版本控制。
- 本机服务端口配置为 8010，地址通常为 `http://127.0.0.1:8010`，是否启动查进程与端口。
- 新库结构 `src/main/resources/db/mysql/001_init.sql`；迁移与服务器执行步骤在 `.doc/<分支>/`。
- 部署查 `deploy/docker-compose.yaml`、`deploy/upload-code-to-server.sh` 和 `deploy/rebuild.sh`。仓库名为 colorful-mgmt，但当前 REMOTE_DIR 仍为 `/root/colorful-geo-mgmt`，Compose 名称和库名仍含 geo。
- 前端单测命令：`node --test src/test/js/*.cjs`；Java 定向测试可用 `-Dtest='A,B' -Dsurefire.failIfNoSpecifiedTests=false`，前提是核对实际 Maven/JDK。

## 历史本机位置，未现场验证

- MySQL 曾使用容器 `colorful-geo-mysql`、库 `colorful_geo`，先查当前容器，不把 Compose 服务名推断为固定容器名；连接时从安全配置读取凭据，避免明文命令或输出。
- Maven 曾使用 `/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn`，JDK 曾为 `~/Library/Java/JavaVirtualMachines/ms-17.0.17/Contents/Home`，可按实际依赖缓存决定是否离线。
- `~/Documents/maven/` 曾遇 macOS 权限错误，这不是永久不可用结论；需要时现场验证。
- Playwright 测试曾用本机 Chrome 与 `/opt/homebrew/lib/node_modules/agent-browser/node_modules/playwright-core`，先查是否存在，不自动安装或更换版本。

旧测试失败数量不作为豁免清单。知识图谱仅在结构性问题时检查是否存在；存在也只作路径导航，关键关系回查当前源码，不要求每次对话扫描图谱或安装技能。
