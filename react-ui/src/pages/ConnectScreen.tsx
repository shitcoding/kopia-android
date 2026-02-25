import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowLeft, Eye, EyeOff, Loader2, FolderOpen, ShieldAlert, Settings } from "lucide-react";
import { Checkbox } from "@/components/ui/checkbox";
import { kopiaBridge, BridgeError } from "@/services/kopiaBridge";
import { ErrorCodes } from "@/types/kopia";
import type { ConnectRequest, StorageType, ConnectionConfig } from "@/types/kopia";
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

type UIStorageType = "local" | "s3" | "webdav" | "sftp";

const ConnectScreen = () => {
  const navigate = useNavigate();
  const [storageType, setStorageType] = useState<UIStorageType>("local");
  const [isConnecting, setIsConnecting] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [rememberPassword, setRememberPassword] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showPermissionDialog, setShowPermissionDialog] = useState(false);
  const [isPasswordAutoFilled, setIsPasswordAutoFilled] = useState(false);

  // Form states for different storage types
  const [localPath, setLocalPath] = useState("");
  const [s3Bucket, setS3Bucket] = useState("");
  const [s3Endpoint, setS3Endpoint] = useState("s3.amazonaws.com");
  const [s3Region, setS3Region] = useState("us-east-1");
  const [s3AccessKey, setS3AccessKey] = useState("");
  const [webdavUrl, setWebdavUrl] = useState("");
  const [webdavUsername, setWebdavUsername] = useState("");
  const [sftpHost, setSftpHost] = useState("");
  const [sftpPort, setSftpPort] = useState("22");
  const [sftpUsername, setSftpUsername] = useState("");
  const [sftpPath, setSftpPath] = useState("");
  const [s3SecretKey, setS3SecretKey] = useState("");
  const [webdavPassword, setWebdavPassword] = useState("");
  const [sftpPassword, setSftpPassword] = useState("");
  const [password, setPassword] = useState("");

  // Subscribe to folder picker results
  useEffect(() => {
    const unsubscribe = kopiaBridge.onDestinationPicked((result) => {
      if (result.displayName) {
        // Convert SAF display name to a path hint
        // e.g., "primary:testrepo" -> "/sdcard/testrepo"
        const displayPath = result.displayName;
        if (displayPath.startsWith("primary:")) {
          setLocalPath("/sdcard/" + displayPath.substring(8));
        } else {
          setLocalPath(displayPath);
        }
      }
    });
    return unsubscribe;
  }, []);

  // Check for stored password when config changes
  useEffect(() => {
    const checkStoredPassword = async () => {
      try {
        const config = buildConnectionConfig();
        const hasPassword = await kopiaBridge.hasStoredPassword(config);
        if (hasPassword) {
          setRememberPassword(true);
          const storedPassword = await kopiaBridge.getStoredPassword(config);
          if (storedPassword) {
            setPassword(storedPassword);
            setIsPasswordAutoFilled(true); // Mark as auto-filled
            setShowPassword(false); // Hide password when auto-filled
          }
        } else {
          // Clear password if switching to a config without stored password
          setPassword("");
          setRememberPassword(true);
          setIsPasswordAutoFilled(false);
        }
      } catch (error) {
        console.error("Failed to check stored password:", error);
      }
    };

    checkStoredPassword();
  }, [storageType, localPath, s3Bucket, s3Endpoint, webdavUrl, sftpHost, sftpPort]);

  const tabs: { id: UIStorageType; label: string }[] = [
    { id: "local", label: "Local" },
    { id: "s3", label: "S3" },
    { id: "webdav", label: "WebDAV" },
    { id: "sftp", label: "SFTP" },
  ];

  const mapStorageType = (ui: UIStorageType): StorageType => {
    switch (ui) {
      case "local": return "LOCAL_FILESYSTEM";
      case "s3": return "S3";
      case "webdav": return "WEBDAV";
      case "sftp": return "SFTP";
    }
  };

  const buildConnectionConfig = (): ConnectionConfig => {
    const config: ConnectionConfig = {
      storageType: mapStorageType(storageType),
    };

    switch (storageType) {
      case "local":
        config.local = { path: localPath };
        break;
      case "s3":
        config.s3 = {
          bucket: s3Bucket,
          endpoint: s3Endpoint,
          region: s3Region,
          accessKeyId: s3AccessKey,
          secretAccessKey: s3SecretKey,
        };
        break;
      case "webdav":
        config.webdav = {
          url: webdavUrl,
          username: webdavUsername,
          password: webdavPassword,
        };
        break;
      case "sftp":
        config.sftp = {
          host: sftpHost,
          port: parseInt(sftpPort, 10) || 22,
          username: sftpUsername,
          path: sftpPath,
          password: sftpPassword,
        };
        break;
    }

    return config;
  };

  const validateForm = (): string | null => {
    if (!password.trim()) {
      return "Repository password is required";
    }

    switch (storageType) {
      case "local":
        if (!localPath.trim()) return "Repository path is required";
        break;
      case "s3":
        if (!s3Bucket.trim()) return "S3 bucket is required";
        if (!s3AccessKey.trim()) return "Access key ID is required";
        if (!s3SecretKey.trim()) return "Secret access key is required";
        break;
      case "webdav":
        if (!webdavUrl.trim()) return "WebDAV URL is required";
        break;
      case "sftp":
        if (!sftpHost.trim()) return "SFTP host is required";
        if (!sftpUsername.trim()) return "SFTP username is required";
        break;
    }

    return null;
  };

  const handleConnect = async () => {
    const validationError = validateForm();
    if (validationError) {
      setError(validationError);
      return;
    }

    setIsConnecting(true);
    setError(null);

    // Allow UI to update before potentially blocking native call
    await new Promise(resolve => setTimeout(resolve, 100));

    try {
      const config = buildConnectionConfig();
      const request: ConnectRequest = {
        config: config,
        repositoryPassword: password,
      };

      await kopiaBridge.connect(request);

      // Store password if "Remember password" is checked
      if (rememberPassword) {
        try {
          await kopiaBridge.storePassword(config, password);
        } catch (storeError) {
          console.error("Failed to store password:", storeError);
          // Don't block navigation if password storage fails
        }
      }

      navigate("/snapshots");
    } catch (e) {
      if (e instanceof BridgeError && e.code === ErrorCodes.STORAGE_PERMISSION_REQUIRED) {
        setShowPermissionDialog(true);
      } else {
        const message = e instanceof Error ? e.message : "Failed to connect to repository";
        setError(message);
      }
    } finally {
      setIsConnecting(false);
    }
  };

  const handleOpenPermissionSettings = () => {
    kopiaBridge.openStoragePermissionSettings();
    setShowPermissionDialog(false);
  };

  return (
    <div className="app-container min-h-screen flex flex-col">
      {/* App Bar */}
      <header className="app-bar">
        <button onClick={() => navigate("/")} className="btn-icon -ml-2" aria-label="Back">
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h1 className="app-bar-title">Connect Repository</h1>
        <button onClick={() => navigate("/settings")} className="btn-icon -mr-2" aria-label="Settings">
          <Settings className="w-5 h-5" />
        </button>
      </header>

      {/* Tab Navigation */}
      <div className="px-4 pt-4">
        <div className="flex gap-1 border-b border-border">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setStorageType(tab.id)}
              className={`px-4 py-3 text-sm font-medium transition-colors ${
                storageType === tab.id
                  ? "tab-active"
                  : "text-muted-foreground hover:text-foreground"
              }`}
              data-testid={`storage-tab-${tab.id}`}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      {/* Form Content */}
      <div className="flex-1 px-4 py-6 space-y-4 animate-fade-in">
        {/* Local Storage Form */}
        {storageType === "local" && (
          <div className="space-y-4">
            <div>
              <div className="flex gap-2 items-stretch">
                <button
                  type="button"
                  onClick={() => {
                    kopiaBridge.pickRestoreDestination();
                  }}
                  disabled={isConnecting}
                  className="shrink-0 w-14 rounded-xl border border-border bg-card hover:bg-accent flex items-center justify-center transition-colors disabled:opacity-50"
                  title="Browse for directory"
                  data-testid="browse-folder-button"
                >
                  <FolderOpen className="w-5 h-5 text-primary" />
                </button>
                <input
                  type="text"
                  placeholder="Repository Path"
                  value={localPath}
                  onChange={(e) => setLocalPath(e.target.value)}
                  className="input-md3 flex-1"
                  disabled={isConnecting}
                  autoComplete="off"
                  autoCorrect="off"
                  autoCapitalize="off"
                  spellCheck={false}
                  data-testid="repo-path-input"
                />
              </div>
              <p className="text-sm text-muted-foreground mt-2 px-1">
                Enter the full path to the Kopia repository directory on the device.
              </p>
            </div>
          </div>
        )}

        {/* S3 Storage Form */}
        {storageType === "s3" && (
          <div className="space-y-3">
            <input
              type="text"
              placeholder="Bucket"
              value={s3Bucket}
              onChange={(e) => setS3Bucket(e.target.value)}
              className="input-md3"
              disabled={isConnecting}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              data-testid="s3-bucket-input"
            />
            <input
              type="url"
              placeholder="Endpoint"
              value={s3Endpoint}
              onChange={(e) => setS3Endpoint(e.target.value)}
              className="input-md3"
              disabled={isConnecting}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              data-testid="s3-endpoint-input"
            />
            <input
              type="text"
              placeholder="Region"
              value={s3Region}
              onChange={(e) => setS3Region(e.target.value)}
              className="input-md3"
              disabled={isConnecting}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              data-testid="s3-region-input"
            />
            <input
              type="text"
              placeholder="Access Key ID"
              value={s3AccessKey}
              onChange={(e) => setS3AccessKey(e.target.value)}
              className="input-md3"
              disabled={isConnecting}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              data-testid="s3-access-key-input"
            />
            <input
              type="password"
              placeholder="Secret Access Key"
              value={s3SecretKey}
              onChange={(e) => setS3SecretKey(e.target.value)}
              className="input-md3"
              disabled={isConnecting}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              data-testid="s3-secret-key-input"
            />
          </div>
        )}

        {/* WebDAV Form */}
        {storageType === "webdav" && (
          <div className="space-y-3">
            <input
              type="url"
              placeholder="WebDAV URL"
              value={webdavUrl}
              onChange={(e) => setWebdavUrl(e.target.value)}
              className="input-md3"
              disabled={isConnecting}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              data-testid="webdav-url-input"
            />
            <input
              type="text"
              placeholder="Username"
              value={webdavUsername}
              onChange={(e) => setWebdavUsername(e.target.value)}
              className="input-md3"
              disabled={isConnecting}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              data-testid="webdav-username-input"
            />
            <input
              type="password"
              placeholder="WebDAV Password"
              value={webdavPassword}
              onChange={(e) => setWebdavPassword(e.target.value)}
              className="input-md3"
              disabled={isConnecting}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              data-testid="webdav-password-input"
            />
          </div>
        )}

        {/* SFTP Form */}
        {storageType === "sftp" && (
          <div className="space-y-3">
            <input
              type="text"
              placeholder="Host"
              value={sftpHost}
              onChange={(e) => setSftpHost(e.target.value)}
              className="input-md3"
              disabled={isConnecting}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              data-testid="sftp-host-input"
            />
            <input
              type="number"
              placeholder="Port"
              value={sftpPort}
              onChange={(e) => setSftpPort(e.target.value)}
              className="input-md3"
              disabled={isConnecting}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              data-testid="sftp-port-input"
            />
            <input
              type="text"
              placeholder="Username"
              value={sftpUsername}
              onChange={(e) => setSftpUsername(e.target.value)}
              className="input-md3"
              disabled={isConnecting}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              data-testid="sftp-username-input"
            />
            <input
              type="text"
              placeholder="Path"
              value={sftpPath}
              onChange={(e) => setSftpPath(e.target.value)}
              className="input-md3"
              disabled={isConnecting}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              data-testid="sftp-path-input"
            />
            <input
              type="password"
              placeholder="SFTP Password"
              value={sftpPassword}
              onChange={(e) => setSftpPassword(e.target.value)}
              className="input-md3"
              disabled={isConnecting}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              data-testid="sftp-password-input"
            />
          </div>
        )}

        {/* Password Section (Common to all) */}
        <div className="pt-2 space-y-3">
          <p className="text-xs text-muted-foreground px-1">
            Repository encryption password
          </p>
          <div className="relative">
            <input
              type={showPassword ? "text" : "password"}
              placeholder="Repository Password"
              value={password}
              onChange={(e) => {
                // Security: If password was auto-filled, clear it entirely on first edit
                if (isPasswordAutoFilled) {
                  setPassword(""); // Clear the saved password
                  setIsPasswordAutoFilled(false);
                  setShowPassword(false);
                  // Don't set the new value yet - let user start typing from scratch
                } else {
                  setPassword(e.target.value);
                }
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  // Dismiss keyboard - multiple methods for Android WebView
                  const input = e.target as HTMLInputElement;
                  input.blur();
                  // Force focus away
                  document.body.focus();
                  // Small delay before connect to ensure keyboard dismisses
                  setTimeout(() => handleConnect(), 100);
                }
              }}
              className="input-md3 pr-12"
              disabled={isConnecting}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              data-testid="password-input"
            />
            <button
              type="button"
              onClick={() => setShowPassword(!showPassword)}
              disabled={isPasswordAutoFilled}
              className={`absolute right-3 top-1/2 -translate-y-1/2 p-1 transition-colors ${
                isPasswordAutoFilled
                  ? "text-muted-foreground/40 cursor-not-allowed"
                  : "text-muted-foreground hover:text-foreground"
              }`}
              title={isPasswordAutoFilled ? "Cannot view saved passwords" : "Show password"}
            >
              {showPassword ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
            </button>
          </div>

          <label className="flex items-center gap-3 cursor-pointer py-1">
            <Checkbox
              checked={rememberPassword}
              onCheckedChange={(checked) => setRememberPassword(checked === true)}
              disabled={isConnecting}
            />
            <span className="text-sm text-foreground">Remember password</span>
          </label>
        </div>

        {/* Error Message */}
        {error && (
          <div className="p-3 rounded-lg bg-destructive/10 border border-destructive/20" data-testid="error-message">
            <p className="text-sm text-destructive">{error}</p>
          </div>
        )}
      </div>

      {/* Connect Button */}
      <div className="px-4 pb-8">
        <button
          onClick={handleConnect}
          disabled={isConnecting}
          className="btn-primary w-full"
          data-testid="connect-button"
        >
          {isConnecting ? (
            <>
              <Loader2 className="w-5 h-5 animate-spin" data-testid="loading-indicator" />
              Connecting...
            </>
          ) : (
            "Connect"
          )}
        </button>
      </div>

      {/* Storage Permission Dialog */}
      <AlertDialog open={showPermissionDialog} onOpenChange={setShowPermissionDialog}>
        <AlertDialogContent className="max-w-[90%] rounded-xl">
          <AlertDialogHeader>
            <div className="flex items-center gap-3 mb-2">
              <div className="p-2 rounded-full bg-warning/10">
                <ShieldAlert className="w-6 h-6 text-warning" />
              </div>
              <AlertDialogTitle>Storage Permission Required</AlertDialogTitle>
            </div>
            <AlertDialogDescription className="text-left">
              To access local repositories, KopiaKt needs permission to manage all files on your device.
              {"\n\n"}
              Please enable <span className="font-medium text-foreground">"Allow access to manage all files"</span> in the next screen.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter className="flex-row gap-2">
            <AlertDialogCancel className="flex-1 mt-0">Cancel</AlertDialogCancel>
            <AlertDialogAction
              className="flex-1"
              onClick={handleOpenPermissionSettings}
            >
              Open Settings
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
};

export default ConnectScreen;
