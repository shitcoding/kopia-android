import { ShieldAlert } from "lucide-react";
import { Checkbox } from "@/components/ui/checkbox";

interface CleartextWarningProps {
  /** testid for the warning text itself. */
  testId: string;
  /** testid for the acknowledgment checkbox. */
  checkboxTestId: string;
  acknowledged: boolean;
  onAcknowledgedChange: (acknowledged: boolean) => void;
  disabled?: boolean;
}

/**
 * Shown beneath a storage endpoint/URL field when it uses plaintext HTTP. Render conditionally with
 * isCleartextUrl() from @/lib/format.
 *
 * The acknowledgment is required, not advisory: the native connect layer refuses a cleartext endpoint
 * unless allowCleartextHttp is set, so this checkbox is what makes the connection possible at all. See
 * the cleartext/TLS posture ADR.
 */
export const CleartextWarning = ({
  testId,
  checkboxTestId,
  acknowledged,
  onAcknowledgedChange,
  disabled = false,
}: CleartextWarningProps) => (
  <div className="rounded-lg border border-warning/30 bg-warning/10 p-3 space-y-2">
    <p className="text-sm text-warning flex items-start gap-2" role="alert" data-testid={testId}>
      <ShieldAlert className="w-4 h-4 shrink-0 mt-0.5" />
      <span>
        Plaintext HTTP — your credentials and this connection can be read and modified by anyone on the
        network. Prefer https. For a self-signed or private-CA server, use https and pin its certificate
        below instead.
      </span>
    </p>
    <label className="flex items-center gap-3 cursor-pointer">
      <Checkbox
        checked={acknowledged}
        onCheckedChange={(checked) => onAcknowledgedChange(checked === true)}
        disabled={disabled}
        id={checkboxTestId}
        aria-label="Allow sending credentials over unencrypted HTTP"
        data-testid={checkboxTestId}
      />
      <span className="text-sm text-foreground">Connect anyway over unencrypted HTTP</span>
    </label>
  </div>
);
