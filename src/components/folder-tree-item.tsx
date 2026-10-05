import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Folder } from "@icon-park/react";
import type { FolderNode } from "../types";
import {
  treePhase,
  PLACEHOLDER_DELAY_MS,
  EXPAND_MS,
  AUTO_SETTLE_SLACK_MS,
} from "./folder-tree-motion";

/** 占位行 —— 不是按钮, 不可聚焦。
 *  用 role="status" + 视觉隐藏文案向读屏说明"在加载"; 注意实时区域是随内容一起插入的,
 *  部分读屏可能不播报, 所以主信号仍是父行的 aria-expanded="true"。 */
function SkeletonRow({ depth, label }: { depth: number; label: string }) {
  return (
    <div
      className="tree-row tree-row-skeleton flex items-center gap-1 rounded text-[11px] text-zinc-600"
      style={{ paddingLeft: `${depth * 12 + 8}px`, paddingRight: "4px" }}
      role="status"
    >
      <span className="sr-only">{label}</span>
      {/* 空占位撑出与真行相同的缩进: 真行在名字前有一个 12px 的箭头槽 */}
      <span className="w-3 flex-shrink-0" aria-hidden="true" />
      <span className="tree-skeleton-bar" aria-hidden="true" />
    </div>
  );
}

export function FolderTreeItem({
  node, activeFolder, onSelect, depth, counting,
}: {
  node: FolderNode; activeFolder: string; onSelect: (path: string) => void;
  depth: number; counting: boolean;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [placeholderElapsed, setPlaceholderElapsed] = useState(false);
  const canExpand = node.hasSubdirs || node.children.length > 0;
  const isActive = activeFolder === node.path;

  const kidsRef = useRef<HTMLDivElement>(null);
  const innerRef = useRef<HTMLDivElement>(null);
  /** 代次: 每次阶段变化 +1, 让先前排队的定时器/rAF 全部作废 */
  const genRef = useRef(0);
  /** 是否已经跑过一次高度 effect —— 见下面"首次运行不收起"的说明 */
  const mountedRef = useRef(false);

  const phase = treePhase({
    open, canExpand, childCount: node.children.length, placeholderElapsed,
  });

  // 占位行的 120ms 延迟: 快卡上子行会在这之前到达, 于是根本不出现占位行。
  // 收起时把标记清掉 —— 否则"延迟到点了"会在收起状态下展出占位行。
  useEffect(() => {
    if (!open || node.children.length > 0) { setPlaceholderElapsed(false); return; }
    const timer = window.setTimeout(() => setPlaceholderElapsed(true), PLACEHOLDER_DELAY_MS);
    return () => { window.clearTimeout(timer); setPlaceholderElapsed(false); };
  }, [open, node.children.length]);

  // 高度驱动。用 layout effect: 在浏览器绘制前就把高度定好, 避免闪一帧错误高度
  // (首次挂载时容器还没有行内高度, 不设的话会先按内容自然高度画出来)。
  useLayoutEffect(() => {
    const el = kidsRef.current;
    const inner = innerRef.current;
    if (!el || !inner) return;
    const myGen = ++genRef.current;
    const alive = () => genRef.current === myGen;
    // 首次运行不许"从当前高度收起": 挂载瞬间没有动画可言, 而一个"子行已缓存但收起"的
    // 分支此刻的自然高度是满高 —— 按它钉一下就会在首帧撑开再收回(逐帧看得见的抽动)。
    // 现阶段 children 只在展开时才有, 所以这个分支实际不会走到; 但别依赖这个巧合。
    const from = mountedRef.current ? el.getBoundingClientRect().height : 0;
    mountedRef.current = true;

    if (phase === "closed") {
      if (from > 0) {
        // height: auto 无法直接插值到 0, 必须先把当前像素高度钉住、下一帧再设 0 ——
        // 这是从 auto 收起唯一能出动画的写法。
        el.style.height = `${from}px`;
        requestAnimationFrame(() => { if (alive()) el.style.height = "0px"; });
      } else {
        el.style.height = "0px";
      }
      return;
    }

    // 目标必须是像素值, 这样才有可插值的起点: 快卡从 0(收起)起、慢卡从占位行那 24px 起。
    el.style.height = `${inner.scrollHeight}px`;
    // 收尾切成 auto, 好让嵌套分支展开时父容器能跟着长(固定像素高度会把内容裁掉)。
    // 项目约定不用 transitionend, 所以用定时器。
    // **只在 content 阶段收尾**: 占位行阶段容器里只有一根灰条, auto 在那里什么都换不来,
    // 却会让"占位行 → 真行"这一步失去可插值的起点。实测(不这样做的版本, gap=600ms):
    // 容器在 410ms 就收尾成 auto, 真行 628ms 到达时 24→96px 一帧到位, 采样里没有任何中间帧 ——
    // 即规格 §3.2 那条"容器已经停在终值上, 只能瞬跳", 单看台阶数看不出来(占位行自己的
    // 0→24 展开已贡献 13 步)。收尾成 auto 的收益(嵌套跟随)只有真行到达后才存在。
    if (phase === "placeholder") return;
    const settle = window.setTimeout(() => {
      if (alive()) el.style.height = "auto";
    }, EXPAND_MS + AUTO_SETTLE_SLACK_MS);
    return () => { window.clearTimeout(settle); };
  }, [phase]);

  return (
    <div>
      <button
        onClick={() => {
          if (canExpand) setOpen(!open);
          onSelect(node.path);
        }}
        aria-expanded={canExpand ? open : undefined}
        className={`tree-row w-full text-left rounded text-[11px] flex items-center gap-1 ${
          isActive
            ? "bg-emerald-900/30 text-emerald-300"
            : "text-zinc-400 hover:bg-zinc-800/50"
        }`}
        style={{ paddingLeft: `${depth * 12 + 8}px`, paddingRight: "4px" }}
      >
        <span className="tree-caret text-[10px] w-3 flex-shrink-0 flex items-center justify-center" aria-hidden="true">
          {canExpand ? <i>▶</i> : <Folder theme="filled" size={12} />}
        </span>
        <span className="truncate">{node.name}</span>
        {!(counting && node.photoCount === 0) && (
          <span className="text-zinc-600 ml-auto flex-shrink-0">
            {node.photoCount}
          </span>
        )}
      </button>
      {/* 子树常驻挂载: 这是高度动画的前提, 也正因为常驻, 收起时必须 inert + aria-hidden,
          否则收起的子行会漏进 Tab 顺序与无障碍树(WCAG 4.1.2)。
          never-opened 的节点 children 为空, 渲染出来是空的, 不产生 DOM 规模。 */}
      {canExpand && (
        <div className="tree-kids" ref={kidsRef} inert={!open} aria-hidden={!open}>
          <div ref={innerRef}>
            {phase === "placeholder" ? (
              <SkeletonRow depth={depth + 1} label={t("devices.loading")} />
            ) : (
              node.children.map((c) => (
                <FolderTreeItem
                  key={c.path}
                  node={c}
                  activeFolder={activeFolder}
                  onSelect={onSelect}
                  depth={depth + 1}
                  counting={counting}
                />
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}
