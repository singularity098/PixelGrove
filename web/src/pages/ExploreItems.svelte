<script lang="ts">
  import MediaGrid from '../components/MediaGrid.svelte';
  import MoreSpace from '../components/MoreSpace.svelte';
  import MediaViewerHost from '../components/MediaViewerHost.svelte';
  import { PagedList, type MediaItem } from '../lib/media.svelte';
  import { openViewer } from '../lib/query';
  import { link } from '../lib/router.svelte';
  import SelectButton from '../components/SelectButton.svelte';
  import SelectionBar from '../components/SelectionBar.svelte';
  import { Selection } from '../lib/selection.svelte';
  import { session } from '../lib/session.svelte';

  /** Items for one Explore group; the filter comes from the URL query. */
  let { query }: { query: URLSearchParams } = $props();

  // Keyed on the filter by the parent, so created once per group.
  // svelte-ignore state_referenced_locally
  const title = query.get('title') ?? 'Explore';
  // svelte-ignore state_referenced_locally
  const filter = new URLSearchParams([...query].filter(([k]) => ['country', 'place', 'make', 'model', 'year', 'kind'].includes(k)));
  const items = new PagedList<MediaItem>(`/explore/items?${filter}`, 200);
  const selection = new Selection();
  const canEdit = $derived(session.current.status === 'signed-in' && session.current.user.role !== 'viewer');
</script>

<section class="page wide">
  <nav class="small"><a href="/explore" onclick={link}>Explore</a></nav>
  <div class="heading">
    <h2>{title}</h2>
    {#if canEdit && items.total}<SelectButton {selection} />{/if}
  </div>
  {#if items.total !== null}
    <p class="muted small">{items.total.toLocaleString()} item{items.total === 1 ? '' : 's'}</p>
  {/if}
  <MediaGrid items={items.items} onopen={(i) => openViewer(items.items[i].id)} selection={canEdit ? selection : undefined} />
  <MoreSpace list={items} />
  {#if items.error}<p class="error">{items.error}</p>{/if}
</section>

<MediaViewerHost source={items} />
{#if canEdit}<SelectionBar {selection} source={items} />{/if}

<style>
  .heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }

  nav a {
    color: var(--text-muted);
    text-decoration: none;
  }
</style>
