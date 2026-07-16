import { describe, it, expect } from "vitest";
import { sourceId, parseSourceId } from "./format";

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
