/**
 * Bridge service for communicating with the Kotlin layer via WebView JavaScript interface.
 * When running in WebView, calls are made via window.KopiaBridge.
 * When running in browser (development), mock responses are returned.
 */

import type {
  ConnectRequest,
  RepositoryConnection,
  SourceInfo,
  SourceWithStats,
  SnapshotInfo,
  SnapshotWithRetention,
  SnapshotListRequest,
  DeleteSnapshotsRequest,
  ListDirectoryRequest,
  DirectoryPage,
  RestoreRequest,
  RestoreProgress,
  SafPickResult,
  WebResult,
  PersistUriRequest,
} from "../types/kopia";

// Declare the global bridge interface injected by Android
declare global {
  interface Window {
    KopiaBridge?: {
      ping(): string;
      connect(json: string): string;
      disconnect(): void;
      listSources(): string;
      listSnapshots(json: string): string;
      listSourcesWithStats(): string;
      listSnapshotsWithRetention(json: string): string;
      deleteSnapshots(json: string): string;
      getSnapshot(id: string): string;
      listDirectory(json: string): string;
      startRestore(json: string): void;
      cancelRestore(): void;
      pickRestoreDestination(): void;
      persistUriPermission(json: string): string;
      hasStoragePermission(): string;
      openStoragePermissionSettings(): void;
      setStatusBarAppearance(isDarkMode: boolean): void;
      getSystemTheme(): string;
      hasStoredPassword(configJson: string): string;
      getStoredPassword(configJson: string): string;
      storePassword(configJson: string, password: string): string;
    };
    KopiaEvents?: {
      onRestoreProgress?: (progress: RestoreProgress) => void;
      onDestinationPicked?: (result: SafPickResult) => void;
      onSystemThemeChanged?: (theme: string) => void;
    };
  }
}

/**
 * Custom error class that includes an error code for special handling.
 */
export class BridgeError extends Error {
  constructor(message: string, public readonly code?: string) {
    super(message);
    this.name = "BridgeError";
  }
}

/**
 * Generic bridge call helper for standalone functions.
 */
function callBridge<T>(method: string, arg?: unknown): T {
  const bridge = window.KopiaBridge;
  if (!bridge) throw new BridgeError("KopiaBridge not available");

  const fn = (bridge as Record<string, unknown>)[method];
  if (typeof fn !== "function") {
    console.error(`[kopiaBridge] Bridge method '${method}' missing or not callable`, {
      methodType: typeof fn,
      bridgeKeys: Object.keys(bridge),
    });
    throw new BridgeError(`Bridge method '${method}' not found`);
  }

  // Call with correct 'this' context (the bridge object)
  let raw: unknown;
  try {
    raw = arg ? fn.call(bridge, JSON.stringify(arg)) : fn.call(bridge);
  } catch (invokeError) {
    console.error(`[kopiaBridge] Bridge invocation threw for '${method}'`, invokeError);
    throw invokeError;
  }

  if (typeof raw !== "string") {
    console.error(`[kopiaBridge] Bridge method '${method}' returned non-string`, raw);
    throw new BridgeError(`Bridge method '${method}' returned invalid response`);
  }

  let result: WebResult<T>;
  try {
    result = JSON.parse(raw);
  } catch (parseError) {
    console.error(`[kopiaBridge] Failed to parse bridge response for '${method}'`, {
      raw,
      parseError,
    });
    throw new BridgeError(`Invalid JSON response from bridge method '${method}'`);
  }

  if (!result.success) throw new BridgeError(result.error ?? "Unknown bridge error");
  return result.data as T;
}

class KopiaBridgeService {
  private get isAndroid(): boolean {
    return typeof window.KopiaBridge !== "undefined";
  }

  /**
   * Parse a JSON result from the bridge and throw on error.
   */
  private parse<T>(json: string): T {
    const result: WebResult<T> = JSON.parse(json);
    if (!result.success) {
      throw new BridgeError(result.error || "Unknown error", result.errorCode);
    }
    return result.data as T;
  }

  async hasStoragePermission(): Promise<boolean> {
    if (!this.isAndroid) return true;
    return this.parse<boolean>(window.KopiaBridge!.hasStoragePermission());
  }

  openStoragePermissionSettings(): void {
    if (!this.isAndroid) return;
    window.KopiaBridge!.openStoragePermissionSettings();
  }

  setStatusBarAppearance(isDarkMode: boolean): void {
    if (!this.isAndroid) return;
    window.KopiaBridge!.setStatusBarAppearance(isDarkMode);
  }

  async getSystemTheme(): Promise<"light" | "dark"> {
    if (!this.isAndroid) {
      // Fallback to media query for browser/development
      const isDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
      return isDark ? "dark" : "light";
    }
    return this.parse<"light" | "dark">(window.KopiaBridge!.getSystemTheme());
  }

  onSystemThemeChanged(callback: (theme: "light" | "dark") => void): () => void {
    if (!this.isAndroid) return () => {};
    window.KopiaEvents = window.KopiaEvents || {};
    window.KopiaEvents.onSystemThemeChanged = callback;
    return () => {
      if (window.KopiaEvents) delete window.KopiaEvents.onSystemThemeChanged;
    };
  }

  async ping(): Promise<string> {
    if (!this.isAndroid) return "pong (mock)";
    return this.parse<string>(window.KopiaBridge!.ping());
  }

  async connect(request: ConnectRequest): Promise<RepositoryConnection> {
    if (!this.isAndroid) throw new Error("Not running in WebView");

    // Use callback pattern to avoid blocking UI thread
    return new Promise((resolve, reject) => {
      // Set up callback
      (window as any).__kopiaConnectCallback = (resultJson: string) => {
        try {
          const result = this.parse<RepositoryConnection>(resultJson);
          resolve(result);
        } catch (error) {
          reject(error);
        } finally {
          // Cleanup callback
          delete (window as any).__kopiaConnectCallback;
        }
      };

      // Call the bridge - it returns immediately, result comes via callback
      window.KopiaBridge!.connect(JSON.stringify(request));
    });
  }

  async disconnect(): Promise<void> {
    if (!this.isAndroid) return;
    window.KopiaBridge!.disconnect();
  }

  async listSources(): Promise<SourceInfo[]> {
    if (!this.isAndroid) return [];
    return this.parse(window.KopiaBridge!.listSources());
  }

  async listSnapshots(request: SnapshotListRequest = {}): Promise<SnapshotInfo[]> {
    if (!this.isAndroid) return [];
    return this.parse(window.KopiaBridge!.listSnapshots(JSON.stringify(request)));
  }

  async getSnapshot(snapshotId: string): Promise<SnapshotInfo | null> {
    if (!this.isAndroid) return null;
    return this.parse(window.KopiaBridge!.getSnapshot(snapshotId));
  }

  async listDirectory(request: ListDirectoryRequest): Promise<DirectoryPage> {
    if (!this.isAndroid) return { entries: [] };
    return this.parse(window.KopiaBridge!.listDirectory(JSON.stringify(request)));
  }

  startRestore(request: RestoreRequest): void {
    if (!this.isAndroid) return;
    window.KopiaBridge!.startRestore(JSON.stringify(request));
  }

  cancelRestore(): void {
    if (!this.isAndroid) return;
    window.KopiaBridge!.cancelRestore();
  }

  pickRestoreDestination(): void {
    if (!this.isAndroid) return;
    window.KopiaBridge!.pickRestoreDestination();
  }

  async persistUriPermission(request: PersistUriRequest): Promise<void> {
    if (!this.isAndroid) return;
    this.parse<void>(window.KopiaBridge!.persistUriPermission(JSON.stringify(request)));
  }

  onRestoreProgress(callback: (progress: RestoreProgress) => void): () => void {
    if (!this.isAndroid) return () => {};
    window.KopiaEvents = window.KopiaEvents || {};
    window.KopiaEvents.onRestoreProgress = callback;
    return () => {
      if (window.KopiaEvents) delete window.KopiaEvents.onRestoreProgress;
    };
  }

  onDestinationPicked(callback: (result: SafPickResult) => void): () => void {
    if (!this.isAndroid) return () => {};
    window.KopiaEvents = window.KopiaEvents || {};
    window.KopiaEvents.onDestinationPicked = callback;
    return () => {
      if (window.KopiaEvents) delete window.KopiaEvents.onDestinationPicked;
    };
  }

  async hasStoredPassword(config: ConnectionConfig): Promise<boolean> {
    if (!this.isAndroid) return false;
    return this.parse<boolean>(
      window.KopiaBridge!.hasStoredPassword(JSON.stringify(config))
    );
  }

  async getStoredPassword(config: ConnectionConfig): Promise<string | null> {
    if (!this.isAndroid) return null;
    return this.parse<string | null>(
      window.KopiaBridge!.getStoredPassword(JSON.stringify(config))
    );
  }

  async storePassword(config: ConnectionConfig, password: string): Promise<void> {
    if (!this.isAndroid) return;
    this.parse<void>(
      window.KopiaBridge!.storePassword(JSON.stringify(config), password)
    );
  }

  get isInWebView(): boolean {
    return this.isAndroid;
  }
}

// Export singleton instance (used by ConnectScreen, FileBrowser, etc.)
export const kopiaBridge = new KopiaBridgeService();

// ---------- Standalone functions for React Query hooks ----------

function groupSnapshotsBySource(snapshots: SnapshotInfo[]): SourceWithStats[] {
  const groups = new Map<string, SnapshotInfo[]>();
  for (const snap of snapshots) {
    const key = `${snap.source.userName}@${snap.source.host}:${snap.source.path}`;
    const existing = groups.get(key) || [];
    existing.push(snap);
    groups.set(key, existing);
  }
  return Array.from(groups.values())
    .map((snaps) => {
      const latest = snaps.reduce((a, b) =>
        a.startTimeEpochMs > b.startTimeEpochMs ? a : b
      );
      return {
        source: latest.source,
        snapshotCount: snaps.length,
        latestSnapshotTime: latest.startTimeEpochMs,
        totalFileCount: latest.stats?.totalFileCount ?? 0,
        totalFileSize: latest.stats?.totalFileSize ?? 0,
      };
    })
    .sort((a, b) => b.latestSnapshotTime - a.latestSnapshotTime);
}

export async function listSourcesWithStats(): Promise<SourceWithStats[]> {
  // Temporary: strict bridge mode for debugging; do not silently fallback.
  const STRICT_BRIDGE_DEBUG = true;

  console.log("[kopiaBridge] listSourcesWithStats preflight", {
    hasBridge: !!window.KopiaBridge,
    methodType: typeof window.KopiaBridge?.listSourcesWithStats,
  });
  try {
    console.log("[kopiaBridge] Calling listSourcesWithStats via bridge");
    const result = callBridge<SourceWithStats[]>("listSourcesWithStats");
    console.log("[kopiaBridge] listSourcesWithStats succeeded:", result);
    return result;
  } catch (error) {
    console.error("[kopiaBridge] listSourcesWithStats bridge failure:", {
      error,
      hasBridge: !!window.KopiaBridge,
      methodType: typeof window.KopiaBridge?.listSourcesWithStats,
    });

    if (STRICT_BRIDGE_DEBUG) {
      throw error;
    }

    console.warn("[kopiaBridge] listSourcesWithStats failed, using fallback:", error);
    // Fallback: fetch all snapshots and group client-side
    const snapshots = await kopiaBridge.listSnapshots();
    return groupSnapshotsBySource(snapshots);
  }
}

export async function listSnapshotsWithRetention(
  request: SnapshotListRequest
): Promise<SnapshotWithRetention[]> {
  try {
    return callBridge<SnapshotWithRetention[]>("listSnapshotsWithRetention", request);
  } catch {
    // Fallback: fetch snapshots without retention data
    const snapshots = await kopiaBridge.listSnapshots(request);
    return snapshots.map((snap) => ({
      ...snap,
      retentionReasons: [],
    }));
  }
}

export async function deleteSnapshots(request: DeleteSnapshotsRequest): Promise<void> {
  callBridge<void>("deleteSnapshots", request);
}
