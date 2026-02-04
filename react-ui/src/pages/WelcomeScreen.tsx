import { CloudUpload, Settings } from "lucide-react";
import { useNavigate } from "react-router-dom";

const WelcomeScreen = () => {
  const navigate = useNavigate();

  return (
    <div className="app-container flex flex-col items-center justify-center min-h-screen px-6">
      {/* Settings Button */}
      <button
        onClick={() => navigate("/settings")}
        className="absolute top-4 right-4 btn-icon"
        aria-label="Settings"
      >
        <Settings className="w-5 h-5" />
      </button>

      {/* App Icon */}
      <div className="animate-scale-in mb-8">
        <div className="w-24 h-24 rounded-3xl bg-gradient-to-br from-primary to-primary-dark flex items-center justify-center shadow-primary-lg">
          <CloudUpload className="w-12 h-12 text-primary-foreground" />
        </div>
      </div>

      {/* App Title */}
      <h1 className="animate-slide-up text-4xl font-bold text-primary mb-3 tracking-tight">
        KopiaKt
      </h1>

      {/* Tagline */}
      <p className="animate-slide-up text-lg text-muted-foreground text-center mb-12" style={{ animationDelay: "0.05s" }}>
        Browse and restore your Kopia backups
      </p>

      {/* CTA Button */}
      <button
        onClick={() => navigate("/connect")}
        className="animate-slide-up btn-primary w-full max-w-xs text-lg"
        style={{ animationDelay: "0.1s" }}
        data-testid="connect-button"
      >
        Connect to Repository
      </button>

    </div>
  );
};

export default WelcomeScreen;
