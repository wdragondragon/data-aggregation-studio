<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>制品仓库管理</h3>
        <p>由平台或租户管理员统一维护 OSS、GitLab、Nexus 和 pypiserver，业务用户无需接触长期凭据。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="load">刷新</el-button>
        <el-button type="primary" @click="open()">新建仓库</el-button>
      </div>
    </div>
    <SectionCard title="物理仓库" description="凭据加密保存；列表和详情接口不会返回凭据原文。">
      <StudioTableShell min-width="1120px">
        <el-table v-loading="loading" :data="stores" border>
          <el-table-column type="index" label="序号" width="72" align="center" />
          <el-table-column prop="storeName" label="仓库名称" min-width="170" />
          <el-table-column prop="storeCode" label="编码" min-width="140" />
          <el-table-column prop="provider" label="类型" width="130" />
          <el-table-column prop="scopeType" label="范围" width="100" />
          <el-table-column prop="endpoint" label="服务地址" min-width="260" show-overflow-tooltip />
          <el-table-column label="凭据" width="100" align="center">
            <template #default="{ row }">{{ row.hasUsername || row.hasSecret ? "已配置" : "未配置" }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }"><StatusPill :label="row.enabled ? '启用' : '停用'" :tone="row.enabled ? 'success' : 'neutral'" /></template>
          </el-table-column>
          <el-table-column label="操作" width="190" fixed="right" align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="open(row)">编辑</el-button>
              <el-button link type="primary" :disabled="!row.id" @click="testStore(row)">测试</el-button>
              <el-button link @click="toggle(row)">{{ row.enabled ? "停用" : "启用" }}</el-button>
              <el-button link type="danger" :disabled="row.enabled" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </StudioTableShell>
    </SectionCard>

    <el-dialog v-model="visible" title="制品仓库" width="760px">
      <el-form label-width="120px">
        <div class="form-grid">
          <el-form-item label="仓库名称"><el-input v-model="form.storeName" /></el-form-item>
          <el-form-item label="仓库编码"><el-input v-model="form.storeCode" :disabled="Boolean(form.id)" /></el-form-item>
          <el-form-item label="仓库类型">
            <el-select v-model="form.provider">
              <el-option label="OSS" value="OSS" />
              <el-option label="GitLab PyPI" value="GITLAB" />
              <el-option label="pypiserver" value="PYPISERVER" />
              <el-option label="Nexus PyPI" value="NEXUS" />
              <el-option label="标准 PyPI" value="PYPI" />
            </el-select>
          </el-form-item>
          <el-form-item label="使用范围">
            <el-select v-model="form.scopeType">
              <el-option label="当前租户" value="TENANT" />
              <el-option label="全平台" value="PLATFORM" />
            </el-select>
          </el-form-item>
          <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
          <el-form-item label="服务地址" class="span-2"><el-input v-model="form.endpoint" placeholder="https://..." /></el-form-item>
          <template v-if="form.provider === 'OSS'">
            <el-form-item label="Bucket"><el-input v-model="form.bucket" /></el-form-item>
            <el-form-item label="Region"><el-input v-model="form.region" /></el-form-item>
            <el-form-item label="根目录" class="span-2"><el-input v-model="form.rootPrefix" placeholder="python-packages" /></el-form-item>
          </template>
          <template v-else>
            <el-form-item label="上传地址" class="span-2"><el-input v-model="form.uploadUrl" placeholder="Hosted 仓库上传地址" /></el-form-item>
            <el-form-item label="Simple 地址" class="span-2"><el-input v-model="form.simpleIndexUrl" placeholder="供 pip install 使用的 Group /simple 地址" /></el-form-item>
          </template>
          <el-form-item label="用户名"><el-input v-model="form.username" placeholder="留空表示不修改" /></el-form-item>
          <el-form-item label="密码/Token"><el-input v-model="form.secret" type="password" show-password placeholder="留空表示不修改" /></el-form-item>
          <el-form-item label="校验 SSL"><el-switch v-model="form.verifySsl" disabled /><span class="security-tip">公共平台强制开启</span></el-form-item>
          <el-form-item label="清除凭据"><el-switch v-model="form.clearCredential" /></el-form-item>
          <el-form-item label="说明" class="span-2"><el-input v-model="form.description" type="textarea" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import type { ArtifactStore, ArtifactStoreProvider, ArtifactStoreSaveRequest } from "@studio/api-sdk";
import { SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import { studioApi } from "@/api/studio";

const stores = ref<ArtifactStore[]>([]);
const loading = ref(false);
const visible = ref(false);
const form = reactive<ArtifactStoreSaveRequest>({
  storeName: "", storeCode: "", provider: "OSS", scopeType: "TENANT", endpoint: "", enabled: true, verifySsl: true,
});

async function load() {
  loading.value = true;
  try { stores.value = await studioApi.artifactStores.list(); }
  finally { loading.value = false; }
}
function open(row?: ArtifactStore) {
  Object.assign(form, {
    id: row?.id, storeName: row?.storeName ?? "", storeCode: row?.storeCode ?? "",
    provider: (row?.provider ?? "OSS") as ArtifactStoreProvider, endpoint: row?.endpoint ?? "",
    scopeType: row?.scopeType ?? "TENANT",
    uploadUrl: row?.uploadUrl ?? "", simpleIndexUrl: row?.simpleIndexUrl ?? "",
    bucket: row?.bucket ?? "", region: row?.region ?? "", rootPrefix: row?.rootPrefix ?? "",
    username: "", secret: "", clearCredential: false, verifySsl: row?.verifySsl !== false,
    enabled: row?.enabled !== false, description: row?.description ?? "",
  });
  visible.value = true;
}
async function save() {
  try {
    await studioApi.artifactStores.save({ ...form });
    visible.value = false; ElMessage.success("仓库已保存"); await load();
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : "保存失败"); }
}
async function toggle(row: ArtifactStore) {
  if (!row.id) return;
  if (row.enabled) await studioApi.artifactStores.disable(row.id); else await studioApi.artifactStores.enable(row.id);
  await load();
}
async function testStore(row: ArtifactStore) {
  if (!row.id) return;
  const result = await studioApi.artifactStores.test(row.id);
  (result.success ? ElMessage.success : ElMessage.error)(result.message || (result.success ? "连接成功" : "连接失败"));
}
async function remove(row: ArtifactStore) {
  if (!row.id) return;
  await ElMessageBox.confirm(`确认删除仓库 ${row.storeName} 吗？`, "确认", { type: "warning" });
  await studioApi.artifactStores.delete(row.id); await load();
}
onMounted(load);
</script>

<style scoped>
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 4px 18px; }
.span-2 { grid-column: span 2; }
.security-tip { margin-left: 8px; color: var(--el-text-color-secondary); font-size: 12px; }
</style>
