<script lang="ts">
  import type { Selection } from '../lib/selection.svelte';
  import Icon from './Icon.svelte';

  /** Starts or ends picking photos for copy, move, trash and delete. */
  let { selection }: { selection: Selection } = $props();
</script>

<button
  class="ghost select"
  class:on={selection.active}
  onclick={() => (selection.active ? selection.clear() : selection.start())}
  aria-pressed={selection.active}
  title="Select photos and videos to copy, move or delete">
  <Icon name="select" size={16} /><span>Select</span>
</button>

<style>
  .select {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    flex: none;
  }

  .on {
    border-color: var(--accent);
    color: var(--accent);
  }

  @media (max-width: 520px) {
    .select span {
      display: none;
    }
  }
</style>
