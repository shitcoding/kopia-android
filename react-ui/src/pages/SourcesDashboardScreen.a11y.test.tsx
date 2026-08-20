import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import SourcesDashboardScreen from "./SourcesDashboardScreen";

const getAllSourceStatuses = vi.hoisted(() => vi.fn());
vi.mock("@/services/kopiaBridge", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/services/kopiaBridge")>()),
  getAllSourceStatuses,
}));

/**
 * task-77: the row's actions menu must take its accessible name from real TEXT, not `aria-label`.
 *
 * Measured on a Nothing Phone (2) (Android 16) by putting four variants of the trigger on screen at
 * once and dumping the live hierarchy: `aria-label` alone, `aria-label` + `title`, `aria-label`
 * without Radix's `asChild`, and a visually-hidden text child. Only the last one reached the
 * accessibility tree; the three `aria-label` variants were exposed as a bare `radix-:r*:` id with no
 * name at all. So the cause is not the `asChild` clone the task suspected -- dropping `asChild`
 * changed nothing -- it is that this WebView drops `aria-label` on a Radix menu trigger outright.
 *
 * The consequence is a button TalkBack cannot announce, and four E2E flows that reach Back Up Now,
 * View Snapshots, Edit Policy and Estimate through `tapOn: "Source options"` and fail on a device.
 *
 * This asserts the TEXT NODE rather than the accessible name on purpose. jsdom honours `aria-label`
 * perfectly, so `getByRole("button", { name: "Source options" })` passes both before and after the
 * fix -- it is exactly the shape of test this project has repeatedly caught pinning nothing.
 */
const source = {
  id: "local@phone:/sdcard/Download/small",
  source: { host: "phone", userName: "local", path: "/sdcard/Download/small" },
  status: "IDLE" as const,
  lastSnapshotTime: null,
  snapshotCount: 1,
  totalSize: 12,
  currentTaskId: null,
  uploadCounters: null,
  lastError: null,
};

function renderScreen() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/sources"]}>
        <SourcesDashboardScreen />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("SourcesDashboardScreen — the actions menu has a name a device can see", () => {
  beforeEach(() => {
    getAllSourceStatuses.mockReset();
    getAllSourceStatuses.mockResolvedValue([source]);
  });

  it("names the actions trigger with text, not only aria-label", async () => {
    renderScreen();

    const trigger = await screen.findByRole("button", { name: "Source options" });
    // Both halves matter and each pins a different regression: the text node is what the device can
    // see at all, and `sr-only` is what keeps it from appearing next to the icon. Asserting only the
    // first leaves "delete the class" green while the row grows a stray caption (Codex).
    expect(within(trigger).getByText("Source options")).toHaveClass("sr-only");
  });
});
