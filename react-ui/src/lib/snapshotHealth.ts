/**
 * What to tell the user about a snapshot that lost entries (task-63).
 *
 * A source that becomes unreadable part way through a walk is handled by record-and-continue — Go's
 * behaviour, and the right one — so the snapshot is saved, marked **complete**, and holds whatever
 * was readable. Measured on a phone: 945 of 2004 files, `numFailed: 1059`, sitting at the top of the
 * list tagged `latest`, which is what "restore the latest backup" hands back. Nothing said so: the
 * count reached the repository and stopped there, and every warning the app already had keys on
 * `isIncomplete`, which such a snapshot is not.
 *
 * Deliberately separate from the `isIncomplete` warnings rather than merged with them. They are
 * different failures — a cancelled run read everything it looked at; this one finished but could not
 * read some of the source — and they can be true at once, so a snapshot may need both sentences.
 * Keeping them apart also leaves the existing wording alone, which about a dozen Maestro flows
 * assert on.
 */
export interface SnapshotFailureWarning {
  /** Short form, for a badge beside the snapshot. */
  label: string;
  /** A sentence, for the warning boxes on the restore and browse screens. */
  detail: string;
  /** The same fact in the past tense, for after a restore has run. */
  restoredDetail: string;
}

interface SnapshotLike {
  failedEntryCount?: number;
}

export function snapshotFailureWarning(
  snapshot: SnapshotLike | undefined | null,
): SnapshotFailureWarning | null {
  const failed = snapshot?.failedEntryCount ?? 0;
  if (failed <= 0) return null;

  const count = failed.toLocaleString();
  // "items", never "files": an unreadable DIRECTORY is ONE failed entry however many files it hid
  // (TreeWalker records a single failure for a DirectoryReadException), so "1 item unreadable" can
  // mean a whole lost subtree. Do not "fix" this to files.
  const items = failed === 1 ? "item" : "items";
  return {
    label: `${count} ${items} unreadable`,
    // Not "will not bring them back": an older complete snapshot may still hold them, and this
    // sentence should not tell the user their files are gone forever when they may not be.
    detail:
      `This backup finished, but ${count} ${items} could not be read, so they are not in it. ` +
      `Restoring this backup will not include them.`,
    // Past tense, for after a restore has already run.
    restoredDetail: `${count} ${items} were missing from this backup and were not restored.`,
  };
}

/**
 * The same warning for a SOURCE, from its newest complete snapshot's failure count (task-63).
 *
 * The dashboard and the source cards print that snapshot's file count and size, so if it lost
 * entries the headline numbers describe a lossy backup. Labelling them is the fix; re-picking a
 * cleaner snapshot to describe instead would make the row disagree with what "restore latest"
 * actually returns.
 */
export function sourceFailureWarning(
  source: { latestFailedEntryCount?: number } | undefined | null,
): SnapshotFailureWarning | null {
  return snapshotFailureWarning({ failedEntryCount: source?.latestFailedEntryCount });
}
