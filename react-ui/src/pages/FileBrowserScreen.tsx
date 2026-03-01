import { useState, useRef, useCallback, useEffect } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  ArrowLeft,
  Download,
  Folder,
  File,
  Link2,
  Loader2,
  AlertTriangle,
  FolderX,
  Settings,
  X,
  CheckSquare,
  Check,
  ChevronRight,
} from "lucide-react";
import ExitDoorIcon from "@/components/ExitDoorIcon";
import { Checkbox } from "@/components/ui/checkbox";
import { useDirectory } from "@/hooks/useKopiaApi";
import { kopiaBridge } from "@/services/kopiaBridge";
import { formatFileSize, formatDateTime } from "@/lib/format";
import type { FileEntry as KopiaFileEntry, FileEntryType } from "@/types/kopia";

interface DisplayFileEntry {
  id: string;
  name: string;
  type: "folder" | "file" | "symlink" | "unknown";
  size: number;
  modifiedAt?: Date;
}

type RestoreState = "idle" | "picking" | "restoring" | "done" | "error";

const LONG_PRESS_MS = 500;

const FileBrowserScreen = () => {
  const navigate = useNavigate();
  const { snapshotId } = useParams<{ snapshotId: string }>();
  const [path, setPath] = useState<string[]>([]);

  // Selection state
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [selectionModeActive, setSelectionModeActive] = useState(false);

  // Long-press refs
  const longPressTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const longPressTriggered = useRef(false);

  // Restore state
  const [restoreState, setRestoreState] = useState<RestoreState>("idle");
  const [restoreDestination, setRestoreDestination] = useState<string | null>(null);

  // Build current path string
  const currentPathStr = "/" + path.join("/");
  const currentFolder = path.length > 0 ? path[path.length - 1] : "/";

  // Fetch directory contents
  const directoryRequest = snapshotId
    ? { snapshotId, path: currentPathStr }
    : null;

  const { data: directoryPage, isLoading, isError } = useDirectory(directoryRequest);

  // Convert Kopia FileEntry to display format
  const mapFileType = (type: FileEntryType): DisplayFileEntry["type"] => {
    switch (type) {
      case "DIRECTORY": return "folder";
      case "FILE": return "file";
      case "SYMLINK": return "symlink";
      case "UNKNOWN": return "unknown";
      default: return "unknown";
    }
  };

  const files: DisplayFileEntry[] = directoryPage?.entries.map((entry: KopiaFileEntry, idx: number) => ({
    id: `${entry.name}-${idx}`,
    name: entry.name,
    type: mapFileType(entry.type),
    size: entry.size,
    modifiedAt: entry.modTimeEpochMs ? new Date(entry.modTimeEpochMs) : undefined,
  })) ?? [];

  const isEmpty = !isLoading && !isError && files.length === 0;

  // Subscribe to folder picker results
  useEffect(() => {
    const unsubscribe = kopiaBridge.onDestinationPicked((result) => {
      if (result.uri && restoreState === "picking") {
        setRestoreDestination(result.displayName || result.uri);
        startRestore(result.uri);
      }
    });
    return unsubscribe;
  }, [restoreState]);

  // Note: onRestoreProgress subscription is now handled within startRestore()
  // to properly coordinate sequential multi-file restore

  const handleFolderClick = (folderName: string) => {
    setPath([...path, folderName]);
  };

  // Selection helpers
  const toggleSelection = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const selectAll = () => {
    setSelectedIds(new Set(files.map((f) => f.id)));
  };

  const clearSelection = () => {
    setSelectedIds(new Set());
    setSelectionModeActive(false);
  };

  // Long-press handlers
  const handlePointerDown = useCallback((fileId: string) => {
    longPressTriggered.current = false;
    longPressTimer.current = setTimeout(() => {
      longPressTriggered.current = true;
      setSelectionModeActive(true);
      setSelectedIds((prev) => {
        const next = new Set(prev);
        next.add(fileId);
        return next;
      });
    }, LONG_PRESS_MS);
  }, []);

  const handlePointerUp = useCallback(() => {
    if (longPressTimer.current) {
      clearTimeout(longPressTimer.current);
      longPressTimer.current = null;
    }
  }, []);

  const handlePointerLeave = useCallback(() => {
    if (longPressTimer.current) {
      clearTimeout(longPressTimer.current);
      longPressTimer.current = null;
    }
  }, []);

  const handleItemClick = (file: DisplayFileEntry) => {
    if (longPressTriggered.current) {
      longPressTriggered.current = false;
      return;
    }
    if (selectionModeActive) {
      toggleSelection(file.id);
    } else if (file.type === "folder") {
      handleFolderClick(file.name);
    }
  };

  // Restore handlers
  const handleRestore = () => {
    setRestoreState("picking");
    kopiaBridge.pickRestoreDestination();
  };

  const startRestore = async (destinationUri: string) => {
    if (!snapshotId) return;

    const selectedFiles = files
      .filter(f => selectedIds.has(f.id))
      .map(f => f.name);

    if (selectedFiles.length === 0) {
      setRestoreState("error");
      return;
    }

    setRestoreState("restoring");

    try {
      // Restore files sequentially (backend only supports one restore at a time)
      for (let i = 0; i < selectedFiles.length; i++) {
        const fileName = selectedFiles[i];

        // Build full source path within snapshot
        const sourcePath = currentPathStr === "/"
          ? `/${fileName}`
          : `${currentPathStr}/${fileName}`;

        console.log(`Restoring file ${i + 1}/${selectedFiles.length}: ${fileName}`);

        // Wait for this file's restore to complete
        await new Promise<void>((resolve, reject) => {
          let progressUnsubscribe: (() => void) | null = null;

          // Subscribe to progress for this specific file
          progressUnsubscribe = kopiaBridge.onRestoreProgress((progress) => {
            if (progress.state === "COMPLETED") {
              console.log(`File ${i + 1}/${selectedFiles.length} restored: ${fileName}`);
              if (progressUnsubscribe) progressUnsubscribe();
              resolve();
            } else if (progress.state === "FAILED") {
              console.error(`File ${i + 1}/${selectedFiles.length} failed: ${fileName}`, progress.errorMessage);
              if (progressUnsubscribe) progressUnsubscribe();
              reject(new Error(progress.errorMessage || `Failed to restore ${fileName}`));
            }
          });

          // Start the restore for this file
          kopiaBridge.startRestore({
            snapshotId,
            sourcePath,
            destinationUri,
            options: {
              overwriteExisting: false,
              incremental: false,
            }
          });
        });
      }

      // All files restored successfully
      console.log(`All ${selectedFiles.length} files restored successfully`);
      setRestoreState("done");
    } catch (error) {
      console.error("Restore failed:", error);
      setRestoreState("error");
    }
  };

  const handleRestoreDone = () => {
    setRestoreState("idle");
    setRestoreDestination(null);
    clearSelection();
  };

  const getFileIcon = (type: DisplayFileEntry["type"]) => {
    switch (type) {
      case "folder":
        return <Folder className="w-6 h-6 text-folder fill-folder/20" />;
      case "file":
        return <File className="w-6 h-6 text-file" />;
      case "symlink":
        return <Link2 className="w-6 h-6 text-symlink" />;
      default:
        return <File className="w-6 h-6 text-muted-foreground" />;
    }
  };

  return (
    <div className="app-container min-h-screen flex flex-col">
      {/* App Bar */}
      <header className="app-bar">
        <button
          onClick={() => path.length > 0 ? setPath(path.slice(0, -1)) : navigate(-1)}
          className="btn-icon -ml-2"
          aria-label="Back"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h1 className="app-bar-title truncate max-w-[50%]">{currentFolder}</h1>
        <div className="flex items-center gap-1">
          <button onClick={() => navigate("/connect")} className="btn-icon" title="Disconnect" aria-label="Disconnect">
            <ExitDoorIcon className="w-5 h-5" />
          </button>
          <button onClick={() => navigate(`/restore/${snapshotId}`)} className="btn-icon" aria-label="Restore">
            <Download className="w-5 h-5" />
          </button>
          <button onClick={() => navigate("/settings")} className="btn-icon -mr-2" aria-label="Settings">
            <Settings className="w-5 h-5" />
          </button>
        </div>
      </header>

      {/* Selection Toolbar */}
      {selectionModeActive && restoreState === "idle" && (
        <div className="sticky top-[53px] z-40 flex items-center justify-between px-4 py-2.5 bg-primary/10 border-b border-border animate-fade-in whitespace-nowrap">
          <div className="flex items-center gap-2 flex-shrink-0">
            <button onClick={clearSelection} className="btn-icon" aria-label="Clear selection">
              <X className="w-5 h-5" />
            </button>
            <span className="text-sm font-medium text-foreground">
              {selectedIds.size} selected
            </span>
          </div>
          <div className="flex items-center gap-1 flex-shrink-0">
            <button
              id="select-all-button"
              onClick={selectedIds.size === files.length ? () => setSelectedIds(new Set()) : selectAll}
              className="flex items-center gap-1.5 px-2 py-1.5 text-sm font-medium text-foreground rounded-lg hover:bg-secondary transition-colors"
              aria-label={selectedIds.size === files.length ? "Deselect all" : "Select all"}
            >
              <CheckSquare className="w-4 h-4 flex-shrink-0" />
              {selectedIds.size === files.length ? "Deselect" : "Select All"}
            </button>
            <button
              onClick={handleRestore}
              disabled={selectedIds.size === 0}
              className="flex items-center gap-1.5 px-2 py-1.5 text-sm font-medium text-primary rounded-lg hover:bg-primary/10 transition-colors disabled:opacity-50"
              aria-label="Restore selected"
            >
              <Download className="w-4 h-4 flex-shrink-0" />
              Restore
            </button>
          </div>
        </div>
      )}

      {/* Restore Progress Bar */}
      {restoreState === "restoring" && (
        <div className="sticky top-[53px] z-40 flex items-center gap-3 px-4 py-3 bg-primary/10 border-b border-border animate-fade-in">
          <Loader2 className="w-5 h-5 text-primary animate-spin flex-shrink-0" />
          <div className="flex-1 min-w-0">
            <p className="text-sm font-medium text-foreground">
              Restoring {selectedIds.size} item{selectedIds.size > 1 ? "s" : ""}…
            </p>
            <div className="mt-1.5 h-1.5 rounded-full bg-secondary overflow-hidden">
              <div className="h-full bg-primary rounded-full animate-pulse w-2/3" />
            </div>
          </div>
        </div>
      )}

      {/* Restore Complete Bar */}
      {restoreState === "done" && (
        <div className="sticky top-[53px] z-40 flex items-center justify-between px-4 py-3 bg-green-500/10 border-b border-border animate-fade-in">
          <div className="flex items-center gap-2">
            <div className="w-6 h-6 rounded-full bg-green-500 flex items-center justify-center">
              <Check className="w-4 h-4 text-white" />
            </div>
            <span className="text-sm font-medium text-foreground">Restore complete</span>
          </div>
          <button
            onClick={handleRestoreDone}
            className="px-3 py-1.5 text-sm font-medium text-foreground rounded-lg hover:bg-secondary transition-colors"
            aria-label="Dismiss restore complete"
          >
            Done
          </button>
        </div>
      )}

      {/* Restore Error Bar */}
      {restoreState === "error" && (
        <div className="sticky top-[53px] z-40 flex items-center justify-between px-4 py-3 bg-destructive/10 border-b border-border animate-fade-in">
          <div className="flex items-center gap-2">
            <AlertTriangle className="w-5 h-5 text-destructive" />
            <span className="text-sm font-medium text-destructive">Restore failed</span>
          </div>
          <button
            onClick={handleRestoreDone}
            className="px-3 py-1.5 text-sm font-medium text-foreground rounded-lg hover:bg-secondary transition-colors"
            aria-label="Dismiss restore error"
          >
            Dismiss
          </button>
        </div>
      )}

      {/* Breadcrumb Navigation */}
      <div className="breadcrumb border-b border-border">
        <button
          onClick={() => setPath([])}
          className={path.length === 0 ? "breadcrumb-item-active" : "breadcrumb-item"}
        >
          /
        </button>
        {path.map((folder, index) => (
          <div key={index} className="flex items-center">
            <ChevronRight className="w-4 h-4 text-muted-foreground mx-1" />
            <button
              onClick={() => setPath(path.slice(0, index + 1))}
              className={
                index === path.length - 1
                  ? "breadcrumb-item-active"
                  : "breadcrumb-item"
              }
            >
              {folder}
            </button>
          </div>
        ))}
      </div>

      {/* Content */}
      <div className="flex-1">
        {/* Loading State */}
        {isLoading && (
          <div className="flex items-center justify-center py-20">
            <Loader2 className="w-10 h-10 text-primary animate-spin" />
          </div>
        )}

        {/* Error State */}
        {isError && (
          <div className="flex flex-col items-center justify-center py-20 text-center px-4 animate-fade-in">
            <div className="w-16 h-16 rounded-full bg-destructive/10 flex items-center justify-center mb-4">
              <AlertTriangle className="w-8 h-8 text-destructive" />
            </div>
            <p className="text-destructive font-medium mb-4">Failed to load directory</p>
            <button onClick={() => navigate(-1)} className="btn-primary">
              Go Back
            </button>
          </div>
        )}

        {/* Empty State */}
        {isEmpty && (
          <div className="flex flex-col items-center justify-center py-20 text-center animate-fade-in">
            <div className="w-16 h-16 rounded-full bg-muted flex items-center justify-center mb-4">
              <FolderX className="w-8 h-8 text-muted-foreground" />
            </div>
            <p className="text-muted-foreground">Empty folder</p>
          </div>
        )}

        {/* Data State - File List */}
        {!isLoading && !isError && files.length > 0 && (
          <div className="divide-y divide-border">
            {files.map((file, index) => (
              <div
                key={file.id}
                onPointerDown={() => handlePointerDown(file.id)}
                onPointerUp={handlePointerUp}
                onPointerLeave={handlePointerLeave}
                onContextMenu={(e) => e.preventDefault()}
                onClick={() => handleItemClick(file)}
                className={`w-full list-item justify-between animate-fade-in cursor-pointer select-none ${
                  !selectionModeActive && file.type !== "folder" ? "cursor-default" : ""
                } ${selectedIds.has(file.id) ? "bg-primary/5" : ""}`}
                style={{ animationDelay: `${index * 0.02}s` }}
                role="button"
                aria-label={`${file.type === "folder" ? "Folder" : "File"} ${file.name}`}
              >
                <div className="flex items-center gap-4 min-w-0 flex-1">
                  {selectionModeActive ? (
                    <div className="flex-shrink-0">
                      <Checkbox
                        checked={selectedIds.has(file.id)}
                        className="w-5 h-5"
                      />
                    </div>
                  ) : (
                    getFileIcon(file.type)
                  )}
                  <div className="text-left min-w-0 flex-1">
                    <p className="font-medium text-foreground truncate">{file.name}</p>
                    <div className="flex items-center justify-between text-sm text-muted-foreground">
                      <span>{file.size > 0 ? formatFileSize(file.size) : ""}</span>
                      {file.modifiedAt && (
                        <span className="flex-shrink-0 ml-2">{formatDateTime(file.modifiedAt)}</span>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default FileBrowserScreen;
