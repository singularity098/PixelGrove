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
   * the folder tree. "New folder" adds folders that don't exist yet (inside each other,
   * if wanted); they're made on disk only when the copy or move runs, so cancelling
   * leaves nothing behind.
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
  /** New folders opened inside `here`, outermost first; made when the move runs. */
  let pending: string[] = $state([]);
  /** The name being typed for a new folder, or null when not naming one. */
  let naming: string | null = $state(null);
  let nameInput: HTMLInputElement | undefined = $state();
  let error = $state('');
  let busy = $state(false);
  const here = $derived(trail.at(-1) ?? null);
  const what = $derived(items.length === 1 ? `“${items[0].name}”` : `${items.length.toLocaleString()} items`);
  const verb = $derived(mode === 'copy' ? 'Copy' : 'Move');
  const sameFolder = $derived(mode === 'move' && here?.id === folderId && pending.length === 0);
  /** Where it goes, as shown: the open folder's name plus any new ones. */
  const target = $derived(here ? [here.name, ...pending].join('/') : '');

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

  function open(folders: TreeFolder[]) {
    trail = folders;
    pending = [];
    naming = null;
  }

  function startNaming() {
    naming = '';
    error = '';
    queueMicrotask(() => nameInput?.focus());
  }

  /** Opens the new folder being named (or the existing one, if the name is taken). */
  function addFolder() {
    const name = (naming ?? '').trim();
    if (!name) {
      naming = null;
      return;
    }
    if (name.startsWith('.') || /[/\\]/.test(name)) {
      error = "Folder names can't start with a dot or contain slashes.";
      return;
    }
    const existing = pending.length === 0 ? folders.find((f) => f.name === name) : undefined;
    naming = null;
    error = '';
    if (existing) open([...trail, existing]);
    else pending = [...pending, name];
  }

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
        new_folder: pending.length ? pending.join('/') : undefined,
      });
      const failedIds = new Set(result.failed.map((f) => f.id));
      if (failedIds.size === ids.length) {
        // Nothing worked: say why here and let them pick another folder.
        error = result.failed[0]?.error ?? 'Something went wrong.';
        return;
      }
      ondone({ mode, done: ids.filter((id) => !failedIds.has(id)), failed: result.failed, folder: target });
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
    <button type="button" class="crumb" onclick={() => open([])} disabled={!here}>Library</button>
    {#each trail as folder, i (folder.id)}
      <span class="muted">/</span>
      <button
        type="button"
        class="crumb"
        onclick={() => open(trail.slice(0, i + 1))}
        disabled={i === trail.length - 1 && pending.length === 0}
      >
        {folder.name}
      </button>
    {/each}
    {#each pending as name, i (i)}
      <span class="muted">/</span>
      <button
        type="button"
        class="crumb new"
        onclick={() => (pending = pending.slice(0, i + 1))}
        disabled={i === pending.length - 1}
        title="New folder"
      >
        {name}
      </button>
    {/each}
  </nav>
  <ul class="folders">
    {#if pending.length === 0}
      {#each folders as folder (folder.id)}
        <li>
          <button type="button" onclick={() => open([...trail, folder])} class:current={folder.id === folderId} class:read-only={!folder.writable}>
            <Icon name="folder" size={18} />
            <span class="name">{folder.name}</span>
            {#if folder.subfolder_count > 0}<Icon name="right" size={16} />{/if}
          </button>
        </li>
      {:else}
        {#if !here?.writable}<li class="muted small empty">No folders inside.</li>{/if}
      {/each}
    {/if}
    {#if here?.writable}
      <li>
        {#if naming !== null}
          <form
            class="naming"
            onsubmit={(e) => {
              e.preventDefault();
              addFolder();
            }}
          >
            <Icon name="folder" size={18} />
            <input
              bind:this={nameInput}
              bind:value={naming}
              placeholder="Folder name"
              aria-label="New folder name"
              maxlength="255"
              onkeydown={(e) => {
                if (e.key === 'Escape') {
                  e.preventDefault();
                  naming = null;
                }
              }}
            />
            <button type="submit" class="ghost">Create</button>
          </form>
        {:else}
          <button type="button" class="add" onclick={startNaming}>
            <Icon name="folderPlus" size={18} />
            <span class="name">New folder</span>
          </button>
        {/if}
      </li>
    {/if}
  </ul>
  {#if here && !here.writable}
    <p class="muted small">“{here.name}” is read-only, so nothing can be put in it. Open another folder.</p>
  {:else if pending.length}
    <p class="muted small">“{pending.at(-1)}” will be made when you {mode === 'copy' ? 'copy' : 'move'} {items.length === 1 ? 'it' : 'them'}.</p>
  {:else if !here}
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

  .crumb.new {
    font-style: italic;
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

  .folders button.add {
    color: var(--accent);
  }

  .naming {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 6px 12px;
  }

  .naming input {
    flex: 1;
    min-width: 0;
  }

  .folders .naming button {
    width: auto;
    padding: 6px 10px;
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
