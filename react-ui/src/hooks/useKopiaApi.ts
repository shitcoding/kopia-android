/**
 * React Query hooks for Kopia bridge API calls.
 * Provides type-safe data fetching with caching and error handling.
 */

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { kopiaBridge } from "../services/kopiaBridge";
import type {
  ConnectRequest,
  SnapshotListRequest,
  ListDirectoryRequest,
} from "../types/kopia";

/**
 * Hook for pinging the bridge to verify communication.
 */
export function usePing() {
  return useQuery({
    queryKey: ["ping"],
    queryFn: () => kopiaBridge.ping(),
    retry: false,
  });
}

/**
 * Hook for listing snapshot sources.
 */
export function useSources() {
  return useQuery({
    queryKey: ["sources"],
    queryFn: () => kopiaBridge.listSources(),
  });
}

/**
 * Hook for listing snapshots with optional source filter.
 */
export function useSnapshots(request: SnapshotListRequest = {}) {
  return useQuery({
    queryKey: ["snapshots", request],
    queryFn: () => kopiaBridge.listSnapshots(request),
  });
}

/**
 * Hook for getting a single snapshot by ID.
 */
export function useSnapshot(snapshotId: string | undefined) {
  return useQuery({
    queryKey: ["snapshot", snapshotId],
    queryFn: () =>
      snapshotId ? kopiaBridge.getSnapshot(snapshotId) : Promise.resolve(null),
    enabled: !!snapshotId,
  });
}

/**
 * Hook for listing directory entries with pagination.
 */
export function useDirectory(request: ListDirectoryRequest | null) {
  return useQuery({
    queryKey: ["directory", request?.snapshotId, request?.path, request?.pageToken],
    queryFn: () =>
      request ? kopiaBridge.listDirectory(request) : Promise.resolve({ entries: [] }),
    enabled: !!request,
  });
}

/**
 * Hook for connecting to a repository.
 * Invalidates queries on success.
 */
export function useConnect() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (request: ConnectRequest) => kopiaBridge.connect(request),
    onSuccess: () => {
      // Invalidate and refetch relevant queries
      queryClient.invalidateQueries({ queryKey: ["sources"] });
      queryClient.invalidateQueries({ queryKey: ["snapshots"] });
    },
  });
}

/**
 * Hook for disconnecting from the repository.
 * Clears all queries on success.
 */
export function useDisconnect() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: () => kopiaBridge.disconnect(),
    onSuccess: () => {
      // Clear all cached data
      queryClient.clear();
    },
  });
}
