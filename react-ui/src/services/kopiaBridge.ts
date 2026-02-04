/**
 * Bridge service for communicating with the Kotlin layer via WebView JavaScript interface.
 * When running in WebView, calls are made via window.KopiaBridge.
 * When running in browser (development), mock responses are returned.
 */

import type {
  ConnectRequest,
  RepositoryConnection,
  SourceInfo,
  SnapshotInfo,
  SnapshotListRequest,
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
      getSnapshot(id: string): string;
      listDirectory(json: string): string;
      startRestore(json: string): void;
      cancelRestore(): void;
      pickRestoreDestination(): void;
      persistUriPermission(json: string): string;
    };
    KopiaEvents?: {
      onRestoreProgress?: (progress: RestoreProgress) => void;
      onDestinationPicked?: (result: SafPickResult) => void;
    };
  }
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
      throw new Error(result.error || "Unknown error");
    }
    return result.data as T;
  }

  /**
   * Ping the bridge to verify communication.
   */
  async ping(): Promise<string> {
    if (!this.isAndroid) {
      return "pong (mock)";
    }
    return this.parse<string>(window.KopiaBridge!.ping());
  }

  /**
   * Connect to a Kopia repository.
   */
  async connect(request: ConnectRequest): Promise<RepositoryConnection> {
    if (!this.isAndroid) {
      throw new Error("Not running in WebView");
    }
    return this.parse(window.KopiaBridge!.connect(JSON.stringify(request)));
  }

  /**
   * Disconnect from the current repository.
   */
  async disconnect(): Promise<void> {
    if (!this.isAndroid) {
      return;
    }
    window.KopiaBridge!.disconnect();
  }

  /**
   * List all snapshot sources in the repository.
   */
  async listSources(): Promise<SourceInfo[]> {
    if (!this.isAndroid) {
      return [];
    }
    return this.parse(window.KopiaBridge!.listSources());
  }

  /**
   * List snapshots, optionally filtered by source.
   */
  async listSnapshots(
    request: SnapshotListRequest = {}
  ): Promise<SnapshotInfo[]> {
    if (!this.isAndroid) {
      return [];
    }
    return this.parse(window.KopiaBridge!.listSnapshots(JSON.stringify(request)));
  }

  /**
   * Get a single snapshot by ID.
   */
  async getSnapshot(snapshotId: string): Promise<SnapshotInfo | null> {
    if (!this.isAndroid) {
      return null;
    }
    return this.parse(window.KopiaBridge!.getSnapshot(snapshotId));
  }

  /**
   * List directory entries with pagination support.
   */
  async listDirectory(request: ListDirectoryRequest): Promise<DirectoryPage> {
    if (!this.isAndroid) {
      return { entries: [] };
    }
    return this.parse(window.KopiaBridge!.listDirectory(JSON.stringify(request)));
  }

  /**
   * Start a restore operation.
   * Subscribe to progress via onRestoreProgress().
   */
  startRestore(request: RestoreRequest): void {
    if (!this.isAndroid) {
      return;
    }
    window.KopiaBridge!.startRestore(JSON.stringify(request));
  }

  /**
   * Cancel an in-progress restore operation.
   */
  cancelRestore(): void {
    if (!this.isAndroid) {
      return;
    }
    window.KopiaBridge!.cancelRestore();
  }

  /**
   * Launch the Android SAF folder picker.
   * Result is delivered via onDestinationPicked().
   */
  pickRestoreDestination(): void {
    if (!this.isAndroid) {
      return;
    }
    window.KopiaBridge!.pickRestoreDestination();
  }

  /**
   * Persist URI permissions for a SAF-selected folder.
   */
  async persistUriPermission(request: PersistUriRequest): Promise<void> {
    if (!this.isAndroid) {
      return;
    }
    this.parse<void>(
      window.KopiaBridge!.persistUriPermission(JSON.stringify(request))
    );
  }

  /**
   * Subscribe to restore progress events.
   * Returns an unsubscribe function.
   */
  onRestoreProgress(callback: (progress: RestoreProgress) => void): () => void {
    if (!this.isAndroid) {
      return () => {};
    }

    window.KopiaEvents = window.KopiaEvents || {};
    window.KopiaEvents.onRestoreProgress = callback;

    return () => {
      if (window.KopiaEvents) {
        delete window.KopiaEvents.onRestoreProgress;
      }
    };
  }

  /**
   * Subscribe to SAF destination picker results.
   * Returns an unsubscribe function.
   */
  onDestinationPicked(callback: (result: SafPickResult) => void): () => void {
    if (!this.isAndroid) {
      return () => {};
    }

    window.KopiaEvents = window.KopiaEvents || {};
    window.KopiaEvents.onDestinationPicked = callback;

    return () => {
      if (window.KopiaEvents) {
        delete window.KopiaEvents.onDestinationPicked;
      }
    };
  }

  /**
   * Check if running in Android WebView.
   */
  get isInWebView(): boolean {
    return this.isAndroid;
  }
}

// Export singleton instance
export const kopiaBridge = new KopiaBridgeService();
