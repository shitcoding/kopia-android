import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowLeft, Info, LinkIcon, Loader2, Moon, Sun, Palette, Check } from "lucide-react";
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
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { useDisconnect } from "@/hooks/useKopiaApi";

const ACCENT_COLORS = [
  { name: "Blue", hsl: "217 91% 60%" },
  { name: "Purple", hsl: "270 70% 60%" },
  { name: "Teal", hsl: "175 70% 45%" },
  { name: "Orange", hsl: "25 95% 55%" },
  { name: "Pink", hsl: "330 80% 60%" },
  { name: "Green", hsl: "142 70% 45%" },
];

const SettingsScreen = () => {
  const navigate = useNavigate();
  const [showDisconnectDialog, setShowDisconnectDialog] = useState(false);
  const [isDark, setIsDark] = useState(false);
  const [accentColor, setAccentColor] = useState(ACCENT_COLORS[0].hsl);
  const [customColor, setCustomColor] = useState("#3b82f6");

  const disconnectMutation = useDisconnect();

  useEffect(() => {
    const isDarkMode = document.documentElement.classList.contains("dark");
    setIsDark(isDarkMode);

    // Load saved accent color
    const savedAccent = localStorage.getItem("accent-color");
    if (savedAccent) {
      setAccentColor(savedAccent);
      applyAccentColor(savedAccent);
    }
  }, []);

  const toggleTheme = () => {
    const newIsDark = !isDark;
    setIsDark(newIsDark);
    if (newIsDark) {
      document.documentElement.classList.add("dark");
    } else {
      document.documentElement.classList.remove("dark");
    }
  };

  const applyAccentColor = (hsl: string) => {
    document.documentElement.style.setProperty("--primary", hsl);
    document.documentElement.style.setProperty("--folder", hsl);
    localStorage.setItem("accent-color", hsl);
  };

  const handleAccentChange = (hsl: string) => {
    setAccentColor(hsl);
    applyAccentColor(hsl);
  };

  const hexToHsl = (hex: string): string => {
    const r = parseInt(hex.slice(1, 3), 16) / 255;
    const g = parseInt(hex.slice(3, 5), 16) / 255;
    const b = parseInt(hex.slice(5, 7), 16) / 255;

    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    let h = 0, s = 0;
    const l = (max + min) / 2;

    if (max !== min) {
      const d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      switch (max) {
        case r: h = ((g - b) / d + (g < b ? 6 : 0)) / 6; break;
        case g: h = ((b - r) / d + 2) / 6; break;
        case b: h = ((r - g) / d + 4) / 6; break;
      }
    }

    return `${Math.round(h * 360)} ${Math.round(s * 100)}% ${Math.round(l * 100)}%`;
  };

  const handleCustomColorChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const hex = e.target.value;
    setCustomColor(hex);
    const hsl = hexToHsl(hex);
    setAccentColor(hsl);
    applyAccentColor(hsl);
  };

  const handleDisconnect = async () => {
    try {
      await disconnectMutation.mutateAsync();
      setShowDisconnectDialog(false);
      navigate("/");
    } catch (e) {
      // Mutation error is handled by React Query
      console.error("Disconnect failed:", e);
    }
  };

  const isDisconnecting = disconnectMutation.isPending;

  return (
    <div className="app-container min-h-screen flex flex-col">
      {/* App Bar */}
      <header className="app-bar">
        <button onClick={() => navigate("/snapshots")} className="btn-icon -ml-2">
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h1 className="app-bar-title">Settings</h1>
        <div className="w-9" />
      </header>

      {/* Content */}
      <div className="flex-1 px-4 py-6 space-y-6">
        {/* Styling Section */}
        <div className="animate-slide-up">
          <p className="section-header">Styling</p>
          <div className="space-y-3">
            {/* Dark Mode Toggle */}
            <div className="card-elevated flex items-center gap-4">
              <div className="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center">
                {isDark ? (
                  <Moon className="w-6 h-6 text-primary" />
                ) : (
                  <Sun className="w-6 h-6 text-primary" />
                )}
              </div>
              <div className="flex-1">
                <p className="font-semibold text-foreground">Dark Mode</p>
                <p className="text-sm text-muted-foreground">Switch between light and dark theme</p>
              </div>
              <Switch checked={isDark} onCheckedChange={toggleTheme} />
            </div>

            {/* Accent Color */}
            <div className="card-elevated">
              <div className="flex items-center gap-4 mb-4">
                <div className="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center">
                  <Palette className="w-6 h-6 text-primary" />
                </div>
                <div className="flex-1">
                  <p className="font-semibold text-foreground">Accent Color</p>
                  <p className="text-sm text-muted-foreground">Choose your preferred accent color</p>
                </div>
              </div>

              {/* Predefined Colors */}
              <div className="flex flex-wrap gap-3 mb-4">
                {ACCENT_COLORS.map((color) => (
                  <button
                    key={color.name}
                    onClick={() => handleAccentChange(color.hsl)}
                    className={`relative w-10 h-10 rounded-full transition-transform hover:scale-110 active:scale-95 ring-offset-2 ring-offset-card ${
                      accentColor === color.hsl ? 'ring-2' : ''
                    }`}
                    style={{
                      backgroundColor: `hsl(${color.hsl})`,
                      '--tw-ring-color': `hsl(${color.hsl})`
                    } as React.CSSProperties}
                    title={color.name}
                  >
                    {accentColor === color.hsl && (
                      <Check className="w-5 h-5 text-white absolute inset-0 m-auto drop-shadow-md" />
                    )}
                  </button>
                ))}
              </div>

              {/* Custom Color Picker */}
              <div className="flex items-center gap-3">
                <Label htmlFor="custom-color" className="text-sm text-muted-foreground">
                  Custom:
                </Label>
                <div className="relative">
                  <input
                    type="color"
                    id="custom-color"
                    value={customColor}
                    onChange={handleCustomColorChange}
                    className="w-10 h-10 rounded-full cursor-pointer border-2 border-border overflow-hidden"
                    style={{ padding: 0 }}
                  />
                </div>
                <span className="text-xs text-muted-foreground font-mono">
                  {customColor.toUpperCase()}
                </span>
              </div>
            </div>
          </div>
        </div>

        {/* About Section */}
        <div className="animate-slide-up" style={{ animationDelay: "0.05s" }}>
          <p className="section-header">About</p>
          <div className="card-elevated flex items-center gap-4">
            <div className="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center">
              <Info className="w-6 h-6 text-primary" />
            </div>
            <div>
              <p className="font-semibold text-foreground">KopiaKt</p>
              <p className="text-sm text-muted-foreground">Native Kopia client for Android</p>
            </div>
          </div>
        </div>

        {/* Repository Section */}
        <div className="animate-slide-up" style={{ animationDelay: "0.1s" }}>
          <p className="section-header">Repository</p>
          <button
            onClick={() => setShowDisconnectDialog(true)}
            className="w-full card-elevated flex items-center gap-4 text-left hover:bg-card transition-colors"
            data-testid="disconnect-button"
          >
            <div className="w-12 h-12 rounded-xl bg-destructive/10 flex items-center justify-center">
              <LinkIcon className="w-6 h-6 text-destructive" />
            </div>
            <div className="flex-1">
              <p className="font-semibold text-destructive">Disconnect Repository</p>
              <p className="text-sm text-muted-foreground">Close connection to current repository</p>
            </div>
            {isDisconnecting && (
              <Loader2 className="w-5 h-5 text-destructive animate-spin" data-testid="loading-indicator" />
            )}
          </button>
        </div>
      </div>

      {/* Disconnect Confirmation Dialog */}
      <AlertDialog open={showDisconnectDialog} onOpenChange={setShowDisconnectDialog}>
        <AlertDialogContent className="max-w-sm mx-4 rounded-2xl">
          <AlertDialogHeader>
            <AlertDialogTitle>Disconnect Repository?</AlertDialogTitle>
            <AlertDialogDescription>
              You will need to reconnect to browse or restore backups.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter className="flex-row gap-3">
            <AlertDialogCancel className="flex-1 mt-0" data-testid="cancel-button">Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDisconnect}
              className="flex-1 bg-destructive text-destructive-foreground hover:bg-destructive/90"
              data-testid="confirm-disconnect-button"
            >
              {isDisconnecting ? (
                <Loader2 className="w-4 h-4 animate-spin" />
              ) : (
                "Disconnect"
              )}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
};

export default SettingsScreen;
