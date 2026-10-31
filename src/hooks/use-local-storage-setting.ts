// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
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

/**
 * 布尔设置。`useLocalStorageSetting<boolean>` 会把字符串 "false" 当成真值
 * （它原样 cast，不解析），所以布尔必须单独走这一条。
 */
export function useLocalStorageBoolean(key: string, initial: boolean): [boolean, (v: boolean) => void] {
  const [value, setValue] = useState<boolean>(() => {
    try {
      const raw = localStorage.getItem(key);
      return raw === null ? initial : raw === "true";
    } catch {
      return initial;
    }
  });
  const set = useCallback(
    (v: boolean) => {
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
      // 注意: key 不存在时 localStorage.getItem 返回 null, 而 Number(null) === 0
      // 且 Number.isFinite(0) 为真 —— 直接 Number(...) 会让"没存过"误判成 0,
      // 再被 clamp 成 min, initial 永远用不上(实测: 玻璃透明度 70% 退化成 0%)。
      const raw = localStorage.getItem(key);
      const v = raw === null ? NaN : Number(raw);
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
