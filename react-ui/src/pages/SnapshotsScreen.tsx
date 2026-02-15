import { useNavigate } from "react-router-dom";
import { RefreshCw, Settings, AlertTriangle, FolderX, Loader2, ChevronRight } from "lucide-react";
import ExitDoorIcon from "@/components/ExitDoorIcon";
import { useSourcesWithStats } from "@/hooks/useKopiaApi";
import { formatFileSize, formatDateTime } from "@/lib/format";
import type { SourceWithStats } from "@/types/kopia";

const SnapshotsScreen = () => {
  const navigate = useNavigate();
  const { data: sources, isLoading, isError, refetch, isRefetching } = useSourcesWithStats();

  const handleSourceClick = (source: SourceWithStats) => {
    const params = new URLSearchParams({
      host: source.source.host,
      userName: source.source.userName,
      path: source.source.path,
    });
    navigate(`/snapshots/source?${params.toString()}`);
  };

  const isEmpty = !isLoading && !isError && (!sources || sources.length === 0);
  const hasData = !isLoading && !isError && sources && sources.length > 0;

  return (
    <div className="app-container min-h-screen flex flex-col">
      {/* App Bar */}
      <header className="app-bar">
        <div className="w-9" />
        <h1 className="app-bar-title">Snapshots</h1>
        <div className="flex items-center gap-1">
          <button onClick={() => navigate("/connect")} className="btn-icon" title="Disconnect">
            <ExitDoorIcon className="w-5 h-5" />
          </button>
          <button onClick={() => navigate("/settings")} className="btn-icon">
            <Settings className="w-5 h-5" />
          </button>
        </div>
      </header>

      {/* Refresh Progress Indicator */}
      {isRefetching && (
        <div className="h-0.5 bg-secondary overflow-hidden">
          <div className="h-full bg-primary animate-pulse w-full" />
        </div>
      )}

      {/* Content */}
      <div className="flex-1 p-4">
        {/* Loading State */}
        {isLoading && (
          <div className="flex-1 flex flex-col items-center justify-center py-20">
            <Loader2 className="w-10 h-10 text-primary animate-spin" />
          </div>
        )}

        {/* Error State */}
        {isError && (
          <div className="flex-1 flex flex-col items-center justify-center py-20 text-center animate-fade-in">
            <div className="w-16 h-16 rounded-full bg-destructive/10 flex items-center justify-center mb-4">
              <AlertTriangle className="w-8 h-8 text-destructive" />
            </div>
            <p className="text-destructive font-medium mb-4">Failed to load snapshots</p>
            <button onClick={() => refetch()} className="btn-primary">
              Retry
            </button>
          </div>
        )}

        {/* Empty State */}
        {isEmpty && (
          <div className="flex-1 flex flex-col items-center justify-center py-20 text-center animate-fade-in">
            <div className="w-16 h-16 rounded-full bg-muted flex items-center justify-center mb-4">
              <FolderX className="w-8 h-8 text-muted-foreground" />
            </div>
            <p className="text-lg font-medium text-foreground mb-1">No snapshots found</p>
            <p className="text-muted-foreground">Connect to a repository with snapshots</p>
          </div>
        )}

        {/* Data State - Source Cards */}
        {hasData && (
          <div className="space-y-3">
            {sources!.map((src, index) => (
              <button
                key={`${src.source.userName}@${src.source.host}:${src.source.path}`}
                onClick={() => handleSourceClick(src)}
                className="w-full card-elevated text-left transition-all duration-200 hover:shadow-lg active:scale-[0.99] animate-slide-up"
                style={{ animationDelay: `${index * 0.05}s` }}
              >
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0 flex-1">
                    <p className="font-semibold text-foreground truncate">
                      {src.source.path}
                    </p>
                    <p className="text-sm text-muted-foreground mt-0.5">
                      {src.source.userName}@{src.source.host}
                    </p>
                  </div>
                  <ChevronRight className="w-5 h-5 text-muted-foreground flex-shrink-0 mt-1" />
                </div>

                <div className="mt-3 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-sm text-muted-foreground">
                  <span>{src.snapshotCount} snapshots</span>
                  <span>·</span>
                  <span>{formatFileSize(src.totalFileSize)}</span>
                  <span>·</span>
                  <span>{src.totalFileCount.toLocaleString()} files</span>
                </div>

                <p className="text-sm text-muted-foreground mt-1">
                  Last: {formatDateTime(src.latestSnapshotTime)}
                </p>
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default SnapshotsScreen;
