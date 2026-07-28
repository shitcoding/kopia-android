/**
 * Bridge service for communicating with the Kotlin layer via WebView JavaScript interface.
 * When running in WebView, calls are made via window.KopiaBridge.
 * When running in browser (development), mock responses are returned.
 */

import { toast } from "@/hooks/use-toast";
import type {
  ConnectionConfig,
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
  WebAlgorithms,
  WebSourceStatus,
  WebTaskInfo,
  WebTaskLogEntry,
  WebPolicy,
  WebResolvedPolicy,
  WebPolicyEntry,
  WebMaintenanceStatus,
  WebRepositoryConnection,
  CreateRepositoryRequest,
  CreateSourceRequest,
  EstimateBackupRequest,
  SetPolicyRequest,
} from "../types/kopia";
import { parseSourceId } from "@/lib/format";

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
      pickBackupSource(): string;
      persistUriPermission(json: string): string;
      hasStoragePermission(): string;
      openStoragePermissionSettings(): void;
      setStatusBarAppearance(isDarkMode: boolean): void;
      getSystemTheme(): string;
      hasStoredPassword(configJson: string): string;
      storePassword(configJson: string, password: string): string;
      // New methods for sources, tasks, policies, maintenance
      getSupportedAlgorithms(): string;
      createRepository(json: string): void;
      testStorageConnection(json: string): string;
      createSource(json: string): string;
      deleteSource(sourceId: string): string;
      getSourceStatus(sourceId: string): string;
      pauseSource(sourceId: string): string;
      resumeSource(sourceId: string): string;
      startBackup(sourceId: string): string;
      estimateBackup(json: string): string;
      getPolicy(requestJson: string): string;
      setPolicy(requestJson: string): string;
      deletePolicy(requestJson: string): string;
      listPolicies(): string;
      resolvePolicy(requestJson: string): string;
      listTasks(): string;
      getTask(taskId: string): string;
      cancelTask(taskId: string): string;
      getTaskLogs(taskId: string): string;
      triggerMaintenance(mode: string): string;
      getMaintenanceStatus(): string;
      listAllSources(): string;
    };
    KopiaEvents?: {
      onRestoreProgress?: (progress: RestoreProgress) => void;
      onDestinationPicked?: (result: SafPickResult) => void;
      onBackupSourcePicked?: (result: SafPickResult) => void;
      onSystemThemeChanged?: (theme: string) => void;
      onRepositoryCreated?: (resultJson: string) => void;
      onBackupProgress?: (sourceId: string, counters: string) => void;
      onTaskCompleted?: (taskId: string, status: string) => void;
      onSourceStatusChanged?: (sourceId: string, status: string) => void;
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

  // Call with correct 'this' context (the bridge object).
  // Pass string args RAW: JSON.stringify("abc") yields '"abc"', which arrives at the Kotlin
  // @JavascriptInterface double-quoted (e.g. getSource("\"abc\"") -> "Source not found"). Only
  // non-string args are JSON-encoded. Use an explicit undefined check so a falsy-but-valid arg
  // (e.g. "" or 0) is still forwarded rather than silently dropped. Mirrors callBridgeVoid.
  let raw: unknown;
  try {
    raw =
      arg !== undefined
        ? fn.call(bridge, typeof arg === "string" ? arg : JSON.stringify(arg))
        : fn.call(bridge);
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

/**
 * Bridge call helper for void-returning methods (no response parsing needed).
 */
function callBridgeVoid(method: string, arg?: unknown): void {
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

  try {
    if (arg !== undefined) {
      fn.call(bridge, typeof arg === "string" ? arg : JSON.stringify(arg));
    } else {
      fn.call(bridge);
    }
  } catch (invokeError) {
    console.error(`[kopiaBridge] Bridge invocation threw for '${method}'`, invokeError);
    throw invokeError;
  }
}

/**
 * Tracks the bridge calls whose native result is delivered through ONE global handler with no request
 * id. A second concurrent call cannot be made safe by replacing the handler: the first native
 * operation is still in flight and would settle the second call's promise with the first one's result.
 * So a call is refused while one is outstanding, which is also what the UI expects (one connect at a
 * time). Each entry is cleared when its native result arrives.
 */
const inFlightSingleSlot = new Set<"connect" | "createRepository">();

function beginSingleSlot(slot: "connect" | "createRepository"): void {
  if (inFlightSingleSlot.has(slot)) {
    throw new BridgeError(`${slot}() is already in progress`);
  }
  inFlightSingleSlot.add(slot);
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
      // One global result handler, no request id: refuse rather than let a stale native result
      // settle this promise. See beginSingleSlot.
      beginSingleSlot("connect");

      // Set up callback
      (window as any).__kopiaConnectCallback = (resultJson: string) => {
        inFlightSingleSlot.delete("connect");
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
      try {
        window.KopiaBridge!.connect(JSON.stringify(request));
      } catch (error) {
        // The callback will never fire, so release the slot or no retry is ever possible.
        inFlightSingleSlot.delete("connect");
        delete (window as any).__kopiaConnectCallback;
        reject(error);
      }
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

  /**
   * Picks a folder to BACK UP. Separate from pickRestoreDestination because native also persists
   * the read grant here — a plain pick only lasts for this process, so the source would back up once
   * and then fail forever, and the grant cannot be taken retroactively.
   */
  pickBackupSource(): void {
    if (!this.isAndroid) return;
    this.parse<void>(window.KopiaBridge!.pickBackupSource());
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
    // Surface a failed pick here rather than in each subscriber: a folder picker that cannot open
    // otherwise looks like a dead button, and none of the four screens that subscribe read `error`.
    window.KopiaEvents.onDestinationPicked = (result) => {
      if (result?.error) {
        toast({ title: "Could not open the folder picker", description: result.error, variant: "destructive" });
      }
      callback(result);
    };
    return () => {
      if (window.KopiaEvents) delete window.KopiaEvents.onDestinationPicked;
    };
  }

  onBackupSourcePicked(callback: (result: SafPickResult) => void): () => void {
    if (!this.isAndroid) return () => {};
    window.KopiaEvents = window.KopiaEvents || {};
    window.KopiaEvents.onBackupSourcePicked = callback;
    return () => {
      if (window.KopiaEvents) delete window.KopiaEvents.onBackupSourcePicked;
    };
  }

  async hasStoredPassword(config: ConnectionConfig): Promise<boolean> {
    if (!this.isAndroid) return false;
    return this.parse<boolean>(
      window.KopiaBridge!.hasStoredPassword(JSON.stringify(config))
    );
  }

  // Note: no getStoredPassword — the native side never returns the plaintext password to JS. When a
  // password is stored, connect() with an empty password uses it natively. JS only checks existence
  // via hasStoredPassword.

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

export async function listSourcesWithStats(): Promise<SourceWithStats[]> {
  return callBridge<SourceWithStats[]>("listSourcesWithStats");
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

// ---------- Source management ----------

export async function getSupportedAlgorithms(): Promise<WebAlgorithms> {
  return callBridge<WebAlgorithms>("getSupportedAlgorithms");
}

export async function createRepository(request: CreateRepositoryRequest): Promise<void> {
  const bridge = window.KopiaBridge;
  if (!bridge) throw new BridgeError("KopiaBridge not available");

  return new Promise<void>((resolve, reject) => {
    // Same single-slot hazard as connect().
    beginSingleSlot("createRepository");

    window.KopiaEvents = window.KopiaEvents || {};
    window.KopiaEvents.onRepositoryCreated = (resultJson: string) => {
      inFlightSingleSlot.delete("createRepository");
      try {
        const result: WebResult<unknown> = JSON.parse(resultJson);
        if (result.success) {
          resolve();
        } else {
          reject(new BridgeError(result.error ?? "Repository creation failed"));
        }
      } catch {
        reject(new BridgeError("Invalid response from createRepository bridge"));
      } finally {
        if (window.KopiaEvents) delete window.KopiaEvents.onRepositoryCreated;
      }
    };

    try {
      bridge.createRepository(JSON.stringify(request));
    } catch (error) {
      // The callback will never fire, so release the slot or no retry is ever possible.
      inFlightSingleSlot.delete("createRepository");
      if (window.KopiaEvents) delete window.KopiaEvents.onRepositoryCreated;
      reject(error);
    }
  });
}

export async function testStorageConnection(config: ConnectionConfig): Promise<string> {
  return callBridge<string>("testStorageConnection", config);
}

export async function getAllSourceStatuses(): Promise<WebSourceStatus[]> {
  return callBridge<WebSourceStatus[]>("listAllSources");
}

export async function getSourceStatus(sourceId: string): Promise<WebSourceStatus> {
  return callBridge<WebSourceStatus>("getSourceStatus", sourceId);
}

export async function createSource(request: CreateSourceRequest): Promise<void> {
  callBridge<void>("createSource", request);
}

export async function deleteSource(sourceId: string): Promise<void> {
  callBridge<void>("deleteSource", sourceId);
}

/** @returns the id of the task tracking the run — needed to show progress or cancel it. */
export async function startBackup(sourceId: string): Promise<string> {
  return callBridge<string>("startBackup", sourceId);
}

export async function pauseSource(sourceId: string): Promise<void> {
  callBridge<void>("pauseSource", sourceId);
}

/**
 * Opens the folder picker for a NEW BACKUP SOURCE and persists the read grant. Throws if a picker is
 * already open. The chosen folder arrives on the onBackupSourcePicked event.
 */
export function pickBackupSource(): void {
  kopiaBridge.pickBackupSource();
}

export function onBackupSourcePicked(callback: (result: SafPickResult) => void): () => void {
  return kopiaBridge.onBackupSourcePicked(callback);
}

export async function resumeSource(sourceId: string): Promise<void> {
  callBridge<void>("resumeSource", sourceId);
}

// ---------- Policy management ----------
// The Kotlin policy methods decode TYPED requests (WebPolicySourceRequest {host, userName, path};
// setPolicy: WebSetPolicyRequest {source, policy}) - NOT the UI's joined "user@host:path" id.
// Translate here so the UI keeps speaking sourceId while the wire honors the bridge contract.

export async function getPolicy(sourceId: string): Promise<WebPolicy> {
  return callBridge<WebPolicy>("getPolicy", parseSourceId(sourceId));
}

export async function setPolicy(request: SetPolicyRequest): Promise<void> {
  return callBridge<void>("setPolicy", {
    source: parseSourceId(request.sourceId),
    policy: request.policy,
  });
}

export async function resolvePolicy(sourceId: string): Promise<WebResolvedPolicy> {
  return callBridge<WebResolvedPolicy>("resolvePolicy", parseSourceId(sourceId));
}

export async function listPolicies(): Promise<WebPolicyEntry[]> {
  return callBridge<WebPolicyEntry[]>("listPolicies");
}

export async function deletePolicy(sourceId: string): Promise<void> {
  return callBridge<void>("deletePolicy", parseSourceId(sourceId));
}

// ---------- Task management ----------

export async function listTasks(): Promise<WebTaskInfo[]> {
  return callBridge<WebTaskInfo[]>("listTasks");
}

export async function getTask(taskId: string): Promise<WebTaskInfo> {
  return callBridge<WebTaskInfo>("getTask", taskId);
}

export async function cancelTask(taskId: string): Promise<void> {
  callBridge<void>("cancelTask", taskId);
}

export async function getTaskLogs(taskId: string): Promise<WebTaskLogEntry[]> {
  return callBridge<WebTaskLogEntry[]>("getTaskLogs", taskId);
}

// ---------- Backup estimation ----------

export async function estimateBackup(request: EstimateBackupRequest): Promise<string> {
  // Returns the estimation task id so the dialog can poll it. The native method rejects while
  // estimation is unimplemented (callers handle the error); once implemented it returns the id.
  return callBridge<string>("estimateBackup", request);
}

// ---------- Maintenance ----------

export async function triggerMaintenance(mode: string): Promise<void> {
  callBridge<void>("triggerMaintenance", mode);
}

export async function getMaintenanceStatus(): Promise<WebMaintenanceStatus> {
  return callBridge<WebMaintenanceStatus>("getMaintenanceStatus");
}
