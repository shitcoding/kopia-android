import { useState, useEffect } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, Download, Folder, File, Link2, ChevronRight, Loader2, AlertTriangle, FolderX } from "lucide-react";
import { useDirectory } from "@/hooks/useKopiaApi";
import type { FileEntry, FileEntryType } from "@/types/kopia";

const FileBrowserScreen = () => {
  const navigate = useNavigate();
  const { snapshotId } = useParams<{ snapshotId: string }>();
  const [path, setPath] = useState<string[]>([]);

  const currentPath = "/" + path.join("/");
  const currentFolder = path.length > 0 ? path[path.length - 1] : "/";

  const { data: directoryPage, isLoading, error, refetch } = useDirectory(
    snapshotId ? { snapshotId, path: currentPath } : null
  );

  const entries = directoryPage?.entries || [];

  // Refetch when path changes
  useEffect(() => {
    if (snapshotId) {
      refetch();
    }
  }, [path, snapshotId, refetch]);

  const formatDate = (epochMs: number | undefined) => {
    if (!epochMs) return "";
    return new Date(epochMs).toLocaleDateString("en-US", {
      month: "numeric",
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

  const handleFolderClick = (folderName: string) => {
    setPath([...path, folderName]);
  };

  const handleBreadcrumbClick = (index: number) => {
    if (index === -1) {
      setPath([]);
    } else {
      setPath(path.slice(0, index + 1));
    }
  };

  const handleBack = () => {
    if (path.length > 0) {
      setPath(path.slice(0, -1));
    } else {
      navigate("/snapshots");
    }
  };

  const getFileIcon = (type: FileEntryType) => {
    switch (type) {
      case "DIRECTORY":
        return <Folder className="w-6 h-6 text-folder fill-folder/20" />;
      case "FILE":
        return <File className="w-6 h-6 text-file" />;
      case "SYMLINK":
        return <Link2 className="w-6 h-6 text-symlink" />;
      default:
        return <File className="w-6 h-6 text-muted-foreground" />;
    }
  };

  return (
    <div className="app-container min-h-screen flex flex-col">
      {/* App Bar */}
      <header className="app-bar">
        <button onClick={handleBack} className="btn-icon -ml-2">
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h1 className="app-bar-title truncate max-w-[60%]">{currentFolder}</h1>
        <button
          onClick={() => navigate(`/restore/${snapshotId}`)}
          className="btn-icon -mr-2"
          data-testid="restore-button"
        >
          <Download className="w-5 h-5" />
        </button>
      </header>

      {/* Breadcrumb Navigation */}
      <div className="breadcrumb border-b border-border">
        <button
          onClick={() => handleBreadcrumbClick(-1)}
          className={path.length === 0 ? "breadcrumb-item-active" : "breadcrumb-item"}
          data-testid="breadcrumb-root"
        >
          /
        </button>
        {path.map((segment, index) => (
          <div key={index} className="flex items-center gap-1">
            <ChevronRight className="w-4 h-4 text-muted-foreground flex-shrink-0" />
            <button
              onClick={() => handleBreadcrumbClick(index)}
              className={index === path.length - 1 ? "breadcrumb-item-active" : "breadcrumb-item"}
              data-testid={`breadcrumb-${segment}`}
            >
              {segment}
            </button>
          </div>
        ))}
      </div>

      {/* Content */}
      <div className="flex-1">
        {/* Loading State */}
        {isLoading && (
          <div className="flex items-center justify-center py-20" data-testid="loading-indicator">
            <Loader2 className="w-10 h-10 text-primary animate-spin" />
          </div>
        )}

        {/* Error State */}
        {error && !isLoading && (
          <div className="flex flex-col items-center justify-center py-20 text-center px-4 animate-fade-in">
            <div className="w-16 h-16 rounded-full bg-destructive/10 flex items-center justify-center mb-4">
              <AlertTriangle className="w-8 h-8 text-destructive" />
            </div>
            <p className="text-destructive font-medium mb-2" data-testid="error-message">Failed to load files</p>
            <p className="text-sm text-muted-foreground mb-4">
              {error instanceof Error ? error.message : "Unknown error"}
            </p>
            <button onClick={() => refetch()} className="btn-primary">
              Retry
            </button>
          </div>
        )}

        {/* Empty State */}
        {!isLoading && !error && entries.length === 0 && (
          <div className="flex flex-col items-center justify-center py-20 text-center animate-fade-in">
            <div className="w-16 h-16 rounded-full bg-muted flex items-center justify-center mb-4">
              <FolderX className="w-8 h-8 text-muted-foreground" />
            </div>
            <p className="text-muted-foreground">Empty folder</p>
          </div>
        )}

        {/* Data State - File List */}
        {!isLoading && !error && entries.length > 0 && (
          <div className="divide-y divide-border">
            {entries.map((entry, index) => (
              <button
                key={entry.name}
                onClick={() => entry.type === "DIRECTORY" && handleFolderClick(entry.name)}
                className={`w-full list-item justify-between animate-fade-in ${
                  entry.type !== "DIRECTORY" ? "cursor-default hover:bg-transparent" : ""
                }`}
                style={{ animationDelay: `${index * 0.02}s` }}
                data-testid={`entry-${entry.name}`}
              >
                <div className="flex items-center gap-4 min-w-0 flex-1">
                  {getFileIcon(entry.type)}
                  <div className="text-left min-w-0 flex-1">
                    <p className="font-medium text-foreground truncate">{entry.name}</p>
                    <p className="text-sm text-muted-foreground">
                      {entry.type === "FILE" && entry.size > 0 && (
                        <span>{formatSize(entry.size)} &nbsp;&nbsp;</span>
                      )}
                      {formatDate(entry.modTimeEpochMs)}
                    </p>
                  </div>
                </div>
                {entry.type === "DIRECTORY" && (
                  <ChevronRight className="w-5 h-5 text-muted-foreground flex-shrink-0" />
                )}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default FileBrowserScreen;
