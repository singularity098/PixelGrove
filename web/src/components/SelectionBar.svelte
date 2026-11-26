<script lang="ts">
  import { post } from '../lib/api';
  import { libraryInfo } from '../lib/library.svelte';
  import type { MediaSource } from '../lib/media.svelte';
  import type { Selection } from '../lib/selection.svelte';
  import Icon from './Icon.svelte';
  import MoveDialog, { type TransferResult } from './MoveDialog.svelte';

  /**
   * The bar shown while photos are being picked: how many, and what to do with them
   * (copy, move, trash, delete). Items that leave the page are dropped from `source`.
   */
  let {
    selection,
    source,
    folderId = null,
    writable = true,
  }: {
    selection: Selection;
    source: MediaSource;
    /** The folder the page shows, if any (where the folder picker marks "current"). */
    folderId?: number | null;
    /** False when the page's folder is read-only: no move, trash or delete. */
    writable?: boolean;
  } = $props();

  let moving = $state<'copy' | 'move' | null>(null);
  let busy = $state(false);
  let toast = $state('');
  let toastTimer: ReturnType<typeof setTimeout>;

  $effect(() => {
    libraryInfo.load();
  });

  const count = $derived(selection.size);
  const picked = $derived(source.items.filter((m) => selection.has(m.id)));
  const allPicked = $derived(source.items.length > 0 && picked.length === source.items.length);
  const what = $derived(count === 1 ? '1 item' : `${count.toLocaleString()} items`);

  function flash(message: string) {
    toast = message;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (toast = ''), 3500);
  }

  function summary(verb: string, done: number, failed: { error: string }[]): string {
    const ok = `${verb} ${done === 1 ? '1 item' : `${done.toLocaleString()} items`}`;
    if (!failed.length) return ok;
    return `${done ? `${ok}. ` : ''}${failed.length.toLocaleString()} couldn't be: ${failed[0].error}`;
  }

  /** Drops what left the page, and keeps the ones that failed selected. */
  function finish(gone: number[]) {
    for (const id of gone) {
      source.remove(id);
      selection.ids.delete(id);
    }
    if (!selection.size) selection.clear();
  }

  async function remove(how: 'trash' | 'delete') {
    if (!count || busy) return;
    const question =
      how === 'trash'
        ? `Move ${what} to the trash? You can restore them for 30 days.`
        : `Delete ${what} for good? They skip the trash and can't be restored.`;
    if (!confirm(question)) return;
    busy = true;
    const ids = [...selection.ids];
    try {
      const result = await post<{ done: number; failed: { id: number; error: string }[] }>(`/media/${how}`, { ids });
      const failed = new Set(result.failed.map((f) => f.id));
      finish(ids.filter((id) => !failed.has(id)));
      flash(summary(how === 'trash' ? 'Moved to trash:' : 'Deleted', result.done, result.failed));
    } catch (e) {
      flash((e as Error).message);
    } finally {
      busy = false;
    }
  }

  function transferred(result: TransferResult) {
    if (result.mode === 'move') finish(result.done);
    else selection.clear();
    flash(summary(result.mode === 'move' ? `Moved to ${result.folder}:` : `Copied to ${result.folder}:`, result.done.length, result.failed));
  }

  function onkeydown(event: KeyboardEvent) {
    // Not while the viewer (which locks the page) or a dialog has the keyboard.
    if (moving || !selection.active || document.body.style.overflow === 'hidden' || event.target instanceof HTMLInputElement) return;
    if (event.key === 'Escape') {
      selection.clear();
      event.preventDefault();
    } else if (event.key === 'Delete' && writable) {
      remove(event.shiftKey ? 'delete' : 'trash');
      event.preventDefault();
    }
  }
</script>

<svelte:window {onkeydown} />

{#if selection.active}
  <div class="bar" role="toolbar" aria-label="Selected items">
    <button class="icon" onclick={() => selection.clear()} aria-label="Cancel selection" title="Cancel (Esc)"><Icon name="close" /></button>
    <span class="count">{count ? `${count.toLocaleString()} selected` : 'Select items'}</span>
    <button
      class="icon"
      onclick={() => (allPicked ? selection.ids.clear() : selection.selectAll(source.items))}
      disabled={!source.items.length}
      aria-label={allPicked ? 'Select none' : 'Select all'}
      title={allPicked ? 'Select none' : 'Select all loaded items'}>
      <Icon name="select" /><span class="label">{allPicked ? 'None' : 'All'}</span>
    </button>
    <button class="icon" onclick={() => (moving = 'copy')} disabled={!count || busy || !libraryInfo.writable} title={libraryInfo.writable ? 'Copy to another folder' : 'Every media folder is read-only'}>
      <Icon name="copyTo" /><span class="label">Copy to…</span>
    </button>
    <button class="icon" onclick={() => (moving = 'move')} disabled={!count || busy || !writable} title={writable ? 'Move to another folder' : 'This folder is read-only'}>
      <Icon name="moveTo" /><span class="label">Move to…</span>
    </button>
    <button class="icon" onclick={() => remove('trash')} disabled={!count || busy || !writable} title={writable ? 'Move to trash (Delete)' : 'This folder is read-only'}>
      <Icon name="trash" /><span class="label">Trash</span>
    </button>
    <button class="icon danger" onclick={() => remove('delete')} disabled={!count || busy || !writable} title={writable ? 'Delete for good, skipping the trash (Shift+Delete)' : 'This folder is read-only'}>
      <Icon name="deleteForever" /><span class="label">Delete</span>
    </button>
  </div>
{/if}

{#if toast}<div class="toast" role="status">{toast}</div>{/if}

{#if moving}
  <MoveDialog
    mode={moving}
    items={picked.length ? picked : [...selection.ids].map((id) => ({ id, name: '' }))}
    {folderId}
    onclose={() => (moving = null)}
    ondone={transferred} />
{/if}

<style>
  .bar {
    position: fixed;
    left: 50%;
    bottom: max(16px, env(safe-area-inset-bottom));
    transform: translateX(-50%);
    z-index: 60;
    display: flex;
    align-items: center;
    gap: 2px;
    max-width: calc(100vw - 16px);
    padding: 6px 8px;
    border: 1px solid var(--border);
    border-radius: 14px;
    background: var(--surface);
    color: var(--text);
    box-shadow: 0 8px 28px rgb(0 0 0 / 0.35);
  }

  .count {
    padding: 0 10px 0 4px;
    font-weight: 600;
    white-space: nowrap;
  }

  .bar .icon {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    width: auto;
    padding: 8px 10px;
    border: none;
    border-radius: 10px;
    background: none;
    color: var(--text);
    font: inherit;
    font-size: 0.9rem;
    cursor: pointer;
  }

  .bar .icon:hover:not(:disabled) {
    background: var(--surface-2);
  }

  .bar .icon:disabled {
    opacity: 0.4;
    cursor: default;
  }

  .bar .danger:not(:disabled) {
    color: var(--danger, #e5484d);
  }

  .toast {
    position: fixed;
    left: 50%;
    bottom: calc(max(16px, env(safe-area-inset-bottom)) + 64px);
    transform: translateX(-50%);
    z-index: 61;
    max-width: calc(100vw - 32px);
    padding: 8px 14px;
    border-radius: 10px;
    background: rgb(0 0 0 / 0.85);
    color: #fff;
    font-size: 0.9rem;
  }

  @media (max-width: 640px) {
    .label {
      display: none;
    }

    .bar .icon {
      padding: 8px;
    }

    .count {
      padding: 0 4px;
      font-size: 0.9rem;
    }
  }
</style>
