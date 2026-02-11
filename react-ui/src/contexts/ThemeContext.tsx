import { createContext, useContext, useEffect, useState, ReactNode } from "react";
import { kopiaBridge } from "@/services/kopiaBridge";

type ThemeMode = "light" | "dark" | "system";

interface ThemeContextType {
  theme: ThemeMode;
  effectiveTheme: "light" | "dark";
  setTheme: (mode: ThemeMode) => void;
}

const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

const STORAGE_KEY = "kopia-theme";

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<ThemeMode>(() => {
    const stored = localStorage.getItem(STORAGE_KEY);
    const selected = (stored === "light" || stored === "dark" || stored === "system")
      ? stored
      : "system";
    return selected;
  });

  const [systemTheme, setSystemTheme] = useState<"light" | "dark">("light");

  const effectiveTheme = theme === "system" ? systemTheme : theme;

  // Initialize system theme from Android bridge or media query
  useEffect(() => {
    const initSystemTheme = async () => {
      try {
        const theme = await kopiaBridge.getSystemTheme();
        setSystemTheme(theme);
      } catch (error) {
        console.error("[ThemeProvider] Failed to get system theme:", error);
        // Fallback to media query
        const isDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
        setSystemTheme(isDark ? "dark" : "light");
      }
    };

    initSystemTheme();

    // Listen for system theme changes via Android bridge
    const unsubscribe = kopiaBridge.onSystemThemeChanged((theme) => {
      setSystemTheme(theme);
    });

    return unsubscribe;
  }, []);

  // Apply theme to DOM and notify Android
  useEffect(() => {
    const root = document.documentElement;
    console.log("[ThemeProvider] Applying theme:", effectiveTheme, "current classes:", root.className);
    root.classList.remove("light", "dark");
    root.classList.add(effectiveTheme);
    console.log("[ThemeProvider] Applied theme:", effectiveTheme, "new classes:", root.className);

    // Sync with Android status bar
    kopiaBridge.setStatusBarAppearance(effectiveTheme === "dark");
  }, [effectiveTheme]);

  // Apply saved accent color on startup
  useEffect(() => {
    const savedAccent = localStorage.getItem("accent-color");
    if (savedAccent) {
      document.documentElement.style.setProperty("--primary", savedAccent);
      document.documentElement.style.setProperty("--folder", savedAccent);
    }
  }, []);

  const setTheme = (mode: ThemeMode) => {
    setThemeState(mode);
    localStorage.setItem(STORAGE_KEY, mode);
  };

  return (
    <ThemeContext.Provider value={{ theme, effectiveTheme, setTheme }}>
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme() {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error("useTheme must be used within ThemeProvider");
  }
  return context;
}
