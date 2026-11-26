<script lang="ts">
  import { formatDuration, libraryUrls, type MediaItem, type MediaUrls } from '../lib/media.svelte';
  import type { Selection } from '../lib/selection.svelte';
  import Icon from './Icon.svelte';
  import Thumb from './Thumb.svelte';

  let {
    items,
    onopen,
    urls = libraryUrls,
    selection,
  }: {
    items: MediaItem[];
    onopen: (index: number) => void;
    urls?: MediaUrls;
    /** Lets photos be picked (Select button, or a long press on touch screens). */
    selection?: Selection;
  } = $props();

  const LONG_PRESS_MS = 450;
  let pressTimer: ReturnType<typeof setTimeout> | undefined;
  let pressStart: { x: number; y: number } | null = null;
  // A long press selects; the click that follows it mustn't toggle it straight back.
  let swallowClick = false;

  function click(event: MouseEvent, index: number) {
    if (swallowClick) {
      swallowClick = false;
      return;
    }
    const id = items[index].id;
    if (selection?.active) {
      if (event.shiftKey) selection.extend(items, id);
      else selection.toggle(id);
    } else if (selection && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      selection.start(id);
    } else {
      onopen(index);
    }
  }

  function pointerdown(event: PointerEvent, index: number) {
    if (!selection || event.pointerType !== 'touch') return;
    pressStart = { x: event.clientX, y: event.clientY };
    clearTimeout(pressTimer);
    pressTimer = setTimeout(() => {
      pressStart = null;
      swallowClick = true;
      const id = items[index].id;
      if (selection!.active) selection!.extend(items, id);
      else selection!.start(id);
      navigator.vibrate?.(20);
    }, LONG_PRESS_MS);
  }

  function pointermove(event: PointerEvent) {
    // Scrolling isn't a long press.
    if (pressStart && Math.hypot(event.clientX - pressStart.x, event.clientY - pressStart.y) > 10) cancelPress();
  }

  function cancelPress() {
    clearTimeout(pressTimer);
    pressStart = null;
  }
</script>

<div class="grid" class:selecting={selection?.active}>
  {#each items as item, index (item.id)}
    {@const picked = selection?.has(item.id) ?? false}
    <button
      class="tile"
      class:hidden-item={item.hidden}
      class:picked
      onclick={(e) => click(e, index)}
      onpointerdown={(e) => pointerdown(e, index)}
      onpointermove={pointermove}
      onpointerup={cancelPress}
      onpointercancel={cancelPress}
      oncontextmenu={(e) => selection && (swallowClick || pressStart) && e.preventDefault()}
      aria-pressed={selection?.active ? picked : undefined}
      aria-label="{selection?.active ? (picked ? 'Deselect' : 'Select') : 'Open'} {item.name}{item.hidden ? ' (hidden)' : ''}">
      {#if item.kind === 'video'}
        <Thumb media={item} {urls} />
        <span class="badge"><Icon name="play" size={12} />{formatDuration(item.duration_ms)}</span>
      {:else}
        <img src={urls.thumb(item)} alt="" loading="lazy" decoding="async" />
      {/if}
      {#if item.hidden}<span class="hidden-badge" title="Hidden"><Icon name="eyeOff" size={14} /></span>{/if}
      {#if selection?.active}
        <span class="check" aria-hidden="true">{#if picked}<Icon name="check" size={14} />{/if}</span>
      {/if}
    </button>
  {/each}
</div>

<style>
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 4px;
  }

  .tile {
    position: relative;
    aspect-ratio: 1;
    padding: 0;
    border: 0;
    border-radius: 4px;
    overflow: hidden;
    background: var(--surface-2);
  }

  .tile:focus-visible {
    outline: 3px solid var(--accent);
    outline-offset: -3px;
  }

  .tile :global(img),
  .tile :global(.placeholder) {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
    pointer-events: none;
    transition: transform 0.15s ease;
  }

  .tile:hover :global(img) {
    transform: scale(1.03);
  }

  .tile {
    -webkit-touch-callout: none;
    user-select: none;
  }

  .selecting .tile:hover :global(img) {
    transform: none;
  }

  .tile.picked :global(img),
  .tile.picked :global(.placeholder) {
    transform: scale(0.88);
    border-radius: 6px;
  }

  .tile.picked {
    background: color-mix(in srgb, var(--accent) 25%, var(--surface-2));
  }

  .check {
    position: absolute;
    right: 6px;
    top: 6px;
    display: grid;
    place-items: center;
    width: 22px;
    height: 22px;
    border-radius: 999px;
    border: 2px solid #fff;
    color: #fff;
    background: rgb(0 0 0 / 0.3);
    box-shadow: 0 0 2px rgb(0 0 0 / 0.5);
  }

  .picked .check {
    background: var(--accent);
    border-color: var(--accent);
  }

  .hidden-item :global(img),
  .hidden-item :global(.placeholder) {
    opacity: 0.4;
  }

  .hidden-badge {
    position: absolute;
    left: 6px;
    top: 6px;
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    border-radius: 999px;
    color: #fff;
    background: rgb(0 0 0 / 0.6);
  }

  .badge {
    position: absolute;
    right: 6px;
    bottom: 6px;
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 2px 7px;
    border-radius: 999px;
    font-size: 0.75rem;
    color: #fff;
    background: rgb(0 0 0 / 0.6);
  }

  @media (max-width: 520px) {
    .grid {
      /* Picked with the header's columns button (lib/columns.ts). */
      grid-template-columns: repeat(var(--phone-columns, 3), 1fr);
      gap: 2px;
    }

    .tile {
      border-radius: 0;
    }
  }
</style>
