import { useNavigate } from "react-router-dom";
import { ArrowLeft, Wrench, Loader2, CheckCircle2, XCircle, Settings } from "lucide-react";
import { useToast } from "@/hooks/use-toast";
import { formatDateTime, formatFileSize } from "@/lib/format";
import { useMaintenanceStatus, useTriggerMaintenance } from "@/hooks/useBackupApi";

const MaintenanceScreen = () => {
  const navigate = useNavigate();
  const { toast } = useToast();
  const { data: status, isLoading } = useMaintenanceStatus();
  const triggerMaintenance = useTriggerMaintenance();

  const handleRunMaintenance = (mode: string) => {
    triggerMaintenance.mutate(mode, {
      onSuccess: () => toast({ title: `${mode} maintenance started` }),
      onError: (err) => toast({ title: "Maintenance failed", description: String(err), variant: "destructive" }),
    });
  };

  const isRunning = triggerMaintenance.isPending;

  return (
    <div className="app-container min-h-screen flex flex-col">
      <header className="app-bar">
        <button onClick={() => navigate(-1)} className="btn-icon -ml-2" aria-label="Back">
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h1 className="app-bar-title">Maintenance</h1>
        <button onClick={() => navigate("/settings")} className="btn-icon -mr-2" aria-label="Settings">
          <Settings className="w-5 h-5" />
        </button>
      </header>

      <div className="flex-1 px-4 py-6 space-y-6">
        {/* Explanation */}
        <div className="animate-fade-in">
          <div className="card-elevated">
            <div className="flex items-start gap-3">
              <div className="w-10 h-10 rounded-xl bg-primary/10 flex items-center justify-center flex-shrink-0">
                <Wrench className="w-5 h-5 text-primary" />
              </div>
              <div>
                <p className="font-semibold text-foreground text-sm">Repository Maintenance</p>
                <p className="text-xs text-muted-foreground mt-1">
                  Maintenance cleans up unused data, compacts indexes, and enforces retention policies.
                </p>
              </div>
            </div>
          </div>
        </div>

        {/* Last maintenance info */}
        {status?.lastRunTimeEpochMs && (
          <div className="animate-slide-up">
            <p className="section-header">Last Run</p>
            <div className="card-elevated space-y-3">
              <div className="flex justify-between">
                <span className="text-sm text-muted-foreground">Time</span>
                <span className="text-sm font-medium text-foreground">{formatDateTime(status.lastRunTimeEpochMs)}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-sm text-muted-foreground">Mode</span>
                <span className="text-sm font-medium text-foreground">{status.lastMode}</span>
              </div>
              <div className="flex justify-between items-center">
                <span className="text-sm text-muted-foreground">Result</span>
                <div className="flex items-center gap-1">
                  {status.lastSuccess ? (
                    <><CheckCircle2 className="w-4 h-4 text-green-600 dark:text-green-400" /><span className="text-sm font-medium text-green-600 dark:text-green-400">Success</span></>
                  ) : (
                    <><XCircle className="w-4 h-4 text-destructive" /><span className="text-sm font-medium text-destructive">Failed</span></>
                  )}
                </div>
              </div>
              {status.lastGcStats && (
                <>
                  <div className="flex justify-between">
                    <span className="text-sm text-muted-foreground">Deleted content</span>
                    <span className="text-sm font-medium text-foreground">{status.lastGcStats.deletedContentCount} items</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-sm text-muted-foreground">Reclaimed space</span>
                    <span className="text-sm font-medium text-foreground">{formatFileSize(status.lastGcStats.reclaimedBytes)}</span>
                  </div>
                </>
              )}
              {status.lastError && (
                <p className="text-sm text-destructive">{status.lastError}</p>
              )}
            </div>
          </div>
        )}

        {/* Running state */}
        {isRunning && (
          <div className="card-elevated flex flex-col items-center py-6 animate-fade-in">
            <Loader2 className="w-10 h-10 text-primary animate-spin mb-3" />
            <p className="text-sm text-foreground font-medium">Running maintenance...</p>
            <p className="text-xs text-muted-foreground mt-1">This may take a few minutes</p>
          </div>
        )}

        {/* Action buttons */}
        <div className="animate-slide-up space-y-3" style={{ animationDelay: "0.05s" }}>
          <p className="section-header">Actions</p>
          <button
            onClick={() => handleRunMaintenance("QUICK")}
            disabled={isRunning}
            className="btn-secondary w-full"
          >
            Run Quick Maintenance
          </button>
          <button
            onClick={() => handleRunMaintenance("FULL")}
            disabled={isRunning}
            className="btn-primary w-full"
          >
            Run Full Maintenance
          </button>
        </div>
      </div>
    </div>
  );
};

export default MaintenanceScreen;
