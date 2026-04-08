# MySQL Schema Snapshots

This directory stores structure-only snapshots for the online Studio MySQL
schema. Snapshots intentionally exclude business data and are used only for
schema rollback and structural verification during the multitenant rollout.

## Layout

- `pre-multitenant/`: baseline snapshot generated before online multitenant work
- `current/`: latest schema snapshot matching the current implementation state

Each snapshot directory contains:

- `schema.json`: ordered table metadata, column definitions, index metadata, and
  full table DDL
- `tables/*.sql`: per-table `CREATE TABLE` statements for human inspection

## Commands

Generate/update the current snapshot:

```powershell
cd C:\dev\ideaProject\DataAggregation\data-aggregation-studio\backend
powershell -ExecutionPolicy Bypass -File .\scripts\snapshot-studio-schema.ps1
```

Restore the current online schema from a snapshot:

```powershell
cd C:\dev\ideaProject\DataAggregation\data-aggregation-studio\backend
powershell -ExecutionPolicy Bypass -File .\scripts\restore-studio-schema.ps1
```
