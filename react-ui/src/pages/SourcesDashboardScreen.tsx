import { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Plus,
  FolderX,
  Loader2,
  MoreVertical,
  Play,
  Pause,
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
import { useSources, useStartBackup, usePauseSource, useResumeSource, useDeleteSource } from "@/hooks/useBackupApi";
import BackupProgressSheet from "@/components/BackupProgressSheet";
import EstimationDialog from "@/components/EstimationDialog";

const STATUS_BADGE: Record<WebSourceStatus["status"], { label: string; className: string }> = {
  IDLE: { label: "Idle", className: "bg-muted text-muted-foreground" },
  UPLOADING: { label: "Uploading", className: "bg-primary/15 text-primary" },
  SCHEDULED: { label: "Scheduled", className: "bg-green-500/15 text-green-700 dark:text-green-300" },
  PAUSED: { label: "Paused", className: "bg-yellow-500/15 text-yellow-700 dark:text-yellow-300" },
  FAILED: { label: "Failed", className: "bg-destructive/15 text-destructive" },
};

const SourcesDashboardScreen = () => {
  const navigate = useNavigate();
  const { toast } = useToast();
  const { data: sources = [], isLoading } = useSources();
  const startBackup = useStartBackup();
  const pauseSource = usePauseSource();
  const resumeSource = useResumeSource();
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

  const handlePause = (sid: string) => {
    pauseSource.mutate(sid, {
      onSuccess: () => toast({ title: "Source paused" }),
      onError: (err) => toast({ title: "Failed to pause source", description: String(err), variant: "destructive" }),
    });
  };

  const handleResume = (sid: string) => {
    resumeSource.mutate(sid, {
      onSuccess: () => toast({ title: "Source resumed" }),
      onError: (err) => toast({ title: "Failed to resume source", description: String(err), variant: "destructive" }),
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
    navigate(`/snapshots/source?${params.toString()}`);
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
              const badge = STATUS_BADGE[src.status];
              const uploadProgress = src.uploadCounters
                ? Math.round((src.uploadCounters.totalUploadedBytes / Math.max(src.uploadCounters.estimatedBytes, 1)) * 100)
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
                            {formatFileSize(src.uploadCounters.totalUploadedBytes)} / {formatFileSize(src.uploadCounters.estimatedBytes)}
                          </p>
                        </div>
                      )}

                      {/* Metadata */}
                      <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground mt-2">
                        {src.lastBackupTimeEpochMs && (
                          <span>Last: {formatRelativeTime(src.lastBackupTimeEpochMs)}</span>
                        )}
                        {src.nextBackupTimeEpochMs && src.status !== "PAUSED" && (
                          <span>Next: {formatRelativeTime(src.nextBackupTimeEpochMs)}</span>
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
                        {src.status === "PAUSED" ? (
                          <DropdownMenuItem onClick={() => handleResume(sid)}>
                            <Play className="w-4 h-4 mr-2" /> Resume
                          </DropdownMenuItem>
                        ) : (
                          <DropdownMenuItem onClick={() => handlePause(sid)}>
                            <Pause className="w-4 h-4 mr-2" /> Pause
                          </DropdownMenuItem>
                        )}
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
