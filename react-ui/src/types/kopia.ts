/**
 * TypeScript type definitions for Kopia bridge communication.
 * These types match the Kotlin WebModels.kt serializable classes.
 */

// ===== Storage Configuration Types =====

export type StorageType =
  | "LOCAL_FILESYSTEM"
  | "S3"
  | "WEBDAV"
  | "SFTP"
  | "SAF";

export interface LocalConfig {
  path: string;
}

export interface S3Config {
  bucket: string;
  endpoint: string;
  region: string;
  accessKeyId: string;
  secretAccessKey: string;
  /** PEM-encoded root CA to trust instead of the system store (private/self-signed servers). */
  rootCaPem?: string;
  /** Explicit acknowledgment that credentials may travel over plaintext http. */
  allowCleartextHttp?: boolean;
}

export interface WebDavConfig {
  url: string;
  username: string;
  password: string;
  /** SHA-256 fingerprint of the one server certificate to trust (self-signed servers). */
  trustedServerCertificateFingerprint?: string;
  /** Explicit acknowledgment that credentials may travel over plaintext http. */
  allowCleartextHttp?: boolean;
}

export interface SftpConfig {
  host: string;
  port: number;
  username: string;
  path: string;
  password: string;
  /** OpenSSH known_hosts content pinning the server key (preferred trust material). */
  knownHostsData?: string;
  /** Host key fingerprint to pin ("SHA256:<base64>"), used if no known_hosts is supplied. */
  hostKeyFingerprint?: string;
  /** Trust ANY server key — insecure, testing only; rejected by release builds. */
  insecureSkipHostKeyVerification?: boolean;
}

export interface SafConfig {
  treeUri: string;
  displayPath: string;
}

export interface ConnectionConfig {
  storageType: StorageType;
  local?: LocalConfig;
  s3?: S3Config;
  webdav?: WebDavConfig;
  sftp?: SftpConfig;
  saf?: SafConfig;
}

// ===== Request Types =====

export interface ConnectRequest {
  config: ConnectionConfig;
  repositoryPassword: string;
}

export interface SnapshotListRequest {
  source?: SourceInfo;
}

export interface ListDirectoryRequest {
  snapshotId: string;
  path: string;
  pageToken?: string;
  pageSize?: number;
}

export interface RestoreOptions {
  parallel?: number;
  incremental?: boolean;
  overwriteExisting?: boolean;
}

export interface RestoreRequest {
  snapshotId: string;
  sourcePath: string;
  destinationUri: string;
  options?: RestoreOptions;
}

export interface PersistUriRequest {
  uri: string;
  read?: boolean;
  write?: boolean;
}

/** Request to delete multiple snapshots */
export interface DeleteSnapshotsRequest {
  snapshotIds: string[];
}

// ===== Response Types =====

export interface RepositoryConnection {
  id: string;
  displayName: string;
  storageType: StorageType;
  connectionConfig: ConnectionConfig;
  lastConnectedEpochMs?: number;
  isConnected: boolean;
}

export interface SourceInfo {
  host: string;
  userName: string;
  path: string;
}

export interface SnapshotStats {
  totalFileSize: number;
  totalFileCount: number;
  totalDirectoryCount: number;
}

export interface SnapshotInfo {
  id: string;
  source: SourceInfo;
  startTimeEpochMs: number;
  endTimeEpochMs?: number;
  description?: string;
  stats?: SnapshotStats;
  isIncomplete?: boolean;
  /**
   * Entries the run could not read (`numFailed` in the manifest, task-63). Zero for a healthy one.
   *
   * NOT the same as `isIncomplete`: a snapshot with failures is complete — the run finished and
   * saved what it could read — so nothing keying on `isIncomplete` speaks for it. See
   * `lib/snapshotHealth`.
   */
  failedEntryCount?: number;
  tags?: Record<string, string>;
}

/**
 * Summary of a backup source, from its latest COMPLETE snapshot.
 *
 * `snapshotCount` counts complete snapshots only — the same rule as Go's `kopia snapshot list`
 * without `--incomplete`, and the same number SourceSnapshotsScreen prints over the list itself.
 * An unfinished run (cancelled, or a checkpoint retention keeps) is not a restore point, and its
 * partial size is not the size of the source.
 */
export interface SourceWithStats {
  source: SourceInfo;
  snapshotCount: number;
  latestSnapshotTime: number;
  totalFileCount: number;
  totalFileSize: number;
  /**
   * Entries the newest COMPLETE snapshot could not read (task-63) — i.e. the very snapshot the
   * counts beside it describe. Zero for a healthy source.
   */
  latestFailedEntryCount?: number;
}

/** Snapshot info extended with computed retention reasons */
export interface SnapshotWithRetention extends SnapshotInfo {
  retentionReasons: string[];
}

export type FileEntryType = "FILE" | "DIRECTORY" | "SYMLINK" | "UNKNOWN";

export interface FileEntry {
  name: string;
  type: FileEntryType;
  size: number;
  modTimeEpochMs?: number;
  permissions: number;
  objectId?: string;
}

export interface DirectoryPage {
  entries: FileEntry[];
  nextPageToken?: string;
}

export type RestoreState =
  | "IDLE"
  | "PREPARING"
  | "IN_PROGRESS"
  | "COMPLETED"
  | "FAILED"
  | "CANCELLED";

export interface RestoreProgress {
  state: RestoreState;
  totalFiles: number;
  restoredFiles: number;
  totalBytes: number;
  restoredBytes: number;
  currentFile?: string;
  errorMessage?: string;
}

export interface SafPickResult {
  uri?: string;
  displayName?: string;
  /** Set when the pick failed, as opposed to the user cancelling (both leave `uri` unset). */
  error?: string;
}

// ===== Upload / Source / Task Types =====

/**
 * One entry of a task's counter map. Kotlin emits Go's shape — an open map of named counters, each
 * with its own unit — rather than a fixed struct, so new counters appear without a wire change.
 * `units` is Go's own vocabulary ("bytes", "count", ...).
 */
export interface WebTaskCounter {
  value: number;
  units: string;
  level?: string;
}

/** Source status */
export interface WebSourceStatus {
  /**
   * The handle to pass back to startBackup and the other per-source bridge calls. Native is
   * authoritative about it — never rebuild it from `source`, which is what used to make every one
   * of those calls answer "Source not found".
   */
  id: string;
  source: SourceInfo;
  /** Mirrors the native SourceStatus enum exactly. */
  status: "IDLE" | "UPLOADING";
  lastBackupTimeEpochMs?: number;
  /** Why the last backup ended without a snapshot; absent when the last one succeeded. */
  lastError?: string;
  lastErrorTimeEpochMs?: number;
  /**
   * The counters of the run named by `currentTaskId`, absent until that run has some to report.
   * Go's named map, the same shape and vocabulary as WebTaskInfo.counters — not a second fixed
   * struct, which is what this field used to be and why it silently carried nothing.
   *
   * Explicitly `| null`: the bridge encodes defaults, so an idle source really does send
   * `"uploadCounters": null` rather than omitting the key.
   */
  uploadCounters?: Record<string, WebTaskCounter> | null;
  /** The task uploading this source right now; the handle the progress sheet opens on. */
  currentTaskId?: string | null;
  /**
   * How many COMPLETE snapshots the repository holds for this source, and how much the newest
   * complete one occupies after deduplication. Absent when the repository cannot say — not
   * connected, unreadable, or it holds no manifest at all for this source. They used to be
   * hardcoded to zero on the native side, so every row read "0 snapshots · 0 B" beside a real
   * "Last backup" time.
   *
   * Zero is a different answer from absent: a source whose only run was cancelled has manifests but
   * nothing complete, and reports 0. The per-source screen is where that is spelled out.
   */
  snapshotCount?: number | null;
  totalFileSize?: number | null;
  /**
   * Entries the newest COMPLETE snapshot could not read (task-63) — the snapshot the two counts
   * above describe. `lastError` cannot cover this: a run that completes with errors is a success and
   * clears it.
   */
  latestFailedEntryCount?: number | null;
}

/** Task info */
export interface WebTaskInfo {
  id: string;
  kind: "Snapshot" | "Restore" | "Maintenance" | "Estimate";
  status: "RUNNING" | "CANCELING" | "SUCCESS" | "FAILED" | "CANCELED";
  description: string;
  startTimeEpochMs: number;
  endTimeEpochMs?: number;
  progressInfo: string;
  /** Named counters, Go's open-map shape. Empty until the run reports any. */
  counters?: Record<string, WebTaskCounter>;
  error?: string;
}

// ===== Policy Types =====

/** Retention policy */
export interface WebRetentionPolicy {
  keepLatest?: number;
  keepHourly?: number;
  keepDaily?: number;
  keepWeekly?: number;
  keepMonthly?: number;
  keepAnnual?: number;
  ignoreIdenticalSnapshots?: boolean;
}

/** Scheduling policy. Field names follow the Kotlin/Go MANIFEST wire format ("timeOfDay", "min"). */
export interface WebSchedulingPolicy {
  intervalSeconds?: number;
  timeOfDay?: Array<{ hour: number; min: number }>;
  manual?: boolean;
  runMissed?: boolean;
}

/** Compression policy */
export interface WebCompressionPolicy {
  compressorName?: string;
  onlyCompress?: string[];
  neverCompress?: string[];
  minSize?: number;
  maxSize?: number;
}

/** Files policy */
export interface WebFilesPolicy {
  ignore?: string[];
  maxFileSize?: number;
}

/** Full policy. Field names follow the Kotlin Policy @SerialName wire format, which mirrors the
 * Go kopia repository manifest JSON ("retention", "scheduling", "compression", "files") - NOT
 * "*Policy"-suffixed names. The bridge Json uses ignoreUnknownKeys, so wrong names are silently
 * dropped and the policy saves as empty defaults (the bug behind the broken policy surface). */
export interface WebPolicy {
  retention?: WebRetentionPolicy;
  scheduling?: WebSchedulingPolicy;
  compression?: WebCompressionPolicy;
  files?: WebFilesPolicy;
}

/** Resolved policy (effective + definition) */
export interface WebResolvedPolicy {
  effective: WebPolicy;
  defined?: WebPolicy;
  upcomingSnapshotTimes: number[];
}

/** Policy entry (for listing) */
export interface WebPolicyEntry {
  source: SourceInfo;
  policy: WebPolicy;
}

// ===== Maintenance Types =====

/** Maintenance status */
export interface WebMaintenanceStatus {
  lastRunTimeEpochMs?: number;
  lastMode?: string;
  lastSuccess?: boolean;
  lastError?: string;
  lastGcStats?: {
    deletedContentCount: number;
    reclaimedBytes: number;
  };
}

// ===== Repository / Algorithm Types =====

/** Supported algorithms */
export interface WebAlgorithms {
  hashing: string[];
  encryption: string[];
  compression: string[];
}

/** Repository connection info */
export interface WebRepositoryConnection {
  storageType: string;
  encryption: string;
  hashing: string;
  description?: string;
}

// ===== New Request Types =====

/** Create repository request */
export interface CreateRepositoryRequest {
  config: ConnectionConfig;
  password: string;
  options: {
    hash: string;
    encryption: string;
    description?: string;
  };
}

/** Create source request */
export interface CreateSourceRequest {
  uri: string;
  policy: WebPolicy;
  startBackup: boolean;
}

/** Estimate backup request */
export interface EstimateBackupRequest {
  sourceId: string;
  policyOverride?: WebPolicy;
}

/** Set policy request */
export interface SetPolicyRequest {
  sourceId: string;
  policy: WebPolicy;
}

// ===== Result Wrapper =====

export interface WebResult<T> {
  success: boolean;
  data?: T;
  error?: string;
  errorCode?: string;
}

// ===== Error Codes =====

export const ErrorCodes = {
  STORAGE_PERMISSION_REQUIRED: "STORAGE_PERMISSION_REQUIRED",
} as const;
