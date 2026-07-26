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
import { CleartextWarning } from "@/components/CleartextWarning";
import { isCleartextUrl } from "@/lib/format";

type UIStorageType = "local" | "s3" | "webdav" | "sftp";

const ConnectScreen = () => {
  const navigate = useNavigate();
  const [storageType, setStorageType] = useState<UIStorageType>("local");
  const [isConnecting, setIsConnecting] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [rememberPassword, setRememberPassword] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showPermissionDialog, setShowPermissionDialog] = useState(false);
  const [hasSavedPassword, setHasSavedPassword] = useState(false);

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
  const [sftpKnownHosts, setSftpKnownHosts] = useState("");
  const [sftpFingerprint, setSftpFingerprint] = useState("");
  const [sftpInsecure, setSftpInsecure] = useState(false);
  const [webdavCertFingerprint, setWebdavCertFingerprint] = useState("");
  const [s3RootCaPem, setS3RootCaPem] = useState("");
  const [allowCleartextHttp, setAllowCleartextHttp] = useState(false);
  const [password, setPassword] = useState("");

  // The endpoint that decides whether this connection is cleartext, per storage type.
  const cleartextEndpoint =
    storageType === "s3" ? s3Endpoint : storageType === "webdav" ? webdavUrl : "";
  const isCleartext = isCleartextUrl(cleartextEndpoint);

  // The acknowledgment is about ONE endpoint. Drop it whenever the storage type or the endpoint
  // changes, so a box ticked for a previous target can never carry over to a new connection.
  useEffect(() => {
    setAllowCleartextHttp(false);
  }, [storageType, cleartextEndpoint]);

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

  // Track only WHETHER a password is stored for this config — never fetch the plaintext into JS.
  // We deliberately do NOT touch the `password` field here: clearing it would wipe a value the user
  // just typed when they tweak another field. `cancelled` guards against a stale async response
  // applying to a newer config.
  useEffect(() => {
    let cancelled = false;
    const checkStoredPassword = async () => {
      try {
        const config = buildConnectionConfig();
        const hasPassword = await kopiaBridge.hasStoredPassword(config);
        if (cancelled) return;
        setHasSavedPassword(hasPassword);
        if (hasPassword) {
          setRememberPassword(true);
          setShowPassword(false);
        }
      } catch (error) {
        if (!cancelled) console.error("Failed to check stored password:", error);
      }
    };

    checkStoredPassword();
    return () => {
      cancelled = true;
    };
  }, [storageType, localPath, s3Bucket, s3Endpoint, webdavUrl, sftpHost, sftpPort]);

  // A saved password is "in use" only while the field is empty; the moment the user types, their
  // input takes over. Derived (not stored) so erasing back to empty restores the saved-password mode
  // — an empty field then connects with the natively-resolved saved password — instead of dead-ending
  // on the "password required" error.
  const usingSavedPassword = hasSavedPassword && !password.trim();

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
          // Trust material is meaningless over cleartext; omit it rather than sending a value the
          // storage layer will reject.
          rootCaPem: isCleartext ? "" : s3RootCaPem,
          allowCleartextHttp,
        };
        break;
      case "webdav":
        config.webdav = {
          url: webdavUrl,
          username: webdavUsername,
          password: webdavPassword,
          trustedServerCertificateFingerprint: isCleartext ? "" : webdavCertFingerprint,
          allowCleartextHttp,
        };
        break;
      case "sftp":
        config.sftp = {
          host: sftpHost,
          port: parseInt(sftpPort, 10) || 22,
          username: sftpUsername,
          path: sftpPath,
          password: sftpPassword,
          knownHostsData: sftpKnownHosts,
          hostKeyFingerprint: sftpFingerprint,
          insecureSkipHostKeyVerification: sftpInsecure,
        };
        break;
    }

    return config;
  };

  const validateForm = (): string | null => {
    // When a saved password exists, an empty field is valid — the native side supplies it.
    if (!password.trim() && !hasSavedPassword) {
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

    // Cleartext http must be acknowledged explicitly — the native connect layer refuses it otherwise.
    if (isCleartext && !allowCleartextHttp) {
      return "This endpoint uses plaintext HTTP. Use https, or confirm you accept sending credentials unencrypted.";
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

      // Store only a freshly-typed password. When the field is empty (a saved password is being
      // used), do nothing — storing "" would clobber the existing saved password.
      if (rememberPassword && password.trim()) {
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
              id={`storage-tab-${tab.id}`}
              aria-label={`${tab.label} storage`}
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
                  aria-label="Browse for directory"
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
                  id="repo-path-input"
                  aria-label="Repository path"
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
              id="s3-bucket-input"
              aria-label="S3 bucket"
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
              id="s3-endpoint-input"
              aria-label="S3 endpoint"
              data-testid="s3-endpoint-input"
            />
            {isCleartextUrl(s3Endpoint) && (
              <CleartextWarning
                testId="s3-cleartext-warning"
                checkboxTestId="s3-cleartext-ack-checkbox"
                acknowledged={allowCleartextHttp}
                onAcknowledgedChange={setAllowCleartextHttp}
                disabled={isConnecting}
              />
            )}
            {!isCleartextUrl(s3Endpoint) && (
              <textarea
                placeholder="Root CA certificate, PEM (optional — for a private CA)"
                value={s3RootCaPem}
                onChange={(e) => setS3RootCaPem(e.target.value)}
                className="input-md3 min-h-[60px] font-mono text-xs"
                disabled={isConnecting}
                autoComplete="off"
                autoCorrect="off"
                autoCapitalize="off"
                spellCheck={false}
                id="s3-root-ca-input"
                aria-label="S3 root CA certificate"
                data-testid="s3-root-ca-input"
              />
            )}
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
              id="s3-region-input"
              aria-label="S3 region"
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
              id="s3-access-key-input"
              aria-label="Access key ID"
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
              id="s3-secret-key-input"
              aria-label="Secret access key"
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
              id="webdav-url-input"
              aria-label="WebDAV URL"
              data-testid="webdav-url-input"
            />
            {isCleartextUrl(webdavUrl) && (
              <CleartextWarning
                testId="webdav-cleartext-warning"
                checkboxTestId="webdav-cleartext-ack-checkbox"
                acknowledged={allowCleartextHttp}
                onAcknowledgedChange={setAllowCleartextHttp}
                disabled={isConnecting}
              />
            )}
            {!isCleartextUrl(webdavUrl) && (
              <input
                type="text"
                placeholder="Server certificate SHA-256 (optional — for a self-signed cert)"
                value={webdavCertFingerprint}
                onChange={(e) => setWebdavCertFingerprint(e.target.value)}
                className="input-md3 font-mono text-xs"
                disabled={isConnecting}
                autoComplete="off"
                autoCorrect="off"
                autoCapitalize="off"
                spellCheck={false}
                id="webdav-cert-fingerprint-input"
                aria-label="WebDAV server certificate fingerprint"
                data-testid="webdav-cert-fingerprint-input"
              />
            )}
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
              id="webdav-username-input"
              aria-label="WebDAV username"
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
              id="webdav-password-input"
              aria-label="WebDAV password"
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
              id="sftp-host-input"
              aria-label="SFTP host"
              data-testid="sftp-host-input"
            />
            <input
              type="text"
              inputMode="numeric"
              placeholder="Port"
              value={sftpPort}
              onChange={(e) => setSftpPort(e.target.value)}
              className="input-md3"
              disabled={isConnecting}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              id="sftp-port-input"
              aria-label="SFTP port"
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
              id="sftp-username-input"
              aria-label="SFTP username"
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
              id="sftp-path-input"
              aria-label="SFTP path"
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
              id="sftp-password-input"
              aria-label="SFTP password"
              data-testid="sftp-password-input"
            />
            <p className="text-xs text-muted-foreground px-1 pt-1">
              Host key verification: paste known_hosts or a fingerprint to pin the server, or enable
              insecure trust for testing. Without one, the connection is rejected.
            </p>
            <textarea
              placeholder="Known hosts (optional — OpenSSH known_hosts line)"
              value={sftpKnownHosts}
              onChange={(e) => setSftpKnownHosts(e.target.value)}
              className="input-md3 min-h-[60px] font-mono text-xs"
              disabled={isConnecting}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              id="sftp-known-hosts-input"
              aria-label="SFTP known hosts"
              data-testid="sftp-known-hosts-input"
            />
            <input
              type="text"
              placeholder="Host key fingerprint (SHA256:… — optional)"
              value={sftpFingerprint}
              onChange={(e) => setSftpFingerprint(e.target.value)}
              className="input-md3"
              disabled={isConnecting}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              id="sftp-fingerprint-input"
              aria-label="SFTP host key fingerprint"
              data-testid="sftp-fingerprint-input"
            />
            <label className="flex items-center gap-3 cursor-pointer py-1">
              <Checkbox
                checked={sftpInsecure}
                onCheckedChange={(checked) => setSftpInsecure(checked === true)}
                disabled={isConnecting}
                id="sftp-insecure-checkbox"
                aria-label="Skip SFTP host key verification"
                data-testid="sftp-insecure-checkbox"
              />
              <span className="text-sm text-warning flex items-center gap-1">
                <ShieldAlert className="w-4 h-4" />
                Trust any host key (insecure — testing only)
              </span>
            </label>
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
              placeholder={usingSavedPassword ? "Using saved password" : "Repository Password"}
              value={password}
              onChange={(e) => {
                // The field never holds plaintext, so typing just sets the value; `usingSavedPassword`
                // derives from (saved exists && field empty), so it flips off automatically here and
                // back on if the user erases to empty. No first-character swallow.
                setPassword(e.target.value);
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
              id="repo-password-input"
              aria-label="Repository password"
              data-testid="password-input"
            />
            <button
              type="button"
              onClick={() => setShowPassword(!showPassword)}
              disabled={usingSavedPassword}
              className={`absolute right-3 top-1/2 -translate-y-1/2 p-1 transition-colors ${
                usingSavedPassword
                  ? "text-muted-foreground/40 cursor-not-allowed"
                  : "text-muted-foreground hover:text-foreground"
              }`}
              aria-label={showPassword ? "Hide password" : "Show password"}
              title={usingSavedPassword ? "Cannot view saved passwords" : "Show password"}
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
          id="connect-button"
          aria-label="Connect to repository"
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
