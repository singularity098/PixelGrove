import { get } from './api';

/**
 * Whether the library can take new files at all: false when every media folder is
 * mounted read-only, and copying is greyed out. (Moving, trashing and deleting depend
 * on each item's own folder, which its details report.) Asked once per visit.
 */
class LibraryInfo {
  writable = $state(true);
  private asked = false;

  load(): void {
    if (this.asked) return;
    this.asked = true;
    get<{ writable: boolean }>('/library/status').then(
      (s) => (this.writable = s.writable),
      () => (this.asked = false),
    );
  }
}

export const libraryInfo = new LibraryInfo();
