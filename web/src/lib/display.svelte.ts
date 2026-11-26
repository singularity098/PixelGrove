/** Per-device display choices kept in localStorage (like the theme). */

const NAMES_KEY = 'pixelgrove-names-on-covers';

function read(key: string): boolean {
  try {
    return localStorage.getItem(key) === '1';
  } catch {
    return false;
  }
}

class Display {
  /** Folder names written over the bottom of their covers instead of underneath. */
  namesOnCovers = $state(read(NAMES_KEY));

  setNamesOnCovers(on: boolean): void {
    this.namesOnCovers = on;
    try {
      localStorage.setItem(NAMES_KEY, on ? '1' : '0');
    } catch {
      // Not remembered; it still applies for this visit.
    }
  }
}

export const display = new Display();
