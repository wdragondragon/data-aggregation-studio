import type { App, Plugin } from "vue";
import {
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTabPane,
  ElTabs,
  ElTree
} from "element-plus";
import "element-plus/es/components/button/style/css";
import "element-plus/es/components/descriptions/style/css";
import "element-plus/es/components/descriptions-item/style/css";
import "element-plus/es/components/dialog/style/css";
import "element-plus/es/components/drawer/style/css";
import "element-plus/es/components/form/style/css";
import "element-plus/es/components/form-item/style/css";
import "element-plus/es/components/input/style/css";
import "element-plus/es/components/message/style/css";
import "element-plus/es/components/message-box/style/css";
import "element-plus/es/components/option/style/css";
import "element-plus/es/components/select/style/css";
import "element-plus/es/components/switch/style/css";
import "element-plus/es/components/table/style/css";
import "element-plus/es/components/table-column/style/css";
import "element-plus/es/components/tab-pane/style/css";
import "element-plus/es/components/tabs/style/css";
import "element-plus/es/components/tree/style/css";

const components: Plugin[] = [
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTabPane,
  ElTabs,
  ElTree
];

export function installElementPlus(app: App) {
  components.forEach((component) => app.use(component));
}
