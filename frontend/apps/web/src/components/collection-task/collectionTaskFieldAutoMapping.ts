import type { FieldMappingDefinition } from "@studio/api-sdk";

export interface CollectionTaskAutoMappingSource {
  sourceAlias: string;
  fields: readonly string[];
}

interface SourceFieldCandidate {
  id: number;
  sourceAlias: string;
  sourceField: string;
}

export function buildAutomaticFieldMappings(
  targetFields: readonly string[],
  sources: readonly CollectionTaskAutoMappingSource[],
): FieldMappingDefinition[] {
  const fallbackSourceAlias = sources.find((source) => source.sourceAlias)?.sourceAlias ?? "";
  const rows = targetFields.map<FieldMappingDefinition>((targetField) => ({
    sourceAlias: fallbackSourceAlias,
    sourceField: "",
    targetField,
    expression: "",
    transformers: [],
  }));
  const candidates = buildSourceFieldCandidates(sources);
  const usedCandidateIds = new Set<number>();

  assignMatches(rows, candidates, usedCandidateIds, (value) => value);
  assignMatches(rows, candidates, usedCandidateIds, (value) => value.toLowerCase());
  return rows;
}

function buildSourceFieldCandidates(sources: readonly CollectionTaskAutoMappingSource[]) {
  const candidates: SourceFieldCandidate[] = [];
  for (const source of sources) {
    if (!source.sourceAlias) {
      continue;
    }
    for (const sourceField of source.fields) {
      if (!sourceField) {
        continue;
      }
      candidates.push({
        id: candidates.length,
        sourceAlias: source.sourceAlias,
        sourceField,
      });
    }
  }
  return candidates;
}

function assignMatches(
  rows: FieldMappingDefinition[],
  candidates: SourceFieldCandidate[],
  usedCandidateIds: Set<number>,
  normalize: (value: string) => string,
) {
  const candidatesByField = new Map<string, SourceFieldCandidate[]>();
  for (const candidate of candidates) {
    const key = normalize(candidate.sourceField);
    const bucket = candidatesByField.get(key) ?? [];
    bucket.push(candidate);
    candidatesByField.set(key, bucket);
  }

  for (const row of rows) {
    if (row.sourceField) {
      continue;
    }
    const targetField = row.targetField ?? "";
    const candidate = candidatesByField
      .get(normalize(targetField))
      ?.find((item) => !usedCandidateIds.has(item.id));
    if (!candidate) {
      continue;
    }
    row.sourceAlias = candidate.sourceAlias;
    row.sourceField = candidate.sourceField;
    usedCandidateIds.add(candidate.id);
  }
}
