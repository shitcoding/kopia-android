import { Moon, Sun } from "lucide-react";
import { useEffect, useState } from "react";
import { kopiaBridge } from "@/services/kopiaBridge";

const ThemeToggle = () => {
  const [isDark, setIsDark] = useState(false);

  useEffect(() => {
    // Check initial preference
    const isDarkMode = document.documentElement.classList.contains("dark");
    setIsDark(isDarkMode);
    // Sync status bar with initial theme
    kopiaBridge.setStatusBarAppearance(isDarkMode);
  }, []);

  const toggleTheme = () => {
    const newIsDark = !isDark;
    setIsDark(newIsDark);

    if (newIsDark) {
      document.documentElement.classList.add("dark");
    } else {
      document.documentElement.classList.remove("dark");
    }

    // Update Android status bar appearance
    kopiaBridge.setStatusBarAppearance(newIsDark);
  };

  return (
    <button
      onClick={toggleTheme}
      className="fixed top-4 right-4 z-[100] w-10 h-10 rounded-full bg-card shadow-lg border border-border flex items-center justify-center transition-all duration-200 hover:scale-105 active:scale-95"
      aria-label={isDark ? "Switch to light mode" : "Switch to dark mode"}
    >
      {isDark ? (
        <Sun className="w-5 h-5 text-warning" />
      ) : (
        <Moon className="w-5 h-5 text-primary" />
      )}
    </button>
  );
};

export default ThemeToggle;
