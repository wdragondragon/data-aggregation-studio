<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.system.heading") }}</h3>
        <p>{{ t("web.system.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="loadPage">{{ t("common.refresh") }}</el-button>
      </div>
    </div>

    <SectionCard :title="t('web.system.title')" :description="t('web.system.subtitle')">
      <el-tabs v-model="activeTab">
        <el-tab-pane :label="t('web.system.usersTab')" name="users">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openUserDialog()">{{ t("common.newUser") }}</el-button>
          </div>
          <el-table :data="users" border>
            <el-table-column prop="username" :label="t('web.system.username')" min-width="160" />
            <el-table-column prop="displayName" :label="t('web.system.displayName')" min-width="180" />
            <el-table-column :label="t('web.system.enabled')" width="120">
              <template #default="{ row }">
                <StatusPill :label="row.enabled ? t('common.on') : t('common.off')" :tone="row.enabled ? 'success' : 'neutral'" />
              </template>
            </el-table-column>
            <el-table-column :label="t('web.metadata.actions')" width="200">
              <template #default="{ row }">
                <el-button link type="primary" @click="openUserDialog(row)">{{ t("common.edit") }}</el-button>
                <el-button link type="danger" @click="deleteUser(row)">{{ t("common.delete") }}</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="t('web.system.rolesTab')" name="roles">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openRoleDialog()">{{ t("common.newRole") }}</el-button>
          </div>
          <el-table :data="roles" border>
            <el-table-column prop="code" :label="t('web.system.code')" min-width="160" />
            <el-table-column prop="name" :label="t('web.system.name')" min-width="180" />
            <el-table-column prop="description" :label="t('web.system.descriptionColumn')" min-width="220" />
            <el-table-column :label="t('web.metadata.actions')" width="200">
              <template #default="{ row }">
                <el-button link type="primary" @click="openRoleDialog(row)">{{ t("common.edit") }}</el-button>
                <el-button link type="danger" @click="deleteRole(row)">{{ t("common.delete") }}</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="t('web.system.permissionsTab')" name="permissions">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openPermissionDialog()">{{ t("common.newPermission") }}</el-button>
          </div>
          <el-table :data="permissions" border>
            <el-table-column prop="code" :label="t('web.system.code')" min-width="160" />
            <el-table-column prop="name" :label="t('web.system.name')" min-width="180" />
            <el-table-column prop="httpMethod" :label="t('web.system.method')" width="120" />
            <el-table-column prop="pathPattern" :label="t('web.system.pathPattern')" min-width="220" />
            <el-table-column :label="t('web.metadata.actions')" width="200">
              <template #default="{ row }">
                <el-button link type="primary" @click="openPermissionDialog(row)">{{ t("common.edit") }}</el-button>
                <el-button link type="danger" @click="deletePermission(row)">{{ t("common.delete") }}</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </SectionCard>

    <el-dialog v-model="userDialogOpen" :title="t('web.system.userDialogTitle')" width="420px">
      <div class="dialog-grid">
        <el-form-item :label="t('web.system.username')">
          <el-input v-model="userForm.username" />
        </el-form-item>
        <el-form-item :label="t('web.system.displayName')">
          <el-input v-model="userForm.displayName" />
        </el-form-item>
        <el-form-item :label="t('web.system.password')">
          <el-input v-model="userForm.passwordHash" type="password" show-password />
        </el-form-item>
        <el-form-item :label="t('web.system.enabled')">
          <el-switch v-model="userForm.enabled" inline-prompt :active-text="t('common.on')" :inactive-text="t('common.off')" />
        </el-form-item>
      </div>
      <template #footer>
        <el-button @click="userDialogOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" @click="saveUser">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="roleDialogOpen" :title="t('web.system.roleDialogTitle')" width="420px">
      <div class="dialog-grid">
        <el-form-item :label="t('web.system.code')">
          <el-input v-model="roleForm.code" />
        </el-form-item>
        <el-form-item :label="t('web.system.name')">
          <el-input v-model="roleForm.name" />
        </el-form-item>
        <el-form-item :label="t('web.system.descriptionColumn')">
          <el-input v-model="roleForm.description" type="textarea" />
        </el-form-item>
      </div>
      <template #footer>
        <el-button @click="roleDialogOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" @click="saveRole">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="permissionDialogOpen" :title="t('web.system.permissionDialogTitle')" width="460px">
      <div class="dialog-grid">
        <el-form-item :label="t('web.system.code')">
          <el-input v-model="permissionForm.code" />
        </el-form-item>
        <el-form-item :label="t('web.system.name')">
          <el-input v-model="permissionForm.name" />
        </el-form-item>
        <el-form-item :label="t('web.system.httpMethod')">
          <el-input v-model="permissionForm.httpMethod" />
        </el-form-item>
        <el-form-item :label="t('web.system.pathPattern')">
          <el-input v-model="permissionForm.pathPattern" />
        </el-form-item>
      </div>
      <template #footer>
        <el-button @click="permissionDialogOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" @click="savePermission">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type { PermissionEntity, RoleEntity, StudioUser } from "@studio/api-sdk";
import { SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { cloneDeep } from "@/utils/studio";

const { t } = useI18n();
const activeTab = ref("users");
const users = ref<StudioUser[]>([]);
const roles = ref<RoleEntity[]>([]);
const permissions = ref<PermissionEntity[]>([]);

const userDialogOpen = ref(false);
const roleDialogOpen = ref(false);
const permissionDialogOpen = ref(false);

const userForm = reactive<Partial<StudioUser>>({});
const roleForm = reactive<Partial<RoleEntity>>({});
const permissionForm = reactive<Partial<PermissionEntity>>({});

function resetUserForm() {
  Object.keys(userForm).forEach((key) => delete userForm[key as keyof typeof userForm]);
  Object.assign(userForm, { enabled: true });
}

function resetRoleForm() {
  Object.keys(roleForm).forEach((key) => delete roleForm[key as keyof typeof roleForm]);
}

function resetPermissionForm() {
  Object.keys(permissionForm).forEach((key) => delete permissionForm[key as keyof typeof permissionForm]);
}

async function loadPage() {
  try {
    const [userData, roleData, permissionData] = await Promise.all([
      studioApi.users.list(),
      studioApi.roles.list(),
      studioApi.permissions.list(),
    ]);
    users.value = userData;
    roles.value = roleData;
    permissions.value = permissionData;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.system.loadFailed"));
  }
}

function openUserDialog(user?: StudioUser) {
  resetUserForm();
  Object.assign(userForm, user ? cloneDeep(user) : { enabled: true });
  userDialogOpen.value = true;
}

function openRoleDialog(role?: RoleEntity) {
  resetRoleForm();
  Object.assign(roleForm, role ? cloneDeep(role) : {});
  roleDialogOpen.value = true;
}

function openPermissionDialog(permission?: PermissionEntity) {
  resetPermissionForm();
  Object.assign(permissionForm, permission ? cloneDeep(permission) : {});
  permissionDialogOpen.value = true;
}

async function saveUser() {
  try {
    await studioApi.users.save(cloneDeep(userForm));
    ElMessage.success(t("web.system.userSaved"));
    userDialogOpen.value = false;
    await loadPage();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.system.userSaveFailed"));
  }
}

async function saveRole() {
  try {
    await studioApi.roles.save(cloneDeep(roleForm));
    ElMessage.success(t("web.system.roleSaved"));
    roleDialogOpen.value = false;
    await loadPage();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.system.roleSaveFailed"));
  }
}

async function savePermission() {
  try {
    await studioApi.permissions.save(cloneDeep(permissionForm));
    ElMessage.success(t("web.system.permissionSaved"));
    permissionDialogOpen.value = false;
    await loadPage();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.system.permissionSaveFailed"));
  }
}

async function deleteUser(user: StudioUser) {
  if (!user.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("web.system.userDeleteConfirmMessage", { username: user.username }),
      t("common.confirm"),
      { type: "warning" },
    );
    await studioApi.users.delete(user.id);
    if (userForm.id === user.id) {
      userDialogOpen.value = false;
      resetUserForm();
    }
    ElMessage.success(t("web.system.userDeleteSuccess"));
    await loadPage();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : t("web.system.userDeleteFailed"));
    }
  }
}

async function deleteRole(role: RoleEntity) {
  if (!role.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("web.system.roleDeleteConfirmMessage", { name: role.name }),
      t("common.confirm"),
      { type: "warning" },
    );
    await studioApi.roles.delete(role.id);
    if (roleForm.id === role.id) {
      roleDialogOpen.value = false;
      resetRoleForm();
    }
    ElMessage.success(t("web.system.roleDeleteSuccess"));
    await loadPage();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : t("web.system.roleDeleteFailed"));
    }
  }
}

async function deletePermission(permission: PermissionEntity) {
  if (!permission.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("web.system.permissionDeleteConfirmMessage", { name: permission.name }),
      t("common.confirm"),
      { type: "warning" },
    );
    await studioApi.permissions.delete(permission.id);
    if (permissionForm.id === permission.id) {
      permissionDialogOpen.value = false;
      resetPermissionForm();
    }
    ElMessage.success(t("web.system.permissionDeleteSuccess"));
    await loadPage();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : t("web.system.permissionDeleteFailed"));
    }
  }
}

onMounted(loadPage);
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

.tab-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 14px;
}

.dialog-grid {
  display: grid;
  gap: 6px;
}
</style>
