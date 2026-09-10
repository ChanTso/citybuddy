import { FormEvent, useCallback, useEffect, useRef, useState } from 'react';

import { login } from './api/auth';
import { ApiFailure, type ApiFailureKind } from './api/client';
import {
  listProducts,
  pollReservation,
  submitReservation,
} from './api/commerce';
import type { Product, Reservation } from './api/decoders';
import './app.css';

type ProductState = {
  phase: 'idle' | 'loading' | 'ready' | 'error';
  items: Product[];
  error?: string;
};
type ReservationIntent = {
  id: string;
  key: string;
  activityId: string;
  quantity: number;
  expectedActivityVersion: number;
  phase: 'submitting' | 'polling' | 'ready' | 'error' | 'indeterminate';
  result?: Reservation;
  error?: string;
};
const TERMINAL_RESERVATIONS = new Set([
  'REJECTED',
  'ORDERED',
  'CANCELLED',
  'UNFULFILLED',
]);
const POLL_LIMIT = 8;
function fixedError(kind: ApiFailureKind): string {
  return {
    unauthorized: '会话已过期，请重新登录。',
    forbidden: '当前账号无权执行此操作。',
    conflict: '请求与现有状态冲突，请检查后重试。',
    invalid: '请求无效，请检查输入。',
    dependency: '依赖服务暂时不可用，请稍后重试。',
    malformed: '服务返回了无法安全读取的数据。',
    network: '网络连接不可用，请稍后重试。',
  }[kind];
}

export function App() {
  const [token, setToken] = useState<string | null>(null);
  const [authPhase, setAuthPhase] = useState<
    'signed-out' | 'loading' | 'signed-in' | 'expired'
  >('signed-out');
  const [authError, setAuthError] = useState('');
  const [products, setProducts] = useState<ProductState>({
    phase: 'idle',
    items: [],
  });
  const [reservation, setReservation] = useState<ReservationIntent | null>(
    null,
  );
  const controllers = useRef(new Set<AbortController>());
  const reservationController = useRef<AbortController | null>(null);
  const activeReservation = useRef<string | null>(null);
  const generation = useRef(0);

  const ownController = useCallback(() => {
    const controller = new AbortController();
    controllers.current.add(controller);
    return controller;
  }, []);
  const releaseController = useCallback((controller: AbortController) => {
    controllers.current.delete(controller);
  }, []);
  const clearPrivateState = useCallback((expired: boolean) => {
    generation.current += 1;
    for (const controller of controllers.current) controller.abort();
    controllers.current.clear();
    reservationController.current = null;
    activeReservation.current = null;
    setToken(null);
    setProducts({ phase: 'idle', items: [] });
    setReservation(null);
    setAuthError('');
    setAuthPhase(expired ? 'expired' : 'signed-out');
  }, []);

  useEffect(
    () => () => {
      generation.current += 1;
      for (const controller of controllers.current) controller.abort();
      controllers.current.clear();
    },
    [],
  );

  const handleFailure = useCallback(
    (error: unknown): string | null => {
      if (error instanceof DOMException && error.name === 'AbortError')
        return null;
      const kind = error instanceof ApiFailure ? error.kind : 'network';
      if (kind === 'unauthorized') {
        clearPrivateState(true);
        return null;
      }
      return fixedError(kind);
    },
    [clearPrivateState],
  );

  async function loadProducts(activeToken: string, expectedGeneration: number) {
    const controller = ownController();
    setProducts({ phase: 'loading', items: [] });
    try {
      const items = await listProducts(activeToken, controller.signal);
      if (generation.current === expectedGeneration)
        setProducts({ phase: 'ready', items: items.slice(0, 100) });
    } catch (error) {
      const message = handleFailure(error);
      if (message && generation.current === expectedGeneration) {
        setProducts({ phase: 'error', items: [], error: message });
      }
    } finally {
      releaseController(controller);
    }
  }

  async function submitLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const loginIdentifier = String(data.get('loginIdentifier') ?? '').trim();
    const password = String(data.get('password') ?? '');
    if (!loginIdentifier || !password) {
      setAuthError('请输入登录名和密码。');
      return;
    }
    const controller = ownController();
    const expectedGeneration = ++generation.current;
    setAuthPhase('loading');
    setAuthError('');
    try {
      const result = await login(loginIdentifier, password, controller.signal);
      if (generation.current !== expectedGeneration) return;
      setToken(result.accessToken);
      setAuthPhase('signed-in');
      form.reset();
      void loadProducts(result.accessToken, expectedGeneration);
    } catch (error) {
      const message = handleFailure(error);
      if (message && generation.current === expectedGeneration) {
        setAuthPhase('signed-out');
        setAuthError(message);
      }
    } finally {
      releaseController(controller);
    }
  }

  async function runReservation(intent: ReservationIntent) {
    if (token === null || activeReservation.current !== null) return;
    activeReservation.current = intent.id;
    const expectedGeneration = generation.current;
    const controller = ownController();
    reservationController.current = controller;
    setReservation({ ...intent, phase: 'submitting', error: undefined });
    try {
      let result = await submitReservation(
        token,
        intent.activityId,
        intent.key,
        {
          quantity: intent.quantity,
          expectedActivityVersion: intent.expectedActivityVersion,
        },
        controller.signal,
      );
      if (generation.current !== expectedGeneration) return;
      setReservation({
        ...intent,
        phase: TERMINAL_RESERVATIONS.has(result.state) ? 'ready' : 'polling',
        result,
      });
      for (
        let attempt = 0;
        attempt < POLL_LIMIT && !TERMINAL_RESERVATIONS.has(result.state);
        attempt += 1
      ) {
        await new Promise<void>((resolve, reject) => {
          const timer = window.setTimeout(resolve, 750);
          controller.signal.addEventListener(
            'abort',
            () => {
              window.clearTimeout(timer);
              reject(new DOMException('Aborted', 'AbortError'));
            },
            { once: true },
          );
        });
        result = await pollReservation(
          token,
          result.reservationId,
          controller.signal,
        );
        if (generation.current !== expectedGeneration) return;
        setReservation({
          ...intent,
          phase: TERMINAL_RESERVATIONS.has(result.state) ? 'ready' : 'polling',
          result,
        });
      }
      if (
        !TERMINAL_RESERVATIONS.has(result.state) &&
        generation.current === expectedGeneration
      ) {
        setReservation({ ...intent, phase: 'indeterminate', result });
      }
    } catch (error) {
      const message = handleFailure(error);
      if (message && generation.current === expectedGeneration) {
        setReservation({ ...intent, phase: 'error', error: message });
      }
    } finally {
      if (activeReservation.current === intent.id) {
        activeReservation.current = null;
      }
      if (reservationController.current === controller) {
        reservationController.current = null;
      }
      releaseController(controller);
    }
  }

  function submitReservationForm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (activeReservation.current !== null) return;
    const data = new FormData(event.currentTarget);
    const activityId = String(data.get('activityId') ?? '').trim();
    const quantity = Number(data.get('quantity'));
    const expectedActivityVersion = Number(data.get('expectedActivityVersion'));
    if (
      !activityId ||
      activityId.length > 64 ||
      !Number.isSafeInteger(quantity) ||
      quantity < 1 ||
      !Number.isSafeInteger(expectedActivityVersion) ||
      expectedActivityVersion < 1
    ) {
      setReservation({
        id: crypto.randomUUID(),
        key: crypto.randomUUID(),
        activityId,
        quantity,
        expectedActivityVersion,
        phase: 'error',
        error: '请输入有效的活动编号、数量和版本。',
      });
      return;
    }
    reservationController.current?.abort();
    const intent: ReservationIntent = {
      id: crypto.randomUUID(),
      key: crypto.randomUUID(),
      activityId,
      quantity,
      expectedActivityVersion,
      phase: 'submitting',
    };
    void runReservation(intent);
  }

  const signedIn = token !== null && authPhase === 'signed-in';
  return (
    <div className="app-shell">
      <header className="site-header">
        <a className="brand" href="#top" aria-label="CityBuddy 首页">
          CityBuddy
        </a>
        <nav aria-label="页面导航">
          <a href="#shop">商品与秒杀</a>
          <a
            href="https://github.com/ChanTso/shopmate/blob/main/android/README.md"
            target="_blank"
            rel="noopener noreferrer"
          >
            买家 App
          </a>
        </nav>
        {signedIn && (
          <button
            className="quiet"
            type="button"
            onClick={() => clearPrivateState(false)}
          >
            退出登录
          </button>
        )}
      </header>
      <main id="top">
        <section className="hero" aria-labelledby="hero-title">
          <p className="eyebrow">JAVA COMMERCE · RETAIL WORKSPACE</p>
          <h1 id="hero-title">共享真实交易，连接购物与经营。</h1>
          <p>
            这里保留 CityBuddy 的商品与秒杀工程演示。购物咨询、商品比较、
            购物车、本人订单及退款确认统一在 ShopMate Android App 完成。
          </p>
          <a
            className="buyer-link"
            href="https://github.com/ChanTso/shopmate/blob/main/android/README.md"
            target="_blank"
            rel="noopener noreferrer"
          >
            安装 ShopMate 买家 App
          </a>
          <p className="hint">
            安装后在 App
            中登录对应服务的买家账号；当前页面的登录令牌不会随链接传递。
          </p>
        </section>

        {!signedIn ? (
          <section className="auth-card" aria-labelledby="login-title">
            <div>
              <p className="eyebrow">DIRECT USER</p>
              <h2 id="login-title">登录本地演示</h2>
              <p>凭证只保存在当前页面内存中；刷新页面会退出。</p>
            </div>
            <form onSubmit={submitLogin}>
              <label htmlFor="loginIdentifier">登录名</label>
              <input
                id="loginIdentifier"
                name="loginIdentifier"
                autoComplete="username"
                maxLength={190}
              />
              <label htmlFor="password">密码</label>
              <input
                id="password"
                name="password"
                type="password"
                autoComplete="current-password"
                maxLength={256}
              />
              <button type="submit" disabled={authPhase === 'loading'}>
                {authPhase === 'loading' ? '正在登录…' : '登录'}
              </button>
              {authPhase === 'loading' && (
                <p className="hint">提交期间按钮不可用。</p>
              )}
            </form>
            {(authError || authPhase === 'expired') && (
              <p role="alert" className="notice error">
                {authError || '会话已过期，请重新登录。'}
              </p>
            )}
          </section>
        ) : (
          <div className="workspace">
            <section id="shop" className="panel" aria-labelledby="shop-title">
              <div className="section-heading">
                <div>
                  <p className="eyebrow">SHOP</p>
                  <h2 id="shop-title">公开商品</h2>
                </div>
                <button
                  className="quiet"
                  type="button"
                  onClick={() => void loadProducts(token, generation.current)}
                  disabled={products.phase === 'loading'}
                >
                  重新加载
                </button>
              </div>
              <p className="hint">
                这里展示至多 100
                个已发布商品；完整零售目录、系列规格与购物车请进入 ShopMate。
              </p>
              {products.phase === 'loading' && (
                <p role="status">正在加载商品…</p>
              )}
              {products.phase === 'error' && (
                <p role="alert" className="notice error">
                  {products.error}
                </p>
              )}
              {products.phase === 'ready' && products.items.length === 0 && (
                <p role="status" className="notice">
                  当前没有已发布商品。
                </p>
              )}
              {products.phase === 'ready' && products.items.length > 0 && (
                <ul className="product-grid">
                  {products.items.map((product) => (
                    <li key={product.productId} className="product-card">
                      <div className="product-meta">
                        <span>
                          {product.currency}{' '}
                          {(product.priceMinor / 100).toFixed(2)}
                        </span>
                        <span>
                          {product.available
                            ? `可用数量 ${product.stockQuantity}`
                            : '当前不可用'}
                        </span>
                      </div>
                      <h3>{product.name}</h3>
                      <p>{product.description}</p>
                      <small>发布版本 {product.publicationVersion}</small>
                    </li>
                  ))}
                </ul>
              )}

              <div className="subpanel">
                <h3>秒杀 reservation</h3>
                <p>
                  此表单用于已配置活动的秒杀工程环境。默认零售部署未启用秒杀，
                  也没有预置可用的秒杀活动；提交后只展示服务端 reservation
                  状态。
                </p>
                <form
                  className="reservation-form"
                  onSubmit={submitReservationForm}
                >
                  <label htmlFor="activityId">活动编号</label>
                  <input id="activityId" name="activityId" maxLength={64} />
                  <label htmlFor="quantity">数量</label>
                  <input
                    id="quantity"
                    name="quantity"
                    type="number"
                    min="1"
                    step="1"
                    defaultValue="1"
                  />
                  <label htmlFor="activityVersion">活动版本</label>
                  <input
                    id="activityVersion"
                    name="expectedActivityVersion"
                    type="number"
                    min="1"
                    step="1"
                    defaultValue="1"
                  />
                  <button
                    type="submit"
                    disabled={
                      reservation?.phase === 'submitting' ||
                      reservation?.phase === 'polling'
                    }
                  >
                    提交 reservation
                  </button>
                  {(reservation?.phase === 'submitting' ||
                    reservation?.phase === 'polling') && (
                    <p className="hint">当前 intent 处理中，重复提交已禁用。</p>
                  )}
                </form>
                {reservation?.error && (
                  <div role="alert" className="notice error">
                    <p>{reservation.error}</p>
                    <button
                      type="button"
                      onClick={() => void runReservation(reservation)}
                    >
                      使用原 intent 重试
                    </button>
                  </div>
                )}
                {reservation?.result && (
                  <div role="status" className="reservation-result">
                    <span className="status-dot" aria-hidden="true" />
                    <div>
                      <strong>服务端状态：{reservation.result.state}</strong>
                      <p>Reservation {reservation.result.reservationId}</p>
                      {reservation.result.state === 'UNFULFILLED' && (
                        <p>
                          准入已完成，但权威库存或订单约束未满足，未创建订单。
                        </p>
                      )}
                      {reservation.phase === 'indeterminate' && (
                        <p>轮询已达上限，状态仍未确定。</p>
                      )}
                    </div>
                  </div>
                )}
              </div>
            </section>
          </div>
        )}
      </main>
      <footer>
        <p>CityBuddy 交易服务 · ShopMate 零售工作台</p>
      </footer>
    </div>
  );
}
