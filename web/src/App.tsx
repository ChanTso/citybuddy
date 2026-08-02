import { FormEvent, useCallback, useEffect, useRef, useState } from 'react';

import { createSupportSession, sendChat, streamChat } from './api/agent';
import { login } from './api/auth';
import { ApiFailure, type ApiFailureKind } from './api/client';
import {
  listProducts,
  pollReservation,
  submitReservation,
} from './api/commerce';
import type {
  Citation,
  ChatOutcome,
  Product,
  Reservation,
} from './api/decoders';
import { UnsupportedReceiptError } from './api/sse';
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
type ChatEntry = {
  id: string;
  speaker: 'you' | 'citybuddy';
  text: string;
  citations?: Citation[];
};
type ChatIntent = {
  key: string;
  message: string;
  mode: 'json' | 'stream';
  phase: 'sending' | 'error';
};
type PendingNotice = {
  phase: 'pending' | 'declined' | 'expired';
  reply: string;
};

const TERMINAL_RESERVATIONS = new Set(['REJECTED', 'ORDERED', 'CANCELLED']);
const POLL_LIMIT = 8;
const CONFIRMATION_MESSAGES = new Set([
  'confirm',
  'confirm refund',
  'yes',
  'yes confirm',
  '确认',
  '确认退款',
  '是的',
  '是的确认',
]);

function isConfirmationMessage(message: string): boolean {
  return CONFIRMATION_MESSAGES.has(
    message.normalize('NFKC').trim().toLocaleLowerCase().replace(/\s+/g, ' '),
  );
}

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

function outcomeLabel(outcome: ChatOutcome): string {
  return {
    completed: '回复已完成',
    budget_exhausted: '本次回复预算已用尽',
    provider_denied: '回复服务暂时不可用',
    retrieval_denied: '没有足够的公开资料来回答',
    action_pending: '动作仍在等待处理',
    action_clarification: '已记录补充说明，动作仍未执行',
    action_declined: '动作已由服务端标记为拒绝，未执行',
    action_expired: '动作已由服务端标记为过期，未执行',
  }[outcome];
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
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [chat, setChat] = useState<ChatEntry[]>([]);
  const [chatIntent, setChatIntent] = useState<ChatIntent | null>(null);
  const [chatStatus, setChatStatus] = useState('');
  const [pending, setPending] = useState<PendingNotice | null>(null);
  const [streamMode, setStreamMode] = useState(false);
  const controllers = useRef(new Set<AbortController>());
  const reservationController = useRef<AbortController | null>(null);
  const activeReservation = useRef<string | null>(null);
  const activeChat = useRef<string | null>(null);
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
    activeChat.current = null;
    setToken(null);
    setProducts({ phase: 'idle', items: [] });
    setReservation(null);
    setSessionId(null);
    setChat([]);
    setChatIntent(null);
    setChatStatus('');
    setPending(null);
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
        setProducts({ phase: 'ready', items });
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

  async function ensureSession(
    activeToken: string,
    expectedGeneration: number,
    signal: AbortSignal,
  ) {
    if (sessionId !== null) return sessionId;
    const created = await createSupportSession(activeToken, signal);
    if (generation.current !== expectedGeneration)
      throw new DOMException('Aborted', 'AbortError');
    setSessionId(created.sessionId);
    return created.sessionId;
  }

  function applyOutcome(outcome: ChatOutcome, reply: string) {
    setChatStatus(outcomeLabel(outcome));
    if (outcome === 'action_pending') setPending({ phase: 'pending', reply });
    if (outcome === 'action_clarification') {
      setPending((current) => (current ? { ...current, reply } : null));
    }
    if (outcome === 'action_declined') setPending({ phase: 'declined', reply });
    if (outcome === 'action_expired') setPending({ phase: 'expired', reply });
  }

  async function runChat(intent: ChatIntent) {
    if (token === null || activeChat.current !== null) return;
    activeChat.current = intent.key;
    const expectedGeneration = generation.current;
    const controller = ownController();
    setChatIntent({ ...intent, phase: 'sending' });
    setChatStatus('正在等待服务端回复…');
    try {
      const activeSession = await ensureSession(
        token,
        expectedGeneration,
        controller.signal,
      );
      const result =
        intent.mode === 'stream'
          ? await streamChat(
              token,
              activeSession,
              intent.key,
              intent.message,
              controller.signal,
            )
          : await sendChat(
              token,
              activeSession,
              intent.key,
              intent.message,
              controller.signal,
            );
      if (generation.current !== expectedGeneration) return;
      setChat((current) => [
        ...current,
        {
          id: crypto.randomUUID(),
          speaker: 'citybuddy',
          text: result.reply,
          citations: 'citations' in result ? result.citations : undefined,
        },
      ]);
      applyOutcome(result.outcome, result.reply);
      setChatIntent(null);
    } catch (error) {
      if (generation.current !== expectedGeneration) return;
      if (error instanceof UnsupportedReceiptError) {
        setChatStatus(
          '收到当前 demo 不支持的动作结果，已停止读取；未建立任何执行状态。',
        );
        setChatIntent({ ...intent, phase: 'error' });
      } else if (
        error instanceof ApiFailure &&
        error.kind === 'conflict' &&
        isConfirmationMessage(intent.message)
      ) {
        setChatStatus('当前 demo 不支持成功确认；敏感动作未执行。');
        setChatIntent(null);
      } else {
        const message = handleFailure(error);
        if (message && generation.current === expectedGeneration) {
          setChatStatus(message);
          setChatIntent({ ...intent, phase: 'error' });
        }
      }
    } finally {
      if (activeChat.current === intent.key) activeChat.current = null;
      releaseController(controller);
    }
  }

  function submitChat(
    event: FormEvent<HTMLFormElement>,
    fixedMessage?: string,
  ) {
    event.preventDefault();
    const form = event.currentTarget;
    const message =
      fixedMessage ?? String(new FormData(form).get('message') ?? '').trim();
    if (
      !message ||
      message.length > 4000 ||
      chatIntent?.phase === 'sending' ||
      activeChat.current !== null
    )
      return;
    setChat((current) => [
      ...current,
      { id: crypto.randomUUID(), speaker: 'you', text: message },
    ]);
    if (!fixedMessage) form.reset();
    void runChat({
      key: crypto.randomUUID(),
      message,
      mode: streamMode ? 'stream' : 'json',
      phase: 'sending',
    });
  }

  const signedIn = token !== null && authPhase === 'signed-in';
  return (
    <div className="app-shell">
      <header className="site-header">
        <a className="brand" href="#top" aria-label="CityBuddy 首页">
          CityBuddy
        </a>
        <nav aria-label="页面导航">
          <a href="#shop">Shop</a>
          <a href="#support">Support</a>
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
          <p className="eyebrow">LOCAL COMMERCE · BOUNDED SUPPORT</p>
          <h1 id="hero-title">把交易真相与客服解释，放在各自可靠的边界内。</h1>
          <p>
            一个最小的 CityBuddy 作品演示：浏览公开商品、提交秒杀
            reservation，并与受限客服路径交谈。
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
                  活动编号与版本来自当前演示数据。提交后只展示服务端 reservation
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
                      {reservation.phase === 'indeterminate' && (
                        <p>轮询已达上限，状态仍未确定。</p>
                      )}
                    </div>
                  </div>
                )}
              </div>
            </section>

            <section
              id="support"
              className="panel support"
              aria-labelledby="support-title"
            >
              <div className="section-heading">
                <div>
                  <p className="eyebrow">SUPPORT</p>
                  <h2 id="support-title">受限客服</h2>
                </div>
                <label className="mode">
                  <input
                    type="checkbox"
                    checked={streamMode}
                    onChange={(event) => setStreamMode(event.target.checked)}
                  />
                  流式回复
                </label>
              </div>
              <div className="chat-log" aria-live="polite">
                {chat.length === 0 ? (
                  <p className="empty-chat">
                    发送一条消息开始当前登录周期的 support session。
                  </p>
                ) : (
                  chat.map((entry) => (
                    <article
                      key={entry.id}
                      className={`message ${entry.speaker}`}
                    >
                      <h3>{entry.speaker === 'you' ? '你' : 'CityBuddy'}</h3>
                      <p>{entry.text}</p>
                      {entry.citations && entry.citations.length > 0 && (
                        <ul className="citations">
                          {entry.citations.map((citation) => (
                            <li
                              key={`${citation.sourceId}:${citation.chunkId}`}
                            >
                              {citation.title} · {citation.docType} v
                              {citation.sourceVersion}
                            </li>
                          ))}
                        </ul>
                      )}
                    </article>
                  ))
                )}
              </div>
              {pending && (
                <aside
                  className={`pending-card ${pending.phase}`}
                  aria-labelledby="pending-title"
                >
                  <p className="eyebrow">BOUNDARY NOTICE</p>
                  <h3 id="pending-title">敏感动作等待处理</h3>
                  <p>{pending.reply}</p>
                  {pending.phase === 'pending' ? (
                    <>
                      <p>
                        动作尚未执行。可在普通输入中补充说明，或明确拒绝；当前
                        demo 不支持成功确认。过期状态以服务端结果为准。
                      </p>
                      <form onSubmit={(event) => submitChat(event, 'decline')}>
                        <button
                          type="submit"
                          className="danger"
                          disabled={chatIntent?.phase === 'sending'}
                        >
                          拒绝此动作
                        </button>
                      </form>
                    </>
                  ) : (
                    <p>
                      {pending.phase === 'declined'
                        ? '服务端已返回拒绝终态；动作未执行。'
                        : '服务端已返回过期终态；动作未执行。'}
                    </p>
                  )}
                </aside>
              )}
              <form className="chat-form" onSubmit={submitChat}>
                <label htmlFor="message">消息或澄清说明</label>
                <textarea
                  id="message"
                  name="message"
                  rows={3}
                  maxLength={4000}
                />
                <button
                  type="submit"
                  disabled={chatIntent?.phase === 'sending'}
                >
                  {chatIntent?.phase === 'sending'
                    ? '正在发送…'
                    : streamMode
                      ? '流式发送'
                      : '发送'}
                </button>
                {chatIntent?.phase === 'error' && (
                  <button
                    type="button"
                    className="quiet"
                    onClick={() => void runChat(chatIntent)}
                  >
                    使用原消息重试
                  </button>
                )}
              </form>
              {chatStatus && (
                <p
                  role={chatIntent?.phase === 'error' ? 'alert' : 'status'}
                  className={`notice ${chatIntent?.phase === 'error' ? 'error' : ''}`}
                >
                  {chatStatus}
                </p>
              )}
            </section>
          </div>
        )}
      </main>
      <footer>
        <p>Portfolio surface · server-owned identity and business truth</p>
      </footer>
    </div>
  );
}
