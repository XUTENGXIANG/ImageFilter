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
      className={`mt-0.5 w-10 h-6 rounded-full relative shrink-0 transition-colors ${
        checked ? "bg-emerald-500" : "bg-muted"
      } disabled:opacity-50 disabled:cursor-not-allowed`}
    >
      <span
        className={`absolute top-0.5 w-5 h-5 rounded-full bg-white shadow transition-all ${
          checked ? "left-[18px]" : "left-0.5"
        }`}
      />
    </button>
  );
}
