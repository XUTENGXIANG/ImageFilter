import { useTranslation } from "react-i18next";
import { HelpSteps, ShortcutList } from "./help-content";

export function WelcomeGuide() {
  const { t } = useTranslation();
  return (
    <div className="max-w-md text-center space-y-6 p-8">
      <h1 className="text-2xl font-light text-zinc-300 tracking-wide">ImageFilter</h1>
      <p className="text-xs text-zinc-500">{t("welcome.subtitle")}</p>
      <div className="space-y-3 text-left">
        <HelpSteps />
      </div>
      <ShortcutList compact />
    </div>
  );
}
