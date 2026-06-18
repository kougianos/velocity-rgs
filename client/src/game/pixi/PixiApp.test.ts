import { describe, expect, it, vi } from 'vitest';

import { PixiApp } from './PixiApp';

describe('PixiApp', () => {
  it('init is idempotent: a second call is a no-op', async () => {
    const app = new PixiApp();
    const fakeCanvas = document.createElement('canvas');
    const initSpy = vi
      .spyOn(app.app, 'init')
      .mockImplementation(async () => Promise.resolve());

    await app.init({ canvas: fakeCanvas, width: 100, height: 100 });
    await app.init({ canvas: fakeCanvas, width: 100, height: 100 });

    expect(initSpy).toHaveBeenCalledTimes(1);
    expect(app.isInitialised).toBe(true);
  });

  it('destroy resets initialised flag', async () => {
    const app = new PixiApp();
    const fakeCanvas = document.createElement('canvas');
    vi.spyOn(app.app, 'init').mockResolvedValue();
    const destroySpy = vi.spyOn(app.app, 'destroy').mockImplementation(() => {});

    await app.init({ canvas: fakeCanvas, width: 50, height: 50 });
    app.destroy();

    expect(destroySpy).toHaveBeenCalledTimes(1);
    expect(app.isInitialised).toBe(false);
  });

  it('destroy before init is a no-op', () => {
    const app = new PixiApp();
    const destroySpy = vi.spyOn(app.app, 'destroy').mockImplementation(() => {});
    app.destroy();
    expect(destroySpy).not.toHaveBeenCalled();
  });
});
