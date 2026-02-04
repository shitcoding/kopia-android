import { useNavigate } from "react-router-dom";
import { RefreshCw, Settings, AlertTriangle, FolderX, Loader2 } from "lucide-react";
import { useSnapshots } from "@/hooks/useKopiaApi";
import type { SnapshotInfo } from "@/types/kopia";

const SnapshotsScreen = () => {
  const navigate = useNavigate();
  const { data: snapshots, isLoading, error, refetch, isRefetching } = useSnapshots();

  const formatDate = (epochMs: number) => {
    return new Date(epochMs).toLocaleDateString("en-US", {
      month: "short",
      day: "numeric",
      year: "numeric",
      hour: "numeric",
      minute: "2-digit",
    });
  };

  const formatSize = (bytes: number): string => {
    if (bytes === 0) return "0 B";
    const units = ["B", "KB", "MB", "GB", "TB"];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return `${(bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
  };

  const formatSource = (snapshot: SnapshotInfo): string => {
    const { source } = snapshot;
    return `${source.userName}@${source.host}:${source.path}`;
  };

  const handleRefresh = () => {
    refetch();
  };

  const isRefreshing = isRefetching;

  return (
    <div className="app-container min-h-screen flex flex-col">
      {/* App Bar */}
      <header className="app-bar">
        <div className="w-9" />
        <h1 className="app-bar-title">Snapshots</h1>
        <div className="flex items-center gap-1">
          <button
            onClick={handleRefresh}
            disabled={isRefreshing}
            className="btn-icon"
            data-testid="refresh-button"
          >
            <RefreshCw className={`w-5 h-5 ${isRefreshing ? "animate-spin" : ""}`} />
          </button>
          <button onClick={() => navigate("/settings")} className="btn-icon" data-testid="settings-button">
            <Settings className="w-5 h-5" />
          </button>
        </div>
      </header>

      {/* Refresh Progress Indicator */}
      {isRefreshing && (
        <div className="h-0.5 bg-secondary overflow-hidden">
          <div className="h-full bg-primary animate-pulse w-full" />
        </div>
      )}

      {/* Content */}
      <div className="flex-1 p-4">
        {/* Loading State */}
        {isLoading && (
          <div className="flex-1 flex flex-col items-center justify-center py-20" data-testid="loading-indicator">
            <Loader2 className="w-10 h-10 text-primary animate-spin" />
          </div>
        )}

        {/* Error State */}
        {error && !isLoading && (
          <div className="flex-1 flex flex-col items-center justify-center py-20 text-center animate-fade-in">
            <div className="w-16 h-16 rounded-full bg-destructive/10 flex items-center justify-center mb-4">
              <AlertTriangle className="w-8 h-8 text-destructive" />
            </div>
            <p className="text-destructive font-medium mb-2" data-testid="error-message">Failed to load snapshots</p>
            <p className="text-sm text-muted-foreground mb-4">
              {error instanceof Error ? error.message : "Unknown error"}
            </p>
            <button onClick={handleRefresh} className="btn-primary">
              Retry
            </button>
          </div>
        )}

        {/* Empty State */}
        {!isLoading && !error && snapshots?.length === 0 && (
          <div className="flex-1 flex flex-col items-center justify-center py-20 text-center animate-fade-in">
            <div className="w-16 h-16 rounded-full bg-muted flex items-center justify-center mb-4">
              <FolderX className="w-8 h-8 text-muted-foreground" />
            </div>
            <p className="text-lg font-medium text-foreground mb-1">No snapshots found</p>
            <p className="text-muted-foreground">Connect to a repository with snapshots</p>
          </div>
        )}

        {/* Data State */}
        {!isLoading && !error && snapshots && snapshots.length > 0 && (
          <div className="space-y-3">
            {snapshots.map((snapshot, index) => (
              <button
                key={snapshot.id}
                onClick={() => navigate(`/files/${snapshot.id}`)}
                className="w-full card-elevated text-left transition-all duration-200 hover:shadow-lg active:scale-[0.99] animate-slide-up"
                style={{ animationDelay: `${index * 0.05}s` }}
                data-testid={`snapshot-card-${snapshot.id}`}
              >
                <div className="flex items-start justify-between gap-2 mb-1">
                  <p className="font-medium text-foreground truncate flex-1">
                    {formatSource(snapshot)}
                  </p>
                  {snapshot.isIncomplete && (
                    <AlertTriangle className="w-4 h-4 text-warning flex-shrink-0 mt-0.5" />
                  )}
                </div>
                <p className="text-sm text-muted-foreground mb-1">
                  {formatDate(snapshot.startTimeEpochMs)}
                </p>
                <p className="text-sm text-muted-foreground">
                  {snapshot.stats ? (
                    <>
                      {snapshot.stats.totalFileCount.toLocaleString()} files, {formatSize(snapshot.stats.totalFileSize)}
                    </>
                  ) : (
                    "Stats unavailable"
                  )}
                </p>
                {snapshot.description && (
                  <p className="text-sm text-muted-foreground mt-1 line-clamp-2">
                    {snapshot.description}
                  </p>
                )}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default SnapshotsScreen;
