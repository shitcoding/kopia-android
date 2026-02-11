import { useEffect } from "react";
import { Toaster } from "@/components/ui/toaster";
import { Toaster as Sonner } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HashRouter, Routes, Route } from "react-router-dom";
import WelcomeScreen from "./pages/WelcomeScreen";
import ConnectScreen from "./pages/ConnectScreen";
import SnapshotsScreen from "./pages/SnapshotsScreen";
import SourceSnapshotsScreen from "./pages/SourceSnapshotsScreen";
import FileBrowserScreen from "./pages/FileBrowserScreen";
import RestoreScreen from "./pages/RestoreScreen";
import SettingsScreen from "./pages/SettingsScreen";
import NotFound from "./pages/NotFound";
import { kopiaBridge } from "./services/kopiaBridge";

const queryClient = new QueryClient();

const App = () => {
  // Sync status bar appearance with theme on app load
  useEffect(() => {
    const isDarkMode = document.documentElement.classList.contains("dark");
    kopiaBridge.setStatusBarAppearance(isDarkMode);
  }, []);

  return (
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <Toaster />
        <Sonner />
        <HashRouter>
          <Routes>
            <Route path="/" element={<WelcomeScreen />} />
            <Route path="/connect" element={<ConnectScreen />} />
            <Route path="/snapshots" element={<SnapshotsScreen />} />
            <Route path="/snapshots/source" element={<SourceSnapshotsScreen />} />
            <Route path="/files/:snapshotId" element={<FileBrowserScreen />} />
            <Route path="/restore/:snapshotId" element={<RestoreScreen />} />
            <Route path="/settings" element={<SettingsScreen />} />
            <Route path="*" element={<NotFound />} />
          </Routes>
        </HashRouter>
      </TooltipProvider>
    </QueryClientProvider>
  );
};

export default App;
