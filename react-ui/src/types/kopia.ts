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
  tags?: Record<string, string>;
}

/** Summary of a backup source with aggregated stats from its latest snapshot */
export interface SourceWithStats {
  source: SourceInfo;
  snapshotCount: number;
  latestSnapshotTime: number;
  totalFileCount: number;
  totalFileSize: number;
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
}

// ===== Upload / Source / Task Types =====

/** Upload progress counters */
export interface WebUploadCounters {
  totalCachedBytes: number;
  totalHashedBytes: number;
  totalUploadedBytes: number;
  estimatedBytes: number;
  totalCachedFiles: number;
  totalHashedFiles: number;
  totalExcludedFiles: number;
  totalExcludedDirs: number;
  fatalErrorCount: number;
  ignoredErrorCount: number;
  estimatedFiles: number;
  currentDirectory: string;
}

/** Source status */
export interface WebSourceStatus {
  /**
   * The handle to pass back to startBackup/pauseSource/resumeSource/getSourceStatus. Native is
   * authoritative about it — never rebuild it from `source`, which is what used to make every one
   * of those calls answer "Source not found".
   */
  id: string;
  source: SourceInfo;
  status: "IDLE" | "UPLOADING" | "PAUSED" | "FAILED" | "SCHEDULED";
  nextBackupTimeEpochMs?: number;
  lastBackupTimeEpochMs?: number;
  uploadCounters?: WebUploadCounters;
  currentTaskId?: string;
  snapshotCount: number;
  totalFileSize: number;
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
  counters?: WebUploadCounters;
  error?: string;
}

/** Task log entry */
export interface WebTaskLogEntry {
  timestamp: number;
  level: "debug" | "info" | "warning" | "error";
  module: string;
  message: string;
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
    compression: string;
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
