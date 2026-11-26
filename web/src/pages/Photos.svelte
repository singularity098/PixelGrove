<script lang="ts">
  import Icon from '../components/Icon.svelte';
  import PullToRefresh from '../components/PullToRefresh.svelte';
  import Thumb from '../components/Thumb.svelte';
  import { get, post } from '../lib/api';
  import { display } from '../lib/display.svelte';
  import { formatDate, PagedList, type Album } from '../lib/media.svelte';
  import { link, onVisible } from '../lib/router.svelte';
  import { session } from '../lib/session.svelte';

  const SORTS = [
    ['newest', 'Newest first'],
    ['oldest', 'Oldest first'],
    ['name', 'Name'],
    ['count', 'Most photos'],
  ] as const;
  type Sort = (typeof SORTS)[number][0];
  // Remembered per device, like the theme.
  const SORT_KEY = 'pixelgrove-folder-sort';

  const initialSort = readSort();
  let sort: Sort = $state(initialSort);
  let albums = $state(new PagedList<Album>(`/albums?sort=${initialSort}`, 60));

  function readSort(): Sort {
    try {
      const saved = localStorage.getItem(SORT_KEY);
      return SORTS.find(([id]) => id === saved)?.[0] ?? 'newest';
    } catch {
      return 'newest';
    }
  }

  const isAdmin = $derived(session.current.status === 'signed-in' && session.current.user.role === 'admin');

  // Rescan (admins): a full scan of every media folder, then the list reloads. Others
  // just reload; the server rescans on its own schedule and watches for changes.
  let rescanning = $state(false);
  async function refresh() {
    if (rescanning) return;
    rescanning = true;
    try {
      if (isAdmin) {
        await post('/library/rescan');
        const started = Date.now();
        // Wait for the scan to finish (polled), up to a couple of minutes.
        while (Date.now() - started < 120_000) {
          await new Promise((r) => setTimeout(r, 1000));
          const status = await get<{ scanning: boolean }>('/library/status');
          if (!status.scanning) break;
        }
      }
      await albums.reset();
    } catch {
      // The list shows its own error if reloading failed.
    } finally {
      rescanning = false;
    }
  }

  function changeSort(event: Event) {
    // Read the choice from the menu: the binding may update after this handler.
    sort = (event.currentTarget as HTMLSelectElement).value as Sort;
    albums = new PagedList<Album>(`/albums?sort=${sort}`, 60);
    window.scrollTo({ top: 0 });
    // The end-of-list marker may already be in view, so it wouldn't ask by itself.
    albums.more();
    try {
      localStorage.setItem(SORT_KEY, sort);
    } catch {
      // Not remembered; fine.
    }
  }
</script>

<section class="page wide">
  <div class="heading">
    <h2>Photos</h2>
    <div class="tools">
      {#if isAdmin}
        <button class="ghost rescan" onclick={refresh} disabled={rescanning} title="Rescan every media folder now">
          <Icon name="refresh" size={16} /><span>{rescanning ? 'Rescanning…' : 'Rescan'}</span>
        </button>
      {/if}
      <select class="sort" aria-label="Sort folders" value={sort} onchange={changeSort}>
        {#each SORTS as [id, label] (id)}
          <option value={id}>{label}</option>
        {/each}
      </select>
    </div>
  </div>
  {#if albums.total === 0}
    <p class="muted">No photos yet. Add folders under <code>/media</code> and they'll appear here once scanned.</p>
  {/if}
  <div class="albums" class:overlay={display.namesOnCovers}>
    {#each albums.items as album (album.id)}
      <a class="album" class:hidden-item={album.hidden} href="/folder/{album.id}" onclick={link} title={album.path}>
        <div class="cover">
          <Thumb media={album.cover} />
          {#if display.namesOnCovers}
            <div class="caption">
              <div class="name">{#if album.hidden}<span class="tag">Hidden</span>{/if}{album.name}</div>
              <div class="meta small">{album.count.toLocaleString()}</div>
            </div>
          {/if}
        </div>
        {#if !display.namesOnCovers}
          <div class="name">{#if album.hidden}<span class="tag">Hidden</span>{/if}{album.name}</div>
          <div class="meta muted small">
            {album.count.toLocaleString()} · {formatDate(album.latest_taken_at)}
          </div>
        {/if}
      </a>
    {/each}
  </div>
  {#if albums.error}<p class="error">{albums.error}</p>{/if}
  <div use:onVisible={() => albums.more()}></div>
</section>

<PullToRefresh onrefresh={refresh} />

<style>
  .heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }

  .heading h2 {
    margin: 0;
  }

  .sort {
    margin: 0;
    padding: 7px 10px;
    font: inherit;
    font-size: 0.9rem;
    color: var(--text);
    background: var(--surface-2);
    border: 1px solid var(--border);
    border-radius: 10px;
  }

  .albums {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
    gap: 20px 14px;
    margin-top: 16px;
  }

  .album {
    color: var(--text);
    text-decoration: none;
    min-width: 0;
  }

  .tools {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .rescan {
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }

  .cover {
    position: relative;
    aspect-ratio: 1;
    overflow: hidden;
    background: var(--surface-2);
  }

  .caption {
    position: absolute;
    inset: auto 0 0;
    padding: 28px 10px 8px;
    color: #fff;
    background: linear-gradient(transparent, rgb(0 0 0 / 0.75));
    text-shadow: 0 1px 2px rgb(0 0 0 / 0.6);
    pointer-events: none;
  }

  .caption .name {
    margin-top: 0;
  }

  .caption .meta {
    opacity: 0.85;
  }

  .caption .tag {
    background: rgb(255 255 255 / 0.2);
    color: #fff;
  }

  .overlay {
    gap: 10px;
  }

  .hidden-item .cover {
    opacity: 0.4;
  }

  .tag {
    font-size: 0.7rem;
    font-weight: 600;
    padding: 1px 6px;
    margin-right: 6px;
    border-radius: 999px;
    background: var(--surface-2);
    color: var(--text-muted);
    vertical-align: 1px;
  }

  .album:hover .cover,
  .album:focus-visible .cover {
    outline: 3px solid var(--accent);
    outline-offset: -3px;
  }

  .name {
    margin-top: 8px;
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .meta {
    margin-top: 2px;
  }

  @media (max-width: 520px) {
    .albums {
      /* Picked with the header's columns button (lib/columns.ts). */
      grid-template-columns: repeat(var(--folder-columns, 2), 1fr);
      gap: 14px 8px;
    }

    .name {
      margin-top: 6px;
      font-size: 0.9rem;
    }

    .overlay {
      gap: 4px;
    }

    .rescan span {
      display: none;
    }

    .meta {
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
  }
</style>
