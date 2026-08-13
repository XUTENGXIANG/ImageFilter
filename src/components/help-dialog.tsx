import { useTranslation } from "react-i18next";
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription,
} from "@/components/ui/dialog";
import { HelpSteps, ShortcutList } from "./help-content";

export function HelpDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const { t } = useTranslation();
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="w-[480px] max-h-[80vh] overflow-auto">
        <DialogHeader>
          <DialogTitle>{t("help.title")}</DialogTitle>
          <DialogDescription>{t("help.subtitle")}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-2 text-sm">
          <HelpSteps />
          <div className="border-t border-border pt-3">
            <p className="text-xs font-medium text-foreground mb-2">{t("help.shortcuts")}</p>
            <ShortcutList />
          </div>
          <div className="border-t border-border pt-3">
            <p className="text-xs font-medium text-foreground mb-2">{t("help.contextMenu")}</p>
            <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs text-muted-foreground">
              <span>{t("help.ctxPhoto")}</span>
              <span>{t("help.ctxEmpty")}</span>
              <span>{t("help.ctxDevice")}</span>
              <span>{t("help.ctxFolder")}</span>
            </div>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
