const CONTROL_SELECTOR = "input.el-input__inner, textarea.el-textarea__inner";
const SELECT_SELECTOR = ".el-select";
const SKIPPED_INPUT_TYPES = new Set(["button", "checkbox", "file", "hidden", "image", "radio", "range", "reset", "submit"]);
const SKIPPED_FIELD_SELECTOR = [
  ".el-date-editor",
  ".el-date-picker",
  ".el-date-range-picker__editor",
  ".el-picker-panel",
  ".el-picker__popper",
  ".el-time-panel",
  ".el-cascader",
  ".el-color-picker",
  ".el-pagination",
  ".el-table",
  ".el-upload",
  "table",
  ".studio-layout__context-select",
  "[data-no-floating-placeholder]",
  ".no-floating-placeholder",
].join(",");

let observer: MutationObserver | null = null;
let scheduled = false;
const cleanupHandlers: Array<() => void> = [];

export function installFloatingPlaceholder() {
  if (typeof document === "undefined" || observer) {
    return () => undefined;
  }

  const refresh = () => refreshFloatingPlaceholders();
  const schedule = () => scheduleRefresh();
  document.addEventListener("focusin", refresh, true);
  document.addEventListener("focusout", schedule, true);
  document.addEventListener("input", refresh, true);
  document.addEventListener("change", refresh, true);
  document.addEventListener("click", schedule, true);
  document.addEventListener("keyup", refresh, true);
  cleanupHandlers.push(
    () => document.removeEventListener("focusin", refresh, true),
    () => document.removeEventListener("focusout", schedule, true),
    () => document.removeEventListener("input", refresh, true),
    () => document.removeEventListener("change", refresh, true),
    () => document.removeEventListener("click", schedule, true),
    () => document.removeEventListener("keyup", refresh, true),
  );

  observer = new MutationObserver(scheduleRefresh);
  observer.observe(document.body, {
    subtree: true,
    childList: true,
    characterData: true,
    attributes: true,
    attributeFilter: ["aria-expanded", "placeholder", "class", "disabled"],
  });
  refreshFloatingPlaceholders();

  return uninstallFloatingPlaceholder;
}

export function uninstallFloatingPlaceholder() {
  observer?.disconnect();
  observer = null;
  while (cleanupHandlers.length) {
    cleanupHandlers.pop()?.();
  }
  document.querySelectorAll<HTMLElement>(".studio-floating-field").forEach((field) => {
    resetField(field);
  });
}

function scheduleRefresh() {
  if (scheduled) {
    return;
  }
  scheduled = true;
  window.requestAnimationFrame(() => {
    window.requestAnimationFrame(() => {
      scheduled = false;
      refreshFloatingPlaceholders();
    });
  });
}

function refreshFloatingPlaceholders() {
  document.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>(CONTROL_SELECTOR).forEach(syncControl);
  document.querySelectorAll<HTMLElement>(SELECT_SELECTOR).forEach(syncSelect);
}

function syncControl(control: HTMLInputElement | HTMLTextAreaElement) {
  const root = resolveFieldRoot(control);
  if (!root) {
    return;
  }
  if (!shouldFloat(control, root)) {
    resetField(root);
    return;
  }

  const placeholder = (control.getAttribute("placeholder") ?? "").trim();
  const focused = document.activeElement === control || root.contains(document.activeElement);
  const hasValue = control.value != null && String(control.value).length > 0;
  root.classList.add("studio-floating-field");
  root.classList.remove("studio-floating-select");
  root.classList.toggle("is-floating", focused || hasValue);
  root.classList.toggle("is-focused", focused);
  root.classList.toggle("has-value", hasValue);
  root.classList.toggle("is-disabled", control.disabled);
  root.dataset.floatingPlaceholder = placeholder;
}

function syncSelect(root: HTMLElement) {
  if (!shouldFloatSelect(root)) {
    resetField(root);
    return;
  }

  const placeholderElement = root.querySelector<HTMLElement>(".el-select__placeholder");
  const placeholderText = (placeholderElement?.textContent ?? "").trim();
  const hasValue = Boolean(placeholderElement && !placeholderElement.classList.contains("is-transparent"));
  if (!hasValue && placeholderText) {
    root.dataset.floatingSelectPlaceholder = placeholderText;
  }

  const label = getSelectFloatingLabel(root, placeholderText, hasValue);
  const focused = root.contains(document.activeElement) || isSelectDropdownExpanded(root);
  const disabled = root.classList.contains("is-disabled") || Boolean(root.querySelector(".el-select__wrapper.is-disabled"));

  root.classList.add("studio-floating-field", "studio-floating-select");
  root.classList.toggle("is-floating", focused || hasValue);
  root.classList.toggle("is-focused", focused);
  root.classList.toggle("has-value", hasValue);
  root.classList.toggle("is-disabled", disabled);
  root.dataset.floatingPlaceholder = label;
}

function shouldFloat(control: HTMLInputElement | HTMLTextAreaElement, root: HTMLElement) {
  const placeholder = (control.getAttribute("placeholder") ?? "").trim();
  if (!placeholder || control.closest(SKIPPED_FIELD_SELECTOR) || control.closest(SELECT_SELECTOR)) {
    return false;
  }
  if (control instanceof HTMLInputElement && SKIPPED_INPUT_TYPES.has(control.type)) {
    return false;
  }
  return !hasVisibleFormLabel(root);
}

function shouldFloatSelect(root: HTMLElement) {
  if (root.closest(SKIPPED_FIELD_SELECTOR)) {
    return false;
  }
  const placeholderElement = root.querySelector<HTMLElement>(".el-select__placeholder");
  const placeholderText = (placeholderElement?.textContent ?? "").trim();
  const hasValue = Boolean(placeholderElement && !placeholderElement.classList.contains("is-transparent"));
  const label = getSelectFloatingLabel(root, placeholderText, hasValue);
  return Boolean(label) && !hasVisibleFormLabel(root);
}

function hasVisibleFormLabel(root: HTMLElement) {
  const formItem = root.closest(".el-form-item");
  if (!formItem) {
    return false;
  }
  const label = Array.from(formItem.children).find((child) => child.classList.contains("el-form-item__label"));
  return Boolean(label?.textContent?.trim());
}

function getSelectFloatingLabel(root: HTMLElement, placeholderText: string, hasValue: boolean) {
  if (!hasValue && placeholderText) {
    return placeholderText;
  }
  return (
    (root.dataset.floatingSelectPlaceholder ?? "").trim() ||
    getVueSelectPlaceholder(root) ||
    placeholderText
  ).trim();
}

function getVueSelectPlaceholder(root: HTMLElement) {
  const component = (root as HTMLElement & { __vueParentComponent?: { props?: Record<string, unknown> } }).__vueParentComponent;
  const placeholder = component?.props?.placeholder;
  return typeof placeholder === "string" ? placeholder.trim() : "";
}

function isSelectDropdownExpanded(root: HTMLElement) {
  return Array.from(root.querySelectorAll<HTMLElement>("[aria-expanded]")).some(
    (element) => element.getAttribute("aria-expanded") === "true",
  );
}

function resolveFieldRoot(control: HTMLInputElement | HTMLTextAreaElement) {
  if (control instanceof HTMLTextAreaElement) {
    return control.closest<HTMLElement>(".el-textarea");
  }
  return control.closest<HTMLElement>(".el-input");
}

function resetField(root: HTMLElement) {
  root.classList.remove("studio-floating-field", "studio-floating-select", "is-floating", "is-focused", "has-value", "is-disabled");
  delete root.dataset.floatingPlaceholder;
  delete root.dataset.floatingSelectPlaceholder;
}
