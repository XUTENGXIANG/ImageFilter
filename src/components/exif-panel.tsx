import { useTranslation } from "react-i18next";
import { formatBytes } from "../lib/format";
import type { ScannedPhoto } from "../types";

export function ExifPanel({ photo, previewSrc }: { photo: ScannedPhoto; previewSrc: string | null }) {
  const { t } = useTranslation();
  const { exif } = photo;
  return (
    <div className="space-y-4">
      {previewSrc && (
        <div className="aspect-square rounded-lg bg-zinc-800 overflow-hidden">
          <img src={previewSrc} alt={photo.fileName} className="w-full h-full object-cover" />
        </div>
      )}
      <Section title={t("exif.fileInfo")}>
        <Row label={t("exif.fileName")} value={photo.fileName} />
        <Row label={t("exif.size")} value={formatBytes(photo.fileSize)} />
        <Row label={t("exif.type")} value={photo.isRaw ? "RAW" : photo.isVideo ? t("exif.typeVideo") : t("exif.typeImage")} />
      </Section>
      {(exif.cameraMake || exif.cameraModel) && (
        <Section title={t("exif.camera")}>
          <Row label={t("exif.brand")} value={exif.cameraMake} />
          <Row label={t("exif.model")} value={exif.cameraModel} />
          <Row label={t("exif.lens")} value={exif.lensModel} />
        </Section>
      )}
      {(exif.aperture || exif.shutterSpeed || exif.iso) && (
        <Section title={t("exif.params")}>
          <Row label={t("exif.aperture")} value={exif.aperture} />
          <Row label={t("exif.shutter")} value={exif.shutterSpeed} />
          <Row label={t("exif.iso")} value={exif.iso?.toString()} />
          <Row label={t("exif.focal")} value={exif.focalLength} />
        </Section>
      )}
      {exif.dateTaken && (
        <Section title={t("exif.date")}>
          <p className="text-[11px] text-zinc-300">{exif.dateTaken}</p>
        </Section>
      )}
      {exif.imageWidth && (
        <Section title={t("exif.dims")}>
          <p className="text-[11px] text-zinc-300">{exif.imageWidth} × {exif.imageHeight}</p>
        </Section>
      )}
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h3 className="text-[10px] font-semibold text-zinc-500 uppercase mb-1.5">{title}</h3>
      <div className="text-[11px] space-y-1">{children}</div>
    </section>
  );
}

function Row({ label, value }: { label: string; value?: string }) {
  if (!value) return null;
  return (
    <div className="flex justify-between gap-2">
      <span className="text-zinc-500 flex-shrink-0">{label}</span>
      <span className="text-zinc-300 text-right truncate">{value}</span>
    </div>
  );
}
