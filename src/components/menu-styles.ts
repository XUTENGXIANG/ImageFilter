/**
 * 菜单类弹层的统一视觉。右键菜单（含二级菜单）与下拉框共用同一份 ——
 * 分开写就会各飘各的圆角、内边距、项高，而用户一眼就能看出两种菜单不是一套东西。
 *
 * 这里放的是"容器与项"的形状，不含入场/出场动画（那由各自的数据属性驱动）。
 */
export const MENU_CONTENT =
  "min-w-[160px] bg-zinc-800 border border-zinc-700 rounded-lg p-1 shadow-2xl z-[100]";

export const MENU_ITEM =
  "group text-[11px] leading-none text-zinc-300 rounded-sm flex items-center h-7 px-2 relative select-none outline-none " +
  "data-[disabled]:text-zinc-600 data-[disabled]:pointer-events-none " +
  "data-[highlighted]:bg-zinc-700 data-[highlighted]:text-zinc-100 cursor-pointer";

/**
 * 弹层的入场/出场动画类。Base UI 在过渡前后打 `data-starting-style` / `data-ending-style`,
 * 所以进出都能动 —— 这比只做"挂载时播一次 animation"更完整（收起也有过渡）。
 */
export const MENU_TRANSITION =
  "origin-[var(--transform-origin)] transition-[opacity,transform] duration-150 ease-out " +
  "data-[starting-style]:opacity-0 data-[starting-style]:-translate-y-1 " +
  "data-[ending-style]:opacity-0 data-[ending-style]:-translate-y-1";
