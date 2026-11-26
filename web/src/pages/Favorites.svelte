<script lang="ts">
  import MediaGrid from '../components/MediaGrid.svelte';
  import MoreSpace from '../components/MoreSpace.svelte';
  import MediaViewerHost from '../components/MediaViewerHost.svelte';
  import { PagedList, type MediaItem } from '../lib/media.svelte';
  import { openViewer } from '../lib/query';
  import SelectButton from '../components/SelectButton.svelte';
  import SelectionBar from '../components/SelectionBar.svelte';
  import { Selection } from '../lib/selection.svelte';
  import { session } from '../lib/session.svelte';

  const favorites = new PagedList<MediaItem>('/favorites', 200);
  const selection = new Selection();
  const canEdit = $derived(session.current.status === 'signed-in' && session.current.user.role !== 'viewer');
</script>

<section class="page wide">
  <div class="heading">
    <h2>Favorites</h2>
    {#if canEdit && favorites.total}<SelectButton {selection} />{/if}
  </div>
  {#if favorites.total === 0}
    <p class="muted">No favorites yet. Open a photo and tap the heart to add it here.</p>
  {:else if favorites.total !== null}
    <p class="muted small">{favorites.total.toLocaleString()} item{favorites.total === 1 ? '' : 's'}</p>
  {/if}
  <MediaGrid items={favorites.items} onopen={(i) => openViewer(favorites.items[i].id)} selection={canEdit ? selection : undefined} />
  <MoreSpace list={favorites} />
  {#if favorites.error}<p class="error">{favorites.error}</p>{/if}
</section>

<MediaViewerHost source={favorites} />
{#if canEdit}<SelectionBar {selection} source={favorites} />{/if}

<style>
  .heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
</style>
