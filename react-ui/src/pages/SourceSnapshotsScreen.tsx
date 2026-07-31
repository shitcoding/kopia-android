import { useState, useMemo } from "react";
import { useLocation, useNavigate, useSearchParams } from "react-router-dom";
import {
  ArrowLeft,
  RefreshCw,
  AlertTriangle,
  FolderX,
  Loader2,
  Trash2,
  X,
  CheckSquare,
  Settings,
} from "lucide-react";
import ExitDoorIcon from "@/components/ExitDoorIcon";
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
import { Checkbox } from "@/components/ui/checkbox";
import { useToast } from "@/hooks/use-toast";
import { useSnapshotsWithRetention, useDeleteSnapshots } from "@/hooks/useKopiaApi";
import { formatFileSize, formatDateTime } from "@/lib/format";
import type { SourceInfo } from "@/types/kopia";

const RETENTION_COLORS: Record<string, string> = {
  latest: "bg-muted text-muted-foreground",
  hourly: "bg-orange-500/15 text-orange-700 dark:text-orange-300",
  daily: "bg-green-500/15 text-green-700 dark:text-green-300",
  weekly: "bg-red-500/15 text-red-700 dark:text-red-300",
  monthly: "bg-blue-500/15 text-blue-700 dark:text-blue-300",
  annual: "bg-yellow-500/15 text-yellow-700 dark:text-yellow-300",
};

function getRetentionColor(tag: string): string {
  const prefix = tag.split("-")[0];
  return RETENTION_COLORS[prefix] ?? "bg-muted text-muted-foreground";
}

const SourceSnapshotsScreen = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const location = useLocation();
  // This screen is opened from two places, and Back used to send everyone to the all-snapshots list
  // -- so opening a source from the Backup Sources dashboard left no way back to it at all. Each
  // caller marks the entry it pushed, and Back POPS that entry rather than pushing another one on
  // top: navigating forward to the dashboard would leave the system Back key walking right back into
  // this screen. Anything arriving without the marker (a reload, a deep link) has no entry to pop,
  // so it gets the snapshot list.
  const cameFromInApp = Boolean((location.state as { from?: string } | null)?.from);
  const goBack = () => {
    if (cameFromInApp) navigate(-1);
    else navigate("/snapshots", { replace: true });
  };
  const { toast } = useToast();

  const source: SourceInfo | null = useMemo(() => {
    const host = searchParams.get("host");
    const userName = searchParams.get("userName");
    const path = searchParams.get("path");
    if (!host || !userName || !path) return null;
    return { host, userName, path };
  }, [searchParams]);

  const {
    data: snapshots,
    isLoading,
    isError,
    refetch,
    isRefetching,
  } = useSnapshotsWithRetention(source);

  const deleteMutation = useDeleteSnapshots();

  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);

  const selectionMode = selectedIds.size > 0;
  const isEmpty = !isLoading && !isError && (!snapshots || snapshots.length === 0);
  const hasData = !isLoading && !isError && snapshots && snapshots.length > 0;

  const toggleSelection = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const selectAll = () => {
    if (snapshots) {
      setSelectedIds(new Set(snapshots.map((s) => s.id)));
    }
  };

  const clearSelection = () => setSelectedIds(new Set());

  const handleDelete = async () => {
    const count = selectedIds.size;
    try {
      await deleteMutation.mutateAsync({ snapshotIds: Array.from(selectedIds) });
      toast({ title: `${count} snapshot${count > 1 ? "s" : ""} deleted` });
    } catch (e) {
      toast({
        title: "Failed to delete snapshots",
        description: e instanceof Error ? e.message : "Unknown error",
        variant: "destructive",
      });
    }
    setShowDeleteDialog(false);
    clearSelection();
  };

  if (!source) {
    return (
      <div className="app-container min-h-screen flex items-center justify-center">
        <p className="text-muted-foreground">Invalid source parameters</p>
      </div>
    );
  }

  return (
    <div className="app-container min-h-screen flex flex-col">
      {/* App Bar */}
      <header className="app-bar">
        <button onClick={goBack} className="btn-icon -ml-2" aria-label="Back">
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h1 className="app-bar-title">Snapshots</h1>
        <div className="flex items-center gap-1">
          <button onClick={() => navigate("/connect")} className="btn-icon" title="Disconnect" aria-label="Disconnect">
            <ExitDoorIcon className="w-5 h-5" />
          </button>
          <button onClick={() => navigate("/settings")} className="btn-icon" aria-label="Settings">
            <Settings className="w-5 h-5" />
          </button>
        </div>
      </header>

      {/* Refresh Progress */}
      {isRefetching && (
        <div className="h-0.5 bg-secondary overflow-hidden">
          <div className="h-full bg-primary animate-pulse w-full" />
        </div>
      )}

      {/* Selection Toolbar */}
      {selectionMode && (
        <div className="sticky top-[53px] z-40 flex items-center justify-between px-4 py-2.5 bg-primary/10 border-b border-border animate-fade-in">
          <div className="flex items-center gap-2">
            <button onClick={clearSelection} className="btn-icon" aria-label="Clear selection">
              <X className="w-5 h-5" />
            </button>
            <span className="text-sm font-medium text-foreground">
              {selectedIds.size} selected
            </span>
          </div>
          <div className="flex items-center gap-1">
            <button
              onClick={selectAll}
              className="flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-foreground rounded-lg hover:bg-secondary transition-colors"
            >
              <CheckSquare className="w-4 h-4" />
              Select All
            </button>
            <button
              onClick={() => setShowDeleteDialog(true)}
              disabled={deleteMutation.isPending}
              className="flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-destructive rounded-lg hover:bg-destructive/10 transition-colors"
            >
              <Trash2 className="w-4 h-4" />
              Delete
            </button>
          </div>
        </div>
      )}

      {/* Source Header */}
      {!selectionMode && (
        <div className="px-4 py-3 border-b border-border/50">
          <p className="font-semibold text-foreground text-sm break-all">{source.path}</p>
          <p className="text-sm text-muted-foreground mt-0.5">
            {source.userName}@{source.host}
            {snapshots ? ` \u00b7 ${snapshots.length} snapshots` : ""}
          </p>
        </div>
      )}

      {/* Content */}
      <div className="flex-1 p-4">
        {isLoading && (
          <div className="flex items-center justify-center py-20">
            <Loader2 className="w-10 h-10 text-primary animate-spin" />
          </div>
        )}

        {isError && (
          <div className="flex flex-col items-center justify-center py-20 text-center animate-fade-in">
            <div className="w-16 h-16 rounded-full bg-destructive/10 flex items-center justify-center mb-4">
              <AlertTriangle className="w-8 h-8 text-destructive" />
            </div>
            <p className="text-destructive font-medium mb-4">Failed to load snapshots</p>
            <button onClick={() => refetch()} className="btn-primary">
              Retry
            </button>
          </div>
        )}

        {isEmpty && (
          <div className="flex flex-col items-center justify-center py-20 text-center animate-fade-in">
            <div className="w-16 h-16 rounded-full bg-muted flex items-center justify-center mb-4">
              <FolderX className="w-8 h-8 text-muted-foreground" />
            </div>
            <p className="text-lg font-medium text-foreground mb-1">No snapshots found</p>
            <p className="text-muted-foreground">No snapshots found for this source</p>
          </div>
        )}

        {hasData && (
          <div className="space-y-3">
            {snapshots!.map((snap, index) => (
              <div
                key={snap.id}
                className="card-elevated flex items-start gap-3 transition-all duration-200 hover:shadow-lg animate-slide-up"
                style={{ animationDelay: `${index * 0.05}s` }}
              >
                {/* Checkbox area */}
                <div
                  className="pt-0.5 flex-shrink-0"
                  role="checkbox"
                  aria-checked={selectedIds.has(snap.id)}
                  aria-label={`Select snapshot ${index + 1}`}
                  onClick={(e) => {
                    e.stopPropagation();
                    toggleSelection(snap.id);
                  }}
                >
                  <Checkbox
                    checked={selectedIds.has(snap.id)}
                    className="w-5 h-5"
                  />
                </div>

                {/* Card body - tappable for navigation */}
                <button
                  onClick={() => navigate(`/files/${snap.id}`)}
                  className="flex-1 text-left min-w-0"
                >
                  <div className="flex items-start justify-between gap-2">
                    <p className="font-semibold text-foreground">
                      {formatDateTime(snap.startTimeEpochMs)}
                    </p>
                    {snap.isIncomplete && (
                      <AlertTriangle className="w-4 h-4 text-warning flex-shrink-0 mt-0.5" />
                    )}
                  </div>

                  {/* Retention tags */}
                  {snap.retentionReasons.length > 0 && (
                    <div className="flex flex-wrap gap-1 mt-2">
                      {snap.retentionReasons.map((tag) => (
                        <span
                          key={tag}
                          className={`text-xs px-1.5 py-0.5 rounded font-medium ${getRetentionColor(tag)}`}
                        >
                          {tag}
                        </span>
                      ))}
                    </div>
                  )}

                  {/* Stats */}
                  <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-sm text-muted-foreground mt-2">
                    <span>{snap.stats?.totalFileCount?.toLocaleString()} files</span>
                    <span>·</span>
                    <span>{snap.stats?.totalDirectoryCount?.toLocaleString()} dirs</span>
                    <span>·</span>
                    <span>{formatFileSize(snap.stats?.totalFileSize ?? 0)}</span>
                  </div>

                  {/* Incomplete indicator */}
                  {snap.isIncomplete && (
                    <div className="flex items-center gap-1.5 mt-2 text-warning">
                      <AlertTriangle className="w-3.5 h-3.5" />
                      <span className="text-xs font-medium">Incomplete</span>
                    </div>
                  )}

                  {/* Description */}
                  {snap.description && (
                    <p className="text-sm text-muted-foreground mt-1.5 line-clamp-2">
                      {snap.description}
                    </p>
                  )}
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Delete Confirmation Dialog */}
      <AlertDialog open={showDeleteDialog} onOpenChange={setShowDeleteDialog}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              Delete {selectedIds.size} snapshot{selectedIds.size > 1 ? "s" : ""}?
            </AlertDialogTitle>
            <AlertDialogDescription>
              This action cannot be undone. The selected snapshots will be permanently removed.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDelete}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
};

export default SourceSnapshotsScreen;
