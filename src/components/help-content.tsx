import { useTranslation } from "react-i18next";
import { Step } from "./step";

/** 四步使用引导（欢迎页与帮助对话框共用） */
export function HelpSteps() {
  const { t } = useTranslation();
  return (
    <div className="space-y-3">
      <Step num="1" title={t("help.step1Title")} desc={t("help.step1Desc")} />
      <Step num="2" title={t("help.step2Title")} desc={t("help.step2Desc")} />
      <Step num="3" title={t("help.step3Title")} desc={t("help.step3Desc")} />
      <Step num="4" title={t("help.step4Title")} desc={t("help.step4Desc")} />
    </div>
  );
}

/** 键盘快捷键列表 — compact 为欢迎页单行排版, 默认两列网格 */
export function ShortcutList({ compact = false }: { compact?: boolean }) {
  const { t } = useTranslation();
  const kbd = "px-1 bg-zinc-800 rounded text-zinc-400";
  const items: { kbd: string; label: string }[] = [
    { kbd: "J", label: t("help.keep") },
    { kbd: "X", label: t("help.trash") },
    { kbd: "1-5", label: t("help.star") },
    { kbd: "R", label: t("help.rotate") },
    { kbd: "←→", label: t("help.nav") },
    { kbd: "0", label: t("help.reset") },
    { kbd: t("help.space"), label: t("help.select") },
    { kbd: t("help.ctrlClick"), label: t("help.multi") },
    { kbd: t("help.shiftClick"), label: t("help.range") },
  ];
  if (compact) {
    return (
      <div className="pt-4 border-t border-zinc-800 text-left text-[10px] text-zinc-600 space-y-1">
        <p>
          <kbd className={kbd}>J</kbd> {t("help.keep")}{" "}
          <kbd className={kbd}>X</kbd> {t("help.trash")}{" "}
          <kbd className={kbd}>1-5</kbd> {t("help.star")}
        </p>
        <p>
          <kbd className={kbd}>{t("help.ctrlClick")}</kbd> {t("help.multi")}{" "}
          <kbd className={kbd}>{t("help.shiftClick")}</kbd> {t("help.range")}
        </p>
      </div>
    );
  }
  return (
    <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs text-muted-foreground">
      {items.map((it) => (
        <span key={it.kbd}>
          <kbd className={kbd}>{it.kbd}</kbd> {it.label}
        </span>
      ))}
    </div>
  );
}
