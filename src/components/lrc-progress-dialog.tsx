import { useTranslation } from "react-i18next";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from "./ui/dialog";
import type { LrcPhase } from "../lightroom";

/**
 * Phase 7 · 「导入到 LrC」这条链的模态进度提示。
 *
 * 为什么需要它(而不是只在导入栏写一行字): 这条链是
 *   **导入**(复制 + 双端 MD5, 可能要几十秒) → **启动 Lightroom**(又要几十秒, 目录库越大越久)
 * 中间那段黑屏期用户会以为按钮没反应, 去重复点。导入栏那行字容易被忽略(它在屏幕底部,
 * 而且用户点完按钮眼睛还在按钮上), 所以用一个模态框把"现在在干什么"顶到眼前。
 *
 * 两个刻意的设计:
 * 1. **可以关掉**("在后台继续"或 Esc): 启动 Lightroom 可能要很久, 不该把用户锁在一个
 *    不能取消的框里。关掉之后导入栏那行脉冲文字**照旧显示**, 所以反馈不会丢, 只是从
 *    模态降级为常驻。
 * 2. **不给"取消"**: 导入一旦开始就写盘了, 中途打断只会留下半批文件, 说不清也收不干净。
 *    要停就等它结束(导入本身很快, 慢的是 Lightroom 启动那一段)。
 */
export function LrcProgressDialog({
  phase,
  imported,
  total,
  open,
  onOpenChange,
}: {
  phase: LrcPhase;
  /** 已导入张数(仅 importing 阶段有意义) */
  imported: number;
  /** 本次要导入的张数 */
  total: number;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const { t } = useTranslation();

  if (phase === "idle") return null;

  const isLaunching = phase === "launching";

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="w-[420px]" showCloseButton={false}>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            {/* 转圈: 与 App 里既有的加载指示同一套 Tailwind 写法 */}
            <span className="w-4 h-4 border-2 border-sky-400 border-t-transparent rounded-full animate-spin flex-shrink-0" />
            {isLaunching ? t("lrc.startingLightroom") : t("lrc.importingPhotos")}
          </DialogTitle>
          <DialogDescription>
            {isLaunching
              ? t("lrc.startingLightroomBody")
              : t("lrc.importingPhotosBody", { done: imported, total })}
          </DialogDescription>
        </DialogHeader>
        <div className="flex justify-end">
          <button
            type="button"
            onClick={() => onOpenChange(false)}
            className="px-3 py-1.5 rounded-md border border-border text-sm hover:bg-muted"
          >
            {t("lrc.keepInBackground")}
          </button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
