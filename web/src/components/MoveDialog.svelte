<script lang="ts" module>
  export interface TransferResult {
    mode: 'copy' | 'move';
    /** Ids that were copied or moved (moved ones are gone from where they were). */
    done: number[];
    failed: { id: number; error: string }[];
    /** Where they went, for the message. */
    folder: string;
  }
</script>

<script lang="ts">
  import { get, post } from '../lib/api';
  import type { TreeFolder } from '../lib/media.svelte';
  import Icon from './Icon.svelte';

  /**
   * Copies or moves photos and videos into another folder of the library, picked from
   * the folder tree (optionally into a new folder made inside it).
   */
  let {
    mode,
    items,
    folderId,
    onclose,
    ondone,
  }: {
    mode: 'copy' | 'move';
    items: { id: number; name: string }[];
    /** The folder they're in now, if they share one (marked in the list). */
    folderId: number | null;
    onclose: () => void;
    ondone: (result: TransferResult) => void;
  } = $props();

  let dialog: HTMLDialogElement | undefined = $state();
  /** The folders opened so far, top first; the last is where it goes. */
  let trail: TreeFolder[] = $state([]);
  let folders: TreeFolder[] = $state([]);
  let newFolder = $state('');
  let error = $state('');
  let busy = $state(false);
  const here = $derived(trail.at(-1) ?? null);
  const what = $derived(items.length === 1 ? `“${items[0].name}”` : `${items.length.toLocaleString()} items`);
  const verb = $derived(mode === 'copy' ? 'Copy' : 'Move');
  const sameFolder = $derived(mode === 'move' && here?.id === folderId && !newFolder.trim());

  $effect(() => {
    dialog?.showModal();
  });

  $effect(() => {
    const id = here?.id;
    error = '';
    (id === undefined ? get<TreeFolder[]>('/files') : get<TreeFolder[]>(`/files/${id}`)).then(
      (list) => {
        if (here?.id === id) folders = list;
      },
      (e) => (error = (e as Error).message),
    );
  });

  function close() {
    dialog?.close();
    onclose();
  }

  async function run() {
    if (!here) return;
    busy = true;
    error = '';
    try {
      const ids = items.map((i) => i.id);
      const result = await post<{ placed?: unknown[]; failed: { id: number; error: string }[] }>(`/media/${mode}`, {
        ids,
        folder_id: here.id,
        new_folder: newFolder.trim() || undefined,
      });
      const failedIds = new Set(result.failed.map((f) => f.id));
      if (failedIds.size === ids.length) {
        // Nothing worked: say why here and let them pick another folder.
        error = result.failed[0]?.error ?? 'Something went wrong.';
        return;
      }
      const folder = newFolder.trim() ? `${here.name}/${newFolder.trim()}` : here.name;
      ondone({ mode, done: ids.filter((id) => !failedIds.has(id)), failed: result.failed, folder });
      close();
    } catch (e) {
      error = (e as Error).message;
    } finally {
      busy = false;
    }
  }
</script>

<dialog bind:this={dialog} onclose={onclose} onkeydown={(e) => e.stopPropagation()}>
  <h3>{verb} {what} to…</h3>
  <nav class="trail" aria-label="Folder">
    <button type="button" class="crumb" onclick={() => (trail = [])} disabled={!here}>Library</button>
    {#each trail as folder, i (folder.id)}
      <span class="muted">/</span>
      <button type="button" class="crumb" onclick={() => (trail = trail.slice(0, i + 1))} disabled={i === trail.length - 1}>
        {folder.name}
      </button>
    {/each}
  </nav>
  <ul class="folders">
    {#each folders as folder (folder.id)}
      <li>
        <button type="button" onclick={() => (trail = [...trail, folder])} class:current={folder.id === folderId} class:read-only={!folder.writable}>
          <Icon name="folder" size={18} />
          <span class="name">{folder.name}</span>
          {#if folder.subfolder_count > 0}<Icon name="right" size={16} />{/if}
        </button>
      </li>
    {:else}
      <li class="muted small empty">No folders inside.</li>
    {/each}
  </ul>
  {#if here && !here.writable}
    <p class="muted small">“{here.name}” is read-only, so nothing can be put in it. Open another folder.</p>
  {:else if here}
    <label>
      New folder inside “{here.name}” (optional)
      <input bind:value={newFolder} placeholder="Leave empty to use “{here.name}”" maxlength="255" />
    </label>
  {:else}
    <p class="muted small">Open the folder to {mode} {items.length === 1 ? 'it' : 'them'} into.</p>
  {/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  <div class="dialog-actions">
    <button type="button" class="ghost" onclick={close}>Cancel</button>
    <button type="button" class="primary" disabled={!here || !here.writable || busy || sameFolder} onclick={run}>
      {busy ? `${mode === 'copy' ? 'Copying' : 'Moving'}…` : `${verb} here`}
    </button>
  </div>
</dialog>

<style>
  dialog {
    width: min(480px, calc(100vw - 32px));
    background: var(--surface);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 24px;
  }

  dialog::backdrop {
    background: rgb(0 0 0 / 0.6);
  }

  h3 {
    margin: 0 0 12px;
    overflow-wrap: anywhere;
  }

  .trail {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 4px;
    margin-bottom: 8px;
  }

  .crumb {
    background: none;
    border: none;
    color: var(--accent);
    font: inherit;
    padding: 2px 4px;
    cursor: pointer;
  }

  .crumb:disabled {
    color: var(--text);
    font-weight: 600;
    cursor: default;
  }

  .folders {
    list-style: none;
    margin: 0 0 12px;
    padding: 0;
    max-height: min(320px, 45vh);
    overflow-y: auto;
    border: 1px solid var(--border);
    border-radius: 10px;
  }

  .folders button {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    padding: 10px 12px;
    background: none;
    border: none;
    color: var(--text);
    font: inherit;
    text-align: left;
    cursor: pointer;
  }

  .folders button:hover {
    background: var(--surface-2);
  }

  .folders button.current .name::after {
    content: ' (current)';
    color: var(--text-muted);
  }

  .folders button.read-only .name::after {
    content: ' (read-only)';
    color: var(--text-muted);
  }

  .name {
    flex: 1;
    overflow-wrap: anywhere;
  }

  .empty {
    padding: 10px 12px;
  }

  .dialog-actions {
    display: flex;
    justify-content: flex-end;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 16px;
  }
</style>
