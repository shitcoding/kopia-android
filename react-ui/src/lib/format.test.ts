import { describe, it, expect } from "vitest";
import { sourceId, parseSourceId, isCleartextUrl } from "./format";

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
