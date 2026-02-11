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
}

export interface WebDavConfig {
  url: string;
  username: string;
}

export interface SftpConfig {
  host: string;
  port: number;
  username: string;
  path: string;
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
  password: string;
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
