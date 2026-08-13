import { useCallback, useState } from "react";

/**
 * localStorage 持久化设置 hook — 统一"读→校验→写回"模式。
 * 所有读取都包 try/catch（localStorage 可能被禁用/损坏），写入幂等。
 */
export function useLocalStorageSetting<T>(key: string, initial: T): [T, (v: T) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const raw = localStorage.getItem(key);
      return raw === null ? initial : (raw as unknown as T);
    } catch {
      return initial;
    }
  });
  const set = useCallback(
    (v: T) => {
      setValue(v);
      try {
        localStorage.setItem(key, String(v));
      } catch {
        /* 忽略写入失败 */
      }
    },
    [key]
  );
  return [value, set];
}

/** 数值型设置: 读取时校验 Number.isFinite 并 clamp 到 [min, max], 防损坏数据产生 NaN */
export function useLocalStorageNumber(
  key: string,
  initial: number,
  min: number,
  max: number
): [number, (v: number) => void] {
  const [value, setValue] = useState<number>(() => {
    try {
      const v = Number(localStorage.getItem(key));
      return Number.isFinite(v) ? Math.min(max, Math.max(min, v)) : initial;
    } catch {
      return initial;
    }
  });
  const set = useCallback(
    (v: number) => {
      const clamped = Number.isFinite(v) ? Math.min(max, Math.max(min, v)) : initial;
      setValue(clamped);
      try {
        localStorage.setItem(key, String(clamped));
      } catch {
        /* 忽略写入失败 */
      }
    },
    [key, initial, min, max]
  );
  return [value, set];
}
