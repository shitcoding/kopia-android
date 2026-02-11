import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, Download, Folder, File, Link2, ChevronRight, Loader2, AlertTriangle, FolderX, Settings } from "lucide-react";
import { useDirectory } from "@/hooks/useKopiaApi";
import { formatFileSize, formatDateTime } from "@/lib/format";
import type { FileEntry as KopiaFileEntry, FileEntryType } from "@/types/kopia";

interface DisplayFileEntry {
  name: string;
  type: "folder" | "file" | "symlink" | "unknown";
  size: number;
  modifiedAt?: Date;
}

const FileBrowserScreen = () => {
  const navigate = useNavigate();
  const { snapshotId } = useParams<{ snapshotId: string }>();
  const [path, setPath] = useState<string[]>([]);

  // Build current path string
  const currentPathStr = "/" + path.join("/");
  const currentFolder = path.length > 0 ? path[path.length - 1] : "/";

  // Fetch directory contents
  const directoryRequest = snapshotId
    ? { snapshotId, path: currentPathStr }
    : null;

  const { data: directoryPage, isLoading, isError, refetch } = useDirectory(directoryRequest);

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

  const files: DisplayFileEntry[] = directoryPage?.entries.map((entry: KopiaFileEntry) => ({
    name: entry.name,
    type: mapFileType(entry.type),
    size: entry.size,
    modifiedAt: entry.modTimeEpochMs ? new Date(entry.modTimeEpochMs) : undefined,
  })) ?? [];

  const isEmpty = !isLoading && !isError && files.length === 0;

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

  const getFileIcon = (type: DisplayFileEntry["type"]) => {
    switch (type) {
      case "folder":
        return <Folder className="w-6 h-6 text-folder fill-folder/20" />;
      case "file":
        return <File className="w-6 h-6 text-file" />;
      case "symlink":
        return <Link2 className="w-6 h-6 text-symlink" />;
      case "unknown":
        return <File className="w-6 h-6 text-muted-foreground" />;
    }
  };

  return (
    <div className="app-container min-h-screen flex flex-col">
      {/* App Bar */}
      <header className="app-bar">
        <button onClick={() => path.length > 0 ? setPath(path.slice(0, -1)) : navigate(-1)} className="btn-icon -ml-2">
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h1 className="app-bar-title truncate max-w-[50%]">{currentFolder}</h1>
        <div className="flex items-center gap-1">
          <button onClick={() => navigate(`/restore/${snapshotId}`)} className="btn-icon">
            <Download className="w-5 h-5" />
          </button>
          <button onClick={() => navigate("/settings")} className="btn-icon -mr-2">
            <Settings className="w-5 h-5" />
          </button>
        </div>
      </header>

      {/* Breadcrumb Navigation */}
      <div className="breadcrumb border-b border-border">
        <button
          onClick={() => handleBreadcrumbClick(-1)}
          className={path.length === 0 ? "breadcrumb-item-active" : "breadcrumb-item"}
        >
          /
        </button>
        {path.map((segment, index) => (
          <div key={index} className="flex items-center gap-1">
            <ChevronRight className="w-4 h-4 text-muted-foreground flex-shrink-0" />
            <button
              onClick={() => handleBreadcrumbClick(index)}
              className={index === path.length - 1 ? "breadcrumb-item-active" : "breadcrumb-item"}
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
            <p className="text-destructive font-medium mb-4">Failed to load files</p>
            <button onClick={() => refetch()} className="btn-primary">
              Retry
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
        {!isLoading && !isError && !isEmpty && (
          <div className="divide-y divide-border">
            {files.map((file, index) => (
              <button
                key={file.name}
                onClick={() => file.type === "folder" && handleFolderClick(file.name)}
                className={`w-full list-item justify-between animate-fade-in ${
                  file.type !== "folder" ? "cursor-default hover:bg-transparent" : ""
                }`}
                style={{ animationDelay: `${index * 0.02}s` }}
              >
                <div className="flex items-center gap-4 min-w-0 flex-1">
                  {getFileIcon(file.type)}
                  <div className="text-left min-w-0 flex-1">
                    <p className="font-medium text-foreground truncate">{file.name}</p>
                    <div className="flex items-center justify-between text-sm text-muted-foreground">
                      <span>{formatFileSize(file.size)}</span>
                      {file.modifiedAt && (
                        <span className="flex-shrink-0 ml-2">{formatDateTime(file.modifiedAt.getTime())}</span>
                      )}
                    </div>
                  </div>
                </div>
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default FileBrowserScreen;
