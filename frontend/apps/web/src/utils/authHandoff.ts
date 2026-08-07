export function resolveSameOriginReturnPath(
  value: unknown,
  currentOrigin: string,
  fallbackPath: string,
) {
  if (typeof value !== "string") {
    return fallbackPath;
  }

  const candidate = value.trim();
  if (!candidate.startsWith("/") || candidate.startsWith("//")) {
    return fallbackPath;
  }

  try {
    const target = new URL(candidate, currentOrigin);
    if (target.origin !== currentOrigin) {
      return fallbackPath;
    }
    return `${target.pathname}${target.search}${target.hash}`;
  } catch {
    return fallbackPath;
  }
}

export function resolveHandoffQueryValue(query: Record<string, unknown>) {
  return query.returnPath ?? query.redirect ?? query.redict;
}
