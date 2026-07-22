import { ShieldAlert } from "lucide-react";

/**
 * Inline warning shown beneath a storage endpoint/URL field when it uses plaintext HTTP.
 * Render conditionally with isCleartextUrl() from @/lib/format. See the cleartext/TLS posture ADR.
 */
export const CleartextWarning = ({ testId }: { testId: string }) => (
  <p className="text-sm text-warning flex items-center gap-1 px-1" role="alert" data-testid={testId}>
    <ShieldAlert className="w-4 h-4 shrink-0" />
    Plaintext HTTP — credentials and data are sent unencrypted. Use https:// unless this is a trusted
    private network.
  </p>
);
