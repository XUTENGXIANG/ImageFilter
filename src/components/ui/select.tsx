// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import { Select as SelectPrimitive } from "@base-ui/react/select";
import { Check } from "@icon-park/react";
import { MENU_CONTENT, MENU_ITEM, MENU_TRANSITION } from "../menu-styles";

export interface DropdownOption<T> {
  value: T;
  label: React.ReactNode;
}

/**
 * 下拉选择。视觉与右键菜单同一套（见 menu-styles.ts），弹层有进出过渡。
 *
 * 为什么不用原生 `<select>`: 它的下拉是**操作系统画的**, CSS 完全碰不到 ——
 * 方角、瞬时、且深浅色主题下由系统决定长什么样。这个应用里其余弹层全是
 * `rounded-lg` 且有过渡, 只有那 4 个原生下拉框不是, 一眼就看得出不搭。
 *
 * 无障碍由 Base UI 提供: Trigger 是 combobox 语义、方向键/Home/End/Escape/输入首字母
 * 跳转、aria-activedescendant 都齐全。**但可访问名称仍要调用方给**
 * （`ariaLabel`）—— 一个没有名字的下拉框读屏只会念出当前值。
 */
export function Dropdown<T extends string | number>({
  value,
  onValueChange,
  options,
  className = "",
  ariaLabel,
}: {
  value: T;
  onValueChange: (v: T) => void;
  options: DropdownOption<T>[];
  /** 触发器的外观(尺寸/配色)由调用方决定, 形状由这里统一 */
  className?: string;
  ariaLabel?: string;
}) {
  return (
    <SelectPrimitive.Root
      value={value}
      onValueChange={(v) => onValueChange(v as T)}
      items={options}
      // 不用模态: 这只占一个控件宽的下拉, 不该锁住整页滚动与外部点击
      modal={false}
    >
      <SelectPrimitive.Trigger
        aria-label={ariaLabel}
        // 高度由这里定死, 不让调用点各配各的 py —— 这几个下拉散在工具栏、筛选面板、
        // 高级选项三处, 各写各的竖向内边距就会各长各的(实测过: 26px 比同一行其它控件高 7px)。
        // h-5 = 20px, 与工具栏那一行的排序方向按钮(20px)/筛选按钮(19px)同一档。
        className={`shrink-0 inline-flex items-center gap-1 h-5 rounded border cursor-pointer transition-colors ${className}`}
      >
        <SelectPrimitive.Value />
        <SelectPrimitive.Icon className="text-zinc-500 text-[9px] leading-none">▾</SelectPrimitive.Icon>
      </SelectPrimitive.Trigger>
      <SelectPrimitive.Portal>
        <SelectPrimitive.Positioner
          sideOffset={4}
          // 默认会把选中项和触发器文字对齐(整个弹层盖住触发器), 那是原生 select 的手感;
          // 这里要的是"从下面展开一个菜单", 所以关掉
          alignItemWithTrigger={false}
          // ⚠️ 层级必须写在 positioner 上, 不能只写在弹层上。
          // positioner 带 transform → 它自己形成一个**层叠上下文**, 弹层上的 z-index 会被
          // 关在里面、对外不生效; 而 positioner 本身是 z-index: auto, 于是整块弹层在应用里的
          // 层级等于 0 —— 输给工具栏的 z-40, 表现为"弹层上半截被工具栏盖住"(实测踩过)。
          // 100 = 本项目的菜单层(与右键菜单同层); 刻度是 10/20/40/50/100/200。
          className="z-[100]"
        >
          <SelectPrimitive.Popup className={`${MENU_CONTENT} ${MENU_TRANSITION}`}>
            <SelectPrimitive.List>
              {options.map((o) => (
                <SelectPrimitive.Item key={String(o.value)} value={o.value} className={MENU_ITEM}>
                  <SelectPrimitive.ItemIndicator className="w-3 shrink-0 flex items-center justify-center">
                    <Check theme="filled" size="11" />
                  </SelectPrimitive.ItemIndicator>
                  <SelectPrimitive.ItemText>{o.label}</SelectPrimitive.ItemText>
                </SelectPrimitive.Item>
              ))}
            </SelectPrimitive.List>
          </SelectPrimitive.Popup>
        </SelectPrimitive.Positioner>
      </SelectPrimitive.Portal>
    </SelectPrimitive.Root>
  );
}
