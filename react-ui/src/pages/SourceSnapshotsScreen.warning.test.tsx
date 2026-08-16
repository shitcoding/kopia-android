import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import SourceSnapshotsScreen from "./SourceSnapshotsScreen";

const listSnapshotsWithRetention = vi.hoisted(() => vi.fn());
vi.mock("@/services/kopiaBridge", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/services/kopiaBridge")>()),
  listSnapshotsWithRetention,
}));

/**
 * task-63: the snapshot list is the screen the defect was measured on — a snapshot marked COMPLETE
 * holding 945 of 2004 files sat at the top of it, tagged `latest`, looking exactly like a healthy
 * one.
 *
 * This exists because every other test for this change stops at the predicate or the bridge: the
 * unit test proves `snapshotFailureWarning` computes the right words, and the Kotlin tests prove the
 * count reaches the wire, but nothing proved a screen *renders* it. Deleting the JSX block left
 * every gate green, which review caught.
 */
const snapshot = (failedEntryCount: number) => ({
  id: "snap-1",
  source: { host: "phone", userName: "local", path: "/sdcard/Download/photos" },
  startTimeEpochMs: Date.UTC(2026, 7, 16, 13, 16, 17),
  endTimeEpochMs: Date.UTC(2026, 7, 16, 13, 16, 33),
  stats: { totalFileSize: 60_100, totalFileCount: 945, totalDirectoryCount: 13 },
  isIncomplete: false,
  failedEntryCount,
  retentionReasons: ["latest-1"],
  tags: {},
});

function renderScreen() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/?host=phone&userName=local&path=/sdcard/Download/photos"]}>
        <SourceSnapshotsScreen />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("SourceSnapshotsScreen — unreadable-entry warning", () => {
  beforeEach(() => {
    listSnapshotsWithRetention.mockReset();
  });

  it("labels a snapshot whose run could not read some entries", async () => {
    listSnapshotsWithRetention.mockResolvedValue([snapshot(1059)]);

    renderScreen();

    expect(await screen.findByText(`${(1059).toLocaleString()} items unreadable`)).toBeInTheDocument();
  });

  it("says nothing about a healthy snapshot", async () => {
    listSnapshotsWithRetention.mockResolvedValue([snapshot(0)]);

    renderScreen();

    // Wait for the list itself, so this cannot pass merely because nothing had rendered yet.
    expect(await screen.findByText(/945/)).toBeInTheDocument();
    expect(screen.queryByText(/unreadable/)).toBeNull();
  });
});
