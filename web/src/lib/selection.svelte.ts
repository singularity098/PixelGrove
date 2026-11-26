import { SvelteSet } from 'svelte/reactivity';

/**
 * Picking several photos and videos at once (for copy, move, trash and delete). On
 * wide screens it starts from the page's Select button; on touch screens, also from a
 * long press on a photo. Shift-click selects everything between two clicks.
 */
export class Selection {
  active = $state(false);
  readonly ids = new SvelteSet<number>();
  /** The last item clicked, where a Shift-click range starts. */
  private anchor: number | null = null;

  get size(): number {
    return this.ids.size;
  }

  has(id: number): boolean {
    return this.ids.has(id);
  }

  start(id?: number): void {
    this.active = true;
    if (id !== undefined) {
      this.ids.add(id);
      this.anchor = id;
    }
  }

  toggle(id: number): void {
    if (this.ids.has(id)) this.ids.delete(id);
    else this.ids.add(id);
    this.anchor = id;
  }

  /** Selects everything in `list` from the last clicked item to `id`. */
  extend(list: { id: number }[], id: number): void {
    const from = this.anchor === null ? -1 : list.findIndex((m) => m.id === this.anchor);
    const to = list.findIndex((m) => m.id === id);
    if (from < 0 || to < 0) {
      this.toggle(id);
      return;
    }
    const [a, b] = from < to ? [from, to] : [to, from];
    for (let i = a; i <= b; i++) this.ids.add(list[i].id);
    this.anchor = id;
  }

  selectAll(list: { id: number }[]): void {
    for (const m of list) this.ids.add(m.id);
  }

  clear(): void {
    this.active = false;
    this.ids.clear();
    this.anchor = null;
  }
}
