import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiFailure } from './api/client';
import type { Product, Reservation } from './api/decoders';
import { App } from './App';

vi.mock('./api/auth', () => ({ login: vi.fn() }));
vi.mock('./api/commerce', () => ({
  listProducts: vi.fn(),
  submitReservation: vi.fn(),
  pollReservation: vi.fn(),
}));

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

const unfulfilled: Reservation = {
  ...ordered,
  state: 'UNFULFILLED',
  decisionCode: 'ADMITTED',
  projectionVersion: 3,
  durableOrderCreated: false,
  orderId: null,
};

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
  mockedSubmitReservation.mockResolvedValue(ordered);
});

describe('CityBuddy portfolio surface', () => {
  it('shows the first 100 retail SKUs with a link to the full buyer catalog', async () => {
    mockedProducts.mockResolvedValue(
      Array.from({ length: 104 }, (_, index) => ({
        ...product,
        productId: `sku-${index}`,
        name: `Retail SKU ${index}`,
      })),
    );
    render(<App />);
    await signIn();
    expect(await screen.findByText('Retail SKU 99')).toBeVisible();
    expect(screen.getAllByRole('listitem')).toHaveLength(100);
    expect(screen.queryByText('Retail SKU 100')).not.toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: '安装 ShopMate 买家 App' }),
    ).toHaveAttribute(
      'href',
      'https://github.com/ChanTso/shopmate/blob/main/android/README.md',
    );
  });

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

  it('clears all authenticated views after a 401 and uses only the new token after login again', async () => {
    render(<App />);
    await signIn();
    await screen.findByText('Harbour tea');
    mockedProducts.mockRejectedValueOnce(new ApiFailure('unauthorized'));
    fireEvent.click(screen.getByRole('button', { name: '重新加载' }));
    expect(await screen.findByText('会话已过期，请重新登录。')).toBeVisible();
    expect(
      screen.queryByRole('heading', { name: '公开商品' }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText('Harbour tea')).not.toBeInTheDocument();

    mockedLogin.mockResolvedValueOnce({
      accessToken: 'new-memory-token',
      tokenType: 'Bearer',
      expiresIn: 900,
    });
    await signIn();
    expect(await screen.findByText('Harbour tea')).toBeVisible();
    expect(mockedProducts).toHaveBeenLastCalledWith(
      'new-memory-token',
      expect.any(AbortSignal),
    );
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
    mockedPollReservation.mockResolvedValue(unfulfilled);
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
    expect(screen.getByText('服务端状态：UNFULFILLED')).toBeVisible();
    expect(
      screen.getByText('准入已完成，但权威库存或订单约束未满足，未创建订单。'),
    ).toBeVisible();
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

  it('keeps the reservation form keyboard-focusable and submit-complete', async () => {
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
  });

  it('links to the single buyer workspace without transferring a login token', async () => {
    render(<App />);
    const link = screen.getByRole('link', { name: '安装 ShopMate 买家 App' });
    expect(link).toHaveAttribute(
      'href',
      'https://github.com/ChanTso/shopmate/blob/main/android/README.md',
    );
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    link.focus();
    expect(link).toHaveFocus();
    await signIn();
    expect(link).toHaveAttribute(
      'href',
      'https://github.com/ChanTso/shopmate/blob/main/android/README.md',
    );
    expect(screen.queryByLabelText('消息或澄清说明')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: '受限客服' }),
    ).not.toBeInTheDocument();
    expect(document.querySelector('iframe')).toBeNull();
    for (const anchor of screen.getAllByRole('link')) {
      expect(anchor.getAttribute('href')).not.toContain('memory-token');
    }
    expect(screen.getByText(/默认零售部署未启用秒杀/)).toBeVisible();
  });

  it('aborts an active reservation and fences its late result on logout', async () => {
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
    fireEvent.submit(screen.getByLabelText('活动编号').closest('form')!);
    const signal = mockedSubmitReservation.mock.calls[0][4];
    expect(signal.aborted).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '退出登录' }));
    expect(signal.aborted).toBe(true);
    await act(async () => resolveReservation(ordered));
    expect(screen.getByRole('heading', { name: '登录本地演示' })).toBeVisible();
    expect(screen.queryByText('服务端状态：ORDERED')).not.toBeInTheDocument();
  });
});
