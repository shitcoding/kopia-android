import { useState, useEffect, useCallback } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { AlertTriangle, ArrowLeft, Check, Download, Folder, Loader2, Settings, X, XCircle } from "lucide-react";
import { kopiaBridge } from "@/services/kopiaBridge";
import { useSnapshot } from "@/hooks/useKopiaApi";
import { snapshotFailureWarning } from "@/lib/snapshotHealth";
import type { RestoreProgress, RestoreState, SafPickResult } from "@/types/kopia";

type UIRestoreState = "idle" | "preparing" | "progress" | "complete" | "failed" | "cancelled";

const mapRestoreState = (state: RestoreState): UIRestoreState => {
  switch (state) {
    case "IDLE": return "idle";
    case "PREPARING": return "preparing";
    case "IN_PROGRESS": return "progress";
    case "COMPLETED": return "complete";
    case "FAILED": return "failed";
    case "CANCELLED": return "cancelled";
  }
};

const RestoreScreen = () => {
  const navigate = useNavigate();
  const { snapshotId } = useParams<{ snapshotId: string }>();
  const [searchParams] = useSearchParams();
  const sourcePath = searchParams.get("path") || "/";

  const { data: snapshot } = useSnapshot(snapshotId);
  const restoreFailureWarning = snapshotFailureWarning(snapshot);

  const [state, setState] = useState<UIRestoreState>("idle");
  const [destination, setDestination] = useState<string | null>(null);
  const [destinationUri, setDestinationUri] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const [filesRestored, setFilesRestored] = useState(0);
  const [totalFiles, setTotalFiles] = useState(0);
  const [bytesRestored, setBytesRestored] = useState(0);
  const [totalBytes, setTotalBytes] = useState(0);
  const [currentFile, setCurrentFile] = useState("");
  const [error, setError] = useState<string | null>(null);

  const formatSize = (bytes: number): string => {
    if (bytes === 0) return "0 B";
    const units = ["B", "KB", "MB", "GB", "TB"];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return `${(bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
  };

  const formatSource = (): string => {
    if (!snapshot?.source) return sourcePath;
    return `${snapshot.source.userName}@${snapshot.source.host}:${snapshot.source.path}`;
  };

  // Handle restore progress updates
  const handleProgressUpdate = useCallback((progressData: RestoreProgress) => {
    setState(mapRestoreState(progressData.state));
    setTotalFiles(progressData.totalFiles);
    setFilesRestored(progressData.restoredFiles);
    setTotalBytes(progressData.totalBytes);
    setBytesRestored(progressData.restoredBytes);
    setCurrentFile(progressData.currentFile || "");

    if (progressData.totalFiles > 0) {
      setProgress(Math.round((progressData.restoredFiles / progressData.totalFiles) * 100));
    }

    if (progressData.errorMessage) {
      setError(progressData.errorMessage);
    }
  }, []);

  // Handle destination picker result
  const handleDestinationPicked = useCallback((result: SafPickResult) => {
    if (result.uri) {
      setDestinationUri(result.uri);
      setDestination(result.displayName || result.uri);
    }
  }, []);

  // Subscribe to bridge events
  useEffect(() => {
    const unsubProgress = kopiaBridge.onRestoreProgress(handleProgressUpdate);
    const unsubDestination = kopiaBridge.onDestinationPicked(handleDestinationPicked);

    return () => {
      unsubProgress();
      unsubDestination();
    };
  }, [handleProgressUpdate, handleDestinationPicked]);

  const handleSelectDestination = () => {
    kopiaBridge.pickRestoreDestination();
  };

  const handleStartRestore = async () => {
    if (!snapshotId || !destinationUri) return;

    setState("preparing");
    setError(null);
    setProgress(0);
    setFilesRestored(0);

    try {
      kopiaBridge.startRestore({
        snapshotId,
        sourcePath,
        destinationUri,
      });
    } catch (e) {
      const message = e instanceof Error ? e.message : "Failed to start restore";
      setError(message);
      setState("failed");
    }
  };

  const handleCancel = () => {
    kopiaBridge.cancelRestore();
  };

  const handleRetry = () => {
    setProgress(0);
    setFilesRestored(0);
    setError(null);
    handleStartRestore();
  };

  return (
    <div className="app-container min-h-screen flex flex-col">
      {/* App Bar */}
      <header className="app-bar">
        <button onClick={() => navigate(-1)} className="btn-icon -ml-2" aria-label="Back">
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h1 className="app-bar-title">Restore</h1>
        <button onClick={() => navigate("/settings")} className="btn-icon -mr-2" aria-label="Settings">
          <Settings className="w-5 h-5" />
        </button>
      </header>

      {/* Content */}
      <div className="flex-1 px-4 py-6 flex flex-col">
        {/* Source Info Card */}
        <div className="flex justify-center mb-6">
          <div className="card-elevated inline-flex flex-col items-center px-8 py-4 animate-fade-in">
            <p className="text-sm text-muted-foreground mb-1">Source</p>
            <p className="font-semibold text-foreground text-center break-all">{formatSource()}</p>
          </div>
        </div>

        {/* Destination Card */}
        <div className="card-elevated mb-8 animate-slide-up" style={{ animationDelay: "0.05s" }}>
          <p className="text-sm text-muted-foreground mb-2">Destination</p>
          {destination && (
            <p className="text-sm text-foreground break-all mb-3 line-clamp-2">
              {destination}
            </p>
          )}
          <button
            onClick={handleSelectDestination}
            disabled={state !== "idle"}
            className="btn-secondary w-full"
            data-testid="select-destination-button"
          >
            <Folder className="w-5 h-5" />
            {destination ? "Change Destination" : "Select Destination"}
          </button>
        </div>

        {/* State-specific content */}
        <div className="flex-1 flex flex-col items-center justify-center">
          {/* Idle State */}
          {state === "idle" && snapshot?.isIncomplete && (
            // A cancelled or failed backup still writes the tree it got through, so these snapshots
            // are real and restorable -- desktop Kopia restores them too. What must not happen is
            // restoring half a backup and being told it worked.
            <div
              className="mb-6 flex items-start gap-2 rounded-lg border border-warning/40 bg-warning/10 p-3 text-left"
              role="status"
              data-testid="incomplete-snapshot-warning"
            >
              <AlertTriangle className="mt-0.5 h-4 w-4 flex-shrink-0 text-warning" aria-hidden="true" />
              <p className="text-sm text-foreground">
                This backup did not finish, so it holds only the files it had copied before it
                stopped. Anything restored from it will be incomplete.
              </p>
            </div>
          )}
          {state === "idle" && restoreFailureWarning && (
            // A DIFFERENT failure from the one above, and it can be true at the same time: this
            // backup finished, but part of the source could not be read while it ran (task-63), so
            // what is missing is missing for good rather than "not copied yet".
            <div
              className="mb-6 flex items-start gap-2 rounded-lg border border-warning/40 bg-warning/10 p-3 text-left"
              role="status"
              data-testid="failed-entries-warning"
            >
              <AlertTriangle className="mt-0.5 h-4 w-4 flex-shrink-0 text-warning" aria-hidden="true" />
              <p className="text-sm text-foreground">{restoreFailureWarning.detail}</p>
            </div>
          )}
          {state === "idle" && (
            <button
              onClick={handleStartRestore}
              disabled={!destinationUri}
              className="btn-primary animate-slide-up"
              style={{ animationDelay: "0.1s" }}
              data-testid="start-restore-button"
            >
              <Download className="w-5 h-5" />
              Start Restore
            </button>
          )}

          {/* Preparing State */}
          {state === "preparing" && (
            <div className="flex flex-col items-center gap-4 animate-fade-in" data-testid="loading-indicator">
              <Loader2 className="w-12 h-12 text-primary animate-spin" />
              <p className="text-muted-foreground">Preparing restore...</p>
            </div>
          )}

          {/* Progress State */}
          {state === "progress" && (
            <div className="w-full max-w-sm space-y-6 animate-fade-in">
              {/* Progress Bar */}
              <div className="space-y-2">
                <div className="progress-bar">
                  <div
                    className="progress-fill"
                    style={{ width: `${progress}%` }}
                  />
                </div>
                <p className="text-center text-lg font-semibold text-foreground">
                  {progress}%
                </p>
              </div>

              {/* Stats */}
              <div className="text-center space-y-1">
                <p className="text-muted-foreground">
                  Files: {filesRestored.toLocaleString()} / {totalFiles.toLocaleString()}
                </p>
                <p className="text-muted-foreground">
                  Size: {formatSize(bytesRestored)} / {formatSize(totalBytes)}
                </p>
              </div>

              {/* Current File */}
              <p className="text-sm text-muted-foreground text-center truncate">
                {currentFile || "Restoring files..."}
              </p>

              {/* Cancel Button */}
              <button onClick={handleCancel} className="btn-secondary w-full" data-testid="cancel-button">
                Cancel
              </button>
            </div>
          )}

          {/* Complete State */}
          {state === "complete" && (
            <div className="flex flex-col items-center gap-4 animate-scale-in">
              <div className="w-20 h-20 rounded-full bg-success/10 flex items-center justify-center animate-check">
                <Check className="w-10 h-10 text-success" strokeWidth={3} />
              </div>
              <h2 className="text-2xl font-semibold text-foreground">Restore Complete!</h2>
              <p className="text-muted-foreground">{filesRestored.toLocaleString()} files restored</p>
              {snapshot?.isIncomplete && (
                <p className="max-w-xs text-center text-sm text-warning">
                  From a backup that did not finish — these are the files it had copied, not
                  everything that was in the folder.
                </p>
              )}
              {restoreFailureWarning && (
                <p className="max-w-xs text-center text-sm text-warning">
                  {restoreFailureWarning.restoredDetail}
                </p>
              )}
              <button onClick={() => navigate(-1)} className="btn-primary mt-4">
                Done
              </button>
            </div>
          )}

          {/* Failed State */}
          {state === "failed" && (
            <div className="flex flex-col items-center gap-4 animate-fade-in">
              <div className="w-20 h-20 rounded-full bg-destructive/10 flex items-center justify-center">
                <XCircle className="w-10 h-10 text-destructive" />
              </div>
              <h2 className="text-2xl font-semibold text-destructive">Restore Failed</h2>
              <p className="text-muted-foreground text-center max-w-xs" data-testid="error-message">
                {error || "An error occurred while restoring files."}
              </p>
              <div className="flex gap-3 mt-4">
                <button onClick={() => navigate(-1)} className="btn-secondary" data-testid="cancel-button">
                  Cancel
                </button>
                <button onClick={handleRetry} className="btn-primary">
                  Retry
                </button>
              </div>
            </div>
          )}

          {/* Cancelled State */}
          {state === "cancelled" && (
            <div className="flex flex-col items-center gap-4 animate-fade-in">
              <div className="w-20 h-20 rounded-full bg-muted flex items-center justify-center">
                <X className="w-10 h-10 text-muted-foreground" />
              </div>
              <h2 className="text-2xl font-semibold text-foreground">Restore Cancelled</h2>
              <button onClick={() => navigate(-1)} className="btn-primary mt-4">
                Go Back
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default RestoreScreen;
