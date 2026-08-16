import { describe, it, expect } from "vitest";
import { snapshotFailureWarning } from "./snapshotHealth";

/**
 * task-63, measured on a phone: a source that became unreadable part way through a walk produced a
 * snapshot marked COMPLETE holding 945 of 2004 files, `numFailed: 1059` in the manifest, sitting at
 * the top of the list tagged `latest` — what "restore the latest backup" hands back — with no
 * warning anywhere. It is *complete*, so every `isIncomplete` warning stays silent for it.
 */
describe("snapshotFailureWarning", () => {
  it("warns, with the count, when the run could not read some entries", () => {
    const warning = snapshotFailureWarning({ failedEntryCount: 1059 });

    expect(warning).not.toBeNull();
    // The number is the point: "some files are missing" is not actionable, a count is. Compared
    // against toLocaleString() rather than a literal "1,059" because that separator is locale
    // dependent (de-DE renders 1.059) — this project has already been bitten by locale-sensitive
    // formatting once, in retention's period ids.
    const rendered = (1059).toLocaleString();
    expect(warning!.label).toContain(rendered);
    expect(warning!.detail).toContain(rendered);
    expect(warning!.restoredDetail).toContain(rendered);
  });

  it("says nothing about a healthy snapshot", () => {
    expect(snapshotFailureWarning({ failedEntryCount: 0 })).toBeNull();
    expect(snapshotFailureWarning({})).toBeNull();
    expect(snapshotFailureWarning(undefined)).toBeNull();
  });

  it("uses the singular for one failed entry", () => {
    const warning = snapshotFailureWarning({ failedEntryCount: 1 });

    expect(warning!.label).toBe("1 item unreadable");
  });

  it("speaks in the past tense once a restore has run", () => {
    const warning = snapshotFailureWarning({ failedEntryCount: 4 })!;

    expect(warning.detail).toContain("Restoring this backup");
    expect(warning.restoredDetail).toContain("were not restored");
  });
});
