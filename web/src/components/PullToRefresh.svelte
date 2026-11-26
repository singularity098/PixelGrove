<script lang="ts">
  import Icon from './Icon.svelte';

  /**
   * Pull down at the top of the page to refresh (touch screens). The browser's own
   * pull-to-reload is turned off while this is on the page, so a pull rescans instead
   * of reloading the whole app.
   */
  let { onrefresh }: { onrefresh: () => Promise<unknown> } = $props();

  const TRIGGER = 70;
  const MAX = 110;
  let pull = $state(0);
  let refreshing = $state(false);
  let start: { x: number; y: number } | null = null;
  let pulling = false;

  // Something modal (the viewer) has the page locked.
  const locked = () => document.body.style.overflow === 'hidden' || !!document.querySelector('dialog[open]');

  $effect(() => {
    const root = document.documentElement;
    const previous = root.style.overscrollBehaviorY;
    root.style.overscrollBehaviorY = 'contain';

    function touchstart(event: TouchEvent) {
      if (refreshing || event.touches.length !== 1 || window.scrollY > 0 || locked()) {
        start = null;
        return;
      }
      start = { x: event.touches[0].clientX, y: event.touches[0].clientY };
      pulling = false;
    }

    function touchmove(event: TouchEvent) {
      if (!start) return;
      const dy = event.touches[0].clientY - start.y;
      const dx = event.touches[0].clientX - start.x;
      if (!pulling) {
        // Only a mostly-downward drag from the very top counts.
        if (dy < 8 || Math.abs(dx) > dy || window.scrollY > 0) {
          if (Math.abs(dx) > 8 || dy < -8) start = null;
          return;
        }
        pulling = true;
      }
      // Resistance: the indicator moves slower than the finger.
      pull = Math.min(MAX, dy * 0.5);
      if (event.cancelable) event.preventDefault();
    }

    async function touchend() {
      if (!start) return;
      start = null;
      if (!pulling) return;
      pulling = false;
      if (pull < TRIGGER) {
        pull = 0;
        return;
      }
      refreshing = true;
      pull = TRIGGER * 0.8;
      try {
        await onrefresh();
      } finally {
        refreshing = false;
        pull = 0;
      }
    }

    window.addEventListener('touchstart', touchstart, { passive: true });
    window.addEventListener('touchmove', touchmove, { passive: false });
    window.addEventListener('touchend', touchend);
    window.addEventListener('touchcancel', touchend);
    return () => {
      root.style.overscrollBehaviorY = previous;
      window.removeEventListener('touchstart', touchstart);
      window.removeEventListener('touchmove', touchmove);
      window.removeEventListener('touchend', touchend);
      window.removeEventListener('touchcancel', touchend);
    };
  });
</script>

{#if pull > 0 || refreshing}
  <div
    class="indicator"
    class:ready={pull >= TRIGGER || refreshing}
    class:spinning={refreshing}
    style="transform: translate(-50%, {pull - 44}px)"
    role="status"
    aria-label={refreshing ? 'Refreshing' : 'Pull to refresh'}>
    <span style="transform: rotate({refreshing ? 0 : pull * 3}deg)"><Icon name="refresh" size={20} /></span>
  </div>
{/if}

<style>
  .indicator {
    position: fixed;
    top: calc(env(safe-area-inset-top) + 56px);
    left: 50%;
    z-index: 40;
    display: grid;
    place-items: center;
    width: 40px;
    height: 40px;
    border-radius: 999px;
    border: 1px solid var(--border);
    background: var(--surface);
    color: var(--text-muted);
    box-shadow: 0 4px 14px rgb(0 0 0 / 0.3);
    pointer-events: none;
  }

  .indicator span {
    display: grid;
  }

  .ready {
    color: var(--accent);
  }

  .spinning span {
    animation: spin 0.8s linear infinite;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
</style>
