export function formatFileSize(bytes: number): string {
  if (bytes === 0) return "0 B";
  // Use base-10 (1000) to match Kopia GUI, not base-2 (1024)
  const units = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.floor(Math.log(bytes) / Math.log(1000));
  const value = bytes / Math.pow(1000, i);
  // Always show 1 decimal for values < 100, then round for larger values
  // This matches Kopia GUI behavior
  return `${value < 100 ? value.toFixed(1) : Math.round(value)} ${units[i]}`;
}

export function formatDateTime(epochMs: number): string {
  return new Date(epochMs).toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

export function formatRelativeTime(epochMs: number): string {
  const now = Date.now();
  const diffMs = now - epochMs;
  const absDiff = Math.abs(diffMs);
  const isFuture = diffMs < 0;

  const seconds = Math.floor(absDiff / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);

  let label: string;
  if (seconds < 60) {
    label = "just now";
    return label;
  } else if (minutes < 60) {
    label = `${minutes}m`;
  } else if (hours < 24) {
    label = `${hours}h`;
  } else if (days < 30) {
    label = `${days}d`;
  } else {
    return formatDateTime(epochMs);
  }

  return isFuture ? `in ${label}` : `${label} ago`;
}

export function formatDuration(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const remainingSeconds = seconds % 60;
  if (minutes < 60) return `${minutes}m ${remainingSeconds}s`;
  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  return `${hours}h ${remainingMinutes}m`;
}

export function sourceId(source: { userName: string; host: string; path: string }): string {
  return `${source.userName}@${source.host}:${source.path}`;
}
