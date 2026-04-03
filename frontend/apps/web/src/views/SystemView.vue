<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>System management</h3>
        <p>JWT and RBAC live here, but the studio still keeps the surface intentionally small for the first release.</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="loadPage">Refresh</el-button>
      </div>
    </div>

    <SectionCard title="Users, Roles and Permissions" description="All three lists are editable so the platform can evolve without recompile-heavy ACL changes.">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="Users" name="users">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openUserDialog()">New User</el-button>
          </div>
          <el-table :data="users" border>
            <el-table-column prop="username" label="Username" min-width="160" />
            <el-table-column prop="displayName" label="Display Name" min-width="180" />
            <el-table-column label="Enabled" width="120">
              <template #default="{ row }">
                <StatusPill :label="row.enabled ? 'On' : 'Off'" :tone="row.enabled ? 'success' : 'neutral'" />
              </template>
            </el-table-column>
            <el-table-column label="Actions" width="120">
              <template #default="{ row }">
                <el-button link type="primary" @click="openUserDialog(row)">Edit</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="Roles" name="roles">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openRoleDialog()">New Role</el-button>
          </div>
          <el-table :data="roles" border>
            <el-table-column prop="code" label="Code" min-width="160" />
            <el-table-column prop="name" label="Name" min-width="180" />
            <el-table-column prop="description" label="Description" min-width="220" />
            <el-table-column label="Actions" width="120">
              <template #default="{ row }">
                <el-button link type="primary" @click="openRoleDialog(row)">Edit</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="Permissions" name="permissions">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openPermissionDialog()">New Permission</el-button>
          </div>
          <el-table :data="permissions" border>
            <el-table-column prop="code" label="Code" min-width="160" />
            <el-table-column prop="name" label="Name" min-width="180" />
            <el-table-column prop="httpMethod" label="Method" width="120" />
            <el-table-column prop="pathPattern" label="Path Pattern" min-width="220" />
            <el-table-column label="Actions" width="120">
              <template #default="{ row }">
                <el-button link type="primary" @click="openPermissionDialog(row)">Edit</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </SectionCard>

    <el-dialog v-model="userDialogOpen" title="User" width="420px">
      <div class="dialog-grid">
        <el-form-item label="Username">
          <el-input v-model="userForm.username" />
        </el-form-item>
        <el-form-item label="Display Name">
          <el-input v-model="userForm.displayName" />
        </el-form-item>
        <el-form-item label="Password">
          <el-input v-model="userForm.passwordHash" type="password" show-password />
        </el-form-item>
        <el-form-item label="Enabled">
          <el-switch v-model="userForm.enabled" inline-prompt active-text="On" inactive-text="Off" />
        </el-form-item>
      </div>
      <template #footer>
        <el-button @click="userDialogOpen = false">Cancel</el-button>
        <el-button type="primary" @click="saveUser">Save</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="roleDialogOpen" title="Role" width="420px">
      <div class="dialog-grid">
        <el-form-item label="Code">
          <el-input v-model="roleForm.code" />
        </el-form-item>
        <el-form-item label="Name">
          <el-input v-model="roleForm.name" />
        </el-form-item>
        <el-form-item label="Description">
          <el-input v-model="roleForm.description" type="textarea" />
        </el-form-item>
      </div>
      <template #footer>
        <el-button @click="roleDialogOpen = false">Cancel</el-button>
        <el-button type="primary" @click="saveRole">Save</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="permissionDialogOpen" title="Permission" width="460px">
      <div class="dialog-grid">
        <el-form-item label="Code">
          <el-input v-model="permissionForm.code" />
        </el-form-item>
        <el-form-item label="Name">
          <el-input v-model="permissionForm.name" />
        </el-form-item>
        <el-form-item label="HTTP Method">
          <el-input v-model="permissionForm.httpMethod" />
        </el-form-item>
        <el-form-item label="Path Pattern">
          <el-input v-model="permissionForm.pathPattern" />
        </el-form-item>
      </div>
      <template #footer>
        <el-button @click="permissionDialogOpen = false">Cancel</el-button>
        <el-button type="primary" @click="savePermission">Save</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import type { PermissionEntity, RoleEntity, StudioUser } from "@studio/api-sdk";
import { SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { cloneDeep } from "@/utils/studio";

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
    ElMessage.error(error instanceof Error ? error.message : "Failed to load system data");
  }
}

function openUserDialog(user?: StudioUser) {
  Object.assign(userForm, user ? cloneDeep(user) : { enabled: true });
  userDialogOpen.value = true;
}

function openRoleDialog(role?: RoleEntity) {
  Object.assign(roleForm, role ? cloneDeep(role) : {});
  roleDialogOpen.value = true;
}

function openPermissionDialog(permission?: PermissionEntity) {
  Object.assign(permissionForm, permission ? cloneDeep(permission) : {});
  permissionDialogOpen.value = true;
}

async function saveUser() {
  try {
    await studioApi.users.save(cloneDeep(userForm));
    ElMessage.success("User saved");
    userDialogOpen.value = false;
    await loadPage();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to save user");
  }
}

async function saveRole() {
  try {
    await studioApi.roles.save(cloneDeep(roleForm));
    ElMessage.success("Role saved");
    roleDialogOpen.value = false;
    await loadPage();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to save role");
  }
}

async function savePermission() {
  try {
    await studioApi.permissions.save(cloneDeep(permissionForm));
    ElMessage.success("Permission saved");
    permissionDialogOpen.value = false;
    await loadPage();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to save permission");
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
