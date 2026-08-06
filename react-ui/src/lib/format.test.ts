import { describe, it, expect } from "vitest";
import { sourceId, parseSourceId, isCleartextUrl, uploadProgressPercent } from "./format";

describe("isCleartextUrl", () => {
  it("flags http: endpoints (case/whitespace-insensitive, incl. OkHttp-lenient variants)", () => {
    expect(isCleartextUrl("http://10.0.2.2:9000")).toBe(true);
    expect(isCleartextUrl("  HTTP://nas.local/dav/ ")).toBe(true);
    expect(isCleartextUrl("http:/nas.local/dav")).toBe(true); // single slash — OkHttp accepts it
  });

  it("treats https:// and scheme-less values as secure", () => {
    expect(isCleartextUrl("https://s3.example.com")).toBe(false);
    expect(isCleartextUrl("s3.amazonaws.com")).toBe(false);
    expect(isCleartextUrl("")).toBe(false);
  });
});

describe("parseSourceId", () => {
  it("round-trips a typical filesystem source", () => {
    const src = { userName: "android", host: "device", path: "/sdcard/Download" };
    expect(parseSourceId(sourceId(src))).toEqual(src);
  });

  it("round-trips a path containing colons (content URI)", () => {
    const src = {
      userName: "android",
      host: "device",
      path: "content://com.android.externalstorage.documents/tree/primary:Download",
    };
    expect(parseSourceId(sourceId(src))).toEqual(src);
  });

  it("round-trips a userName containing dots and a host containing dashes", () => {
    const src = { userName: "user.name", host: "my-device-01", path: "/data/x" };
    expect(parseSourceId(sourceId(src))).toEqual(src);
  });

  it("throws on a string without the expected separators", () => {
    expect(() => parseSourceId("not-a-source-id")).toThrow();
  });
});

describe("uploadProgressPercent", () => {
  const bytes = (value: number) => ({ value, units: "bytes" });

  it("divides processed by the estimate", () => {
    expect(uploadProgressPercent({ "Processed Bytes": bytes(50), "Estimated Bytes": bytes(200) })).toBe(25);
  });

  it("stays below 100 while the run is live — reaching the estimate is not finishing", () => {
    expect(uploadProgressPercent({ "Processed Bytes": bytes(400), "Estimated Bytes": bytes(200) })).toBe(99);
  });

  it("is null with nothing to divide by, so the bar renders indeterminate", () => {
    expect(uploadProgressPercent({ "Processed Bytes": bytes(50) })).toBeNull();
    expect(uploadProgressPercent({ "Processed Bytes": bytes(50), "Estimated Bytes": bytes(0) })).toBeNull();
    expect(uploadProgressPercent({ "Estimated Bytes": bytes(200) })).toBeNull();
    expect(uploadProgressPercent(undefined)).toBeNull();
  });
});
