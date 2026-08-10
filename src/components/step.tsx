export function Step({ num, title, desc }: { num: string; title: string; desc: string }) {
  return (
    <div className="flex gap-3">
      <span className="w-5 h-5 rounded-full bg-zinc-800 text-zinc-400 text-[10px] flex items-center justify-center flex-shrink-0 mt-0.5">{num}</span>
      <div>
        <p className="text-xs text-zinc-300">{title}</p>
        <p className="text-[10px] text-zinc-600">{desc}</p>
      </div>
    </div>
  );
}
