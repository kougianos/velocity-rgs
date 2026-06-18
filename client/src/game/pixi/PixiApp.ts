import { Application, type ApplicationOptions } from 'pixi.js';

/**
 * Thin async wrapper around `PIXI.Application` for v8. The renderer is
 * created via `app.init(...)` because Pixi v8 made init async; subsequent
 * accessors (`stage`, `ticker`, `renderer`) become available only after
 * `init` resolves.
 *
 * Idempotent: calling `init` on an already-initialised instance is a no-op.
 */
export interface PixiAppInitOptions {
  canvas: HTMLCanvasElement;
  width: number;
  height: number;
  backgroundColor?: number;
  backgroundAlpha?: number;
  resolution?: number;
  antialias?: boolean;
}

export class PixiApp {
  readonly app: Application;
  private initialised = false;

  constructor() {
    this.app = new Application();
  }

  async init(options: PixiAppInitOptions): Promise<void> {
    if (this.initialised) return;

    const appOptions: Partial<ApplicationOptions> = {
      canvas: options.canvas,
      width: options.width,
      height: options.height,
      backgroundColor: options.backgroundColor ?? 0x0d1117,
      backgroundAlpha: options.backgroundAlpha ?? 1,
      resolution: options.resolution ?? (globalThis.devicePixelRatio || 1),
      autoDensity: true,
      antialias: options.antialias ?? true,
    };

    await this.app.init(appOptions);
    this.initialised = true;
  }

  get isInitialised(): boolean {
    return this.initialised;
  }

  resize(width: number, height: number): void {
    if (!this.initialised) return;
    this.app.renderer.resize(width, height);
  }

  destroy(): void {
    if (!this.initialised) return;
    this.app.destroy(false, { children: true, texture: false });
    this.initialised = false;
  }
}
