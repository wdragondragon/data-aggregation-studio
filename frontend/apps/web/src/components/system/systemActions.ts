import type {
  ResourceShare,
  StudioUser,
  SystemProject,
  SystemProjectMember,
  SystemProjectMemberRequest,
  SystemProjectWorker,
  SystemTenant,
  SystemTenantMember,
  UserRegistrationRequestView,
} from "@studio/api-sdk";
import type { OverflowActionItem } from "@studio/ui";

export interface SystemActionHandlers {
  openUserDialog: (row?: StudioUser) => void;
  deleteUser: (row: StudioUser) => void | Promise<void>;
  approveRegistration: (row: UserRegistrationRequestView) => void | Promise<void>;
  rejectRegistration: (row: UserRegistrationRequestView) => void | Promise<void>;
  deleteRegistration: (row: UserRegistrationRequestView) => void | Promise<void>;
  openTenantDialog: (row?: SystemTenant) => void;
  deleteTenant: (row: SystemTenant) => void | Promise<void>;
  openProjectDialog: (row?: SystemProject) => void;
  deleteProject: (row: SystemProject) => void | Promise<void>;
  openTenantMemberDialog: (row?: SystemTenantMember) => void;
  deleteTenantMember: (row: SystemTenantMember) => void | Promise<void>;
  openProjectMemberDialog: (row?: SystemProjectMember) => void;
  deleteProjectMember: (row: SystemProjectMember) => void | Promise<void>;
  canReviewProjectRequest: (row: SystemProjectMemberRequest) => boolean;
  approveProjectRequest: (row: SystemProjectMemberRequest) => void | Promise<void>;
  rejectProjectRequest: (row: SystemProjectMemberRequest) => void | Promise<void>;
  openRequestDialog: (row?: SystemProjectMemberRequest) => void;
  deleteProjectRequest: (row: SystemProjectMemberRequest) => void | Promise<void>;
  openWorkerDialog: (row?: SystemProjectWorker) => void;
  deleteProjectWorker: (row: SystemProjectWorker) => void | Promise<void>;
  openShareDialog: (row?: ResourceShare) => void;
  deleteResourceShare: (row: ResourceShare) => void | Promise<void>;
}

export function buildUserActions(row: StudioUser, handlers: SystemActionHandlers): OverflowActionItem[] {
  return [
    { key: "edit", label: "编辑", type: "primary", onClick: () => handlers.openUserDialog(row) },
    { key: "delete", label: "删除", type: "danger", onClick: () => handlers.deleteUser(row) },
  ];
}

export function buildRegistrationActions(row: UserRegistrationRequestView, handlers: SystemActionHandlers): OverflowActionItem[] {
  return [
    { key: "approve", label: "通过", type: "success", disabled: row.status !== "PENDING", onClick: () => handlers.approveRegistration(row) },
    { key: "reject", label: "拒绝", type: "warning", disabled: row.status !== "PENDING", onClick: () => handlers.rejectRegistration(row) },
    { key: "delete", label: "删除", type: "danger", onClick: () => handlers.deleteRegistration(row) },
  ];
}

export function buildTenantActions(row: SystemTenant, handlers: SystemActionHandlers): OverflowActionItem[] {
  return [
    { key: "edit", label: "编辑", type: "primary", onClick: () => handlers.openTenantDialog(row) },
    { key: "delete", label: "删除", type: "danger", onClick: () => handlers.deleteTenant(row) },
  ];
}

export function buildProjectActions(row: SystemProject, handlers: SystemActionHandlers): OverflowActionItem[] {
  return [
    { key: "edit", label: "编辑", type: "primary", onClick: () => handlers.openProjectDialog(row) },
    { key: "delete", label: "删除", type: "danger", onClick: () => handlers.deleteProject(row) },
  ];
}

export function buildTenantMemberActions(row: SystemTenantMember, handlers: SystemActionHandlers): OverflowActionItem[] {
  return [
    { key: "edit", label: "编辑", type: "primary", onClick: () => handlers.openTenantMemberDialog(row) },
    { key: "delete", label: "删除", type: "danger", onClick: () => handlers.deleteTenantMember(row) },
  ];
}

export function buildProjectMemberActions(row: SystemProjectMember, handlers: SystemActionHandlers): OverflowActionItem[] {
  return [
    { key: "edit", label: "编辑", type: "primary", onClick: () => handlers.openProjectMemberDialog(row) },
    { key: "delete", label: "删除", type: "danger", onClick: () => handlers.deleteProjectMember(row) },
  ];
}

export function buildProjectRequestActions(row: SystemProjectMemberRequest, handlers: SystemActionHandlers): OverflowActionItem[] {
  return [
    { key: "approve", label: "通过", type: "success", visible: handlers.canReviewProjectRequest(row), onClick: () => handlers.approveProjectRequest(row) },
    { key: "reject", label: "拒绝", type: "warning", visible: handlers.canReviewProjectRequest(row), onClick: () => handlers.rejectProjectRequest(row) },
    { key: "edit", label: "编辑", type: "primary", onClick: () => handlers.openRequestDialog(row) },
    { key: "delete", label: "删除", type: "danger", onClick: () => handlers.deleteProjectRequest(row) },
  ];
}

export function buildWorkerActions(row: SystemProjectWorker, handlers: SystemActionHandlers): OverflowActionItem[] {
  return [
    { key: "edit", label: "编辑", type: "primary", onClick: () => handlers.openWorkerDialog(row) },
    { key: "delete", label: "解绑", type: "danger", visible: Boolean(row.id), onClick: () => handlers.deleteProjectWorker(row) },
  ];
}

export function buildResourceShareActions(row: ResourceShare, handlers: SystemActionHandlers): OverflowActionItem[] {
  return [
    { key: "edit", label: "编辑", type: "primary", onClick: () => handlers.openShareDialog(row) },
    { key: "delete", label: "取消共享", type: "danger", onClick: () => handlers.deleteResourceShare(row) },
  ];
}
