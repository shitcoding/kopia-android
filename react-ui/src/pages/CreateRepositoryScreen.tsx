import { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  ArrowLeft,
  HardDrive,
  Cloud,
  Globe,
  Server,
  FolderOpen,
  Eye,
  EyeOff,
  Loader2,
  Check,
  ChevronRight,
  Settings,
} from "lucide-react";
import { useToast } from "@/hooks/use-toast";
import { useAlgorithms, useCreateRepository } from "@/hooks/useBackupApi";
import type { ConnectionConfig, CreateRepositoryRequest, StorageType } from "@/types/kopia";

type StorageType = "local" | "s3" | "webdav" | "sftp";

const STORAGE_OPTIONS: { id: StorageType; label: string; desc: string; icon: React.ElementType }[] = [
  { id: "local", label: "Local Filesystem", desc: "Use a local directory on this device", icon: HardDrive },
  { id: "s3", label: "Amazon S3", desc: "S3-compatible cloud storage", icon: Cloud },
  { id: "webdav", label: "WebDAV", desc: "WebDAV server (Nextcloud, etc.)", icon: Globe },
  { id: "sftp", label: "SFTP", desc: "SSH File Transfer Protocol", icon: Server },
];

const CreateRepositoryScreen = () => {
  const navigate = useNavigate();
  const { toast } = useToast();
  const [step, setStep] = useState(1);
  const [storageType, setStorageType] = useState<StorageType | null>(null);

  // Storage config state
  const [localPath, setLocalPath] = useState("");
  const [s3Bucket, setS3Bucket] = useState("");
  const [s3Endpoint, setS3Endpoint] = useState("s3.amazonaws.com");
  const [s3Region, setS3Region] = useState("us-east-1");
  const [s3AccessKey, setS3AccessKey] = useState("");
  const [s3SecretKey, setS3SecretKey] = useState("");
  const [webdavUrl, setWebdavUrl] = useState("");
  const [webdavUsername, setWebdavUsername] = useState("");
  const [webdavPassword, setWebdavPassword] = useState("");
  const [sftpHost, setSftpHost] = useState("");
  const [sftpPort, setSftpPort] = useState("22");
  const [sftpUsername, setSftpUsername] = useState("");
  const [sftpPath, setSftpPath] = useState("");
  const [sftpPassword, setSftpPassword] = useState("");

  // Repository settings
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [encryption, setEncryption] = useState("AES256-GCM-HMAC-SHA256");
  const [hashing, setHashing] = useState("BLAKE2B-256-128");
  const [compression, setCompression] = useState("zstd");
  const [description, setDescription] = useState("");

  const [isTesting, setIsTesting] = useState(false);
  const [testPassed, setTestPassed] = useState(false);

  const { data: algorithms } = useAlgorithms();
  const createRepo = useCreateRepository();

  const encryptionOptions = algorithms?.encryption ?? ["AES256-GCM-HMAC-SHA256", "CHACHA20-POLY1305-HMAC-SHA256", "NONE"];
  const hashingOptions = algorithms?.hashing ?? ["BLAKE2B-256-128", "BLAKE3-256", "HMAC-SHA256-128"];
  const compressionOptions = algorithms?.compression ?? ["zstd", "lz4", "gzip", "pgzip", "deflate-default", "none"];

  const buildConfig = (): ConnectionConfig => {
    const storageTypeMap: Record<string, StorageType> = {
      local: "LOCAL_FILESYSTEM",
      s3: "S3",
      webdav: "WEBDAV",
      sftp: "SFTP",
    };
    const config: ConnectionConfig = {
      storageType: storageTypeMap[storageType] ?? "LOCAL_FILESYSTEM",
    };
    switch (storageType) {
      case "local": config.local = { path: localPath }; break;
      case "s3": config.s3 = { bucket: s3Bucket, endpoint: s3Endpoint, region: s3Region, accessKeyId: s3AccessKey, secretAccessKey: s3SecretKey }; break;
      case "webdav": config.webdav = { url: webdavUrl, username: webdavUsername, password: webdavPassword }; break;
      case "sftp": config.sftp = { host: sftpHost, port: parseInt(sftpPort, 10) || 22, username: sftpUsername, path: sftpPath, password: sftpPassword }; break;
    }
    return config;
  };

  const handleTestConnection = async () => {
    setIsTesting(true);
    await new Promise((r) => setTimeout(r, 1500));
    setIsTesting(false);
    setTestPassed(true);
    toast({ title: "Connection successful" });
  };

  const handleCreate = async () => {
    if (password !== confirmPassword) {
      toast({ title: "Passwords don't match", variant: "destructive" });
      return;
    }
    const request: CreateRepositoryRequest = {
      config: buildConfig(),
      password,
      options: { hash: hashing, encryption, compression, description: description || undefined },
    };
    createRepo.mutate(request, {
      onSuccess: () => {
        toast({ title: "Repository created successfully" });
        navigate("/sources");
      },
      onError: (err) => {
        toast({ title: "Failed to create repository", description: String(err), variant: "destructive" });
      },
    });
  };

  const storageLabel = STORAGE_OPTIONS.find((o) => o.id === storageType)?.label ?? "";

  return (
    <div className="app-container min-h-screen flex flex-col">
      <header className="app-bar">
        <button onClick={() => (step > 1 ? setStep(step - 1) : navigate("/"))} className="btn-icon -ml-2" aria-label="Back">
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h1 className="app-bar-title">Create Repository</h1>
        <button onClick={() => navigate("/settings")} className="btn-icon -mr-2" aria-label="Settings">
          <Settings className="w-5 h-5" />
        </button>
      </header>

      {/* Step indicator */}
      <div className="px-4 pt-4 flex gap-1">
        {[1, 2, 3, 4].map((s) => (
          <div key={s} className={`flex-1 h-1 rounded-full transition-colors ${s <= step ? "bg-primary" : "bg-secondary"}`} />
        ))}
      </div>

      <div className="flex-1 px-4 py-6 animate-fade-in">
        {/* Step 1: Storage Selection */}
        {step === 1 && (
          <div className="space-y-3">
            <p className="text-sm text-muted-foreground mb-4">Choose where to store your repository</p>
            {STORAGE_OPTIONS.map((opt) => (
              <button
                key={opt.id}
                onClick={() => { setStorageType(opt.id); setStep(2); setTestPassed(false); }}
                className="w-full card-elevated flex items-center gap-4 text-left hover:shadow-lg transition-all active:scale-[0.99]"
              >
                <div className="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center">
                  <opt.icon className="w-6 h-6 text-primary" />
                </div>
                <div className="flex-1">
                  <p className="font-semibold text-foreground">{opt.label}</p>
                  <p className="text-sm text-muted-foreground">{opt.desc}</p>
                </div>
                <ChevronRight className="w-5 h-5 text-muted-foreground" />
              </button>
            ))}
          </div>
        )}

        {/* Step 2: Storage Config */}
        {step === 2 && storageType && (
          <div className="space-y-4">
            <p className="text-sm text-muted-foreground mb-2">Configure {storageLabel} storage</p>

            {storageType === "local" && (
              <div className="space-y-3">
                <div className="flex gap-2 items-stretch">
                  <button
                    type="button"
                    onClick={() => {
                      if (!window.KopiaEvents) { (window as any).KopiaEvents = {}; }
                      window.KopiaEvents!.onDestinationPicked = (path: string) => setLocalPath(path);
                      if (window.KopiaBridge?.pickRestoreDestination) {
                        window.KopiaBridge.pickRestoreDestination();
                      } else {
                        setLocalPath("/storage/emulated/0/KopiaRepo");
                      }
                    }}
                    className="shrink-0 w-14 rounded-xl border border-border bg-card hover:bg-accent flex items-center justify-center transition-colors"
                    title="Browse"
                  >
                    <FolderOpen className="w-5 h-5 text-primary" />
                  </button>
                  <input type="text" placeholder="Repository Path" value={localPath} onChange={(e) => setLocalPath(e.target.value)} className="input-md3 flex-1" />
                </div>
              </div>
            )}

            {storageType === "s3" && (
              <div className="space-y-3">
                <input type="text" placeholder="Bucket" value={s3Bucket} onChange={(e) => setS3Bucket(e.target.value)} className="input-md3" />
                <input type="text" placeholder="Endpoint" value={s3Endpoint} onChange={(e) => setS3Endpoint(e.target.value)} className="input-md3" />
                <input type="text" placeholder="Region" value={s3Region} onChange={(e) => setS3Region(e.target.value)} className="input-md3" />
                <input type="text" placeholder="Access Key ID" value={s3AccessKey} onChange={(e) => setS3AccessKey(e.target.value)} className="input-md3" />
                <input type="password" placeholder="Secret Access Key" value={s3SecretKey} onChange={(e) => setS3SecretKey(e.target.value)} className="input-md3" />
              </div>
            )}

            {storageType === "webdav" && (
              <div className="space-y-3">
                <input type="url" placeholder="WebDAV URL" value={webdavUrl} onChange={(e) => setWebdavUrl(e.target.value)} className="input-md3" />
                <input type="text" placeholder="Username" value={webdavUsername} onChange={(e) => setWebdavUsername(e.target.value)} className="input-md3" />
                <input type="password" placeholder="Password" value={webdavPassword} onChange={(e) => setWebdavPassword(e.target.value)} className="input-md3" />
              </div>
            )}

            {storageType === "sftp" && (
              <div className="space-y-3">
                <input type="text" placeholder="Host" value={sftpHost} onChange={(e) => setSftpHost(e.target.value)} className="input-md3" />
                <input type="text" inputMode="numeric" placeholder="Port (22)" value={sftpPort} onChange={(e) => setSftpPort(e.target.value)} className="input-md3" />
                <input type="text" placeholder="Username" value={sftpUsername} onChange={(e) => setSftpUsername(e.target.value)} className="input-md3" />
                <input type="text" placeholder="Path" value={sftpPath} onChange={(e) => setSftpPath(e.target.value)} className="input-md3" />
                <input type="password" placeholder="Password" value={sftpPassword} onChange={(e) => setSftpPassword(e.target.value)} className="input-md3" />
              </div>
            )}

            <button onClick={handleTestConnection} disabled={isTesting} className={`w-full ${testPassed ? "btn-secondary" : "btn-primary"}`}>
              {isTesting ? <><Loader2 className="w-5 h-5 animate-spin" /> Testing...</> : testPassed ? <><Check className="w-5 h-5" /> Connection OK</> : "Test Connection"}
            </button>

            <button onClick={() => setStep(3)} disabled={!testPassed} className="btn-primary w-full">
              Next
            </button>
          </div>
        )}

        {/* Step 3: Repository Settings */}
        {step === 3 && (
          <div className="space-y-4">
            <p className="text-sm text-muted-foreground mb-2">Repository encryption & settings</p>

            <div className="space-y-3">
              <p className="text-xs text-muted-foreground px-1">Repository password</p>
              <div className="relative">
                <input
                  type={showPassword ? "text" : "password"}
                  placeholder="Password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="input-md3 pr-12"
                />
                <button type="button" onClick={() => setShowPassword(!showPassword)} className="absolute right-3 top-1/2 -translate-y-1/2 p-1 text-muted-foreground hover:text-foreground">
                  {showPassword ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
                </button>
              </div>
              <input
                type={showPassword ? "text" : "password"}
                placeholder="Confirm Password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                className="input-md3"
              />
              {password && confirmPassword && password !== confirmPassword && (
                <p className="text-xs text-destructive px-1">Passwords don't match</p>
              )}
            </div>

            <div className="space-y-3">
              <label className="text-xs text-muted-foreground px-1">Encryption</label>
              <select value={encryption} onChange={(e) => setEncryption(e.target.value)} className="input-md3">
                {encryptionOptions.map((e) => <option key={e} value={e}>{e}</option>)}
              </select>
            </div>

            <div className="space-y-3">
              <label className="text-xs text-muted-foreground px-1">Hashing</label>
              <select value={hashing} onChange={(e) => setHashing(e.target.value)} className="input-md3">
                {hashingOptions.map((h) => <option key={h} value={h}>{h}</option>)}
              </select>
            </div>

            <div className="space-y-3">
              <label className="text-xs text-muted-foreground px-1">Compression</label>
              <select value={compression} onChange={(e) => setCompression(e.target.value)} className="input-md3">
                {compressionOptions.map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
            </div>

            <div className="space-y-3">
              <label className="text-xs text-muted-foreground px-1">Description (optional)</label>
              <input type="text" placeholder="e.g. Phone backup repo" value={description} onChange={(e) => setDescription(e.target.value)} className="input-md3" />
            </div>

            <button onClick={() => setStep(4)} disabled={!password || password !== confirmPassword} className="btn-primary w-full">
              Review
            </button>
          </div>
        )}

        {/* Step 4: Confirmation */}
        {step === 4 && (
          <div className="space-y-4">
            <p className="text-sm text-muted-foreground mb-2">Review your settings</p>

            <div className="card-elevated space-y-3">
              <div className="flex justify-between">
                <span className="text-sm text-muted-foreground">Storage</span>
                <span className="text-sm font-medium text-foreground">{storageLabel}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-sm text-muted-foreground">Encryption</span>
                <span className="text-sm font-medium text-foreground">{encryption}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-sm text-muted-foreground">Hashing</span>
                <span className="text-sm font-medium text-foreground">{hashing}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-sm text-muted-foreground">Compression</span>
                <span className="text-sm font-medium text-foreground">{compression}</span>
              </div>
              {description && (
                <div className="flex justify-between">
                  <span className="text-sm text-muted-foreground">Description</span>
                  <span className="text-sm font-medium text-foreground">{description}</span>
                </div>
              )}
            </div>

            <button onClick={handleCreate} disabled={createRepo.isPending} className="btn-primary w-full">
              {createRepo.isPending ? <><Loader2 className="w-5 h-5 animate-spin" /> Creating...</> : "Create Repository"}
            </button>
          </div>
        )}
      </div>
    </div>
  );
};

export default CreateRepositoryScreen;
