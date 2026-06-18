import { Assets, type Texture } from 'pixi.js';

import { SYMBOLS } from '@/game/math/aztec-fire';

/**
 * Loads the placeholder symbol sprites (one per `symbolId` declared in the
 * math mirror) via `PIXI.Assets.load`. Returns a `Map<number, Texture>`.
 *
 * Idempotent — subsequent calls return the cached map. Pixi's own asset
 * cache also de-dupes downloads, so calling `load()` repeatedly is safe.
 */
const ASSET_BASE = '/assets/symbols';

function urlFor(symbolId: number): string {
  return `${ASSET_BASE}/${symbolId}.png`;
}

let cache: Map<number, Texture> | null = null;
let inflight: Promise<Map<number, Texture>> | null = null;

export async function loadSymbolTextures(): Promise<Map<number, Texture>> {
  if (cache) return cache;
  if (inflight) return inflight;

  const ids = SYMBOLS.map((s) => s.id);
  inflight = (async () => {
    const urls = ids.map(urlFor);
    const loaded = (await Assets.load(urls)) as Record<string, Texture>;
    const map = new Map<number, Texture>();
    ids.forEach((id, i) => {
      const url = urls[i];
      if (url === undefined) return;
      const tex = loaded[url];
      if (tex) map.set(id, tex);
    });
    cache = map;
    inflight = null;
    return map;
  })();

  return inflight;
}

/** Test-only: drop the in-memory texture cache. */
export function _resetSymbolTextureCacheForTests(): void {
  cache = null;
  inflight = null;
}
