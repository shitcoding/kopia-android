import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getAllSourceStatuses,
  getSourceStatus,
  startBackup as startBackupBridge,
  pauseSource as pauseSourceBridge,
  resumeSource as resumeSourceBridge,
  deleteSource as deleteSourceBridge,
  createSource as createSourceBridge,
  listTasks,
  getTask,
  cancelTask as cancelTaskBridge,
  getPolicy as getPolicyBridge,
  setPolicy as setPolicyBridge,
  resolvePolicy as resolvePolicyBridge,
  getMaintenanceStatus as getMaintenanceStatusBridge,
  triggerMaintenance as triggerMaintenanceBridge,
  estimateBackup as estimateBackupBridge,
  createRepository as createRepositoryBridge,
  getSupportedAlgorithms,
} from "@/services/kopiaBridge";
import type {
  WebSourceStatus,
  WebTaskInfo,
  CreateSourceRequest,
  SetPolicyRequest,
  CreateRepositoryRequest,
  EstimateBackupRequest,
} from "@/types/kopia";

// ---------- Sources ----------

export function useSources() {
  return useQuery({
    queryKey: ["backup-sources"],
    queryFn: () => getAllSourceStatuses(),
    retry: 1,
    refetchInterval: (query) => {
      const hasUploading = query.state.data?.some((s) => s.status === "UPLOADING");
      return hasUploading ? 5000 : false;
    },
  });
}

export function useSourceStatus(sourceId: string | null) {
  return useQuery({
    queryKey: ["source-status", sourceId],
    queryFn: () => getSourceStatus(sourceId!),
    enabled: !!sourceId,
    retry: 1,
  });
}

export function useCreateSource() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (request: CreateSourceRequest) => Promise.resolve(createSourceBridge(request)),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["backup-sources"] }),
  });
}

export function useDeleteSource() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (sourceId: string) => {
      deleteSourceBridge(sourceId);
      return Promise.resolve();
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["backup-sources"] }),
  });
}

export function useStartBackup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (sourceId: string) => Promise.resolve(startBackupBridge(sourceId)),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["backup-sources"] }),
  });
}

export function usePauseSource() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (sourceId: string) => {
      pauseSourceBridge(sourceId);
      return Promise.resolve();
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["backup-sources"] }),
  });
}

export function useResumeSource() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (sourceId: string) => {
      resumeSourceBridge(sourceId);
      return Promise.resolve();
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["backup-sources"] }),
  });
}

// ---------- Tasks ----------

export function useTasks(filter?: WebTaskInfo["status"]) {
  return useQuery({
    queryKey: ["tasks", filter],
    queryFn: () => {
      const tasks = listTasks();
      if (filter) return tasks.filter((t) => t.status === filter);
      return tasks;
    },
    retry: 1,
  });
}

export function useTask(taskId: string | null) {
  return useQuery({
    queryKey: ["task", taskId],
    queryFn: () => getTask(taskId!),
    enabled: !!taskId,
    retry: 1,
    refetchInterval: (query) => {
      const data = query.state.data;
      return data?.status === "RUNNING" || data?.status === "CANCELING" ? 2000 : false;
    },
  });
}

export function useCancelTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (taskId: string) => {
      cancelTaskBridge(taskId);
      return Promise.resolve();
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["tasks"] });
      qc.invalidateQueries({ queryKey: ["backup-sources"] });
    },
  });
}

// ---------- Policies ----------

export function usePolicy(sourceId: string | null) {
  return useQuery({
    queryKey: ["policy", sourceId],
    queryFn: () => getPolicyBridge(sourceId!),
    enabled: !!sourceId,
    retry: 1,
  });
}

export function useResolvedPolicy(sourceId: string | null) {
  return useQuery({
    queryKey: ["resolved-policy", sourceId],
    queryFn: () => resolvePolicyBridge(sourceId!),
    enabled: !!sourceId,
    retry: 1,
  });
}

export function useMutatePolicy() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (request: SetPolicyRequest) => {
      setPolicyBridge(request);
      return Promise.resolve();
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["policy"] }),
  });
}

// ---------- Maintenance ----------

export function useMaintenanceStatus() {
  return useQuery({
    queryKey: ["maintenance-status"],
    queryFn: () => getMaintenanceStatusBridge(),
    retry: 1,
  });
}

export function useTriggerMaintenance() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (mode: string) => Promise.resolve(triggerMaintenanceBridge(mode)),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["maintenance-status"] });
      qc.invalidateQueries({ queryKey: ["tasks"] });
    },
  });
}

// ---------- Estimation ----------

export function useEstimate() {
  return useMutation({
    mutationFn: (request: EstimateBackupRequest) => Promise.resolve(estimateBackupBridge(request)),
  });
}

// ---------- Repository Creation ----------

export function useCreateRepository() {
  return useMutation({
    mutationFn: (request: CreateRepositoryRequest) => createRepositoryBridge(request),
  });
}

export function useAlgorithms() {
  return useQuery({
    queryKey: ["supported-algorithms"],
    queryFn: () => getSupportedAlgorithms(),
    staleTime: Infinity,
  });
}
