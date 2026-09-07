#!/usr/bin/env python3
"""Print the shared retail startup, browser story, or shutdown instructions; perform no actions."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
from shlex import quote

CITY = Path(__file__).resolve().parents[1]
SHOP = Path(os.environ.get("SHOPMATE_DIR", CITY.parent / "shopmate")).resolve()


def startup() -> None:
    print("启动导引：未启动或停止任何服务，也未修改数据库、签名配置或凭证。")
    if not (SHOP / "scripts/local_runtime.py").is_file():
        print(f"尚未找到 {SHOP / 'scripts/local_runtime.py'}。")
        print("请先准备同级 ShopMate 仓库，或设置 SHOPMATE_DIR 后重新查看本导引。")
    print("准备 Java 构建及依赖（首次运行）：")
    print(f"  cd {quote(str(CITY))}")
    print("  make init-local setup-java setup-python")
    print(
        "  ./mvnw --batch-mode --no-transfer-progress -pl auth-service,commerce-service -am package"
    )
    print(f"  cd {quote(str(SHOP))}")
    print("  uv sync --frozen")
    print("确认 ShopMate API 已停止后，准备同一套零售 Java/数据服务：")
    print(f"  CITYBUDDY_DIR={quote(str(CITY))} python3 scripts/local_runtime.py up")
    print("up 首次初始化零售夹具，已有当前版本数据则保留业务变更；不是手工 reset。")
    print("终端一，在 ShopMate 目录启动 API：")
    print("  uv run uvicorn shopmate.app:create_app --factory --host 127.0.0.1 --port 8101")
    print("终端二，在 ShopMate 目录启动网页：")
    print("  npm --prefix web ci")
    print("  npm --prefix web run build")
    print("  npm --prefix web run start")
    print("买家 http://127.0.0.1:3100/buyer；商家 http://127.0.0.1:3100。")
    print("可选的 City 商品/秒杀工程页面（另开终端，不是第二个 Agent）：")
    print(f"  cd {quote(str(CITY))}")
    print("  npm --prefix web ci")
    print("  npm --prefix web run dev -- --host 127.0.0.1")
    print("默认代理 Auth 9081 / Commerce 9082，与 ShopMate 共用业务权威。")
    print("若已有 web/.env.local，请将两个公共代理目标更新到 9081/9082 后重启 Vite。")
    print("运行 make demo-story 查看买家操作步骤；这些步骤尚未自动执行或验收。")


def story() -> None:
    print("演示步骤导引：未启动或停止任何服务，未调用模型、下单、付款或退款。")
    print("先按 make demo 的说明启动共享零售部署。")
    print("买家页面：http://127.0.0.1:3100/buyer")
    print(f"账号 shopmate-retail-buyer；密码文件 {SHOP / '.run/buyer_1_password'}。")
    print("密码只在本机读取，不复制到执行记录、URL 或提交。")
    print("1. 登录并打开商品页，选一个有货的实际 SKU，核对规格后加入购物车。")
    print("2. 可向助手提问推荐、比较或政策；回答和卡片不自动构成业务确认。")
    print("3. 打开结账页，核对整车 SKU、数量、当前价格与合计，勾选后确认结账。")
    print("4. 对创建的结账记录明确确认模拟付款；订单创建、付款成功和发货分别核对。")
    print("5. 在本人订单页准备退款，核对保存的订单、金额和有效期，再明确提交申请。")
    print("6. 查看真实退款回执和订单状态；REQUESTED 是申请受理，不代表资金到账。")
    print("7. 刷新并重新登录，恢复原会话及业务记录；未知写入先只读恢复，再决定原请求重试。")
    print("City 页面用同一买家账号登录 9081，打开 ShopMate 链接后需重新登录，不传 JWT。")
    print("默认零售部署没有启用秒杀或预置活动。秒杀页面与 bench 保留为独立工程演示。")
    print("这些是操作说明，不是自动验收或新的模型成功率；业务回归使用 ShopMate 的真实集成测试。")


def shutdown() -> None:
    print("停止导引：未停止任何服务，未发送进程信号，也未删除容器、数据或凭证。")
    print("先在自己启动的 ShopMate API、ShopMate 网页与 City Vite 终端分别按 Ctrl-C。")
    print("确认没有运行中的任务或未知业务写入后，在 ShopMate 目录停止其 Java/数据服务：")
    print(f"  cd {quote(str(SHOP))}")
    print(f"  CITYBUDDY_DIR={quote(str(CITY))} python3 scripts/local_runtime.py stop")
    print("该命令停止 ShopMate 的 Java 容器与 shopmate Compose 数据服务，保留持久卷。")
    print("此导引不读取旧 .citybuddy-demo/*.pid，不按端口杀进程，也不修改旧 City 演示库。")
    print("若仍有历史演示服务，先核实其所属原启动会话；不要将旧 reseed/清理脚本指向零售库。")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("start", "story", "stop"), default="story")
    arguments = parser.parse_args()
    {"start": startup, "story": story, "stop": shutdown}[arguments.mode]()


if __name__ == "__main__":
    main()
