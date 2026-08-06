import { describe, it, expect, vi, afterEach } from "vitest";
import { getPolicy, setPolicy, getTask, createRepository, getAllSourceStatuses, startBackup, BridgeError, kopiaBridge } from "@/services/kopiaBridge";
import { sourceId, uploadProgressPercent } from "@/lib/format";

// Contract pins for the JS -> Kotlin bridge marshalling. The Kotlin counterpart lives in
// app-android/.../bridge/WebModelsTest.kt; both ends must agree on these wire shapes because the
// bridge Json uses ignoreUnknownKeys and silently drops mismatched field names — exactly the
// class of bug task-10 fixes, which no runtime error would otherwise surface.

/** Installs a one-method window.KopiaBridge whose impl captures the raw arg the bridge receives. */
function stubBridge(method: string, impl: (arg?: string) => string) {
  const fn = vi.fn(impl);
  (window as unknown as { KopiaBridge: Record<string, unknown> }).KopiaBridge = { [method]: fn };
  return fn;
}

const ok = (data: unknown) => JSON.stringify({ success: true, data });

afterEach(() => {
  delete (window as unknown as { KopiaBridge?: unknown }).KopiaBridge;
  vi.restoreAllMocks();
});

describe("bridge contract: policy ops send typed source objects", () => {
  it("getPolicy sends { host, userName, path }, not the joined sourceId", async () => {
    let received: string | undefined;
    stubBridge("getPolicy", (arg) => {
      received = arg;
      return ok({});
    });

    await getPolicy(sourceId({ userName: "user", host: "laptop", path: "/home/user/docs" }));

    // Kotlin decodes WebPolicySourceRequest { host, userName, path }; a bare sourceId string would
    // throw on decode (the original task-10 breakage).
    expect(JSON.parse(received!)).toEqual({ userName: "user", host: "laptop", path: "/home/user/docs" });
  });

  it("setPolicy wraps the request as { source, policy }", async () => {
    let received: string | undefined;
    stubBridge("setPolicy", (arg) => {
      received = arg;
      return ok(true);
    });

    await setPolicy({
      sourceId: sourceId({ userName: "u", host: "h", path: "/p" }),
      policy: { retention: { keepLatest: 5 } },
    });

    const parsed = JSON.parse(received!);
    expect(parsed.source).toEqual({ userName: "u", host: "h", path: "/p" });
    expect(parsed.policy).toEqual({ retention: { keepLatest: 5 } });
  });
});

describe("bridge contract: string-id marshalling", () => {
  it("getTask forwards the raw task id, not a JSON-quoted string", async () => {
    let received: string | undefined;
    stubBridge("getTask", (arg) => {
      received = arg;
      return ok({ id: "task-123" });
    });

    await getTask("task-123");

    // Regression guard for the double-stringify bug: must be "task-123", never "\"task-123\"".
    expect(received).toBe("task-123");
  });
});

describe("bridge contract: WebResult errors surface as BridgeError", () => {
  it("a { success:false } response throws BridgeError carrying the message", async () => {
    stubBridge("getTask", () =>
      JSON.stringify({ success: false, error: "Task log storage is not yet implemented" })
    );

    // This is how unimplemented/stub bridge methods degrade: callers catch the rejection (React
    // Query error state / toast) rather than crashing.
    await expect(getTask("x")).rejects.toBeInstanceOf(BridgeError);
    await expect(getTask("x")).rejects.toThrow("not yet implemented");
  });
});

describe("bridge single-slot callbacks", () => {
  it("refuses a concurrent createRepository rather than misattributing the result", async () => {
    stubBridge("createRepository", () => "");

    const first = createRepository({} as never);
    // One global handler with no request id: allowing a second call would let the FIRST native
    // result settle the SECOND promise.
    await expect(createRepository({} as never)).rejects.toBeInstanceOf(BridgeError);

    (window as unknown as { KopiaEvents: { onRepositoryCreated: (s: string) => void } })
      .KopiaEvents.onRepositoryCreated(JSON.stringify({ success: true, data: null }));
    await expect(first).resolves.toBeUndefined();

    // ...and the slot is released, so a later call works.
    const third = createRepository({} as never);
    (window as unknown as { KopiaEvents: { onRepositoryCreated: (s: string) => void } })
      .KopiaEvents.onRepositoryCreated(JSON.stringify({ success: true, data: null }));
    await expect(third).resolves.toBeUndefined();
  });

  it("releases the slot when the native call throws synchronously", async () => {
    stubBridge("createRepository", () => {
      throw new Error("bridge exploded");
    });

    await expect(createRepository({} as never)).rejects.toThrow("bridge exploded");

    stubBridge("createRepository", () => "");
    const retry = createRepository({} as never);
    (window as unknown as { KopiaEvents: { onRepositoryCreated: (s: string) => void } })
      .KopiaEvents.onRepositoryCreated(JSON.stringify({ success: true, data: null }));
    await expect(retry).resolves.toBeUndefined();
  });
})

describe("bridge contract: the source id is native's, not rebuilt from the source triple", () => {
  it("getAllSourceStatuses surfaces the id native assigned", async () => {
    stubBridge("listAllSources", () =>
      ok([
        {
          id: "local@Pixel 7:/sdcard/DCIM",
          source: { userName: "local", host: "Pixel 7", path: "/sdcard/DCIM" },
          status: "IDLE",
          snapshotCount: 0,
          totalFileSize: 0,
        },
      ]),
    );

    const [status] = await getAllSourceStatuses();

    // The dashboard addresses the source by this field. Rebuilding user@host:path locally is what
    // made pauseSource/resumeSource/getSourceStatus answer "Source not found".
    expect(status.id).toBe("local@Pixel 7:/sdcard/DCIM");
  });

  it("an uploading source carries its task id and that task's counters", async () => {
    // The dashboard shows a progress bar on the strength of uploadCounters and opens the progress
    // sheet on currentTaskId. Kotlin populated neither for the whole life of the feature, so the
    // block rendered nothing; these two names are the join, and a rename on either side would put
    // it straight back to silent.
    stubBridge("listAllSources", () =>
      ok([
        {
          id: "local@Pixel 7:/sdcard/DCIM",
          source: { userName: "local", host: "Pixel 7", path: "/sdcard/DCIM" },
          status: "UPLOADING",
          currentTaskId: "task-3",
          uploadCounters: {
            "Processed Bytes": { value: 50, units: "bytes" },
            "Estimated Bytes": { value: 200, units: "bytes" },
          },
          snapshotCount: 0,
          totalFileSize: 0,
        },
      ]),
    );

    const [status] = await getAllSourceStatuses();

    expect(status.currentTaskId).toBe("task-3");
    expect(uploadProgressPercent(status.uploadCounters)).toBe(25);
  });

  it("task counters arrive as Go's named map, keyed by display name", async () => {
    // Kotlin emits Map<String, WebTaskCounterValue>, mirroring Go's uitask.CounterValue, and the
    // Tasks screen reads counters by name. The TS type was once a fixed struct
    // (WebUploadCounters); with ignoreUnknownKeys on the Kotlin side, that mismatch loses counters
    // silently rather than failing. This pins the shape from the JS end.
    stubBridge("getTask", () =>
      ok({
        id: "task-1",
        kind: "Snapshot",
        status: "RUNNING",
        description: "Backing up",
        startTimeEpochMs: 0,
        progressInfo: "",
        counters: {
          "Uploaded Bytes": { value: 1024, units: "bytes" },
          Errors: { value: 2, units: "", level: "error" },
        },
      }),
    );

    const task = await getTask("task-1");

    expect(task.counters?.["Uploaded Bytes"]).toEqual({ value: 1024, units: "bytes" });
    expect(task.counters?.Errors?.level).toBe("error");
  });

  it("getSnapshot carries isIncomplete, which three warnings depend on", async () => {
    // A cancelled backup keeps the tree it managed to upload, so those snapshots are browsable and
    // restorable -- and the only thing telling the user they hold half a folder is this flag. Rename
    // it on either side and all three warnings silently stop rendering with every gate still green.
    stubBridge("getSnapshot", () =>
      ok({
        id: "snap-1",
        source: { host: "h", userName: "u", path: "/p" },
        startTimeEpochMs: 0,
        endTimeEpochMs: 1,
        fileCount: 1,
        totalSize: 2,
        isIncomplete: true,
      }),
    );

    const snapshot = await kopiaBridge.getSnapshot("snap-1");

    expect(snapshot?.isIncomplete).toBe(true);
  });

  it("a string id is forwarded raw, not JSON-quoted", async () => {
    // JSON.stringify("abc") arrives at the @JavascriptInterface as '"abc"', which made every
    // per-source call answer "Source not found". Pinned through startBackup since pauseSource --
    // the call this originally caught it on -- no longer exists.
    let received: string | undefined;
    stubBridge("startBackup", (arg) => {
      received = arg;
      return ok("task-1");
    });

    await startBackup("local@Pixel 7:/sdcard/DCIM");

    expect(received).toBe("local@Pixel 7:/sdcard/DCIM");
  });
})
