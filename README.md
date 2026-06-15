# Data Aggregation Studio

`data-aggregation-studio` 是 DataAggregation 的可视化管理端，提供在线 Web 控制台、在线 Worker 和本地桌面运行时。项目以 Web 控制台为主，也提供 Electron 桌面壳用于离线场景。

本目录是一个独立 Git 仓库，放在 `DataAggregation` 工作区下仅用于本地并排开发。它不加入外层 `DataAggregation` Maven Reactor，也不共享外层父 POM。

## 项目结构

```text
data-aggregation-studio/
├── frontend/
│   ├── apps/
│   │   ├── web/                 # 在线 Web 控制台
│   │   └── desktop/             # Electron 桌面壳
│   └── packages/
│       ├── api-sdk/             # 前端 API SDK 与 DTO 类型
│       ├── i18n/                # 多语言文案
│       ├── meta-form/           # 元模型表单渲染
│       ├── ui/                  # 通用布局和基础 UI 组件
│       └── workflow-designer/   # 工作流 DAG 设计器
└── backend/
    ├── studio-commons/          # 公共常量、异常和基础工具
    ├── studio-dto/              # DTO、VO、请求和响应模型
    ├── studio-core/             # Studio SPI 和集成契约
    ├── studio-infra/            # 实体、Mapper、仓储、安全和运行适配
    ├── studio-server/           # 在线 REST API、开放 API、调度和管理端接口
    ├── studio-worker/           # 在线 Worker，负责采集任务和工作流节点执行
    └── studio-desktop-runtime/  # 本地离线运行时
```

## 功能模块

### 工作台

提供总览入口，展示当前租户、项目、资产、任务和运行状态。

### 数据资产

- 数据源管理
- 元模型管理
- 模型中心
- 模型同步任务
- 模型血缘
- 统计分析

### 数据采集

- 字段映射规则
- 采集任务管理
- 采集运行记录
- HTTP REST/XML/SOAP 采集配置
- 采集任务运行指标

### 数据开发

- SQL 脚本目录
- SQL 执行
- 工作流编排
- 工作流运行日志

### 数据服务

- 数据服务：将模型或 SQL 查询发布为开放 API。
- 数据接入服务：接收外部请求并写入目标模型。
- 协议转换服务：接收 HTTP/SOAP 请求，按配置转换为目标 HTTP JSON、HTTP XML 或 SOAP 1.1/1.2 请求并转发。
- 服务监控、接入监控和协议转换调用日志。

### 数据质量

- 质量规则
- 质量任务
- 质量运行记录
- 质量指标和问题分析

### 运维中心

- 运行总览
- Worker 状态
- 任务队列
- 服务异常
- 日志异常

## 后端模块说明

- `studio-commons`：公共常量、错误码、异常、日志过滤器和通用工具。
- `studio-dto`：前后端共享模型、请求对象、响应对象和枚举。
- `studio-core`：Studio 内部 SPI 和运行契约。
- `studio-infra`：MyBatis-Plus 实体、Mapper、业务服务、安全上下文、DataAggregation 适配。
- `studio-server`：在线管理端 API、开放 API、Swagger/Knife4j、调度管理和访问日志。
- `studio-worker`：采集任务和工作流节点执行进程。
- `studio-desktop-runtime`：离线桌面运行时，使用 SQLite 本地存储。

## 前端模块说明

- `frontend/apps/web`：在线 Web 管理端，默认基座路径为 `/dfs/data-aggregation-studio/`。
- `frontend/apps/desktop`：Electron 桌面端。
- `frontend/packages/api-sdk`：封装后端 API 请求和 TypeScript 类型。
- `frontend/packages/ui`：Studio Shell、卡片、表格壳、状态标签等通用组件。
- `frontend/packages/meta-form`：元模型驱动的动态表单。
- `frontend/packages/workflow-designer`：工作流画布、节点和连线编辑。

## 集成边界

- Studio 不加入外层 `DataAggregation` 根 Maven Reactor。
- Studio 不使用外层 `DataAggregation` 父 POM。
- Studio 通过 Maven 依赖集成已发布的 `com.jdragon.aggregation:*` 构件。
- 插件运行仍依赖 `aggregation.home` 指向 DataAggregation 插件运行目录。

需要准备的 DataAggregation 构件包括：

- `com.jdragon.aggregation:commons`
- `com.jdragon.aggregation:core`
- `com.jdragon.aggregation:data-source-handler-abstract`
- `com.jdragon.aggregation:plugins-loader-center`

## 快速启动

### 1. 准备 DataAggregation 本地依赖

```powershell
cd C:\dev\ideaProject\DataAggregation
mvn -DskipTests install
```

### 2. 初始化 Studio 数据

Studio MySQL 环境推荐直接执行 SQL 脚本初始化。脚本位于：

```text
backend/studio-server/src/main/resources/
```

首次初始化时，进入上述目录并连接目标 MySQL 库后，按以下顺序执行：

```sql
source schema-mysql.sql;
source data-mysql-base.sql;
source data-mysql-builtin.sql;
source data-mysql-runtime-options.sql;
```

也可以在命令行中直接执行：

```powershell
cd C:\dev\ideaProject\DataAggregation\data-aggregation-studio\backend\studio-server\src\main\resources
mysql -h 192.168.188.128 -P 3306 -u root -p data_aggregation_studio
```

进入 MySQL 客户端后执行上面的 `source` 命令。

脚本用途：

- `schema-mysql.sql`：创建 Studio 所需表结构。
- `data-mysql-base.sql`：初始化默认用户、角色、租户、项目和基础权限。
- `data-mysql-builtin.sql`：初始化内置数据源、元模型和系统内置配置。
- `data-mysql-runtime-options.sql`：初始化采集插件运行参数元模型。

`backend/scripts/init-studio-data.ps1` 仅作为开发环境辅助入口保留。MySQL 首次初始化优先使用上述 SQL 脚本；桌面端 SQLite 和部分测试场景仍可使用程序化初始化。

### 3. 启动在线后端

Server 和 Worker 是两个独立进程。

```powershell
cd C:\dev\ideaProject\DataAggregation\data-aggregation-studio\backend
mvn -pl studio-server spring-boot:run
```

```powershell
cd C:\dev\ideaProject\DataAggregation\data-aggregation-studio\backend
mvn -pl studio-worker spring-boot:run
```

默认端口：

- `studio-server`: `18080`
- `studio-worker`: `18081`

默认在线数据库：

- MySQL: `jdbc:mysql://192.168.188.128:3306/data_aggregation_studio`
- username: `root`
- password: `951753`

### 4. 启动桌面离线运行时

```powershell
cd C:\dev\ideaProject\DataAggregation\data-aggregation-studio\backend
mvn -pl studio-desktop-runtime spring-boot:run
```

默认端口：

- `studio-desktop-runtime`: `18180`

默认本地数据库：

- SQLite: `./runtime/studio-desktop.db`

### 5. 启动前端

安装依赖：

```powershell
cd C:\dev\ideaProject\DataAggregation\data-aggregation-studio\frontend
npm install
```

启动 Web 控制台：

```powershell
cd C:\dev\ideaProject\DataAggregation\data-aggregation-studio\frontend
npm run dev:web
```

启动桌面端：

```powershell
cd C:\dev\ideaProject\DataAggregation\data-aggregation-studio\frontend
npm run dev:desktop
```

默认前端端口：

- Web: `5173`
- Desktop renderer: `5174`

## 构建

### 后端

```powershell
cd C:\dev\ideaProject\DataAggregation\data-aggregation-studio\backend
mvn -pl studio-server -am -DskipTests package
mvn -pl studio-worker -am -DskipTests package
```

### 前端

```powershell
cd C:\dev\ideaProject\DataAggregation\data-aggregation-studio\frontend
npm run build:web
```

前端生产构建产物位于：

```text
frontend/apps/web/dist
```

生产部署 Web 控制台时，需要将该目录下的静态资源部署到 nginx 或静态资源服务的 `/dfs/data-aggregation-studio/` 基座路径下。

## 登录与接口文档

默认管理员账号由 `data-mysql-base.sql` 创建：

- username: `admin`
- password: `admin123`

接口文档：

- 在线 Server: [http://127.0.0.1:18080/doc.html](http://127.0.0.1:18080/doc.html)
- 桌面运行时: [http://127.0.0.1:18180/doc.html](http://127.0.0.1:18180/doc.html)

所有面向前端的接口使用统一 `Result<T>` 包装。

## 运行说明

- 在线模式使用 `studio-server + studio-worker + MySQL`。
- 桌面模式使用 `Electron + studio-desktop-runtime + SQLite`。
- 在线模式和桌面模式当前相互独立。
- 数据交换通过导入导出接口完成。
- 元模型支持草稿版本和发布版本。
- 数据源、模型、工作流和服务发布均围绕动态元数据建模。

## 菜单与路由

Web 控制台菜单由前端路由描述生成。主要分组包括：

- 工作台
- 数据资产
- 数据采集
- 数据开发
- 数据服务
- 数据质量
- 系统管理
- 运维中心

“协议转换服务”位于“数据服务”分组下，与“数据服务”“数据接入服务”“服务监控”等能力同属开放服务域。

## 日志

Studio 日志分为主程序日志和业务调用归档日志。

- Server 主程序日志：`applogs/data-aggregation-studio/studio-server.log`
- Worker 主程序日志：`applogs/data-aggregation-studio/studio-worker.log`
- 采集任务运行日志：按运行记录归档
- 开放调用日志：按数据服务、数据接入服务、协议转换服务分域归档

同步开放调用日志使用 MDC 隔离请求上下文，并可归档到对象存储。主程序文件日志用于普通运行诊断，调用级详细日志由各自业务归档保留。

## 文档

项目文档统一放在 [docs/README.md](./docs/README.md) 下。
