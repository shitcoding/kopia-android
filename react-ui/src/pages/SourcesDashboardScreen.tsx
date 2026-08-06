import { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Plus,
  FolderX,
  Loader2,
  AlertTriangle,
  MoreVertical,
  Play,
  Eye,
  FileEdit,
  Trash2,
  BarChart3,
  Settings,
} from "lucide-react";
import ExitDoorIcon from "@/components/ExitDoorIcon";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { useToast } from "@/hooks/use-toast";
import { formatFileSize, formatRelativeTime } from "@/lib/format";
import type { WebSourceStatus } from "@/types/kopia";
import { useSources, useStartBackup, useDeleteSource } from "@/hooks/useBackupApi";
import BackupProgressSheet from "@/components/BackupProgressSheet";
import EstimationDialog from "@/components/EstimationDialog";

// Only what the native SourceStatus enum can emit. It previously also listed SCHEDULED and FAILED,
// which no code path could ever produce. PAUSED is kept because the native enum still has it and the
// bridge could still return it -- not because a stored source could: BackupSourceManager
// deliberately does not persist status, so a reloaded source always comes back IDLE.
const STATUS_BADGE: Record<WebSourceStatus["status"], { label: string; className: string }> = {
  IDLE: { label: "Idle", className: "bg-muted text-muted-foreground" },
  UPLOADING: { label: "Uploading", className: "bg-primary/15 text-primary" },
  PAUSED: { label: "Paused", className: "bg-yellow-500/15 text-yellow-700 dark:text-yellow-300" },
};

const UNKNOWN_BADGE = { label: "Unknown", className: "bg-muted text-muted-foreground" };

const SourcesDashboardScreen = () => {
  const navigate = useNavigate();
  const { toast } = useToast();
  const { data: sources = [], isLoading } = useSources();
  const startBackup = useStartBackup();
  const deleteSource = useDeleteSource();
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
  const [progressTaskId, setProgressTaskId] = useState<string | null>(null);
  const [estimateSourceId, setEstimateSourceId] = useState<string | null>(null);

  const handleBackupNow = (sid: string) => {
    startBackup.mutate(sid, {
      // The returned task id is the handle for watching and cancelling the run; open the progress
      // sheet on it rather than dropping it and showing a toast that says nothing.
      onSuccess: (taskId) => setProgressTaskId(taskId),
      onError: (err) => toast({ title: "Failed to start backup", description: String(err), variant: "destructive" }),
    });
  };

  const handleDelete = () => {
    if (!deleteTarget) return;
    deleteSource.mutate(deleteTarget, {
      onSuccess: () => { toast({ title: "Source deleted" }); setDeleteTarget(null); },
      onError: (err) => { toast({ title: "Failed to delete source", description: String(err), variant: "destructive" }); setDeleteTarget(null); },
    });
  };

  const handleViewSnapshots = (src: WebSourceStatus) => {
    const params = new URLSearchParams({
      host: src.source.host,
      userName: src.source.userName,
      path: src.source.path,
    });
    navigate(`/snapshots/source?${params.toString()}`, { state: { from: "/sources" } });
  };

  const isEmpty = sources.length === 0;

  return (
    <div className="app-container min-h-screen flex flex-col">
      <header className="app-bar">
        <div className="w-9" />
        <h1 className="app-bar-title">Backup Sources</h1>
        <div className="flex items-center gap-1">
          <button onClick={() => navigate("/connect")} className="btn-icon" title="Disconnect" aria-label="Disconnect">
            <ExitDoorIcon className="w-5 h-5" />
          </button>
          <button onClick={() => navigate("/settings")} className="btn-icon" aria-label="Settings">
            <Settings className="w-5 h-5" />
          </button>
        </div>
      </header>

      <div className="flex-1 p-4">
        {isLoading ? (
          <div className="flex flex-col items-center justify-center py-20 animate-fade-in">
            <Loader2 className="w-8 h-8 text-primary animate-spin mb-3" />
            <p className="text-muted-foreground">Loading sources...</p>
          </div>
        ) : isEmpty ? (
          <div className="flex-1 flex flex-col items-center justify-center py-20 text-center animate-fade-in">
            <div className="w-16 h-16 rounded-full bg-muted flex items-center justify-center mb-4">
              <FolderX className="w-8 h-8 text-muted-foreground" />
            </div>
            <p className="text-lg font-medium text-foreground mb-1">No backup sources configured</p>
            <p className="text-muted-foreground mb-6">Add a folder to start backing up</p>
            <button onClick={() => navigate("/sources/add")} className="btn-primary" aria-label="Add your first source">
              <Plus className="w-5 h-5" />
              Add Your First Source
            </button>
          </div>
        ) : (
          <div className="space-y-3">
            {/* Add source button */}
            <button
              onClick={() => navigate("/sources/add")}
              className="w-full card-elevated flex items-center gap-3 text-left hover:shadow-lg transition-all active:scale-[0.99] border-2 border-dashed border-primary/20"
              aria-label="Add backup source"
            >
              <div className="w-10 h-10 rounded-full bg-primary/10 flex items-center justify-center">
                <Plus className="w-5 h-5 text-primary" />
              </div>
              <p className="font-medium text-primary">Add Backup Source</p>
            </button>

            {sources.map((src, index) => {
              // Native's id, never a locally rebuilt user@host:path — see WebSourceStatus.id.
              const sid = src.id;
              const badge = STATUS_BADGE[src.status] ?? UNKNOWN_BADGE;
              // Processed (hashed + cached), not Uploaded, and capped at 99 while the run is live --
              // the same basis as the Tasks screen and the progress sheet. "Uploaded Bytes" is what
              // actually went to storage after dedup and compression, so an incremental run that
              // mostly cached would sit near 0% and then jump to done; and reaching the estimate is
              // not finishing, because the manifest still has to be written, flushed and retained.
              const counters = src.uploadCounters;
              const uploadProgress = counters
                ? Math.min(
                    99,
                    Math.round(
                      ((counters.totalHashedBytes + counters.totalCachedBytes) /
                        Math.max(counters.estimatedBytes, 1)) * 100,
                    ),
                  )
                : 0;

              return (
                <div
                  key={sid}
                  className="card-elevated animate-slide-up"
                  style={{ animationDelay: `${index * 0.05}s` }}
                >
                  <div className="flex items-start justify-between gap-2">
                    <button
                      onClick={() => src.status === "UPLOADING" && src.currentTaskId ? setProgressTaskId(src.currentTaskId) : handleViewSnapshots(src)}
                      className="flex-1 text-left min-w-0"
                      aria-label={`Source ${src.source.path.split("/").pop() || src.source.path}`}
                    >
                      <div className="flex items-center gap-2 mb-1">
                        <p className="font-semibold text-foreground truncate text-sm">
                          {src.source.path.split("/").pop() || src.source.path}
                        </p>
                        {/* Stateful element: the aria-label must track the label, or E2E can't
                            tell "Paused" on this row from "Paused" anywhere else on screen. */}
                        <span
                          role="status"
                          className={`text-xs px-2 py-0.5 rounded-full font-medium ${badge.className}`}
                          aria-label={`Source status ${badge.label}`}
                        >
                          {badge.label}
                        </span>
                      </div>
                      <p className="text-xs text-muted-foreground truncate">{src.source.path}</p>

                      {/* Upload progress */}
                      {src.status === "UPLOADING" && src.uploadCounters && (
                        <div className="mt-2">
                          <div className="progress-bar">
                            <div className="progress-fill" style={{ width: `${uploadProgress}%` }} />
                          </div>
                          <p className="text-xs text-muted-foreground mt-1">
                            {formatFileSize(src.uploadCounters.totalHashedBytes + src.uploadCounters.totalCachedBytes)} / {formatFileSize(src.uploadCounters.estimatedBytes)}
                          </p>
                        </div>
                      )}

                      {/* A backup that died in a background process leaves nothing else behind:
                          the task the user was watching is long gone, and the error notification is
                          dropped outright on API 33+ when POST_NOTIFICATIONS was denied. Without
                          this the row looks exactly like a source that has never failed. */}
                      {src.lastError && (
                        <div className="flex items-start gap-1.5 mt-2 text-xs text-destructive">
                          <AlertTriangle className="w-3.5 h-3.5 flex-shrink-0 mt-px" />
                          <span className="min-w-0 break-words">
                            Last backup failed
                            {src.lastErrorTimeEpochMs ? ` ${formatRelativeTime(src.lastErrorTimeEpochMs)}` : ""}
                            : {src.lastError}
                          </span>
                        </div>
                      )}

                      {/* Metadata */}
                      <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground mt-2">
                        {src.lastBackupTimeEpochMs && (
                          <span>Last: {formatRelativeTime(src.lastBackupTimeEpochMs)}</span>
                        )}
                        <span>{src.snapshotCount} snapshots · {formatFileSize(src.totalFileSize)}</span>
                      </div>
                    </button>

                    {/* Actions menu */}
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <button className="btn-icon -mr-2 -mt-1" aria-label="Source options">
                          <MoreVertical className="w-5 h-5" />
                        </button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end">
                        <DropdownMenuItem onClick={() => handleBackupNow(sid)}>
                          <Play className="w-4 h-4 mr-2" /> Back Up Now
                        </DropdownMenuItem>
                        <DropdownMenuItem onClick={() => handleViewSnapshots(src)}>
                          <Eye className="w-4 h-4 mr-2" /> View Snapshots
                        </DropdownMenuItem>
                        <DropdownMenuItem onClick={() => navigate(`/sources/${encodeURIComponent(sid)}/policy`)}>
                          <FileEdit className="w-4 h-4 mr-2" /> Edit Policy
                        </DropdownMenuItem>
                        <DropdownMenuItem onClick={() => setEstimateSourceId(sid)}>
                          <BarChart3 className="w-4 h-4 mr-2" /> Estimate
                        </DropdownMenuItem>
                        <DropdownMenuItem onClick={() => setDeleteTarget(sid)} className="text-destructive focus:text-destructive">
                          <Trash2 className="w-4 h-4 mr-2" /> Delete Source
                        </DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Delete Confirmation */}
      <AlertDialog open={!!deleteTarget} onOpenChange={() => setDeleteTarget(null)}>
        <AlertDialogContent className="max-w-sm mx-4 rounded-2xl">
          <AlertDialogHeader>
            <AlertDialogTitle>Delete Source?</AlertDialogTitle>
            <AlertDialogDescription>This stops backing up this folder. Its policy and its existing snapshots are kept in the repository.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter className="flex-row gap-3">
            <AlertDialogCancel className="flex-1 mt-0">Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className="flex-1 bg-destructive text-destructive-foreground hover:bg-destructive/90">Delete</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Backup Progress Sheet */}
      {progressTaskId && (
        <BackupProgressSheet taskId={progressTaskId} onClose={() => setProgressTaskId(null)} />
      )}

      {/* Estimation Dialog */}
      {estimateSourceId && (
        <EstimationDialog sourceId={estimateSourceId} onClose={() => setEstimateSourceId(null)} />
      )}
    </div>
  );
};

export default SourcesDashboardScreen;
