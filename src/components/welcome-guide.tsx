import { useTranslation } from "react-i18next";
import { Step } from "./step";

export function WelcomeGuide() {
  const { t } = useTranslation();
  return (
    <div className="max-w-md text-center space-y-6 p-8">
      <h1 className="text-2xl font-light text-zinc-300 tracking-wide">ImageFilter</h1>
      <p className="text-xs text-zinc-500">{t("welcome.subtitle")}</p>
      <div className="space-y-3 text-left">
        <Step num="1" title={t("help.step1Title")} desc={t("help.step1Desc")} />
        <Step num="2" title={t("help.step2Title")} desc={t("help.step2Desc")} />
        <Step num="3" title={t("help.step3Title")} desc={t("help.step3Desc")} />
        <Step num="4" title={t("help.step4Title")} desc={t("help.step4Desc")} />
      </div>
      <div className="pt-4 border-t border-zinc-800 text-left text-[10px] text-zinc-600 space-y-1">
        <p><kbd className="px-1 bg-zinc-800 rounded text-zinc-400">J</kbd> {t("welcome.keep")} <kbd className="px-1 bg-zinc-800 rounded text-zinc-400">X</kbd> {t("welcome.trash")} <kbd className="px-1 bg-zinc-800 rounded text-zinc-400">1-5</kbd> {t("welcome.star")}</p>
        <p><kbd className="px-1 bg-zinc-800 rounded text-zinc-400">Ctrl+点击</kbd> {t("help.multi")} <kbd className="px-1 bg-zinc-800 rounded text-zinc-400">Shift+点击</kbd> {t("help.range")}</p>
      </div>
    </div>
  );
}
