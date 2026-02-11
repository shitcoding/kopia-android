import { Sun, Moon, Monitor } from "lucide-react";
import { useTheme } from "@/contexts/ThemeContext";

export function ThemeSettings() {
  const { theme, setTheme } = useTheme();

  const options = [
    { id: "light" as const, label: "Light", icon: Sun },
    { id: "dark" as const, label: "Dark", icon: Moon },
    { id: "system" as const, label: "System", icon: Monitor },
  ];

  return (
    <div className="space-y-2">
      <h3 className="text-sm font-medium text-foreground">Theme</h3>
      <div className="flex gap-2">
        {options.map(({ id, label, icon: Icon }) => (
          <button
            key={id}
            onClick={() => setTheme(id)}
            className={`flex-1 flex flex-col items-center gap-2 py-3 px-4 rounded-xl border transition-all ${
              theme === id
                ? "border-primary bg-primary/10 text-primary"
                : "border-border bg-card text-muted-foreground hover:bg-accent"
            }`}
          >
            <Icon className="w-5 h-5" />
            <span className="text-xs font-medium">{label}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
