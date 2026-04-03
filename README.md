# Data Aggregation Studio

`data-aggregation-studio` is an independent repository placed under the
`DataAggregation` workspace only for local side-by-side development. It is not
part of the root Maven reactor and does not share the parent POM of
`DataAggregation`.

The application is Web-first and also includes an Electron desktop shell for
offline runtime.

## Layout

```text
data-aggregation-studio/
├── frontend/
│   ├── apps/
│   │   ├── web/
│   │   └── desktop/
│   └── packages/
└── backend/
    ├── studio-commons/
    ├── studio-dto/
    ├── studio-core/
    ├── studio-infra/
    ├── studio-server/
    ├── studio-worker/
    └── studio-desktop-runtime/
```

## Backend Modules

- `studio-commons`: shared constants, exceptions, helper utilities
- `studio-dto`: shared DTO, VO, request/response models, generic `Result<T>`
- `studio-core`: studio SPI and integration contracts
- `studio-infra`: MyBatis-Plus entities, mapper, repositories, security, plugin scanning, DataAggregation adapters
- `studio-server`: online REST API, JWT/RBAC, Swagger/Knife4j, scheduling and orchestration APIs
- `studio-worker`: online worker process, task lease and execution
- `studio-desktop-runtime`: local offline runtime backed by SQLite

## Frontend Apps

- `frontend/apps/web`: online Web management console
- `frontend/apps/desktop`: Electron shell for offline runtime
- `frontend/packages/ui`: shared layout and presentational components
- `frontend/packages/api-sdk`: shared API client and DTO typings
- `frontend/packages/meta-form`: schema-driven metadata form renderer
- `frontend/packages/workflow-designer`: DAG designer and field mapping editor

## Integration Boundary

- This project does not join the `DataAggregation` root Maven `modules`
- This project does not use the `DataAggregation` parent POM
- This project does not depend on the deprecated `core.job.pipline` packages
- Runtime integration is done through ordinary Maven dependencies on published
  `com.jdragon.aggregation:*` artifacts
- Plugin loading still relies on `aggregation.home`

Required DataAggregation artifacts:

- `com.jdragon.aggregation:commons`
- `com.jdragon.aggregation:core`
- `com.jdragon.aggregation:data-source-handler-abstract`
- `com.jdragon.aggregation:plugins-loader-center`

## Quick Start

### 1. Prepare DataAggregation artifacts

Install the required `com.jdragon.aggregation:*` artifacts into your local Maven
repository or a private Maven registry before starting the studio backend.

If you are working in the adjacent `DataAggregation` source tree, a common
local workflow is:

```powershell
cd C:\dev\ideaProject\DataAggregation
mvn -DskipTests install
```

### 2. Start the online backend

The server and worker are separate processes.

```powershell
cd C:\dev\ideaProject\DataAggregation\data-aggregation-studio\backend
mvn -pl studio-server spring-boot:run
```

```powershell
cd C:\dev\ideaProject\DataAggregation\data-aggregation-studio\backend
mvn -pl studio-worker spring-boot:run
```

Default ports:

- `studio-server`: `18080`
- `studio-worker`: `18081`

Default online database:

- MySQL: `jdbc:mysql://127.0.0.1:3306/data_aggregation_studio`
- username: `root`
- password: `root`

The MySQL schema is initialized automatically from
`backend/studio-server/src/main/resources/schema-mysql.sql`.

### 3. Start the offline desktop runtime

```powershell
cd C:\dev\ideaProject\DataAggregation\data-aggregation-studio\backend
mvn -pl studio-desktop-runtime spring-boot:run
```

Default desktop runtime port:

- `studio-desktop-runtime`: `18180`

Default local database:

- SQLite: `./runtime/studio-desktop.db`

The SQLite schema is initialized automatically from
`backend/studio-desktop-runtime/src/main/resources/schema-sqlite.sql`.

### 4. Start the frontend

Install dependencies once:

```powershell
cd C:\dev\ideaProject\DataAggregation\data-aggregation-studio\frontend
npm install
```

Start the Web console:

```powershell
cd C:\dev\ideaProject\DataAggregation\data-aggregation-studio\frontend
npm run dev:web
```

Start the desktop shell:

```powershell
cd C:\dev\ideaProject\DataAggregation\data-aggregation-studio\frontend
npm run dev:desktop
```

Useful frontend scripts:

- `npm run build`
- `npm run build:web`
- `npm run build:desktop`

Default frontend ports:

- Web: `5173`
- Desktop renderer dev server: `5174`

## Login And Docs

Bootstrap data creates a default administrator account automatically.

- username: `admin`
- password: `admin123`

Swagger and Knife4j:

- online server: [http://127.0.0.1:18080/doc.html](http://127.0.0.1:18080/doc.html)
- desktop runtime: [http://127.0.0.1:18180/doc.html](http://127.0.0.1:18180/doc.html)

All frontend-facing APIs use the generic `Result<T>` wrapper and are documented
through Swagger/OpenAPI with Knife4j UI.

## Runtime Notes

- Online mode uses `studio-server + studio-worker + MySQL`
- Desktop mode uses `Electron + studio-desktop-runtime + SQLite`
- Online and desktop are independent in the current version
- Cross-end exchange is done through import/export APIs rather than automatic sync
- Metadata schemas support draft/published versions
- Datasource, model and workflow APIs are designed around dynamic metadata

## Build Verification

Verified in this workspace:

- `backend`: `mvn -q -DskipTests compile`
- `frontend`: `npm run build`

## Git

This directory is initialized as an independent repository so it can later be
bound to its own remote and referenced as a formal Git submodule from the outer
workspace.
