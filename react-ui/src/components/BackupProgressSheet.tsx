import { useState } from "react";
import { X } from "lucide-react";
import { formatFileSize } from "@/lib/format";
import { useTask, useCancelTask } from "@/hooks/useBackupApi";
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

interface BackupProgressSheetProps {
  taskId: string;
  onClose: () => void;
}

const BackupProgressSheet = ({ taskId, onClose }: BackupProgressSheetProps) => {
  const { data: task } = useTask(taskId);
  const cancelTask = useCancelTask();
  const [showCancelDialog, setShowCancelDialog] = useState(false);

  const counters = task?.counters;
  const progress = counters
    ? Math.round((counters.totalUploadedBytes / Math.max(counters.estimatedBytes, 1)) * 100)
    : 0;

  const handleCancel = () => {
    cancelTask.mutate(taskId);
    setShowCancelDialog(false);
    onClose();
  };

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 z-50 bg-black/50 animate-fade-in" onClick={onClose} />

      {/* Sheet */}
      <div className="fixed inset-x-0 bottom-0 z-50 bg-card rounded-t-2xl shadow-xl animate-slide-up max-w-md mx-auto">
        <div className="p-4">
          {/* Header */}
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold text-foreground text-sm">Backup Progress</h3>
            <button onClick={onClose} className="btn-icon -mr-2">
              <X className="w-5 h-5" />
            </button>
          </div>

          {/* Progress bar */}
          <div className="mb-4">
            <div className="progress-bar h-3">
              <div className="progress-fill" style={{ width: `${progress}%` }} />
            </div>
            <p className="text-center text-lg font-bold text-foreground mt-2">{progress}%</p>
          </div>

          {/* Stats grid */}
          {counters && (
            <div className="grid grid-cols-2 gap-3 mb-4">
              <div className="card-elevated !p-3">
                <p className="text-xs text-muted-foreground">Files hashed</p>
                <p className="text-sm font-semibold text-foreground">{counters.totalHashedFiles.toLocaleString()} / {counters.estimatedFiles.toLocaleString()}</p>
              </div>
              <div className="card-elevated !p-3">
                <p className="text-xs text-muted-foreground">Files cached</p>
                <p className="text-sm font-semibold text-foreground">{counters.totalCachedFiles.toLocaleString()}</p>
              </div>
              <div className="card-elevated !p-3">
                <p className="text-xs text-muted-foreground">Uploaded</p>
                <p className="text-sm font-semibold text-foreground">{formatFileSize(counters.totalUploadedBytes)} / {formatFileSize(counters.estimatedBytes)}</p>
              </div>
              <div className="card-elevated !p-3">
                <p className="text-xs text-muted-foreground">Excluded</p>
                <p className="text-sm font-semibold text-foreground">{counters.totalExcludedFiles} files, {counters.totalExcludedDirs} dirs</p>
              </div>
              <div className="card-elevated !p-3 col-span-2">
                <p className="text-xs text-muted-foreground">Current directory</p>
                <p className="text-xs font-medium text-foreground truncate">{counters.currentDirectory || "—"}</p>
              </div>
              {(counters.fatalErrorCount > 0 || counters.ignoredErrorCount > 0) && (
                <div className="card-elevated !p-3 col-span-2">
                  <p className="text-xs text-muted-foreground">Errors</p>
                  <p className="text-sm font-semibold text-foreground">{counters.ignoredErrorCount} ignored, {counters.fatalErrorCount} fatal</p>
                </div>
              )}
            </div>
          )}

          {/* Cancel button */}
          <button
            onClick={() => setShowCancelDialog(true)}
            className="w-full py-3 rounded-full border-2 border-destructive/30 text-destructive font-medium transition-all hover:bg-destructive/5 active:scale-[0.98]"
          >
            Cancel Backup
          </button>
        </div>

        {/* Safe area padding */}
        <div className="h-4" />
      </div>

      {/* Cancel Confirmation */}
      <AlertDialog open={showCancelDialog} onOpenChange={setShowCancelDialog}>
        <AlertDialogContent className="max-w-sm mx-4 rounded-2xl">
          <AlertDialogHeader>
            <AlertDialogTitle>Cancel Backup?</AlertDialogTitle>
            <AlertDialogDescription>The backup in progress will be stopped. Partial data may be uploaded.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter className="flex-row gap-3">
            <AlertDialogCancel className="flex-1 mt-0">Keep Running</AlertDialogCancel>
            <AlertDialogAction onClick={handleCancel} className="flex-1 bg-destructive text-destructive-foreground hover:bg-destructive/90">Cancel Backup</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
};

export default BackupProgressSheet;
