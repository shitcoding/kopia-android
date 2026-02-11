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
