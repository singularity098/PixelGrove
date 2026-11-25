<script lang="ts">
  import { get, post } from '../lib/api';
  import Icon from './Icon.svelte';

  /**
   * Copies or moves a photo or video into another folder of the library, picked from
   * the folder tree (optionally into a new folder made inside it).
   */
  let {
    item,
    folderId,
    onclose,
    ondone,
  }: {
    item: { id: number; name: string };
    /** The folder it's in now, where browsing starts. */
    folderId: number | null;
    onclose: () => void;
    /** After a move (`moved`) or copy, with the item's new id. */
    ondone: (result: { moved: boolean; id: number | null; folder: string }) => void;
  } = $props();

  interface TreeFolder {
    id: number;
    name: string;
    path: string;
    subfolder_count: number;
  }

  let dialog: HTMLDialogElement | undefined = $state();
  /** The folders opened so far, top first; the last is where it goes. */
  let trail: TreeFolder[] = $state([]);
  let folders: TreeFolder[] = $state([]);
  let newFolder = $state('');
  let error = $state('');
  let busy = $state(false);
  const here = $derived(trail.at(-1) ?? null);

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

  async function run(mode: 'copy' | 'move') {
    if (!here) return;
    busy = true;
    error = '';
    try {
      const result = await post<{ id: number | null; name: string }>(`/media/${item.id}/${mode}`, {
        folder_id: here.id,
        new_folder: newFolder.trim() || undefined,
      });
      const folder = newFolder.trim() ? `${here.name}/${newFolder.trim()}` : here.name;
      ondone({ moved: mode === 'move', id: result.id, folder });
      close();
    } catch (e) {
      error = (e as Error).message;
    } finally {
      busy = false;
    }
  }
</script>

<dialog bind:this={dialog} onclose={onclose} onkeydown={(e) => e.stopPropagation()}>
  <h3>Copy or move “{item.name}”</h3>
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
        <button type="button" onclick={() => (trail = [...trail, folder])} class:current={folder.id === folderId}>
          <Icon name="folder" size={18} />
          <span class="name">{folder.name}</span>
          {#if folder.subfolder_count > 0}<Icon name="right" size={16} />{/if}
        </button>
      </li>
    {:else}
      <li class="muted small empty">No folders inside.</li>
    {/each}
  </ul>
  {#if here}
    <label>
      New folder inside “{here.name}” (optional)
      <input bind:value={newFolder} placeholder="Leave empty to use “{here.name}”" maxlength="255" />
    </label>
  {:else}
    <p class="muted small">Open the folder to copy or move it into.</p>
  {/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  <div class="dialog-actions">
    <button type="button" class="ghost" onclick={close}>Cancel</button>
    <button type="button" class="ghost" disabled={!here || busy} onclick={() => run('copy')}>Copy here</button>
    <button type="button" class="primary" disabled={!here || busy || (here.id === folderId && !newFolder.trim())} onclick={() => run('move')}>
      Move here
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
