# CityBuddy 与 ShopMate 演示

CityBuddy 提供身份与 Java 交易服务；ShopMate 提供统一的商家和买家零售 Agent。正式演示只使用 ShopMate 零售部署中的一套 Auth、Commerce 与数据卷：Auth 为 `127.0.0.1:9081`，Commerce 为 `127.0.0.1:9082`，ShopMate API 为 `127.0.0.1:8101`，网页为 `127.0.0.1:3100`。

City 的可选 Vite 页面保留基本商品读取与秒杀工程表单，默认代理同一套 9081/9082。购物助手链接直接打开 `http://127.0.0.1:3100/buyer`；新页面使用同一买家账号重新登录。页面间不传 JWT、不共享浏览器令牌，也不保留旧客服会话或聊天接口。

## 启动导引

```sh
make demo
```

**此命令只打印操作说明，未启动或停止任何服务，也未修改数据库或凭证。** `make demo-story` 打印买家操作步骤；`make demo-stop` 打印停止步骤。它们保留命令名便于查找，不再自动运行旧模型 fixture、清理签名表或创建退款订单。

需要同级 ShopMate 仓库、Java 21、Python 3.11、Node.js 24、uv 和 Docker Compose。目录不同时可设置 `SHOPMATE_DIR` 供导引生成正确路径。首次准备：

```sh
# CityBuddy 目录
make init-local setup-java setup-python
./mvnw --batch-mode --no-transfer-progress -pl auth-service,commerce-service -am package

# ShopMate 目录
cd ../shopmate
uv sync --frozen
python3 scripts/local_runtime.py up
```

`up` 前先停止 ShopMate API。它使用 `shopmate` Compose 项目及其持久卷，首次初始化当前零售夹具；已有该版本数据时保留实际业务变更。它不是手工业务 reset，也不会将旧 City 演示库迁入零售库。需要恢复夹具时，先停业务写入并按 ShopMate 的 `docs/retail-fixture.md` 操作。

在 ShopMate 目录的一个终端启动 API：

```sh
uv run uvicorn shopmate.app:create_app --factory --host 127.0.0.1 --port 8101
```

另一个终端启动网页：

```sh
npm --prefix web ci
npm --prefix web run build
npm --prefix web run start
```

买家入口为 <http://127.0.0.1:3100/buyer>，商家入口为 <http://127.0.0.1:3100>。演示账号与私有密码文件：

| 角色 | 账号 | ShopMate 内的密码文件 |
| --- | --- | --- |
| 买家 | `shopmate-retail-buyer` | `.run/buyer_1_password` |
| 第二买家 | `shopmate-retail-buyer-2` | `.run/buyer_2_password` |
| 商家 | `shopmate-fixture-operator` | `.run/operator_password` |

密码和模型代理配置只从现有本机文件读取，不放进 URL、截图、文档或提交。模型代理来源仍是 CityBuddy `.env`，由 ShopMate 实际启动配置接入；以上启动命令本身不是一次模型验收。

如需演示 City 的基本商品接口，另开终端在 CityBuddy 目录运行：

```sh
npm --prefix web ci
npm --prefix web run dev -- --host 127.0.0.1
```

打开 <http://127.0.0.1:5173>。`web/vite.config.ts` 与 `web/.env.example` 默认 Auth/Commerce 为 9081/9082；已有 `web/.env.local` 时，把 `CITYBUDDY_AUTH_TARGET`、`CITYBUDDY_COMMERCE_TARGET` 更新为对应地址并重启 Vite，删除旧 Agent 代理配置。旧 8081/8082 或压测用 18080/18081 不是此零售入口的共享权威。

## 买家操作顺序

1. 登录买家页面，读取真实目录与规格，选择有货 SKU 加入购物车。City 的基础商品列表最多展示 100 条；完整目录、商品系列与规格以 ShopMate 为入口。
2. 可向助手询问推荐、比较、购物规划、本人订单或政策。资料页按关键词搜索已发布政策，每次最多返回三条匹配；不是全部政策列表。
3. 打开结账页，核对整车 SKU、规格、数量、当前价格与商品合计，勾选后确认创建订单。旧报价冲突时重新读回再决定，不自动接受新价。
4. 对结账记录明确确认模拟付款。创建订单不等于付款成功，付款成功也不等于已发货；配送估算不加入商品付款。
5. 在本人订单页或通过助手准备退款申请，核对保存的订单、金额和有效期，再由登录买家明确确认。`REQUESTED` 表示退款申请已记录，不代表真实资金到账。
6. 刷新、重新登录并恢复原会话，核对持久订单、购物车和退款回执。未知写入先只读恢复，再按页面提示决定是否重试原请求。

这些步骤是操作说明，不代表自动执行成功或新的模型成绩。写入正确性由 ShopMate 的 `integration_tests` 通过真实接口及权威 SQL 验证；混合确认、重复提交、归属隔离等业务断言不依赖旧页面或旧聊天协议。

## 秒杀工程演示的边界

默认零售 `local_runtime.py up` **未启用秒杀，也没有预置可用秒杀活动或买家秒杀权限**。City 秒杀表单仍保留原请求幂等、版本提交、有界轮询、终态展示和退出取消行为，供已有秒杀专用部署与活动夹具使用；不能把默认零售页上的表单视为已开放活动。

秒杀专用启动、压测脚本与历史结果继续保留在 [bench](../bench/README.md)。若切到专用测量环境，应明确该环境的身份与数据库，不能把另一套默认 City 库中的订单当成 ShopMate 账户的订单。此入口切换不新增活动、改容量参数或重跑压测。

## 停止与历史数据

```sh
make demo-stop
```

此命令**未停止任何服务**。先在自己启动 API、ShopMate 网页、City Vite 的终端按 Ctrl-C，确认没有运行中的任务或未知写入，再在 ShopMate 目录执行：

```sh
python3 scripts/local_runtime.py stop
```

ShopMate 的停止命令停止自身 Java 容器和 `shopmate` Compose 数据服务，保留持久卷。导引不读取旧 `.citybuddy-demo` PID 文件、不杀未知进程，也不操作旧 `citybuddy` 数据卷或签名元数据。旧客服的历史 PendingAction、回执与证据不自动迁移或确认；旧运行进程需先核实所属启动会话后处理。

旧 `demo.sh` / `demo_story.py` 的自动六幕演示绑定 `/api/sessions`、`/api/chat` 和旧模型 fixture，随该正式循环退出。历史代码仍可在切换前版本 `2eb42634f082c0ddf93639f902db38009381d337` 查阅。StateEval 历史消融原件按其记录的源版本保存，不能将旧协议的成绩直接称为新版购物助手成绩，也不能把历史 reseed 脚本改端口后指向当前零售库。
