import { useState, useEffect, useRef } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, Loader2, RotateCcw, Settings } from "lucide-react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Switch } from "@/components/ui/switch";
import { useToast } from "@/hooks/use-toast";
import { usePolicy, useMutatePolicy } from "@/hooks/useBackupApi";
import type { WebPolicy } from "@/types/kopia";

// Values are the case-sensitive Kotlin/Go compressor wire IDs (core Compressor enum); an unknown
// id silently resolves to no compression. Labels are display-only.
const COMPRESSION_OPTIONS = [
  { value: "zstd", label: "ZSTD" },
  { value: "lz4", label: "LZ4" },
  { value: "gzip", label: "GZIP" },
  { value: "deflate-default", label: "DEFLATE" },
  { value: "none", label: "NONE" },
];
const INTERVAL_UNITS = [
  { label: "Minutes", value: 60 },
  { label: "Hours", value: 3600 },
  { label: "Days", value: 86400 },
];

const FILTER_PRESETS: { label: string; patterns: string[] }[] = [
  { label: "Caches", patterns: [".cache/**", "**/cache/**"] },
  { label: "Build outputs", patterns: ["build/**", "dist/**", "out/**"] },
  { label: "Node modules", patterns: ["node_modules/**"] },
  { label: "Temp files", patterns: ["*.tmp", "*.temp", "*.swp", "~*"] },
  // Excludes any file OR directory whose name starts with a dot, at any depth (gitignore semantics).
  // Kopia has no native "exclude dot files" flag — it is expressed as an ignore pattern — so this
  // replaces the old dead exclude-dot-files/dirs switches with a real, persisted rule.
  { label: "Dot files", patterns: [".*"] },
];

const PolicyEditorScreen = () => {
  const navigate = useNavigate();
  const { sourceId } = useParams();
  const { toast } = useToast();
  const { data: existingPolicy } = usePolicy(sourceId ?? null);
  const mutatePolicy = useMutatePolicy();

  // Retention
  const [keepLatest, setKeepLatest] = useState("");
  const [keepHourly, setKeepHourly] = useState("");
  const [keepDaily, setKeepDaily] = useState("");
  const [keepWeekly, setKeepWeekly] = useState("");
  const [keepMonthly, setKeepMonthly] = useState("");
  const [keepAnnual, setKeepAnnual] = useState("");
  const [ignoreIdentical, setIgnoreIdentical] = useState(false);

  // Scheduling
  const [manual, setManual] = useState(false);
  const [intervalNum, setIntervalNum] = useState("24");
  const [intervalUnit, setIntervalUnit] = useState(3600);
  const [runMissed, setRunMissed] = useState(true);
  const [timesOfDay, setTimesOfDay] = useState<string[]>([]);
  const [newTime, setNewTime] = useState("09:00");

  // Compression
  const [compressor, setCompressor] = useState("zstd");
  const [onlyCompress, setOnlyCompress] = useState("");
  const [neverCompress, setNeverCompress] = useState("");
  const [minSize, setMinSize] = useState("");
  const [maxSize, setMaxSize] = useState("");

  // Files
  const [ignoreRules, setIgnoreRules] = useState("");
  const [maxFileSize, setMaxFileSize] = useState("");

  // Raw loaded values, kept so a save doesn't mutate what the user didn't touch:
  // - loadedPolicy: sections/fields this editor doesn't surface (errorHandling, splitter, cron,
  //   ignoreDotFiles, ...) must survive an open->save round-trip, especially for Go-written policies.
  // - sizes: the KB/MB display is rounded, so an untouched field re-encodes its ORIGINAL byte value.
  const loadedPolicy = useRef<WebPolicy | undefined>(undefined);
  const loadedSizes = useRef<{ minSize?: number; minSizeStr: string; maxSize?: number; maxSizeStr: string; maxFileSize?: number; maxFileSizeStr: string }>({ minSizeStr: "", maxSizeStr: "", maxFileSizeStr: "" });

  // Populate from existing policy
  useEffect(() => {
    if (!existingPolicy) return;
    loadedPolicy.current = existingPolicy;
    const r = existingPolicy.retention;
    if (r) {
      setKeepLatest(r.keepLatest?.toString() ?? "");
      setKeepHourly(r.keepHourly?.toString() ?? "");
      setKeepDaily(r.keepDaily?.toString() ?? "");
      setKeepWeekly(r.keepWeekly?.toString() ?? "");
      setKeepMonthly(r.keepMonthly?.toString() ?? "");
      setKeepAnnual(r.keepAnnual?.toString() ?? "");
      setIgnoreIdentical(r.ignoreIdenticalSnapshots ?? false);
    }
    const s = existingPolicy.scheduling;
    if (s) {
      setManual(s.manual ?? false);
      if (s.intervalSeconds) {
        if (s.intervalSeconds % 86400 === 0) { setIntervalNum(String(s.intervalSeconds / 86400)); setIntervalUnit(86400); }
        else if (s.intervalSeconds % 3600 === 0) { setIntervalNum(String(s.intervalSeconds / 3600)); setIntervalUnit(3600); }
        else { setIntervalNum(String(s.intervalSeconds / 60)); setIntervalUnit(60); }
      }
      setRunMissed(s.runMissed ?? true);
      setTimesOfDay((s.timeOfDay ?? []).map((t) => `${String(t.hour).padStart(2, "0")}:${String(t.min).padStart(2, "0")}`));
    }
    const c = existingPolicy.compression;
    if (c) {
      setCompressor(c.compressorName || "zstd");
      setOnlyCompress((c.onlyCompress ?? []).join("\n"));
      setNeverCompress((c.neverCompress ?? []).join("\n"));
      const minStr = c.minSize ? String(Math.round(c.minSize / 1024)) : "";
      const maxStr = c.maxSize ? String(Math.round(c.maxSize / 1048576)) : "";
      loadedSizes.current.minSize = c.minSize; loadedSizes.current.minSizeStr = minStr;
      loadedSizes.current.maxSize = c.maxSize; loadedSizes.current.maxSizeStr = maxStr;
      setMinSize(minStr);
      setMaxSize(maxStr);
    }
    const f = existingPolicy.files;
    if (f) {
      setIgnoreRules((f.ignore ?? []).join("\n"));
      const mfsStr = f.maxFileSize ? String(Math.round(f.maxFileSize / 1048576)) : "";
      loadedSizes.current.maxFileSize = f.maxFileSize; loadedSizes.current.maxFileSizeStr = mfsStr;
      setMaxFileSize(mfsStr);
    }
  }, [existingPolicy]);

  const addTime = () => {
    if (newTime && !timesOfDay.includes(newTime)) {
      setTimesOfDay([...timesOfDay, newTime].sort());
    }
  };

  const removeTime = (t: string) => setTimesOfDay(timesOfDay.filter((x) => x !== t));

  const addPreset = (patterns: string[]) => {
    const current = ignoreRules.split("\n").map((l) => l.trim()).filter(Boolean);
    const merged = [...new Set([...current, ...patterns])];
    setIgnoreRules(merged.join("\n"));
  };

  const optInt = (v: string) => { const n = parseInt(v, 10); return isNaN(n) ? undefined : n; };
  // The UI labels these fields in KB/MB, but the manifest fields are BYTE sizes. The display is
  // rounded, so an UNTOUCHED field re-encodes its original byte value (lossless round-trip).
  const optBytes = (v: string, unit: number) => { const n = optInt(v); return n === undefined ? undefined : n * unit; };
  const sizeBytes = (cur: string, origStr: string, origRaw: number | undefined, unit: number) =>
    cur === origStr ? (origRaw || undefined) : optBytes(cur, unit);

  const handleSave = () => {
    if (!sourceId) return;
    // Field names are the Kotlin/Go manifest wire format - see WebPolicy in types/kopia.ts.
    // Spread the LOADED policy first: sections and fields this editor doesn't surface
    // (errorHandling, splitter, scheduling.cron, files.ignoreDotFiles, ...) must survive an
    // open->save round-trip - wiping them would silently mutate Go-written policies.
    const loaded = loadedPolicy.current;
    const sizes = loadedSizes.current;
    const policy: WebPolicy = {
      ...loaded,
      retention: {
        ...loaded?.retention,
        keepLatest: optInt(keepLatest), keepHourly: optInt(keepHourly), keepDaily: optInt(keepDaily),
        keepWeekly: optInt(keepWeekly), keepMonthly: optInt(keepMonthly), keepAnnual: optInt(keepAnnual),
        ignoreIdenticalSnapshots: ignoreIdentical,
      },
      scheduling: {
        ...loaded?.scheduling,
        manual,
        intervalSeconds: manual ? undefined : (parseInt(intervalNum, 10) || 24) * intervalUnit,
        timeOfDay: timesOfDay.map((t) => { const [h, m] = t.split(":"); return { hour: parseInt(h, 10), min: parseInt(m, 10) }; }),
        runMissed,
      },
      compression: {
        ...loaded?.compression,
        compressorName: compressor,
        onlyCompress: onlyCompress.split("\n").map((l) => l.trim()).filter(Boolean),
        neverCompress: neverCompress.split("\n").map((l) => l.trim()).filter(Boolean),
        minSize: sizeBytes(minSize, sizes.minSizeStr, sizes.minSize, 1024),
        maxSize: sizeBytes(maxSize, sizes.maxSizeStr, sizes.maxSize, 1048576),
      },
      files: {
        ...loaded?.files,
        ignore: ignoreRules.split("\n").map((l) => l.trim()).filter(Boolean),
        maxFileSize: sizeBytes(maxFileSize, sizes.maxFileSizeStr, sizes.maxFileSize, 1048576),
      },
    };
    mutatePolicy.mutate(
      { sourceId, policy },
      {
        onSuccess: () => toast({ title: "Policy saved" }),
        onError: (err) => toast({ title: "Failed to save policy", description: String(err), variant: "destructive" }),
      }
    );
  };

  const handleReset = () => {
    // Kopia's real default retention (RetentionDefaults / Go policy.defaultRetentionPolicy). Keep this in
    // sync with AddSourceScreen.buildPolicy so a wizard-created policy and a Reset match (an async policy
    // load must not visibly diverge from Reset). The policy_editor E2E only pins keepLatest=10.
    setKeepLatest("10"); setKeepHourly("48"); setKeepDaily("7"); setKeepWeekly("4");
    setKeepMonthly("24"); setKeepAnnual("3"); setIgnoreIdentical(false);
    setManual(false); setIntervalNum("24"); setIntervalUnit(3600); setRunMissed(true);
    setTimesOfDay([]); setCompressor("zstd"); setOnlyCompress(""); setNeverCompress("");
    setMinSize(""); setMaxSize(""); setIgnoreRules("*.tmp\n.cache/**"); setMaxFileSize("");
    toast({ title: "Reset to defaults" });
  };

  const NumberInput = ({ label, value, onChange, placeholder, id }: { label: string; value: string; onChange: (v: string) => void; placeholder?: string; id?: string }) => (
    <div className="flex items-center justify-between gap-4">
      <span className="text-sm text-foreground">{label}</span>
      <input
        autoComplete="off"
        autoCorrect="off"
        autoCapitalize="off"
        spellCheck={false} type="text" inputMode="numeric" value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder || "—"} className="input-md3 w-20 text-center text-sm py-2" aria-label={label} id={id} />
    </div>
  );

  return (
    <div className="app-container min-h-screen flex flex-col">
      <header className="app-bar">
        <button onClick={() => navigate(-1)} className="btn-icon -ml-2" aria-label="Back">
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h1 className="app-bar-title">Edit Policy</h1>
        <button onClick={() => navigate("/settings")} className="btn-icon -mr-2" aria-label="Settings">
          <Settings className="w-5 h-5" />
        </button>
      </header>

      <Tabs defaultValue="retention" className="flex-1 flex flex-col">
        <div className="px-4 pt-2">
          <TabsList className="w-full grid grid-cols-4 h-10">
            <TabsTrigger value="retention" className="text-xs" aria-label="Retention tab">Retention</TabsTrigger>
            <TabsTrigger value="schedule" className="text-xs" aria-label="Schedule tab">Schedule</TabsTrigger>
            <TabsTrigger value="compress" className="text-xs" aria-label="Compression tab">Compress</TabsTrigger>
            <TabsTrigger value="files" className="text-xs" aria-label="Files tab">Files</TabsTrigger>
          </TabsList>
        </div>

        <div className="flex-1 px-4 py-4 overflow-y-auto">
          {/* Retention Tab */}
          <TabsContent value="retention" className="mt-0 space-y-3">
            <p className="text-xs text-muted-foreground">Empty fields inherit from parent policy</p>
            <div className="card-elevated space-y-4">
              <NumberInput label="Keep latest" value={keepLatest} onChange={setKeepLatest} id="keep-latest-input" />
              <NumberInput label="Keep hourly" value={keepHourly} onChange={setKeepHourly} />
              <NumberInput label="Keep daily" value={keepDaily} onChange={setKeepDaily} />
              <NumberInput label="Keep weekly" value={keepWeekly} onChange={setKeepWeekly} />
              <NumberInput label="Keep monthly" value={keepMonthly} onChange={setKeepMonthly} />
              <NumberInput label="Keep annual" value={keepAnnual} onChange={setKeepAnnual} />
            </div>
            <div className="card-elevated flex items-center justify-between">
              <span className="text-sm text-foreground">Ignore identical snapshots</span>
              <Switch checked={ignoreIdentical} onCheckedChange={setIgnoreIdentical} />
            </div>
          </TabsContent>

          {/* Schedule Tab */}
          <TabsContent value="schedule" className="mt-0 space-y-3">
            <div className="card-elevated flex items-center justify-between">
              <div>
                <p className="text-sm font-medium text-foreground">Manual only</p>
                <p className="text-xs text-muted-foreground">Disable all automatic scheduling</p>
              </div>
              <Switch checked={manual} onCheckedChange={setManual} />
            </div>

            {!manual && (
              <>
                <div className="card-elevated space-y-3">
                  <p className="text-sm font-medium text-foreground">Interval</p>
                  <div className="flex gap-2">
                    <input
                      autoComplete="off"
                      autoCorrect="off"
                      autoCapitalize="off"
                      spellCheck={false} type="text" inputMode="numeric" value={intervalNum} onChange={(e) => setIntervalNum(e.target.value)} className="input-md3 flex-1 text-sm py-2" aria-label="Schedule interval value" />
                    <select value={intervalUnit} onChange={(e) => setIntervalUnit(Number(e.target.value))} className="input-md3 flex-1 text-sm py-2" aria-label="Schedule interval unit">
                      {INTERVAL_UNITS.map((u) => <option key={u.value} value={u.value}>{u.label}</option>)}
                    </select>
                  </div>
                </div>

                <div className="card-elevated space-y-3">
                  <p className="text-sm font-medium text-foreground">Times of day</p>
                  <div className="flex gap-2">
                    <input
                      autoComplete="off"
                      autoCorrect="off"
                      autoCapitalize="off"
                      spellCheck={false} type="time" value={newTime} onChange={(e) => setNewTime(e.target.value)} className="input-md3 flex-1 text-sm py-2" aria-label="Time of day" />
                    <button onClick={addTime} className="btn-secondary text-sm px-4 py-2" aria-label="Add time">Add</button>
                  </div>
                  {timesOfDay.length > 0 && (
                    <div className="flex flex-wrap gap-2">
                      {timesOfDay.map((t) => (
                        <span key={t} className="inline-flex items-center gap-1 text-xs bg-secondary px-2 py-1 rounded-full">
                          {t}
                          <button onClick={() => removeTime(t)} className="text-muted-foreground hover:text-foreground">×</button>
                        </span>
                      ))}
                    </div>
                  )}
                </div>

                <div className="card-elevated flex items-center justify-between">
                  <div>
                    <p className="text-sm font-medium text-foreground">Run missed backups</p>
                    <p className="text-xs text-muted-foreground">Catch up on restart</p>
                  </div>
                  <Switch checked={runMissed} onCheckedChange={setRunMissed} />
                </div>
              </>
            )}

            <p className="text-xs text-muted-foreground px-1">
              On Android, scheduled backups may be delayed by battery optimization. Exact timing is not guaranteed.
            </p>
          </TabsContent>

          {/* Compression Tab */}
          <TabsContent value="compress" className="mt-0 space-y-3">
            <div className="card-elevated space-y-3">
              <p className="text-sm font-medium text-foreground">Algorithm</p>
              <select value={compressor} onChange={(e) => setCompressor(e.target.value)} className="input-md3 text-sm" aria-label="Compression algorithm">
                {COMPRESSION_OPTIONS.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
              </select>
            </div>

            <div className="card-elevated space-y-3">
              <p className="text-sm font-medium text-foreground">Only compress (globs)</p>
              <textarea value={onlyCompress} onChange={(e) => setOnlyCompress(e.target.value)} rows={3} className="input-md3 font-mono text-xs" placeholder={"*.txt\n*.log"} aria-label="Only compress globs" />
            </div>

            <div className="card-elevated space-y-3">
              <p className="text-sm font-medium text-foreground">Never compress (globs)</p>
              <textarea value={neverCompress} onChange={(e) => setNeverCompress(e.target.value)} rows={3} className="input-md3 font-mono text-xs" placeholder={"*.jpg\n*.mp4"} aria-label="Never compress globs" />
            </div>

            <div className="card-elevated space-y-3">
              <NumberInput label="Min file size (KB)" value={minSize} onChange={setMinSize} />
              <NumberInput label="Max file size (MB)" value={maxSize} onChange={setMaxSize} />
            </div>
          </TabsContent>

          {/* Files Tab */}
          <TabsContent value="files" className="mt-0 space-y-3">
            <div className="card-elevated space-y-3">
              <p className="text-sm font-medium text-foreground">Ignore rules (one glob per line)</p>
              <textarea value={ignoreRules} onChange={(e) => setIgnoreRules(e.target.value)} rows={5} className="input-md3 font-mono text-xs" aria-label="Ignore rules" />
              <div className="flex flex-wrap gap-2">
                {FILTER_PRESETS.map((p) => (
                  <button key={p.label} onClick={() => addPreset(p.patterns)} className="text-xs bg-secondary hover:bg-secondary/80 px-2.5 py-1 rounded-full text-foreground transition-colors" aria-label={`Add ${p.label} preset`}>
                    + {p.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="card-elevated space-y-3">
              <NumberInput label="Max file size (MB)" value={maxFileSize} onChange={setMaxFileSize} />
            </div>
          </TabsContent>
        </div>
      </Tabs>

      {/* Footer */}
      <div className="px-4 pb-6 flex gap-3">
        <button onClick={handleReset} className="btn-secondary flex-1" aria-label="Reset to defaults">
          <RotateCcw className="w-4 h-4" /> Defaults
        </button>
        <button onClick={handleSave} disabled={mutatePolicy.isPending} className="btn-primary flex-1" id="save-policy-button" aria-label="Save policy">
          {mutatePolicy.isPending ? <Loader2 className="w-4 h-4 animate-spin" /> : "Save Policy"}
        </button>
      </div>
    </div>
  );
};

export default PolicyEditorScreen;
