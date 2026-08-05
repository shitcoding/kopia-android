import { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  ArrowLeft,
  Camera,
  Download,
  Wrench,
  BarChart3,
  CheckCircle2,
  XCircle,
  Clock,
  Loader2,
  X,
  Settings,
} from "lucide-react";
import { formatDateTime, formatRelativeTime, formatDuration, formatFileSize } from "@/lib/format";
import type { WebTaskInfo } from "@/types/kopia";
import { useTasks, useCancelTask } from "@/hooks/useBackupApi";
import BackupProgressSheet from "@/components/BackupProgressSheet";

const TASK_KIND_ICON: Record<WebTaskInfo["kind"], React.ElementType> = {
  Snapshot: Camera,
  Restore: Download,
  Maintenance: Wrench,
  Estimate: BarChart3,
};

const STATUS_BADGE: Record<WebTaskInfo["status"], { label: string; className: string; icon: React.ElementType }> = {
  RUNNING: { label: "Running", className: "bg-primary/15 text-primary", icon: Loader2 },
  CANCELING: { label: "Canceling", className: "bg-yellow-500/15 text-yellow-700 dark:text-yellow-300", icon: Clock },
  SUCCESS: { label: "Success", className: "bg-green-500/15 text-green-700 dark:text-green-300", icon: CheckCircle2 },
  FAILED: { label: "Failed", className: "bg-destructive/15 text-destructive", icon: XCircle },
  CANCELED: { label: "Canceled", className: "bg-muted text-muted-foreground", icon: X },
};

type FilterTab = "all" | "RUNNING" | "SUCCESS" | "FAILED";

const TaskListScreen = () => {
  const navigate = useNavigate();
  const [filter, setFilter] = useState<FilterTab>("all");
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [progressTaskId, setProgressTaskId] = useState<string | null>(null);
  const { data: tasks = [], isLoading } = useTasks(filter === "all" ? undefined : filter);
  const cancelTask = useCancelTask();

  const filters: { id: FilterTab; label: string }[] = [
    { id: "all", label: "All" },
    { id: "RUNNING", label: "Running" },
    { id: "SUCCESS", label: "Completed" },
    { id: "FAILED", label: "Failed" },
  ];

  return (
    <div className="app-container min-h-screen flex flex-col">
      <header className="app-bar">
        <button onClick={() => navigate(-1)} className="btn-icon -ml-2" aria-label="Back">
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h1 className="app-bar-title">Tasks</h1>
        <button onClick={() => navigate("/settings")} className="btn-icon -mr-2" aria-label="Settings">
          <Settings className="w-5 h-5" />
        </button>
      </header>

      {/* Filter tabs */}
      <div className="px-4 pt-2">
        <div className="flex gap-2">
          {filters.map((f) => (
            <button
              key={f.id}
              onClick={() => setFilter(f.id)}
              className={`px-3 py-1.5 text-xs rounded-full transition-colors ${
                filter === f.id ? "bg-primary text-primary-foreground" : "bg-secondary text-secondary-foreground"
              }`}
              id={`task-filter-${f.id}`}
              aria-label={`Filter ${f.label.toLowerCase()} tasks`}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      <div className="flex-1 p-4 space-y-3">
        {isLoading ? (
          <div className="flex flex-col items-center justify-center py-20 animate-fade-in">
            <Loader2 className="w-8 h-8 text-primary animate-spin mb-3" />
            <p className="text-muted-foreground">Loading tasks...</p>
          </div>
        ) : tasks.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-20 text-center animate-fade-in">
            <p className="text-muted-foreground">No tasks found</p>
          </div>
        ) : (
          tasks.map((task, index) => {
            const KindIcon = TASK_KIND_ICON[task.kind];
            const badge = STATUS_BADGE[task.status];
            const isExpanded = expandedId === task.id;
            const isRunning = task.status === "RUNNING" || task.status === "CANCELING";
            // Counters are an open map and a run reports none until it has something to report;
            // indexing into fields that may not exist used to blank the whole screen.
            // Processed, not Uploaded: "Uploaded Bytes" is what actually went to the server, so a
            // run that dedups or caches most of its data would crawl towards a total it never
            // reaches. Processed = hashed + cached is the work done against the estimate.
            // Capped at 99 while the task is live, like the notification: reaching the estimate is
            // not finishing. The snapshot manifest still has to be written, flushed and retained,
            // and a bar that reads 100% while the app is visibly still working reads as a hang.
            const processed = task.counters?.["Processed Bytes"]?.value;
            const estimated = task.counters?.["Estimated Bytes"]?.value;
            const uploadProgress =
              processed !== undefined && estimated !== undefined && estimated > 0
                ? Math.min(99, Math.round((processed / estimated) * 100))
                : null;

            return (
              <div
                key={task.id}
                className="w-full card-elevated text-left transition-all duration-200 hover:shadow-lg animate-slide-up"
                style={{ animationDelay: `${index * 0.03}s` }}
              >
                <button
                  onClick={() => {
                    if (isRunning) {
                      setProgressTaskId(task.id);
                    } else {
                      setExpandedId(isExpanded ? null : task.id);
                    }
                  }}
                  className="w-full text-left"
                >
                  <div className="flex items-start gap-3">
                    <div className="w-9 h-9 rounded-lg bg-primary/10 flex items-center justify-center flex-shrink-0">
                      <KindIcon className="w-4 h-4 text-primary" />
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 mb-0.5">
                        <p className="font-medium text-foreground text-sm truncate">{task.description}</p>
                      </div>
                      <div className="flex items-center gap-2 text-xs">
                        <span className={`px-2 py-0.5 rounded-full font-medium ${badge.className}`}>{badge.label}</span>
                        <span className="text-muted-foreground">{formatRelativeTime(task.startTimeEpochMs)}</span>
                        {task.endTimeEpochMs && (
                          <span className="text-muted-foreground">· {formatDuration(task.endTimeEpochMs - task.startTimeEpochMs)}</span>
                        )}
                      </div>

                      {/* Running progress */}
                      {isRunning && (
                        <div className="mt-2">
                          <div className="progress-bar">
                            <div
                              className="progress-fill"
                              style={{ width: uploadProgress === null ? "100%" : `${uploadProgress}%` }}
                            />
                          </div>
                          <p className="text-xs text-muted-foreground mt-1">{task.progressInfo}</p>
                        </div>
                      )}

                      {/* Failed error */}
                      {task.status === "FAILED" && task.error && (
                        <p className="text-xs text-destructive mt-1">{task.error}</p>
                      )}

                      {/* Expanded details */}
                      {isExpanded && Object.keys(task.counters ?? {}).length > 0 && (
                        <div className="mt-3 pt-3 border-t border-border space-y-1 text-xs text-muted-foreground">
                          <div className="grid grid-cols-2 gap-2">
                            {Object.entries(task.counters ?? {}).map(([name, counter]) => (
                              <span key={name}>
                                {name}: {counter.units === "bytes" ? formatFileSize(counter.value) : counter.value.toLocaleString()}
                              </span>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                  </div>
                </button>

                {/* Cancel button for running tasks */}
                {isRunning && (
                  <div className="mt-3 pt-3 border-t border-border">
                    <button
                      onClick={() => cancelTask.mutate(task.id)}
                      disabled={cancelTask.isPending || task.status === "CANCELING"}
                      className="text-xs text-destructive font-medium hover:underline"
                      aria-label="Cancel task"
                    >
                      {task.status === "CANCELING" ? "Canceling..." : "Cancel Task"}
                    </button>
                  </div>
                )}
              </div>
            );
          })
        )}
      </div>

      {progressTaskId && (
        <BackupProgressSheet taskId={progressTaskId} onClose={() => setProgressTaskId(null)} />
      )}
    </div>
  );
};

export default TaskListScreen;
