import { describe, it, expect, vi, afterEach } from "vitest";
import { getPolicy, setPolicy, getTask, createRepository, BridgeError } from "@/services/kopiaBridge";
import { sourceId } from "@/lib/format";

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
