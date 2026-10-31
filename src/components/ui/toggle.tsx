// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
/** 设置面板开关（胶囊形 Switch） */
export function Toggle({
  checked,
  onChange,
  disabled,
}: {
  checked: boolean;
  onChange: () => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onChange}
      disabled={disabled}
      aria-pressed={checked}
      className={`mt-0.5 w-10 h-6 rounded-full relative shrink-0 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 ${
        checked ? "bg-brand" : "bg-muted"
      } disabled:opacity-50 disabled:cursor-not-allowed`}
    >
      <span
        className={`absolute left-0.5 top-0.5 w-5 h-5 rounded-full bg-white shadow transition-transform duration-150 ease-out motion-reduce:transition-none ${
          checked ? "translate-x-4" : "translate-x-0"
        }`}
      />
    </button>
  );
}
