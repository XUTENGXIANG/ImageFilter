// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import * as ContextMenuPrimitive from "@radix-ui/react-context-menu";
import { MENU_CONTENT, MENU_ITEM } from "./components/menu-styles";

export interface MenuItem {
  label: string;
  action?: () => void;
  children?: MenuItem[];
  disabled?: boolean;
}

interface Props {
  items: MenuItem[];
  onOpenChange?: (open: boolean) => void;
  children: React.ReactNode;
}

// 容器与项的视觉取自 menu-styles.ts —— 与下拉框共用同一份, 否则两种菜单会长得不一样
const itemClass = MENU_ITEM;
const contentClass = MENU_CONTENT;

export function PixelMenu({ items, onOpenChange, children }: Props) {
  return (
    <ContextMenuPrimitive.Root onOpenChange={onOpenChange}>
      <ContextMenuPrimitive.Trigger className="contents">
        {children}
      </ContextMenuPrimitive.Trigger>
      <ContextMenuPrimitive.Portal>
        <ContextMenuPrimitive.Content className={contentClass} alignOffset={-4}>
          <MenuItems items={items} />
        </ContextMenuPrimitive.Content>
      </ContextMenuPrimitive.Portal>
    </ContextMenuPrimitive.Root>
  );
}

function MenuItems({ items }: { items: MenuItem[] }) {
  return items.map((item, i) => {
    if (item.label === "" && !item.action) {
      return <ContextMenuPrimitive.Separator key={i} className="h-px bg-zinc-700 mx-2 my-1" />;
    }
    if (item.children) {
      return (
        <ContextMenuPrimitive.Sub key={i}>
          <ContextMenuPrimitive.SubTrigger className={itemClass}>
            {item.label}
            <span className="ml-auto text-zinc-500">▶</span>
          </ContextMenuPrimitive.SubTrigger>
          <ContextMenuPrimitive.Portal>
            <ContextMenuPrimitive.SubContent className={contentClass} sideOffset={2} alignOffset={-4}>
              <MenuItems items={item.children} />
            </ContextMenuPrimitive.SubContent>
          </ContextMenuPrimitive.Portal>
        </ContextMenuPrimitive.Sub>
      );
    }
    return (
      <ContextMenuPrimitive.Item
        key={i}
        className={itemClass}
        disabled={item.disabled}
        onSelect={item.action}
      >
        {item.label}
      </ContextMenuPrimitive.Item>
    );
  });
}

// Re-export separator items signal
export const SEPARATOR: MenuItem = { label: "", disabled: true };
