import { useState, useEffect } from "react";
import { X, Loader2, Play } from "lucide-react";
import { formatFileSize } from "@/lib/format";
import { useEstimate, useTask, useStartBackup } from "@/hooks/useBackupApi";

interface EstimationDialogProps {
  sourceId: string;
  onClose: () => void;
}

const EstimationDialog = ({ sourceId, onClose }: EstimationDialogProps) => {
  const estimate = useEstimate();
  const startBackup = useStartBackup();
  const [taskId, setTaskId] = useState<string | null>(null);
  const { data: task } = useTask(taskId);

  // Kick off estimation on mount
  useEffect(() => {
    estimate.mutate(
      { sourceId },
      {
        onSuccess: (id) => setTaskId(id),
      }
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sourceId]);

  const isEstimating = !task || task.status === "RUNNING" || task.status === "CANCELING";
  const isComplete = task?.status === "SUCCESS";
  const counters = task?.counters;

  const handleStartBackup = () => {
    startBackup.mutate(sourceId);
    onClose();
  };

  return (
    <>
      <div className="fixed inset-0 z-50 bg-black/50 animate-fade-in" onClick={onClose} />

      <div className="fixed inset-x-0 bottom-0 z-50 bg-card rounded-t-2xl shadow-xl animate-slide-up max-w-md mx-auto">
        <div className="p-4">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold text-foreground text-sm">Backup Estimation</h3>
            <button onClick={onClose} className="btn-icon -mr-2">
              <X className="w-5 h-5" />
            </button>
          </div>

          {isEstimating ? (
            <div className="flex flex-col items-center py-8">
              <Loader2 className="w-10 h-10 text-primary animate-spin mb-3" />
              <p className="text-sm text-foreground font-medium">Estimating backup size...</p>
              <p className="text-xs text-muted-foreground mt-1">This may take a moment</p>
              <button onClick={onClose} className="btn-secondary mt-4">Cancel</button>
            </div>
          ) : isComplete && counters ? (
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-3">
                <div className="card-elevated !p-3">
                  <p className="text-xs text-muted-foreground">Total files</p>
                  <p className="text-lg font-bold text-foreground">{counters.estimatedFiles.toLocaleString()}</p>
                </div>
                <div className="card-elevated !p-3">
                  <p className="text-xs text-muted-foreground">Total size</p>
                  <p className="text-lg font-bold text-foreground">{formatFileSize(counters.estimatedBytes)}</p>
                </div>
                <div className="card-elevated !p-3">
                  <p className="text-xs text-muted-foreground">Excluded files</p>
                  <p className="text-lg font-bold text-foreground">{counters.totalExcludedFiles.toLocaleString()}</p>
                </div>
                <div className="card-elevated !p-3">
                  <p className="text-xs text-muted-foreground">Excluded dirs</p>
                  <p className="text-lg font-bold text-foreground">{counters.totalExcludedDirs.toLocaleString()}</p>
                </div>
              </div>

              <div className="flex gap-3">
                <button onClick={onClose} className="btn-secondary flex-1">Close</button>
                <button onClick={handleStartBackup} className="btn-primary flex-1">
                  <Play className="w-4 h-4" /> Start Backup
                </button>
              </div>
            </div>
          ) : task?.status === "FAILED" ? (
            <div className="flex flex-col items-center py-8">
              <p className="text-sm text-destructive font-medium">Estimation failed</p>
              <p className="text-xs text-muted-foreground mt-1">{task.error || "Unknown error"}</p>
              <button onClick={onClose} className="btn-secondary mt-4">Close</button>
            </div>
          ) : null}
        </div>
        <div className="h-4" />
      </div>
    </>
  );
};

export default EstimationDialog;
