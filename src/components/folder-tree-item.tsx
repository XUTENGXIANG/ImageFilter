import { useState } from "react";
import { Folder } from "@icon-park/react";
import type { FolderNode } from "../types";

export function FolderTreeItem({
  node, activeFolder, onSelect, depth, counting,
}: {
  node: FolderNode; activeFolder: string; onSelect: (path: string) => void;
  depth: number; counting: boolean;
}) {
  const [open, setOpen] = useState(false);
  const canExpand = node.hasSubdirs || node.children.length > 0;
  const isActive = activeFolder === node.path;

  return (
    <div>
      <button
        onClick={() => {
          if (canExpand) setOpen(!open);
          onSelect(node.path);
        }}
        className={`w-full text-left rounded text-[11px] flex items-center gap-1 ${
          isActive
            ? "bg-emerald-900/30 text-emerald-300"
            : "text-zinc-400 hover:bg-zinc-800/50"
        }`}
        style={{ paddingLeft: `${depth * 12 + 8}px`, paddingRight: "4px", paddingTop: "2px", paddingBottom: "2px" }}
      >
        <span className="text-[10px] w-3 flex-shrink-0">
          {canExpand ? (open ? "▼" : "▶") : <Folder theme="filled" size="12" />}
        </span>
        <span className="truncate">{node.name}</span>
        {!(counting && node.photoCount === 0) && (
          <span className="text-zinc-600 ml-auto flex-shrink-0">
            {node.photoCount}
          </span>
        )}
      </button>
      {open && canExpand &&
        node.children.map((c) => (
          <FolderTreeItem
            key={c.path}
            node={c}
            activeFolder={activeFolder}
            onSelect={onSelect}
            depth={depth + 1}
            counting={counting}
          />
        ))}
    </div>
  );
}
