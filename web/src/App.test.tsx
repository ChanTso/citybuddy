import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiFailure } from './api/client';
import type { ChatResponse, Product, Reservation } from './api/decoders';
import { App } from './App';

vi.mock('./api/auth', () => ({ login: vi.fn() }));
vi.mock('./api/commerce', () => ({
  listProducts: vi.fn(),
  submitReservation: vi.fn(),
  pollReservation: vi.fn(),
}));
vi.mock('./api/agent', () => ({
  createSupportSession: vi.fn(),
  sendChat: vi.fn(),
  streamChat: vi.fn(),
}));

import { createSupportSession, sendChat, streamChat } from './api/agent';
import { login } from './api/auth';
import {
  listProducts,
  pollReservation,
  submitReservation,
} from './api/commerce';

const mockedLogin = vi.mocked(login);
const mockedProducts = vi.mocked(listProducts);
const mockedSubmitReservation = vi.mocked(submitReservation);
const mockedPollReservation = vi.mocked(pollReservation);
const mockedCreateSession = vi.mocked(createSupportSession);
const mockedSendChat = vi.mocked(sendChat);
const mockedStreamChat = vi.mocked(streamChat);
const UUID = '00000000-0000-0000-0000-000000000001';

const product: Product = {
  productId: 'tea-1',
  name: 'Harbour tea',
  description: 'A published local blend.',
  priceMinor: 1250,
  currency: 'AUD',
  stockQuantity: 4,
  available: true,
  publicationVersion: 3,
};
const ordered: Reservation = {
  reservationId: UUID,
  activityId: 'tea-drop',
  quantity: 1,
  activityProjectionVersion: 2,
  state: 'ORDERED',
  decisionCode: 'ADMITTED',
  projectionVersion: 3,
  replay: false,
  durableOrderCreated: true,
  orderId: '00000000-0000-0000-0000-000000000002',
};

function response(
  outcome: ChatResponse['outcome'],
  reply: string,
): ChatResponse {
  return {
    conversationId: UUID,
    traceId: UUID,
    turnId: UUID,
    reply,
    outcome,
    receiptId: null,
    citations: [],
  };
}

async function signIn() {
  fireEvent.change(screen.getByLabelText('登录名'), {
    target: { value: 'demo' },
  });
  fireEvent.change(screen.getByLabelText('密码'), {
    target: { value: 'secret' },
  });
  fireEvent.click(screen.getByRole('button', { name: '登录' }));
  await screen.findByRole('heading', { name: '公开商品' });
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedLogin.mockResolvedValue({
    accessToken: 'memory-token',
    tokenType: 'Bearer',
    expiresIn: 900,
  });
  mockedProducts.mockResolvedValue([product]);
  mockedCreateSession.mockResolvedValue({ sessionId: 'server-session' });
  mockedSendChat.mockResolvedValue(response('completed', 'Bounded answer.'));
  mockedStreamChat.mockResolvedValue({
    outcome: 'completed',
    reply: 'Streamed answer.',
    receiptId: null,
  });
  mockedSubmitReservation.mockResolvedValue(ordered);
});

describe('CityBuddy portfolio surface', () => {
  it('logs in, loads published products, stays keyboard-addressable, and clears user state on logout', async () => {
    const storageSpy = vi.spyOn(Storage.prototype, 'setItem');
    const logSpy = vi.spyOn(console, 'log');
    const warnSpy = vi.spyOn(console, 'warn');
    const errorSpy = vi.spyOn(console, 'error');
    const initialUrl = window.location.href;
    const initialCookie = document.cookie;
    render(<App />);

    expect(screen.getByRole('banner')).toBeVisible();
    expect(screen.getByRole('main')).toBeVisible();
    await signIn();

    expect(await screen.findByText('Harbour tea')).toBeVisible();
    expect(screen.getByText('AUD 12.50')).toBeVisible();
    expect(mockedProducts).toHaveBeenCalledWith(
      'memory-token',
      expect.any(AbortSignal),
    );
    expect(storageSpy).not.toHaveBeenCalled();
    expect(logSpy).not.toHaveBeenCalled();
    expect(warnSpy).not.toHaveBeenCalled();
    expect(errorSpy).not.toHaveBeenCalled();
    expect(window.location.href).toBe(initialUrl);
    expect(document.cookie).toBe(initialCookie);

    fireEvent.click(screen.getByRole('button', { name: '退出登录' }));
    expect(screen.getByRole('heading', { name: '登录本地演示' })).toBeVisible();
    expect(screen.queryByText('Harbour tea')).not.toBeInTheDocument();
    expect(screen.queryByText('memory-token')).not.toBeInTheDocument();
    storageSpy.mockRestore();
    logSpy.mockRestore();
    warnSpy.mockRestore();
    errorSpy.mockRestore();
  });

  it.each([
    ['forbidden', '当前账号无权执行此操作。'],
    ['malformed', '服务返回了无法安全读取的数据。'],
    ['dependency', '依赖服务暂时不可用，请稍后重试。'],
  ] as const)(
    'does not present a %s product request as an empty catalog',
    async (kind, message) => {
      mockedProducts.mockRejectedValue(new ApiFailure(kind));
      render(<App />);
      await signIn();

      expect(await screen.findByRole('alert')).toHaveTextContent(message);
      expect(
        screen.queryByText('当前没有已发布商品。'),
      ).not.toBeInTheDocument();
    },
  );

  it('shows an empty product state only for a decoded successful response', async () => {
    mockedProducts.mockResolvedValue([]);
    render(<App />);
    await signIn();

    expect(await screen.findByText('当前没有已发布商品。')).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('aborts in-flight private work and fences a stale response on logout', async () => {
    let resolveProducts!: (value: Product[]) => void;
    mockedProducts.mockImplementation(
      (_token, signal) =>
        new Promise((resolve) => {
          expect(signal.aborted).toBe(false);
          resolveProducts = resolve;
        }),
    );
    render(<App />);
    await signIn();
    expect(screen.getByText('正在加载商品…')).toBeVisible();
    const privateSignal = mockedProducts.mock.calls[0][1];

    fireEvent.click(screen.getByRole('button', { name: '退出登录' }));
    expect(privateSignal.aborted).toBe(true);
    await act(async () => resolveProducts([product]));

    expect(screen.getByRole('heading', { name: '登录本地演示' })).toBeVisible();
    expect(screen.queryByText('Harbour tea')).not.toBeInTheDocument();
  });

  it('clears all authenticated views after a 401 and creates a fresh session after login again', async () => {
    mockedSendChat.mockRejectedValueOnce(new ApiFailure('unauthorized'));
    render(<App />);
    await signIn();
    fireEvent.change(screen.getByLabelText('消息或澄清说明'), {
      target: { value: 'hello' },
    });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    expect(await screen.findByText('会话已过期，请重新登录。')).toBeVisible();
    expect(
      screen.queryByRole('heading', { name: '公开商品' }),
    ).not.toBeInTheDocument();

    await signIn();
    fireEvent.change(screen.getByLabelText('消息或澄清说明'), {
      target: { value: 'again' },
    });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    await screen.findByText('Bounded answer.');
    expect(mockedCreateSession).toHaveBeenCalledTimes(2);
  });

  it('uses one reservation mutation and reuses its idempotency key on retry', async () => {
    mockedSubmitReservation
      .mockRejectedValueOnce(new ApiFailure('network'))
      .mockResolvedValueOnce(ordered);
    render(<App />);
    await signIn();
    fireEvent.change(screen.getByLabelText('活动编号'), {
      target: { value: 'tea-drop' },
    });
    fireEvent.click(screen.getByRole('button', { name: '提交 reservation' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '网络连接不可用',
    );
    const firstKey = mockedSubmitReservation.mock.calls[0][2];
    fireEvent.click(screen.getByRole('button', { name: '使用原 intent 重试' }));
    expect(await screen.findByText('服务端状态：ORDERED')).toBeVisible();
    expect(mockedSubmitReservation).toHaveBeenCalledTimes(2);
    expect(mockedSubmitReservation.mock.calls[1][2]).toBe(firstKey);
  });

  it('disables duplicate reservation submission while the intent is active', async () => {
    let resolveReservation!: (value: Reservation) => void;
    mockedSubmitReservation.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveReservation = resolve;
        }),
    );
    render(<App />);
    await signIn();
    fireEvent.change(screen.getByLabelText('活动编号'), {
      target: { value: 'tea-drop' },
    });
    const submit = screen.getByRole('button', { name: '提交 reservation' });
    fireEvent.click(submit);

    await waitFor(() => expect(submit).toBeDisabled());
    fireEvent.click(submit);
    expect(mockedSubmitReservation).toHaveBeenCalledTimes(1);
    await act(async () => resolveReservation(ordered));
  });

  it('synchronously fences a duplicate reservation submit without aborting the first intent', async () => {
    let resolveReservation!: (value: Reservation) => void;
    mockedSubmitReservation.mockImplementation(
      (_token, _activity, _key, _body, signal) =>
        new Promise((resolve, reject) => {
          resolveReservation = resolve;
          signal.addEventListener(
            'abort',
            () => reject(new DOMException('Aborted', 'AbortError')),
            { once: true },
          );
        }),
    );
    render(<App />);
    await signIn();
    fireEvent.change(screen.getByLabelText('活动编号'), {
      target: { value: 'tea-drop' },
    });
    fireEvent.change(screen.getByLabelText('活动版本'), {
      target: { value: '2' },
    });
    const form = screen.getByLabelText('活动编号').closest('form');
    expect(form).not.toBeNull();
    fireEvent.submit(form!);
    fireEvent.submit(form!);

    expect(mockedSubmitReservation).toHaveBeenCalledTimes(1);
    expect(mockedSubmitReservation.mock.calls[0][4].aborted).toBe(false);
    await act(async () => resolveReservation(ordered));
    expect(await screen.findByText('服务端状态：ORDERED')).toBeVisible();
  });

  it('aborts an active reservation mutation when the component unmounts', async () => {
    let reservationSignal!: AbortSignal;
    mockedSubmitReservation.mockImplementation(
      (_token, _activity, _key, _body, signal) =>
        new Promise((_resolve, reject) => {
          reservationSignal = signal;
          signal.addEventListener(
            'abort',
            () => reject(new DOMException('Aborted', 'AbortError')),
            { once: true },
          );
        }),
    );
    const view = render(<App />);
    await signIn();
    fireEvent.change(screen.getByLabelText('活动编号'), {
      target: { value: 'tea-drop' },
    });
    fireEvent.change(screen.getByLabelText('活动版本'), {
      target: { value: '2' },
    });
    fireEvent.submit(screen.getByLabelText('活动编号').closest('form')!);
    await waitFor(() => expect(mockedSubmitReservation).toHaveBeenCalledOnce());

    expect(reservationSignal.aborted).toBe(false);
    view.unmount();
    expect(reservationSignal.aborted).toBe(true);
  });

  it('polls without overlap and stops at the first server terminal', async () => {
    mockedSubmitReservation.mockResolvedValue({
      ...ordered,
      state: 'ADMITTED',
      projectionVersion: 2,
      durableOrderCreated: false,
      orderId: null,
    });
    mockedPollReservation.mockResolvedValue(ordered);
    render(<App />);
    await signIn();
    vi.useFakeTimers();
    fireEvent.change(screen.getByLabelText('活动编号'), {
      target: { value: 'tea-drop' },
    });
    fireEvent.click(screen.getByRole('button', { name: '提交 reservation' }));
    await act(async () => {
      await Promise.resolve();
      await vi.advanceTimersByTimeAsync(750);
    });
    expect(mockedPollReservation).toHaveBeenCalledTimes(1);
    expect(screen.getByText('服务端状态：ORDERED')).toBeVisible();
    vi.useRealTimers();
  });

  it('stops bounded polling as indeterminate without inventing a terminal state', async () => {
    const admitted: Reservation = {
      ...ordered,
      state: 'ADMITTED',
      projectionVersion: 2,
      durableOrderCreated: false,
      orderId: null,
    };
    mockedSubmitReservation.mockResolvedValue(admitted);
    mockedPollReservation.mockResolvedValue(admitted);
    render(<App />);
    await signIn();
    vi.useFakeTimers();
    fireEvent.change(screen.getByLabelText('活动编号'), {
      target: { value: 'tea-drop' },
    });
    fireEvent.click(screen.getByRole('button', { name: '提交 reservation' }));
    await act(async () => {
      await Promise.resolve();
      await vi.advanceTimersByTimeAsync(8 * 750);
    });

    expect(mockedPollReservation).toHaveBeenCalledTimes(8);
    expect(screen.getByText('服务端状态：ADMITTED')).toBeVisible();
    expect(screen.getByText('轮询已达上限，状态仍未确定。')).toBeVisible();
    vi.useRealTimers();
  });

  it('renders PendingAction only from the public outcome and declines only after the server terminal', async () => {
    let resolveDecline!: (value: ChatResponse) => void;
    mockedSendChat
      .mockResolvedValueOnce(
        response('action_pending', 'Opaque server reply: orderId-looking-text'),
      )
      .mockReturnValueOnce(
        new Promise((resolve) => {
          resolveDecline = resolve;
        }),
      );
    render(<App />);
    await signIn();
    fireEvent.change(screen.getByLabelText('消息或澄清说明'), {
      target: { value: 'prepare refund' },
    });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));

    const card = await screen.findByRole('heading', {
      name: '敏感动作等待处理',
    });
    expect(card).toBeVisible();
    expect(
      screen.getAllByText('Opaque server reply: orderId-looking-text'),
    ).toHaveLength(2);
    expect(screen.queryByText(/pendingActionId/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/deadline/i)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '拒绝此动作' }));
    expect(
      screen.queryByText('服务端已返回拒绝终态；动作未执行。'),
    ).not.toBeInTheDocument();
    await waitFor(() => expect(mockedSendChat).toHaveBeenCalledTimes(2));
    expect(mockedSendChat.mock.calls[1][3]).toBe('decline');
    resolveDecline(
      response('action_declined', 'Declined by the server and not executed.'),
    );
    expect(
      await screen.findByText('服务端已返回拒绝终态；动作未执行。'),
    ).toBeVisible();
    expect(
      screen.queryByRole('button', { name: /确认/ }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText(/receipt/i)).not.toBeInTheDocument();
  });

  it('keeps clarification pending, accepts server expiry, and reports a confirmation conflict', async () => {
    mockedSendChat
      .mockResolvedValueOnce(response('action_pending', 'Waiting.'))
      .mockResolvedValueOnce(
        response('action_clarification', 'More detail requested.'),
      )
      .mockResolvedValueOnce(
        response('action_expired', 'Expired and not executed.'),
      )
      .mockRejectedValueOnce(new ApiFailure('conflict'));
    render(<App />);
    await signIn();
    const input = screen.getByLabelText('消息或澄清说明');
    for (const message of [
      'prepare',
      'different amount',
      'check expiry',
      '确认退款',
    ]) {
      fireEvent.change(input, { target: { value: message } });
      fireEvent.click(screen.getByRole('button', { name: '发送' }));
      await waitFor(() =>
        expect(mockedSendChat).toHaveBeenCalledTimes(
          ['prepare', 'different amount', 'check expiry', '确认退款'].indexOf(
            message,
          ) + 1,
        ),
      );
      await waitFor(() =>
        expect(screen.getByRole('button', { name: '发送' })).toBeEnabled(),
      );
    }
    expect(
      await screen.findByText('服务端已返回过期终态；动作未执行。'),
    ).toBeVisible();
    expect(
      await screen.findByText(
        '确认与另一次处理冲突；请稍后重试，动作未重复执行。',
      ),
    ).toBeVisible();
  });

  it('confirms a prepared action from the notice and renders its receipt', async () => {
    const receiptId = '00000000-0000-0000-0000-0000000001a1';
    mockedSendChat
      .mockResolvedValueOnce(response('action_pending', 'Waiting.'))
      .mockResolvedValueOnce({
        ...response('action_completed', '退款申请已提交并记录。'),
        receiptId,
      });
    render(<App />);
    await signIn();
    const input = screen.getByLabelText('消息或澄清说明');
    fireEvent.change(input, { target: { value: 'prepare refund' } });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));

    fireEvent.click(await screen.findByRole('button', { name: '确认此动作' }));

    expect(await screen.findByText('敏感动作已提交')).toBeVisible();
    expect(screen.getByText(receiptId)).toBeVisible();
    expect(mockedSendChat.mock.calls[1][3]).toBe('confirm');
    expect(
      screen.queryByRole('button', { name: '拒绝此动作' }),
    ).not.toBeInTheDocument();
  });

  it('uses exactly one selected chat endpoint and reuses the owned session', async () => {
    render(<App />);
    await signIn();
    fireEvent.click(screen.getByLabelText('流式回复'));
    const input = screen.getByLabelText('消息或澄清说明');
    fireEvent.change(input, { target: { value: 'stream this' } });
    fireEvent.click(screen.getByRole('button', { name: '流式发送' }));
    expect(await screen.findByText('Streamed answer.')).toBeVisible();
    expect(mockedStreamChat).toHaveBeenCalledTimes(1);
    expect(mockedSendChat).not.toHaveBeenCalled();
    expect(mockedCreateSession).toHaveBeenCalledTimes(1);

    expect(
      within(screen.getByRole('banner')).getByRole('link', {
        name: 'CityBuddy 首页',
      }),
    ).toBeVisible();
  });

  it.each([
    ['budget_exhausted', '本次回复预算已用尽'],
    ['provider_denied', '回复服务暂时不可用'],
    ['retrieval_denied', '没有足够的公开资料来回答'],
  ] as const)(
    'renders the bounded %s public outcome',
    async (outcome, label) => {
      mockedSendChat.mockResolvedValue(response(outcome, 'Public reply.'));
      render(<App />);
      await signIn();
      fireEvent.change(screen.getByLabelText('消息或澄清说明'), {
        target: { value: 'bounded outcome' },
      });
      fireEvent.click(screen.getByRole('button', { name: '发送' }));

      expect(await screen.findByText(label)).toBeVisible();
      expect(screen.getByText('Public reply.')).toBeVisible();
    },
  );

  it('synchronously fences duplicate support submissions before state rendering', async () => {
    let resolveChat!: (value: ChatResponse) => void;
    mockedSendChat.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveChat = resolve;
        }),
    );
    render(<App />);
    await signIn();
    const input = screen.getByLabelText('消息或澄清说明');
    const form = input.closest('form');
    expect(form).not.toBeNull();
    fireEvent.change(input, { target: { value: 'one bounded turn' } });
    fireEvent.submit(form!);
    fireEvent.submit(form!);

    await waitFor(() => expect(mockedSendChat).toHaveBeenCalledTimes(1));
    await act(async () => resolveChat(response('completed', 'Done.')));
    expect(await screen.findByText('Done.')).toBeVisible();
  });

  it('reuses a failed chat intent key and gives a new message a new key', async () => {
    mockedSendChat
      .mockRejectedValueOnce(new ApiFailure('network'))
      .mockResolvedValue(response('completed', 'Recovered.'));
    render(<App />);
    await signIn();
    const input = screen.getByLabelText('消息或澄清说明');
    fireEvent.change(input, { target: { value: 'retry me' } });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    await screen.findByText('网络连接不可用，请稍后重试。');
    const firstKey = mockedSendChat.mock.calls[0][2];

    fireEvent.click(screen.getByRole('button', { name: '使用原消息重试' }));
    await screen.findByText('Recovered.');
    expect(mockedSendChat.mock.calls[1][2]).toBe(firstKey);

    fireEvent.change(input, { target: { value: 'new message' } });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    await waitFor(() => expect(mockedSendChat).toHaveBeenCalledTimes(3));
    expect(mockedSendChat.mock.calls[2][2]).not.toBe(firstKey);
  });

  it('shows the committed receipt when a confirmed action streams back', async () => {
    const receiptId = '00000000-0000-0000-0000-0000000001a1';
    mockedStreamChat.mockResolvedValue({
      outcome: 'action_completed',
      reply: '退款申请已提交并记录。',
      receiptId,
    });
    render(<App />);
    await signIn();
    fireEvent.click(screen.getByLabelText('流式回复'));
    fireEvent.change(screen.getByLabelText('消息或澄清说明'), {
      target: { value: 'confirm' },
    });
    fireEvent.click(screen.getByRole('button', { name: '流式发送' }));

    expect(await screen.findByText('敏感动作已提交')).toBeVisible();
    expect(screen.getByText(receiptId)).toBeVisible();
    expect(
      screen.queryByRole('button', { name: '确认此动作' }),
    ).not.toBeInTheDocument();
  });

  it('aborts an active chat stream and clears its session on logout', async () => {
    let streamSignal!: AbortSignal;
    mockedStreamChat.mockImplementation(
      (_token, _session, _key, _message, signal) =>
        new Promise((_resolve, reject) => {
          streamSignal = signal;
          signal.addEventListener(
            'abort',
            () => reject(new DOMException('Aborted', 'AbortError')),
            { once: true },
          );
        }),
    );
    render(<App />);
    await signIn();
    fireEvent.click(screen.getByLabelText('流式回复'));
    fireEvent.change(screen.getByLabelText('消息或澄清说明'), {
      target: { value: 'stream until logout' },
    });
    fireEvent.submit(screen.getByLabelText('消息或澄清说明').closest('form')!);
    await waitFor(() => expect(mockedStreamChat).toHaveBeenCalledOnce());

    expect(streamSignal.aborted).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '退出登录' }));
    expect(streamSignal.aborted).toBe(true);
    expect(screen.getByRole('heading', { name: '登录本地演示' })).toBeVisible();
  });

  it('keeps reservation, chat, and decline forms keyboard-focusable and submit-complete', async () => {
    mockedSendChat
      .mockResolvedValueOnce(response('action_pending', 'Waiting.'))
      .mockResolvedValueOnce(response('action_declined', 'Declined.'));
    render(<App />);
    await signIn();

    const activity = screen.getByLabelText('活动编号');
    const activityVersion = screen.getByLabelText('活动版本');
    fireEvent.change(activity, { target: { value: 'tea-drop' } });
    fireEvent.change(activityVersion, { target: { value: '2' } });
    activity.focus();
    expect(activity).toHaveFocus();
    expect(activity.tabIndex).toBe(0);
    fireEvent.keyDown(activity, { key: 'Enter', code: 'Enter' });
    fireEvent.submit(activity.closest('form')!);
    expect(await screen.findByText('服务端状态：ORDERED')).toBeVisible();

    const message = screen.getByLabelText('消息或澄清说明');
    fireEvent.change(message, { target: { value: 'prepare' } });
    const send = screen.getByRole('button', { name: '发送' });
    send.focus();
    expect(send).toHaveFocus();
    expect(send.tabIndex).toBe(0);
    fireEvent.keyDown(send, { key: 'Enter', code: 'Enter' });
    fireEvent.submit(message.closest('form')!);
    const decline = await screen.findByRole('button', { name: '拒绝此动作' });

    decline.focus();
    expect(decline).toHaveFocus();
    expect(decline.tabIndex).toBe(0);
    fireEvent.keyDown(decline, { key: 'Enter', code: 'Enter' });
    fireEvent.submit(decline.closest('form')!);
    expect(
      await screen.findByText('服务端已返回拒绝终态；动作未执行。'),
    ).toBeVisible();
    expect(mockedSendChat.mock.calls[1][3]).toBe('decline');
  });
});
