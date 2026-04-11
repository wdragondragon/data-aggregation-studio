export interface StudioNavItem {
  label: string;
  path?: string;
  key?: string;
  caption?: string;
  children?: StudioNavItem[];
}

export interface StudioLocaleOption {
  label: string;
  value: string;
}

export interface OverflowActionItem {
  key: string;
  label: string;
  type?: string;
  link?: boolean;
  plain?: boolean;
  disabled?: boolean;
  divided?: boolean;
  visible?: boolean;
  onClick?: () => void | Promise<void>;
}
