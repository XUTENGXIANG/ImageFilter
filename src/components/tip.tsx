/** 自定义圆角 tooltip — 替代浏览器默认方形提示 */
export function Tip({ label, children, className = "" }: { label: string; children: React.ReactNode; className?: string }) {
  return (
    <span className={`tooltip-wrap ${className}`}>
      {children}
      <span className="tooltip">{label}</span>
    </span>
  );
}
