import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowLeft } from "lucide-react";

/**
 * The notices this app is legally required to carry.
 *
 * Apache-2.0 §4(d) and the BSD-2 clause-2 obligations are not satisfied by the files sitting in the
 * source repository: packaging strips dependency notices, so a released APK that pointed at GitHub
 * would be redistributing this code without the attribution its licences require. Gradle copies the
 * real LICENSE, NOTICE and THIRD_PARTY_NOTICES.md into the bundle (`copyLegalNotices`) so this screen
 * shows the same text that ships in the repo, rather than a copy that can silently drift out of date.
 */
const DOCUMENTS = [
  { key: "NOTICE", label: "Notice", file: "NOTICE" },
  { key: "THIRD_PARTY", label: "Third-party notices", file: "THIRD_PARTY_NOTICES.md" },
  // Generated at build time, not committed: the Gradle report covers the release runtime classpath
  // (the exact set the APK ships) and the npm report covers the packages compiled into the bundle.
  // Hand-maintained lists drift from the dependency graph the moment a dependency changes.
  { key: "DEPENDENCIES", label: "App dependencies", file: "DEPENDENCY_LICENSES.md" },
  { key: "JS_DEPENDENCIES", label: "Interface dependencies", file: "JS_DEPENDENCY_LICENSES.md" },
  { key: "LICENSE", label: "Apache License 2.0", file: "LICENSE" },
] as const;

type DocKey = (typeof DOCUMENTS)[number]["key"];

const LicensesScreen = () => {
  const navigate = useNavigate();
  const [selected, setSelected] = useState<DocKey>("NOTICE");
  const [text, setText] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const doc = DOCUMENTS.find((d) => d.key === selected);
    if (!doc) return;
    let cancelled = false;
    setText(null);
    setFailed(false);
    // Relative to the single React document. Routing is hash-based, so the document path never
    // changes and this always resolves through the same WebViewAssetLoader handler the bundle
    // itself came from — no file:// access and no second origin.
    fetch(`./legal/${doc.file}`)
      .then((r) => (r.ok ? r.text() : Promise.reject(new Error(String(r.status)))))
      .then((t) => {
        if (!cancelled) setText(t);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [selected]);

  return (
    <div className="app-container min-h-screen flex flex-col">
      <header className="app-bar">
        <button onClick={() => navigate(-1)} className="btn-icon -ml-2" aria-label="Back">
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h1 className="app-bar-title">Open-source licences</h1>
      </header>

      <div className="flex gap-2 px-4 pt-3 pb-1 overflow-x-auto">
        {DOCUMENTS.map((d) => (
          <button
            key={d.key}
            onClick={() => setSelected(d.key)}
            aria-label={d.label}
            className={`text-xs whitespace-nowrap px-3 py-1.5 rounded-full transition-colors ${
              selected === d.key ? "bg-primary text-primary-foreground" : "bg-secondary text-muted-foreground"
            }`}
          >
            {d.label}
          </button>
        ))}
      </div>

      <div className="flex-1 overflow-y-auto px-4 pb-6">
        {failed && (
          <p className="text-sm text-muted-foreground py-4">
            This build does not carry the licence texts. They are in the project repository under
            LICENSE, NOTICE and THIRD_PARTY_NOTICES.md.
          </p>
        )}
        {!failed && text === null && <p className="text-sm text-muted-foreground py-4">Loading…</p>}
        {text !== null && (
          <pre className="text-[11px] leading-relaxed text-foreground whitespace-pre-wrap break-words font-mono">
            {text}
          </pre>
        )}
      </div>
    </div>
  );
};

export default LicensesScreen;
