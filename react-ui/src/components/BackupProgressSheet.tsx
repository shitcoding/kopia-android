import { useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { X } from "lucide-react";
import { formatFileSize, uploadProgressPercent } from "@/lib/format";
import { useTask, useCancelTask } from "@/hooks/useBackupApi";
import type { WebTaskCounter } from "@/types/kopia";
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
  const qc = useQueryClient();

  // Close once the run is over. Leaving a finished backup's progress sheet on screen - with a
  // "Cancel Backup" button under it - reads as though it were still running. The outcome is carried
  // by the completion notification.
  const status = task?.status;
  useEffect(() => {
    if (status === "SUCCESS" || status === "FAILED" || status === "CANCELED") {
      // This sheet learns the run is over within one 2s task poll; the sources list is polled
      // separately, every 5s and ONLY while something is UPLOADING. So for up to a poll after this
      // sheet closes, the dashboard row still claims a run in progress — and a tap on such a row
      // opens a progress sheet for a task that has already finished, which closes itself again
      // instantly, instead of opening the source. The tap looks ignored. Refresh the rows from the
      // one place that already knows the run ended.
      qc.invalidateQueries({ queryKey: ["backup-sources"] });
      onClose();
    }
  }, [status, onClose, qc]);

  // Counters are Go's open map of named values, not a fixed struct, and a run reports none until it
  // has something to report. Render whatever arrives; never index into fields that may not exist.
  const counters = Object.entries(task?.counters ?? {});
  // Shared with the dashboard row, which shows the same run: see uploadProgressPercent for why it is
  // Processed rather than Uploaded, and why it stops at 99 until the run is actually over.
  const progress = uploadProgressPercent(task?.counters);

  const formatCounter = (counter: WebTaskCounter) =>
    counter.units === "bytes" ? formatFileSize(counter.value) : counter.value.toLocaleString();

  const handleCancel = () => {
    cancelTask.mutate(taskId);
    setShowCancelDialog(false);
    // Deliberately does NOT close: the sheet stays up, showing the wind-down, until the task itself
    // reports a terminal state and the effect above closes it. Closing here made the sheet vanish
    // on the tap whether or not the cancel reached anything — and because that unmounted this
    // component, the effect never saw the terminal status, so the dashboard row went on claiming a
    // run for up to one 5s poll. A tap on such a row opens a progress sheet for a finished task
    // (which closes itself instantly) instead of opening the source: the tap looks ignored.
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

          {/* Progress bar — indeterminate until the run has an estimate to divide by */}
          <div className="mb-4">
            <div className="progress-bar h-3">
              <div className="progress-fill" style={{ width: progress === null ? "100%" : `${progress}%` }} />
            </div>
            <p className="text-center text-lg font-bold text-foreground mt-2" aria-label="Backup progress">
              {progress === null ? task?.progressInfo || "Working…" : `${progress}%`}
            </p>
          </div>

          {/* Stats grid */}
          {counters.length > 0 && (
            <div className="grid grid-cols-2 gap-3 mb-4">
              {counters.map(([name, counter]) => (
                <div key={name} className="card-elevated !p-3">
                  <p className="text-xs text-muted-foreground">{name}</p>
                  <p className="text-sm font-semibold text-foreground">{formatCounter(counter)}</p>
                </div>
              ))}
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
            {/* Its own label: the sheet's button, this dialog's title and this button would
                otherwise all match the same text. */}
            <AlertDialogAction
              onClick={handleCancel}
              id="confirm-cancel-backup-button"
              aria-label="Confirm cancel backup"
              className="flex-1 bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              Cancel Backup
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
};

export default BackupProgressSheet;
