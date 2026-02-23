import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowLeft, FolderOpen, Loader2, Settings } from "lucide-react";
import { Checkbox } from "@/components/ui/checkbox";
import { useToast } from "@/hooks/use-toast";
import { useCreateSource } from "@/hooks/useBackupApi";
import type { WebPolicy } from "@/types/kopia";

const COMPRESSION_OPTIONS = ["ZSTD", "LZ4", "GZIP", "PGZIP", "DEFLATE", "NONE"];

const AddSourceScreen = () => {
  const navigate = useNavigate();
  const { toast } = useToast();
  const createSource = useCreateSource();
  const [step, setStep] = useState(1);

  // Step 1: folder
  const [selectedPath, setSelectedPath] = useState("");
  const [manualEntry, setManualEntry] = useState(false);

  // Step 2: policy
  const [autoBackup, setAutoBackup] = useState(true);
  const [intervalValue, setIntervalValue] = useState("24");
  const [intervalUnit, setIntervalUnit] = useState<"hours" | "days">("hours");
  const [compression, setCompression] = useState("ZSTD");
  const [exclusions, setExclusions] = useState("*.tmp\n.cache/**");

  // Step 3
  const [startImmediately, setStartImmediately] = useState(true);

  const handlePickFolder = () => {
    if (!window.KopiaEvents) {
      (window as any).KopiaEvents = {};
    }
    window.KopiaEvents!.onDestinationPicked = (path: string) => {
      setSelectedPath(path);
    };
    const bridge = window.KopiaBridge as any;
    if (bridge?.pickRestoreDestination) {
      bridge.pickRestoreDestination();
    } else {
      // Mock fallback for development
      setSelectedPath("/storage/emulated/0/Documents");
    }
  };

  const buildPolicy = (): WebPolicy => {
    const intervalSeconds = autoBackup
      ? parseInt(intervalValue, 10) * (intervalUnit === "days" ? 86400 : 3600)
      : undefined;

    return {
      schedulingPolicy: {
        manual: !autoBackup,
        intervalSeconds,
        runMissed: true,
      },
      compressionPolicy: { compressorName: compression },
      filesPolicy: {
        ignore: exclusions.split("\n").map((l) => l.trim()).filter(Boolean),
      },
    };
  };

  const handleCreate = () => {
    createSource.mutate(
      {
        uri: selectedPath,
        policy: buildPolicy(),
        startBackup: startImmediately,
      },
      {
        onSuccess: () => {
          toast({ title: "Source added successfully" });
          navigate("/sources");
        },
        onError: (err) => {
          toast({ title: "Failed to add source", description: String(err), variant: "destructive" });
        },
      }
    );
  };

  return (
    <div className="app-container min-h-screen flex flex-col">
      <header className="app-bar">
        <button onClick={() => (step > 1 ? setStep(step - 1) : navigate("/sources"))} className="btn-icon -ml-2">
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h1 className="app-bar-title">Add Source</h1>
        <button onClick={() => navigate("/settings")} className="btn-icon -mr-2">
          <Settings className="w-5 h-5" />
        </button>
      </header>

      {/* Step indicator */}
      <div className="px-4 pt-4 flex gap-1">
        {[1, 2, 3].map((s) => (
          <div key={s} className={`flex-1 h-1 rounded-full transition-colors ${s <= step ? "bg-primary" : "bg-secondary"}`} />
        ))}
      </div>

      <div className="flex-1 px-4 py-6 animate-fade-in">
        {/* Step 1: Select Folder */}
        {step === 1 && (
          <div className="space-y-4">
            <p className="text-sm text-muted-foreground">Choose a folder to back up</p>

            <button onClick={handlePickFolder} className="btn-primary w-full">
              <FolderOpen className="w-5 h-5" />
              Choose Folder
            </button>

            {selectedPath && (
              <div className="card-elevated">
                <p className="text-xs text-muted-foreground mb-1">Selected path</p>
                <p className="text-sm font-medium text-foreground break-all">{selectedPath}</p>
                <button onClick={handlePickFolder} className="text-xs text-primary mt-2">Change</button>
              </div>
            )}

            <div className="pt-2">
              <button onClick={() => setManualEntry(!manualEntry)} className="text-xs text-muted-foreground underline">
                {manualEntry ? "Hide" : "Enter path manually"}
              </button>
              {manualEntry && (
                <input
                  type="text"
                  placeholder="/storage/emulated/0/..."
                  value={selectedPath}
                  onChange={(e) => setSelectedPath(e.target.value)}
                  className="input-md3 mt-2"
                />
              )}
            </div>

            <button onClick={() => setStep(2)} disabled={!selectedPath.trim()} className="btn-primary w-full">
              Next
            </button>
          </div>
        )}

        {/* Step 2: Policy */}
        {step === 2 && (
          <div className="space-y-4">
            <p className="text-sm text-muted-foreground">Configure backup schedule</p>

            {/* Auto backup toggle */}
            <div className="card-elevated flex items-center justify-between">
              <div>
                <p className="font-medium text-foreground text-sm">Automatic backups</p>
                <p className="text-xs text-muted-foreground">Run backups on a schedule</p>
              </div>
              <label className="relative inline-flex items-center cursor-pointer">
                <input type="checkbox" checked={autoBackup} onChange={(e) => setAutoBackup(e.target.checked)} className="sr-only peer" />
                <div className="w-11 h-6 bg-secondary rounded-full peer peer-checked:bg-primary transition-colors after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:after:translate-x-full" />
              </label>
            </div>

            {autoBackup && (
              <div className="flex gap-2">
                <div className="flex-1">
                  <p className="text-xs text-muted-foreground px-1 mb-1">Every</p>
                  <input type="text" inputMode="numeric" value={intervalValue} onChange={(e) => setIntervalValue(e.target.value)} className="input-md3" />
                </div>
                <div className="flex-1">
                  <p className="text-xs text-muted-foreground px-1 mb-1">Unit</p>
                  <select value={intervalUnit} onChange={(e) => setIntervalUnit(e.target.value as "hours" | "days")} className="input-md3">
                    <option value="hours">Hours</option>
                    <option value="days">Days</option>
                  </select>
                </div>
              </div>
            )}

            <div>
              <p className="text-xs text-muted-foreground px-1 mb-1">Compression</p>
              <select value={compression} onChange={(e) => setCompression(e.target.value)} className="input-md3">
                {COMPRESSION_OPTIONS.map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
            </div>

            <div>
              <p className="text-xs text-muted-foreground px-1 mb-1">File exclusions (one glob per line)</p>
              <textarea
                value={exclusions}
                onChange={(e) => setExclusions(e.target.value)}
                rows={4}
                className="input-md3 font-mono text-sm"
                placeholder={"*.tmp\n.cache/**"}
              />
              <p className="text-xs text-muted-foreground px-1 mt-1">Glob patterns for files/directories to exclude</p>
            </div>

            <button onClick={() => setStep(3)} className="btn-primary w-full">
              Review
            </button>
          </div>
        )}

        {/* Step 3: Review */}
        {step === 3 && (
          <div className="space-y-4">
            <p className="text-sm text-muted-foreground">Review and add source</p>

            <div className="card-elevated space-y-3">
              <div>
                <p className="text-xs text-muted-foreground">Path</p>
                <p className="text-sm font-medium text-foreground break-all">{selectedPath}</p>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">Schedule</p>
                <p className="text-sm font-medium text-foreground">
                  {autoBackup ? `Every ${intervalValue} ${intervalUnit}` : "Manual only"}
                </p>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">Compression</p>
                <p className="text-sm font-medium text-foreground">{compression}</p>
              </div>
              {exclusions.trim() && (
                <div>
                  <p className="text-xs text-muted-foreground">Exclusions</p>
                  <p className="text-sm font-mono text-foreground whitespace-pre-line">{exclusions.trim()}</p>
                </div>
              )}
            </div>

            <label className="flex items-center gap-3 cursor-pointer py-1">
              <Checkbox
                checked={startImmediately}
                onCheckedChange={(c) => setStartImmediately(c === true)}
              />
              <span className="text-sm text-foreground">Start first backup immediately</span>
            </label>

            <button onClick={handleCreate} disabled={createSource.isPending} className="btn-primary w-full">
              {createSource.isPending ? <><Loader2 className="w-5 h-5 animate-spin" /> Adding Source...</> : "Add Source"}
            </button>
          </div>
        )}
      </div>
    </div>
  );
};

export default AddSourceScreen;
