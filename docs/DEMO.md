<a id="citybuddy-与-shopmate-演示"></a>

# CityBuddy and ShopMate demo

CityBuddy provides identity and Java transaction services; ShopMate provides Android/iOS buyer apps, a React merchant workspace, and buyer and merchant Agents. The demo uses one Auth/Commerce pair and the data volumes owned by the ShopMate retail deployment: Auth at `127.0.0.1:9081`, Commerce at `127.0.0.1:9082`, and the ShopMate API plus built merchant Web client at the same origin, `127.0.0.1:8101`.

CityBuddy's optional Vite page retains basic catalog reads and a flash-sale engineering form, proxying the same 9081/9082 services by default. Buyers use native apps; installation instructions are available for [Android](https://github.com/ChanTso/shopmate/blob/main/android/README.md) and [iOS](https://github.com/ChanTso/shopmate/blob/main/ios/README.md). Each entry signs in independently with the same accounts; links do not carry JWTs.

<a id="启动导引"></a>

## Startup guide

```sh
make demo
```

**This command only prints instructions. It does not start or stop services or change data or credentials.** `make demo-story` prints the buyer walkthrough; `make demo-stop` prints shutdown instructions. These command names remain discoverable, but no longer run the old model fixture, clean signing tables or create refund orders automatically.

Requires a sibling ShopMate checkout, Java 21, Python 3.11, Node.js 24, uv and Docker Compose. Set `SHOPMATE_DIR` if the checkout is elsewhere so the guide prints the correct paths. First-time preparation:

```sh
# CityBuddy directory
make init-local setup-java setup-python
./mvnw --batch-mode --no-transfer-progress -pl auth-service,commerce-service -am package

# ShopMate directory
cd ../shopmate
uv sync --frozen
python3 scripts/local_runtime.py up
```

Stop the ShopMate API before `up`. It uses the `shopmate` Compose project and persistent volumes, initializing the current retail fixture on first use and preserving business changes when that fixture version already exists. It is not a manual business reset and does not migrate the old CityBuddy demo database. To restore the fixture, first stop business writes and follow ShopMate's `docs/retail-fixture.md`.

Build the merchant Web client in ShopMate, then start the API:

```sh
npm --prefix web ci
npm --prefix web run build
uv run uvicorn shopmate.app:create_app --factory --host 127.0.0.1 --port 8101
```

The merchant entry is <http://127.0.0.1:8101/>; no separate Node service is required. For Web hot reload, run `npm --prefix web run dev` in another terminal; port 3100 proxies API requests to 8101.

Configure the Android emulator with API/Commerce origins `http://10.0.2.2:8101` and `http://10.0.2.2:9082`; the iOS simulator uses `http://localhost:8101` and `http://localhost:9082`. Device connectivity and signing are covered in the client guides. Demo accounts and private password files:

| Role | Account | Password file inside ShopMate |
| --- | --- | --- |
| Buyer | `shopmate-retail-buyer` | `.run/buyer_1_password` |
| Second buyer | `shopmate-retail-buyer-2` | `.run/buyer_2_password` |
| Merchant | `shopmate-fixture-operator` | `.run/operator_password` |

Read passwords and model-provider configuration only from existing local files; do not include them in URLs, screenshots, documents or commits. The provider configuration still comes from CityBuddy's `.env` through ShopMate's actual startup configuration. These startup commands are not a model acceptance run.

To demonstrate City's basic catalog API, open another terminal in CityBuddy:

```sh
npm --prefix web ci
npm --prefix web run dev -- --host 127.0.0.1
```

Open <http://127.0.0.1:5173>. `web/vite.config.ts` and `web/.env.example` default to Auth/Commerce on 9081/9082. If `web/.env.local` exists, update `CITYBUDDY_AUTH_TARGET` and `CITYBUDDY_COMMERCE_TARGET` accordingly, remove the old Agent proxy configuration and restart Vite. The old 8081/8082 services and benchmark ports 18080/18081 are not the shared authority for this retail entry.

<a id="买家操作顺序"></a>

## Buyer walkthrough

1. Sign in to the buyer app, read the actual catalog and variants, and add an in-stock SKU to the cart. City's basic product list shows at most 100 entries; use ShopMate for the complete catalog, product families and variants.
2. Ask the assistant about recommendations, comparisons, shopping plans, your orders or policies. The profile page searches published policies by keyword and returns at most three matches; it is not a complete policy listing.
3. Open checkout, review every SKU, variant, quantity, current price and product total, then confirm order creation. A stale quote requires another read and decision; a new price is not accepted automatically.
4. Explicitly confirm simulated payment for the checkout. Creating an order is distinct from successful payment, which is distinct from shipment; delivery estimates are not included in the product payment.
5. Prepare a refund from your orders or through the assistant, review the saved order, amount and expiry, then explicitly confirm as the signed-in buyer. `REQUESTED` records a refund application, not actual settlement.
6. Reopen the app and restore the original conversation, checking persisted orders, cart and refund receipts. For unknown writes, recover through reads first, then follow the UI before retrying the original request.

These are operating instructions, not automatic success claims or new model results. ShopMate's `integration_tests` verify writes through actual APIs and authoritative SQL; mixed confirmations, duplicate submissions and ownership isolation do not depend on the retired page or chat protocol.

<a id="秒杀功能演示"></a>

## Flash-sale functional demo

The current `local_runtime.py up` enables admission, order creation and timeout messaging, and grants reservation permission to demo buyers. First startup creates activity `shopmate-demo-seckill` and product `SM-LIMITED-CUP`, with an activity quota of 10. Existing activities retain their quota, reservations and orders rather than being replenished on restart.

Open the limited-offer entry from the app home page and confirm a reservation. Activity reads, reservation submission and status queries call Commerce 9082 directly. After order creation, simulated payment uses the server-side signer through the ShopMate API; the app contains no callback key. Automatic polling is bounded and runs only while the app is foregrounded and the offer page is visible. Admission, order creation and payment appear as distinct states. City's engineering form can use the same activity and buyer account.

This is a finite-inventory functional demo, not a capacity measurement. Dedicated flash-sale workloads and historical results remain in [bench](../bench/README.md). When switching to a benchmark deployment, distinguish its identities and database; do not mix orders or performance numbers across environments.

<a id="停止与历史数据"></a>

## Shutdown and historical data

```sh
make demo-stop
```

This command **does not stop any services**. Press Ctrl-C in the terminals where you started the API, optional merchant Web development server and City Vite. After confirming that no tasks or unknown writes remain active, run in ShopMate:

```sh
python3 scripts/local_runtime.py stop
```

ShopMate's stop command stops its Java containers and `shopmate` Compose data services while retaining persistent volumes. The guide does not read old `.citybuddy-demo` PID files, kill unknown processes or change old `citybuddy` data volumes or signing metadata. Historical support PendingActions, receipts and evidence are not migrated or confirmed automatically. Identify the owning startup session before handling an old running process.

The former automatic six-scene `demo.sh` / `demo_story.py` walkthrough depended on `/api/sessions`, `/api/chat` and the old model fixture; it retired with that model loop. Its code remains available at the pre-switch revision `2eb42634f082c0ddf93639f902db38009381d337`. StateEval's historical ablation artifacts retain their recorded source versions. Do not attribute old-protocol results to the new shopping assistant or retarget historical reseed scripts to the retail database by changing ports.
