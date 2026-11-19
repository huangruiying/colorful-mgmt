# 运行时环境记忆

用于编译、跑测试、接口回归和查库时快速定位本项目的运行环境。密码、密钥、Cookie 不写入 memory，需要时从环境变量或用户处获取。

## 本机

- 服务：一般由 IDEA 启动，端口 8010，`BASE_URL=http://127.0.0.1:8010`；除登录页外的接口都要求登录。
- MySQL：docker 容器 `colorful-geo-mysql`，库 `colorful_geo`。用容器内的环境变量登录，不在命令里写密码：
  `docker exec -i colorful-geo-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 colorful_geo'`
- 往临时库导入 `001_init.sql` 时，先去掉开头的 `CREATE DATABASE` 和 `USE colorful_geo;` 两行。
- Maven：用 IDEA 自带的 `/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn`，配合 `JAVA_HOME=~/Library/Java/JavaVirtualMachines/ms-17.0.17/Contents/Home`，离线执行（`-o`）。`~/Documents/maven/` 下的 Maven 会被 macOS 隐私保护拦截（Operation not permitted），沙箱外执行也一样，不要用。
- 只跑部分测试：`-Dtest='A,B' -Dsurefire.failIfNoSpecifiedTests=false`。前端测试：`node --test src/test/js/*.cjs`。
- 无头页面检查：playwright-core 在 `/opt/homebrew/lib/node_modules/agent-browser/node_modules/playwright-core`，浏览器用本机 Chrome。
- 截至 2026-11-19 的既有失败：Java `LLMStrategyRouterTest.shouldRejectDisabledExactTaskChannel`；前端 11 项（browser-login 9、http 2）。判断是否新引入失败时先对照。

## 服务器

- 部署目录 `/root/colorful-geo-mgmt/deploy`（见 `deploy/upload-code-to-server.sh` 的 `REMOTE_DIR`），docker compose 运行；看日志：`docker compose -f docker-compose.yaml --env-file .env logs --tail=100 app`。
- 发新代码：已上传时在部署目录执行 `sh rebuild.sh`；未上传时在本地执行 `sh deploy/upload-code-to-server.sh -r`。
- 改库命令由 `.doc/<分支>/*-服务器执行.md` 给出，按其中顺序先改库再发代码。
