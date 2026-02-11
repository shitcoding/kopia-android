/**
 * React Query hooks for Kopia bridge API calls.
 * Provides type-safe data fetching with caching and error handling.
 */

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  kopiaBridge,
  listSourcesWithStats,
  listSnapshotsWithRetention,
  deleteSnapshots,
} from "@/services/kopiaBridge";
import type {
  ConnectRequest,
  SourceInfo,
  SnapshotListRequest,
  ListDirectoryRequest,
  DeleteSnapshotsRequest,
} from "@/types/kopia";

export function usePing() {
  return useQuery({
    queryKey: ["ping"],
    queryFn: () => kopiaBridge.ping(),
    retry: false,
  });
}

export function useSources() {
  return useQuery({
    queryKey: ["sources"],
    queryFn: () => kopiaBridge.listSources(),
  });
}

export function useSnapshots(request: SnapshotListRequest = {}) {
  return useQuery({
    queryKey: ["snapshots", request],
    queryFn: () => kopiaBridge.listSnapshots(request),
  });
}

export function useSnapshot(snapshotId: string | undefined) {
  return useQuery({
    queryKey: ["snapshot", snapshotId],
    queryFn: () =>
      snapshotId ? kopiaBridge.getSnapshot(snapshotId) : Promise.resolve(null),
    enabled: !!snapshotId,
  });
}

export function useDirectory(request: ListDirectoryRequest | null) {
  return useQuery({
    queryKey: ["directory", request?.snapshotId, request?.path, request?.pageToken],
    queryFn: () =>
      request ? kopiaBridge.listDirectory(request) : Promise.resolve({ entries: [] }),
    enabled: !!request,
  });
}

export function useConnect() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: ConnectRequest) => kopiaBridge.connect(request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["sources"] });
      queryClient.invalidateQueries({ queryKey: ["snapshots"] });
    },
  });
}

export function useDisconnect() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => kopiaBridge.disconnect(),
    onSuccess: () => {
      queryClient.clear();
    },
  });
}

// ---------- New grouped snapshot hooks ----------

export function useSourcesWithStats() {
  return useQuery({
    queryKey: ["sources-with-stats"],
    queryFn: listSourcesWithStats,
    retry: 1,
  });
}

export function useSnapshotsWithRetention(source: SourceInfo | null) {
  return useQuery({
    queryKey: ["snapshots-with-retention", source],
    queryFn: () => listSnapshotsWithRetention({ source: source! }),
    enabled: !!source,
    retry: 1,
  });
}

export function useDeleteSnapshots() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: DeleteSnapshotsRequest) => deleteSnapshots(request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["snapshots-with-retention"] });
      queryClient.invalidateQueries({ queryKey: ["sources-with-stats"] });
    },
  });
}
